package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.iam.adapter.outbound.persistence.JooqPermissionResolutionQueries
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.support.NoOpCacheManager
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `V21`'s guarantee read through the application's own resolver, plus its fail-fast paths.
 *
 * [BranchApprovePermissionMigrationTests] proves the rows. This class proves what the rows mean:
 * for each representative principal the effective permission set the production resolver
 * computes answers "may approve a branch" the same way after the migration (through
 * `branch.approve`) as it did before (through `branch.activate`). The resolver here is
 * uncached, so nothing is written to Redis, and every test runs in a transaction that is always
 * rolled back, so nothing reaches the shared container.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BranchApproveEffectiveAuthorityMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    queries: JooqPermissionResolutionQueries,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private val seeder = PermissionMigrationSeeder(jdbcTemplate, "V21E")
    private val resolver = EffectivePermissionResolver(queries, NoOpCacheManager())

    @Test
    fun `every representative principal approves after exactly when it approved before`() {
        inRolledBackTransaction {
            val organisationId = seeder.organisation()
            val activateRole = seeder.role(organisationId, "ACT", "branch.activate")
            val approveRole = seeder.role(organisationId, "APP", "branch.approve")
            val cases =
                listOf(
                    Case("activate-only role", principal(organisationId, "p1", activateRole), true),
                    Case("approve-only role", principal(organisationId, "p2", approveRole), false),
                    Case(
                        "activate role + lone approve DENY",
                        principal(organisationId, "p3", activateRole) { member ->
                            seeder.override(organisationId, member, "branch.approve", "DENY")
                        },
                        true,
                    ),
                    Case(
                        "activate role + approve-ALLOW-only override",
                        principal(organisationId, "p4", activateRole) { member ->
                            seeder.override(organisationId, member, "branch.approve", "ALLOW")
                        },
                        true,
                    ),
                    Case(
                        "approve-only role + activate ALLOW override",
                        principal(organisationId, "p5", approveRole) { member ->
                            seeder.override(organisationId, member, "branch.activate", "ALLOW")
                        },
                        true,
                    ),
                    Case(
                        "activate role + activate DENY + approve ALLOW",
                        principal(organisationId, "p6", activateRole) { member ->
                            seeder.override(organisationId, member, "branch.activate", "DENY")
                            seeder.override(organisationId, member, "branch.approve", "ALLOW")
                        },
                        false,
                    ),
                    Case(
                        "approve ALLOW override alone",
                        principal(organisationId, "p7", null) { member ->
                            seeder.override(organisationId, member, "branch.approve", "ALLOW")
                        },
                        false,
                    ),
                )

            val before = cases.associate { it.name to canApprove(it.principal, "branch.activate") }
            runMigration()
            val after = cases.associate { it.name to canApprove(it.principal, "branch.approve") }

            assertEquals(before, after, "effective approval ability must not move")
            assertEquals(cases.associate { it.name to it.expected }, after)
        }
    }

    @Test
    fun `two roles in one organisation are rebuilt independently`() {
        inRolledBackTransaction {
            val organisationId = seeder.organisation()
            val activateOnly = seeder.role(organisationId, "ACT", "branch.activate")
            val approveOnly = seeder.role(organisationId, "APP", "branch.approve")
            val holder = principal(organisationId, "holder", activateOnly)
            val dead = principal(organisationId, "dead", approveOnly)

            runMigration()

            assertTrue(canApprove(holder, "branch.approve"))
            assertTrue(!canApprove(dead, "branch.approve"), "a counterpart keyed on the tenant")
            assertEquals(setOf("branch.activate", "branch.approve"), codesOfRole(activateOnly))
            assertEquals(emptySet(), codesOfRole(approveOnly))
        }
    }

    @Test
    fun `a re-run after a new approve only grant is refused`() {
        inRolledBackTransaction {
            val organisationId = seeder.organisation()
            seeder.role(organisationId, "ACT", "branch.activate")
            runMigration()
            // After the release an owner deliberately grants approval to a new role.
            seeder.role(organisationId, "NEW_APPROVER", "branch.approve")

            assertRerunRefused()
        }
    }

    @Test
    fun `a re-run refuses an activate only role row`() {
        inRolledBackTransaction {
            val organisationId = seeder.organisation()
            seeder.role(organisationId, "ACT", "branch.activate")
            runMigration()
            // The deprecated code is granted again on a role that has no branch.approve.
            seeder.role(organisationId, "LEGACY", "branch.activate")

            assertRerunRefused()
        }
    }

    @Test
    fun `a re-run refuses an override whose approve effect differs from activate`() {
        inRolledBackTransaction {
            val organisationId = seeder.organisation()
            val member = seeder.member(organisationId, "flip")
            seeder.override(organisationId, member.membershipId, "branch.activate", "ALLOW")
            runMigration()
            // An owner later denies approval directly.
            jdbcTemplate.update(
                "UPDATE membership_permission SET effect = 'DENY' WHERE membership_id = ? AND " +
                    "permission_id = (SELECT id FROM permission WHERE " +
                    "permission_code = 'branch.approve')",
                member.membershipId,
            )

            // Both rows of the pair now disagree with their counterpart.
            assertRerunRefused()
        }
    }

    @Test
    fun `a re-run refuses an approve only override`() {
        inRolledBackTransaction {
            val organisationId = seeder.organisation()
            runMigration()
            val member = seeder.member(organisationId, "solo")
            seeder.override(organisationId, member.membershipId, "branch.approve", "ALLOW")

            assertRerunRefused()
        }
    }

    private fun assertRerunRefused() {
        val failure = assertFailsWith<DataAccessException> { runMigration() }
        val message = failure.message.orEmpty()

        assertTrue(
            "V21 must not run when branch.activate is already DEPRECATED" in message,
            "unexpected failure: $message",
        )
        assertTrue("role grants: " in message && "overrides: " in message, message)
        assertTrue("set branch.approve to DISABLED by hand" in message, message)
        assertTrue("set branch.activate back to ACTIVE" in message, message)
    }

    @Test
    fun `a re-run with matching grants is refused too`() {
        inRolledBackTransaction {
            val organisationId = seeder.organisation()
            seeder.role(organisationId, "ACT", "branch.activate")
            runMigration()

            // Row equality cannot show that V21 completed earlier, so even this state is refused.
            assertRerunRefused()
        }
    }

    @Test
    fun `each post-condition fires on a deliberately broken shape`() {
        val broken =
            listOf(
                "DELETE FROM role_permission WHERE role_id = '%ROLE%' AND permission_id = " +
                    "(SELECT id FROM permission WHERE permission_code = 'branch.approve');" to
                    "a role holds branch.activate but not branch.approve",
                "INSERT INTO role_permission (organisation_id, role_id, permission_id, " +
                    "granted_at, created_at, updated_at) SELECT '%ORG%', '%BARE%', id, NOW(), " +
                    "NOW(), NOW() FROM permission WHERE permission_code = 'branch.approve';" to
                    "a role holds branch.approve without branch.activate",
                "DELETE FROM membership_permission WHERE membership_id = '%MEMBER%' AND " +
                    "permission_id = (SELECT id FROM permission WHERE " +
                    "permission_code = 'branch.approve');" to
                    "a branch.activate override was not copied",
                "INSERT INTO membership_permission (organisation_id, membership_id, " +
                    "permission_id, effect, granted_at, created_at, updated_at) SELECT '%ORG%', " +
                    "'%OTHER%', id, 'ALLOW', NOW(), NOW(), NOW() FROM permission WHERE " +
                    "permission_code = 'branch.approve';" to
                    "a branch.approve override has no matching branch.activate override",
                "UPDATE permission SET status = 'DISABLED' WHERE " +
                    "permission_code = 'branch.approve';" to
                    "branch.approve is not ACTIVE",
            )

        broken.forEach { (doctoring, expected) ->
            inRolledBackTransaction {
                val organisationId = seeder.organisation()
                val role = seeder.role(organisationId, "ACT", "branch.activate")
                val bare = seeder.role(organisationId, "BARE")
                val member = seeder.member(organisationId, "m1")
                seeder.override(organisationId, member.membershipId, "branch.activate", "ALLOW")
                val other = seeder.member(organisationId, "m2")
                val sql =
                    doctoring
                        .replace("%ORG%", organisationId.toString())
                        .replace("%ROLE%", role.toString())
                        .replace("%BARE%", bare.toString())
                        .replace("%MEMBER%", member.membershipId.toString())
                        .replace("%OTHER%", other.membershipId.toString())

                val failure = assertFailsWith<DataAccessException>(expected) { runMigration(sql) }

                assertTrue(expected in failure.message.orEmpty(), "got: ${failure.message}")
            }
        }
    }

    private data class Case(
        val name: String,
        val principal: SeededPrincipal,
        val expected: Boolean,
    )

    private fun principal(
        organisationId: UUID,
        username: String,
        roleId: UUID?,
        overrides: (UUID) -> Unit = {},
    ): SeededPrincipal {
        val principal = seeder.member(organisationId, username)
        if (roleId != null) seeder.assign(organisationId, principal, roleId)
        overrides(principal.membershipId)
        return principal
    }

    private fun canApprove(
        principal: SeededPrincipal,
        permissionCode: String,
    ): Boolean = permissionCode in resolver.effectivePermissions(principal.membershipId)

    private fun inRolledBackTransaction(block: () -> Unit) {
        transactionTemplate.execute { transaction ->
            // Rebuild what an installation held before the upgrade: Flyway has already moved
            // branch.activate to DEPRECATED on this container.
            jdbcTemplate.update(
                "UPDATE permission SET status = 'ACTIVE' WHERE permission_code = 'branch.activate'",
            )
            try {
                block()
            } finally {
                transaction.setRollbackOnly()
            }
        }
    }

    /** Runs the migration, with [doctoring] SQL spliced in before its post-conditions if given. */
    private fun runMigration(doctoring: String? = null) {
        val text =
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() }
        val sql =
            if (doctoring == null) {
                text
            } else {
                assertTrue(POST_CONDITIONS_MARKER in text, "the doctoring marker moved")
                text.replace(POST_CONDITIONS_MARKER, "$doctoring\n$POST_CONDITIONS_MARKER")
            }
        jdbcTemplate.execute(sql)
    }

    private fun codesOfRole(roleId: UUID): Set<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT p.permission_code FROM role_permission rp
                JOIN permission p ON p.id = rp.permission_id WHERE rp.role_id = ?
                """.trimIndent(),
                String::class.java,
                roleId,
            ).filterNotNull()
            .toSet()

    private companion object {
        const val MIGRATION = "db/migration/V21__branch_approve_permission.sql"
        const val POST_CONDITIONS_MARKER = "-- Post-conditions for this branch"
    }
}
