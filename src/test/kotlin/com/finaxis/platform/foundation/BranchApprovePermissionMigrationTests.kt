package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `V21`'s move of branch approval from `branch.activate` to `branch.approve`, proved on PostgreSQL.
 *
 * Flyway has already run the file, so on the shared container `branch.activate` is DEPRECATED and
 * no custom role holds it. Each test therefore rebuilds the pre-upgrade shape inside one
 * transaction that is **always rolled back** - `branch.activate` flipped back to ACTIVE, roles and
 * memberships seeded, the migration file executed (read from the classpath, not restated) - and
 * asserts inside it. `JdbcTemplate` joins the transaction, so the script runs on the same
 * connection, nothing is visible to another connection, and nothing leaks into the shared
 * catalogue. The one test that reads committed state only looks.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BranchApprovePermissionMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
    private val dataSource: DataSource,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @Test
    fun `flyway left branch approve active and branch activate deprecated`() {
        assertEquals("ACTIVE", statusOf("branch.approve"))
        assertEquals("DEPRECATED", statusOf("branch.activate"))
        assertTrue(
            "branch.approve" in codesOfRole(PLATFORM_SUPER_ADMIN),
            "PLATFORM_SUPER_ADMIN keeps approving through the code that now approves",
        )
    }

    @Test
    fun `a role that held branch activate holds branch approve and keeps its other grants`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val checker = seedRole(organisationId, "CHECKER", "branch.activate", "branch.view")

            runMigration()

            assertEquals(
                setOf("branch.activate", "branch.view", "branch.approve"),
                codesOfRole(checker),
            )
        }
    }

    @Test
    fun `roles without branch activate gain nothing and a dead approve grant is discarded`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val maker = seedRole(organisationId, "MAKER", "branch.create", "branch.view")
            // branch.approve was checked nowhere, so this role could not approve before and must
            // not be able to afterwards: the rebuild starts from branch.activate alone.
            val approveOnly =
                seedRole(organisationId, "APPROVE_ONLY", "branch.approve", "branch.view")
            val empty = seedRole(organisationId, "EMPTY")

            runMigration()

            assertEquals(setOf("branch.create", "branch.view"), codesOfRole(maker))
            assertEquals(setOf("branch.view"), codesOfRole(approveOnly))
            assertEquals(emptySet(), codesOfRole(empty))
        }
    }

    @Test
    fun `a role holding both codes keeps exactly one row of each`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val both = seedRole(organisationId, "BOTH", "branch.activate", "branch.approve")

            runMigration()

            assertEquals(2, rolePermissionRows(both))
            assertEquals(setOf("branch.activate", "branch.approve"), codesOfRole(both))
        }
    }

    @Test
    fun `direct overrides on branch activate are copied with their effect`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val allowed = seedMembership(organisationId, "v21-allow", "branch.activate", "ALLOW")
            val denied = seedMembership(organisationId, "v21-deny", "branch.activate", "DENY")
            val unrelated = seedMembership(organisationId, "v21-other", "branch.view", "ALLOW")

            runMigration()

            assertEquals(
                mapOf("branch.activate" to "ALLOW", "branch.approve" to "ALLOW"),
                overrides(allowed),
            )
            assertEquals(
                mapOf("branch.activate" to "DENY", "branch.approve" to "DENY"),
                overrides(denied),
            )
            assertEquals(mapOf("branch.view" to "ALLOW"), overrides(unrelated))
        }
    }

    @Test
    fun `an override on branch activate replaces an inert override of the other effect`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val denied = seedMembership(organisationId, "v21-d", "branch.activate", "DENY")
            addOverride(organisationId, denied, "branch.approve", "ALLOW")
            val allowed = seedMembership(organisationId, "v21-a", "branch.activate", "ALLOW")
            addOverride(organisationId, allowed, "branch.approve", "DENY")

            runMigration()

            // What the member could do before this release is what branch.activate said.
            assertEquals("DENY", overrides(denied).getValue("branch.approve"))
            assertEquals("ALLOW", overrides(allowed).getValue("branch.approve"))
        }
    }

    @Test
    fun `a dead branch approve override is discarded, ALLOW and DENY alike`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val allowed = seedMembership(organisationId, "v21-solo-a", "branch.approve", "ALLOW")
            val denied = seedMembership(organisationId, "v21-solo-d", "branch.approve", "DENY")
            val kept = seedMembership(organisationId, "v21-solo-k", "branch.view", "ALLOW")
            addOverride(organisationId, allowed, "branch.view", "ALLOW")

            runMigration()

            // An ALLOW on a code nothing checked must not become approval authority.
            assertEquals(mapOf("branch.view" to "ALLOW"), overrides(allowed))
            assertEquals(emptyMap(), overrides(denied))
            assertEquals(mapOf("branch.view" to "ALLOW"), overrides(kept))
        }
    }

    @Test
    fun `a lone branch approve deny does not narrow a role that grants branch activate`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val checker = seedRole(organisationId, "CHECKER", "branch.activate")
            // Before: the DENY was inert, so this member approved through the role. After: the
            // DENY is gone and the role holds branch.approve, so the member still approves.
            val member = seedMembership(organisationId, "v21-lone-deny", "branch.approve", "DENY")

            runMigration()

            assertEquals(emptyMap(), overrides(member))
            assertEquals(setOf("branch.activate", "branch.approve"), codesOfRole(checker))
        }
    }

    @Test
    fun `branch activate ends deprecated, its rows stay, and branch approve is active`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val checker = seedRole(organisationId, "CHECKER", "branch.activate")
            setStatus("branch.approve", "DEPRECATED")

            runMigration()

            assertEquals("DEPRECATED", statusOf("branch.activate"))
            assertEquals("ACTIVE", statusOf("branch.approve"), "approve must be repaired to ACTIVE")
            assertTrue("branch.activate" in codesOfRole(checker), "the grant row is not deleted")
            assertEquals(1, permissionRows("branch.activate"), "the code row is never removed")
        }
    }

    @Test
    fun `re-running the migration is refused once branch activate is deprecated`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            seedRole(organisationId, "CHECKER", "branch.activate")

            runMigration()

            // The state a completed run leaves: the codes agree row for row, and the file still
            // refuses, because that state cannot be told from a pre-deployment shutdown.
            assertRunRefused()
        }
    }

    @Test
    fun `a deprecated source with branch approve active is refused whatever the rows say`() {
        listOf<(UUID) -> Unit>(
            // The stock shape: a role holding both codes, and an override pair that agrees.
            { organisationId ->
                seedRole(organisationId, "BOTH", "branch.activate", "branch.approve")
                seedMembership(organisationId, "v21-both", "branch.activate", "ALLOW")
                    .also { addOverride(organisationId, it, "branch.approve", "ALLOW") }
            },
            // An approve-only role: the old runtime authorized nobody through it.
            { organisationId -> seedRole(organisationId, "APPROVE_ONLY", "branch.approve") },
            // No grants of either code at all.
            { },
        ).forEach { seed ->
            inRolledBackTransaction {
                seed(seedOrganisation())
                setStatus("branch.activate", "DEPRECATED")

                assertRunRefused()
            }
        }
    }

    @Test
    fun `a deprecated source confers nothing, so nothing is copied or re-activated`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val stale = seedRole(organisationId, "STALE", "branch.activate")
            val member = seedMembership(organisationId, "v21-stale", "branch.activate", "ALLOW")
            val approved = seedRole(organisationId, "APPROVED", "branch.approve")
            setStatus("branch.activate", "DEPRECATED")
            setStatus("branch.approve", "DEPRECATED")

            runMigration()

            assertEquals(setOf("branch.activate"), codesOfRole(stale))
            assertEquals(setOf("branch.approve"), codesOfRole(approved), "nothing is discarded")
            assertEquals(mapOf("branch.activate" to "ALLOW"), overrides(member))
            assertEquals("DEPRECATED", statusOf("branch.activate"))
            assertEquals("DEPRECATED", statusOf("branch.approve"), "no status is revived")
        }
    }

    @Test
    fun `a disabled source switches approval off with it instead of switching it on`() {
        inRolledBackTransaction {
            val organisationId = seedOrganisation()
            val checker = seedRole(organisationId, "CHECKER", "branch.activate")
            val approved = seedRole(organisationId, "APPROVED", "branch.approve")
            setStatus("branch.activate", "DISABLED")

            runMigration()

            assertEquals(setOf("branch.activate"), codesOfRole(checker), "never copied")
            assertEquals(setOf("branch.approve"), codesOfRole(approved), "nothing is discarded")
            assertEquals("DISABLED", statusOf("branch.activate"))
            assertEquals("DISABLED", statusOf("branch.approve"))
        }
    }

    @Test
    fun `the rebuild blocks grant writes from other connections and allows reads`() {
        // Positive control: with no migration running the same statements go straight through,
        // so a timeout below can only come from the lock the migration takes.
        assertEquals(emptyMap(), otherConnectionOutcomes().filterValues { it != OK })

        transactionTemplate.execute { transaction ->
            setStatus("branch.activate", "ACTIVE")
            try {
                seedRole(seedOrganisation(), "LOCKED", "branch.activate")
                runMigration()

                val outcomes = otherConnectionOutcomes()

                assertEquals(OK, outcomes.getValue("SELECT role_permission"))
                assertEquals(OK, outcomes.getValue("SELECT membership_permission"))
                WRITES.keys.forEach { statement ->
                    assertEquals(LOCK_NOT_AVAILABLE, outcomes.getValue(statement), statement)
                }
            } finally {
                transaction.setRollbackOnly()
            }
        }

        // Rolled back: the lock is released and nothing was left behind.
        assertEquals(emptyMap(), otherConnectionOutcomes().filterValues { it != OK })
    }

    @Test
    fun `no production source checks branch activate as a permission any more`() {
        // A DEPRECATED code is not honoured at runtime, so a route that still demanded it would be
        // unreachable for everyone. Audit actions are not permissions: `branch.activate` as an
        // action is composed by the FSM at runtime, and the platform-checker marker is
        // `branch.activate_as_platform_checker`, which the pattern does not match.
        val sources =
            Path
                .of("src/main/kotlin")
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        // A scan that finds nothing passes vacuously (a wrong working directory looks the same as
        // a clean tree), so require it to see the code that replaced the deprecated one.
        assertTrue(
            sources.any { """"branch.approve"""" in it.readText() },
            "the scan did not find the branch.approve gate it should have found",
        )

        val offenders =
            sources
                .filter { CHECKS_THE_DEPRECATED_CODE.containsMatchIn(it.readText()) }
                .map { it.path }

        assertEquals(emptyList(), offenders, "these files still name the deprecated code")
    }

    private fun assertRunRefused() {
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

    private fun inRolledBackTransaction(block: () -> Unit) {
        transactionTemplate.execute { transaction ->
            // Rebuild what an installation held before the upgrade: Flyway has already moved
            // branch.activate to DEPRECATED on this container.
            setStatus("branch.activate", "ACTIVE")
            try {
                block()
            } finally {
                transaction.setRollbackOnly()
            }
        }
    }

    /** Every branch.activate / branch.approve row with its effect and version, not just counts. */
    private data class Snapshot(
        val roleGrants: Set<String>,
        val overrides: Set<String>,
        val statuses: Map<String, String>,
    )

    private fun snapshot() =
        Snapshot(
            jdbcTemplate
                .queryForList(
                    """
                    SELECT rp.id::text || ':' || p.permission_code || ':' || rp.row_version
                    FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
                    WHERE p.permission_code IN ('branch.activate', 'branch.approve')
                    """.trimIndent(),
                    String::class.java,
                ).filterNotNull()
                .toSet(),
            jdbcTemplate
                .queryForList(
                    """
                    SELECT mp.id::text || ':' || p.permission_code || ':' || mp.effect || ':' ||
                           mp.row_version
                    FROM membership_permission mp JOIN permission p ON p.id = mp.permission_id
                    WHERE p.permission_code IN ('branch.activate', 'branch.approve')
                    """.trimIndent(),
                    String::class.java,
                ).filterNotNull()
                .toSet(),
            mapOf(
                "branch.activate" to statusOf("branch.activate")!!,
                "branch.approve" to statusOf("branch.approve")!!,
            ),
        )

    /**
     * Runs each statement on its own physical connection (the test's transaction holds the pool's
     * other one) with a short `lock_timeout`, and reports `OK` or the SQLState it failed with.
     * `WHERE FALSE` writes touch no row: the table lock is taken before any row is looked at.
     */
    private fun otherConnectionOutcomes(): Map<String, String> =
        (READS + WRITES).mapValues { (_, sql) ->
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.createStatement().use {
                        it.execute("SET LOCAL lock_timeout = '300ms'")
                        it.execute(sql)
                    }
                    OK
                } catch (e: SQLException) {
                    e.sqlState
                } finally {
                    connection.rollback()
                }
            }
        }

    private fun runMigration() {
        jdbcTemplate.execute(
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() },
        )
    }

    private fun statusOf(code: String): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM permission WHERE permission_code = ?",
            String::class.java,
            code,
        )

    private fun setStatus(
        code: String,
        status: String,
    ) {
        jdbcTemplate.update(
            "UPDATE permission SET status = ? WHERE permission_code = ?",
            status,
            code,
        )
    }

    private fun permissionRows(code: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM permission WHERE permission_code = ?",
            Int::class.java,
            code,
        )!!

    private fun rolePermissionRows(roleId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM role_permission WHERE role_id = ?",
            Int::class.java,
            roleId,
        )!!

    private fun seedOrganisation(): UUID {
        val id = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, activated_at, created_at, updated_at
            ) VALUES (?, ?, 'V21 Test', 'KE', 'KES', 'UTC', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            id,
            "V21-" + id.toString().takeLast(TENANT_CODE_SUFFIX),
        )
        return id
    }

    private fun seedRole(
        organisationId: UUID,
        roleCode: String,
        vararg permissionCodes: String,
    ): UUID {
        val roleId = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO role (
                id, organisation_id, role_code, role_name, system_role, status, created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, FALSE, 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            roleId,
            organisationId,
            roleCode,
            roleCode,
        )
        permissionCodes.forEach { code ->
            jdbcTemplate.update(
                """
                INSERT INTO role_permission (
                    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
                )
                SELECT ?, ?, id, NOW(), NOW(), NOW() FROM permission WHERE permission_code = ?
                """.trimIndent(),
                organisationId,
                roleId,
                code,
            )
        }
        return roleId
    }

    private fun seedMembership(
        organisationId: UUID,
        username: String,
        permissionCode: String,
        effect: String,
    ): UUID {
        val userId = uuidV7()
        val membershipId = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO user_account (id, username, email, display_name, status, created_at,
                                      updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            userId,
            username,
            "$username@example.test",
            username,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_organisation_membership (
                id, organisation_id, user_id, membership_status, membership_type, joined_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'ACTIVE', 'STAFF', NOW(), NOW(), NOW())
            """.trimIndent(),
            membershipId,
            organisationId,
            userId,
        )
        addOverride(organisationId, membershipId, permissionCode, effect)
        return membershipId
    }

    private fun addOverride(
        organisationId: UUID,
        membershipId: UUID,
        permissionCode: String,
        effect: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO membership_permission (
                organisation_id, membership_id, permission_id, effect, granted_at, created_at,
                updated_at
            )
            SELECT ?, ?, id, ?, NOW(), NOW(), NOW() FROM permission WHERE permission_code = ?
            """.trimIndent(),
            organisationId,
            membershipId,
            effect,
            permissionCode,
        )
    }

    private fun codesOfRole(roleId: String): Set<String> = codesOfRole(UUID.fromString(roleId))

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

    private fun overrides(membershipId: UUID): Map<String, String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT p.permission_code, mp.effect FROM membership_permission mp
                JOIN permission p ON p.id = mp.permission_id WHERE mp.membership_id = ?
                """.trimIndent(),
                membershipId,
            ).associate { (it["permission_code"] as String) to (it["effect"] as String) }

    private companion object {
        const val OK = "ok"
        const val LOCK_NOT_AVAILABLE = "55P03"

        val READS =
            mapOf(
                "SELECT role_permission" to "SELECT COUNT(*) FROM role_permission",
                "SELECT membership_permission" to "SELECT COUNT(*) FROM membership_permission",
            )
        val WRITES =
            mapOf(
                "INSERT role_permission" to
                    "INSERT INTO role_permission SELECT * FROM role_permission WHERE FALSE",
                "UPDATE role_permission" to
                    "UPDATE role_permission SET updated_at = updated_at WHERE FALSE",
                "DELETE role_permission" to "DELETE FROM role_permission WHERE FALSE",
                "INSERT membership_permission" to
                    "INSERT INTO membership_permission " +
                    "SELECT * FROM membership_permission WHERE FALSE",
                "UPDATE membership_permission" to
                    "UPDATE membership_permission SET updated_at = updated_at WHERE FALSE",
                "DELETE membership_permission" to "DELETE FROM membership_permission WHERE FALSE",
            )

        const val MIGRATION = "db/migration/V21__branch_approve_permission.sql"
        const val PLATFORM_SUPER_ADMIN = "50000000-0000-0000-0000-000000000001"
        const val TENANT_CODE_SUFFIX = 12

        /** `"branch.activate"` or `'branch.activate'`: a quoted, complete permission code. */
        val CHECKS_THE_DEPRECATED_CODE = Regex("""["']branch\.activate["']""")
    }
}
