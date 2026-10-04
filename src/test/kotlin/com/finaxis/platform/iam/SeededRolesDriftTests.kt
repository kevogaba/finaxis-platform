package com.finaxis.platform.iam

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drift tests for the seeded roles: the administrator roles hold EVERY permission of their
 * scope, derived from `permission.grant_scope` rather than listed, and no seeded bundle breaks the
 * ADR 0030 rule that a mutation is held with its views.
 *
 * "Every permission of the scope" is asserted against the catalogue itself, never against a list
 * typed here, so a new `ACTIVE` code that a later migration adds without granting it to the
 * administrators fails these tests. Everything runs in a rolled-back transaction.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class SeededRolesDriftTests(
    private val jdbcTemplate: JdbcTemplate,
    private val dsl: DSLContext,
    private val authorizationService: AuthorizationService,
    organisationProvisioningService: OrganisationProvisioningService,
    transactionManager: PlatformTransactionManager,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val savepoint =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
        }

    @Test
    fun `a new tenant's administrator holds exactly the active tenant-scope codes`() {
        val organisationId = fixture.createActiveOrganisation("drift-admin", ACTOR_ID)

        val held = heldByRole(organisationId, TENANT_ADMIN)

        assertEquals(activeCodesOfScope("TENANT"), held)
        assertTrue(held.containsAll(BREAK_GLASS), "break-glass codes are held by the admin")
        assertTrue(held.containsAll(CHECKER_CODES), "maker and checker codes are held alike")
        assertTrue(held.none { it in activeCodesOfScope("PLATFORM") }, "a platform code is inert")
    }

    @Test
    fun `the migrated bootstrap local admin holds the same set as a provisioned tenant admin`() {
        val organisationId = fixture.createActiveOrganisation("drift-local", ACTOR_ID)

        assertEquals(
            heldByRole(organisationId, TENANT_ADMIN),
            heldBy(LOCAL_ADMIN_ROLE_ID),
        )
    }

    @Test
    fun `the platform super admin holds every active code`() {
        assertEquals(activeCodes(), heldBy(PLATFORM_SUPER_ADMIN_ROLE_ID))
    }

    @Test
    fun `every administrator role in the database holds every permission of its scope`() {
        // The test that catches a forgotten backfill: it runs against the migrated database, where
        // local-admin is the V3-era role every test database holds, so a migration that adds an
        // ACTIVE tenant-scope code and does not grant it to the administrators fails here.
        val tenantCodes = activeCodesOfScope("TENANT")
        val platformCodes = activeCodesOfScope("PLATFORM")
        val admins =
            jdbcTemplate.queryForList(
                """
                SELECT id, organisation_id, role_code FROM role
                WHERE system_role AND role_code IN ('TENANT_ADMIN', 'local-admin')
                  AND organisation_id <> ?
                """.trimIndent(),
                PlatformOrganisation.ID,
            )

        assertTrue(admins.isNotEmpty(), "the bootstrap tenant's local-admin is always seeded")
        admins.forEach { admin ->
            val held = heldBy(admin["id"].toString())
            val label = "${admin["role_code"]} of ${admin["organisation_id"]}"
            assertEquals(emptySet(), tenantCodes - held, "$label lacks tenant-scope codes")
            assertEquals(emptySet(), held intersect platformCodes, "$label holds inert codes")
        }
    }

    @Test
    fun `every default role of a new tenant is a system role, which is how a migration finds it`() {
        val organisationId = fixture.createActiveOrganisation("drift-system", ACTOR_ID)

        val notSystem =
            jdbcTemplate.queryForList(
                "SELECT role_code FROM role WHERE organisation_id = ? AND NOT system_role",
                String::class.java,
                organisationId,
            )

        assertEquals(emptyList(), notSystem)
    }

    @Test
    fun `the non-admin bundles gain exactly the owner's additions and nothing else changes`() {
        val organisationId = fixture.createActiveOrganisation("drift-bundles", ACTOR_ID)
        val held = ROLE_CODES.associateWith { heldByRole(organisationId, it) }

        assertTrue("branch.view" in held.getValue("IAM_ADMIN"))
        assertTrue(
            held.getValue("BRANCH_MANAGER").containsAll(setOf("user.revoke_branch", "user.view")),
        )
        assertTrue("branch.view" in held.getValue("BRANCH_OPERATOR"))
        // The auditor is unchanged: it reads, and the export has no route yet.
        assertTrue("accounting_report.export" !in held.getValue("TENANT_AUDITOR"))
        assertTrue("accounting_report.view" in held.getValue("TENANT_AUDITOR"))
        // The break-glass codes stay out of every non-admin bundle.
        held.filterKeys { it != TENANT_ADMIN }.forEach { (role, codes) ->
            assertEquals(emptySet(), codes intersect BREAK_GLASS, role)
        }
    }

    @Test
    fun `every seeded bundle of a new tenant holds a mutation only with its views`() {
        val organisationId = fixture.createActiveOrganisation("drift-views", ACTOR_ID)

        val violations =
            (ROLE_CODES + TENANT_ADMIN).associateWith { role ->
                violations(heldByRole(organisationId, role))
            }

        assertEquals(violations.mapValues { emptyMap<String, Set<String>>() }, violations)
    }

    @Test
    fun `platform support holds a mutation only with its views and can enter the platform`() {
        assertEquals(emptyMap(), violations(heldBy(PLATFORM_SUPPORT_ROLE_ID)), "PLATFORM_SUPPORT")

        val user = seedUser("drift-support")
        fixture.grantPlatformSupport(user)
        val effective =
            withRequestContext {
                authorizationService.listEffectivePermissions(user, PlatformOrganisation.ID)
            }

        assertTrue("auth.select_organisation" in effective, "it must be able to select PLATFORM")
        assertTrue("iam.profile.read" in effective, "it must be able to read its own profile")
        assertEquals(heldBy(PLATFORM_SUPPORT_ROLE_ID), effective)
        assertEquals(
            emptySet(),
            effective intersect AccountingPermissions.ALL,
            "support stays free of accounting codes",
        )
    }

    @Test
    fun `a new active tenant code reaches the next provisioned tenant admin with no code edit`() {
        insertPermission("drift.new_view", "VIEW", "TENANT", "ACTIVE")
        insertPermission("drift.new_platform", "VIEW", "PLATFORM", "ACTIVE")
        insertPermission("drift.new_disabled", "VIEW", "TENANT", "DISABLED")

        val organisationId = fixture.createActiveOrganisation("drift-new", ACTOR_ID)
        val held = heldByRole(organisationId, TENANT_ADMIN)

        assertTrue("drift.new_view" in held, "a new ACTIVE tenant code is derived into the bundle")
        assertTrue("drift.new_platform" !in held, "a platform code is not")
        assertTrue("drift.new_disabled" !in held, "an inactive code is not")
        assertEquals(activeCodesOfScope("TENANT"), held)
    }

    @Test
    fun `a permission without a grant scope cannot exist`() {
        assertFailsWith<DataIntegrityViolationException> {
            savepoint.executeWithoutResult {
                insertPermission(
                    "drift.no_scope",
                    "VIEW",
                    null,
                    "ACTIVE",
                )
            }
        }
        assertFailsWith<DataIntegrityViolationException> {
            savepoint.executeWithoutResult {
                insertPermission("drift.bad_scope", "VIEW", "EVERYWHERE", "ACTIVE")
            }
        }
    }

    private fun violations(held: Set<String>): Map<String, Set<String>> =
        requirements()
            .filterKeys { it in held }
            .flatMap { (mutation, views) -> (views - held).map { view -> view to mutation } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private fun requirements(): Map<String, Set<String>> =
        jdbcTemplate
            .queryForList(
                """
                SELECT m.permission_code AS mutation, v.permission_code AS view
                FROM permission_view_requirement r
                JOIN permission m ON m.id = r.permission_id AND m.status = 'ACTIVE'
                JOIN permission v ON v.id = r.required_view_permission_id
                """.trimIndent(),
            ).groupBy({ it["mutation"] as String }, { it["view"] as String })
            .mapValues { it.value.toSet() }

    private fun activeCodes(): Set<String> =
        jdbcTemplate
            .queryForList(
                "SELECT permission_code FROM permission WHERE status = 'ACTIVE'",
                String::class.java,
            ).filterNotNull()
            .toSet()

    private fun activeCodesOfScope(scope: String): Set<String> =
        jdbcTemplate
            .queryForList(
                "SELECT permission_code FROM permission " +
                    "WHERE status = 'ACTIVE' AND grant_scope = ?",
                String::class.java,
                scope,
            ).filterNotNull()
            .toSet()

    private fun heldBy(roleId: String): Set<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT p.permission_code FROM role_permission rp
                JOIN permission p ON p.id = rp.permission_id AND p.status = 'ACTIVE'
                WHERE rp.role_id = ?::uuid
                """.trimIndent(),
                String::class.java,
                roleId,
            ).filterNotNull()
            .toSet()

    private fun heldByRole(
        organisationId: UUID,
        roleCode: String,
    ): Set<String> =
        heldBy(
            jdbcTemplate
                .queryForObject(
                    "SELECT id FROM role WHERE organisation_id = ? AND role_code = ?",
                    UUID::class.java,
                    organisationId,
                    roleCode,
                ).toString(),
        )

    private fun insertPermission(
        code: String,
        kind: String,
        grantScope: String?,
        status: String,
    ) = jdbcTemplate.update(
        """
        INSERT INTO permission (
            permission_code, permission_name, module_code, risk_level, status, kind, grant_scope,
            created_at, updated_at
        ) VALUES (?, 'Drift test', 'iam', 'LOW', ?, ?, ?, NOW(), NOW())
        """.trimIndent(),
        code,
        status,
        kind,
        grantScope,
    )

    private fun seedUser(label: String): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "$label-$id")
            .set(USER_ACCOUNT.EMAIL, "$label-$id@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, label)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
        return id
    }

    private companion object {
        const val TENANT_ADMIN = "TENANT_ADMIN"
        const val PLATFORM_SUPER_ADMIN_ROLE_ID = "50000000-0000-0000-0000-000000000001"
        const val PLATFORM_SUPPORT_ROLE_ID = "50000000-0000-0000-0000-000000000002"
        const val LOCAL_ADMIN_ROLE_ID = "77777777-7777-7777-7777-777777777777"
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val ROLE_CODES =
            listOf(
                "TENANT_AUDITOR",
                "IAM_ADMIN",
                "BRANCH_MANAGER",
                "BRANCH_OPERATOR",
                "ACCOUNTING_OPERATOR",
                "ACCOUNTING_APPROVER",
            )
        val BREAK_GLASS = setOf("fiscal_period.reopen", "journal.post_prior_period")
        val CHECKER_CODES =
            setOf("journal.approve", "journal.reverse", "reconciliation.resolve", "journal.submit")
    }
}
