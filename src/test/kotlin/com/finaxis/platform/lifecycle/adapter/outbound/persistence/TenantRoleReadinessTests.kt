package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.FoundationLifecycleService
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.OrganisationSetupRequirement
import com.finaxis.platform.lifecycle.application.OrganisationTransitionCommand
import com.finaxis.platform.lifecycle.application.ReactivateOrganisationCommand
import com.finaxis.platform.lifecycle.application.Reason
import com.finaxis.platform.lifecycle.application.SuspendOrganisationCommand
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The readiness check behind tenant reactivation, against the `branch.approve` move of #208.
 *
 * `JooqOrganisationAccessStore.hasDefaultRolesAndPermissions` counts each default role's `ACTIVE`
 * permissions that are in the current bundle and requires the count to equal the bundle's size. It
 * is a superset test over the bundle's own codes: a role may hold any number of other rows. These
 * tests pin that, because `V21` leaves the deprecated `branch.activate` rows on every existing
 * tenant's system roles and a reactivation that began failing on them would strand suspended
 * tenants. Everything runs in a rolled-back transaction.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class TenantRoleReadinessTests(
    private val dsl: DSLContext,
    private val lifecycleService: FoundationLifecycleService,
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val accessStore: JooqOrganisationAccessStore,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `an existing tenant whose roles still hold the deprecated branch activate stays ready`() {
        // A tenant seeded before V21 holds branch.activate beside branch.approve on its system
        // roles. V21 deprecates branch.activate and leaves those rows. The check counts only the
        // codes of the current bundle, so the extra, deprecated rows neither break it nor are
        // needed by it.
        val organisationId = approvedOrganisation("legacy")
        assertEquals("DEPRECATED", statusOf("branch.activate"))
        assertTrue(isReady(organisationId), "a freshly seeded tenant is ready")

        listOf("TENANT_ADMIN", "BRANCH_MANAGER").forEach { roleCode ->
            grantToRole(organisationId, roleCode, "branch.activate")
            val held = rolePermissionCodes(organisationId, roleCode)
            assertTrue("branch.approve" in held, roleCode)
            assertTrue("branch.activate" in held, roleCode)
        }

        assertTrue(isReady(organisationId), "legacy branch.activate rows must not fail readiness")
    }

    @Test
    fun `new tenants are seeded with branch approve and without the deprecated code`() {
        val organisationId = approvedOrganisation("fresh")

        listOf("TENANT_ADMIN", "BRANCH_MANAGER").forEach { roleCode ->
            val held = rolePermissionCodes(organisationId, roleCode)
            assertTrue("branch.approve" in held, roleCode)
            assertTrue("branch.activate" !in held, roleCode)
        }
    }

    @Test
    fun `a tenant whose system role lost branch approve is not ready`() {
        val organisationId = approvedOrganisation("lost-approve")
        dsl
            .deleteFrom(ROLE_PERMISSION)
            .where(ROLE_PERMISSION.ORGANISATION_ID.eq(organisationId))
            .and(
                ROLE_PERMISSION.PERMISSION_ID.eq(
                    dsl
                        .select(PERMISSION.ID)
                        .from(PERMISSION)
                        .where(PERMISSION.PERMISSION_CODE.eq("branch.approve")),
                ),
            ).execute()
        // Holding the deprecated code is no substitute for the code that now approves.
        grantToRole(organisationId, "BRANCH_MANAGER", "branch.activate")

        assertTrue(
            OrganisationSetupRequirement.DEFAULT_ROLES_AND_PERMISSIONS in
                accessStore.missingRequiredSetup(organisationId),
        )
    }

    @Test
    fun `a legacy tenant is refused reactivation until V23 backfills it, then reactivates`() {
        // A tenant approved before holds the old 70-code TENANT_ADMIN: the ten inert tenant.*
        // codes and none of the accounting maker and checker codes. The bundle is now derived from
        // the catalogue, so that role no longer satisfies readiness, and a suspended legacy tenant
        // would stay suspended (409) until its roles are backfilled. V23 is that backfill.
        val organisationId = approvedOrganisation("legacy-backfill")
        legacyTenantAdmin(organisationId)
        organisationProvisioningService.suspend(
            SuspendOrganisationCommand(organisationId, Reason.required("Review")),
        )
        assertTrue(!isReady(organisationId), "a legacy TENANT_ADMIN lacks the derived bundle")
        assertFailsWith<ConflictException> {
            organisationProvisioningService.reactivate(
                ReactivateOrganisationCommand(organisationId),
            )
        }

        jdbcTemplate.execute(
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() },
        )

        assertTrue(isReady(organisationId), "the backfill completes the legacy role")
        organisationProvisioningService.reactivate(ReactivateOrganisationCommand(organisationId))
        assertEquals(
            OrganisationLifecycleState.ACTIVE.name,
            dsl
                .select(ORGANISATION.STATUS)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchSingle(ORGANISATION.STATUS),
        )
    }

    @Test
    fun `the backfill keeps a deprecated row and removes only the inert platform codes`() {
        val organisationId = approvedOrganisation("legacy-extra")
        legacyTenantAdmin(organisationId)
        // A deprecated leftover and an extra row (V21 and any hand grant) neither break readiness
        // nor are removed: only the inert platform-only codes are deleted.
        grantToRole(organisationId, "TENANT_ADMIN", "branch.activate")

        jdbcTemplate.execute(
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() },
        )

        val held = rolePermissionCodes(organisationId, "TENANT_ADMIN")
        assertTrue("branch.activate" in held)
        assertTrue("tenant.create" !in held && "tenant.bootstrap_retry" !in held, "$held")
        assertTrue(isReady(organisationId))
    }

    /** Rebuilds the pre-V23 TENANT_ADMIN: inert tenant.* codes in, seven accounting codes out. */
    private fun legacyTenantAdmin(organisationId: UUID) {
        jdbcTemplate.update(
            """
            DELETE FROM role_permission
            WHERE organisation_id = ?
              AND role_id = (SELECT id FROM role WHERE organisation_id = ?
                             AND role_code = 'TENANT_ADMIN')
              AND permission_id IN (SELECT id FROM permission WHERE permission_code IN (
                  'journal.create_manual', 'journal.submit', 'journal.approve', 'journal.reverse',
                  'reconciliation.resolve', 'fiscal_period.reopen', 'journal.post_prior_period'))
            """.trimIndent(),
            organisationId,
            organisationId,
        )
        LEGACY_PLATFORM_CODES.forEach { grantToRole(organisationId, "TENANT_ADMIN", it) }
    }

    private fun isReady(organisationId: UUID): Boolean =
        OrganisationSetupRequirement.DEFAULT_ROLES_AND_PERMISSIONS !in
            accessStore.missingRequiredSetup(organisationId)

    private fun approvedOrganisation(label: String): UUID {
        val requestedBy = insertUser()
        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "$label-$requestedBy",
                        displayName = "Readiness Organisation",
                        legalName = "Readiness Organisation Limited",
                        registrationNumber = "RDY-$requestedBy",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = requestedBy,
                    ),
                ).organisationId
        lifecycleService.transition(
            OrganisationTransitionCommand(organisationId, OrganisationLifecycleTransition.SUBMIT),
        )
        organisationProvisioningService.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId),
        )
        return organisationId
    }

    private fun insertUser(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "user-$id")
            .set(USER_ACCOUNT.EMAIL, "user-$id@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Test User")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun statusOf(permissionCode: String): String? =
        dsl
            .select(PERMISSION.STATUS)
            .from(PERMISSION)
            .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
            .fetchOne(PERMISSION.STATUS)

    private fun grantToRole(
        organisationId: UUID,
        roleCode: String,
        permissionCode: String,
    ) {
        val roleId =
            dsl
                .select(ROLE.ID)
                .from(ROLE)
                .where(ROLE.ORGANISATION_ID.eq(organisationId))
                .and(ROLE.ROLE_CODE.eq(roleCode))
                .fetchSingle(ROLE.ID)
        val permissionId =
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                .fetchSingle(PERMISSION.ID)
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
            .set(ROLE_PERMISSION.ROLE_ID, roleId)
            .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
            .set(ROLE_PERMISSION.GRANTED_AT, now)
            .set(ROLE_PERMISSION.CREATED_AT, now)
            .set(ROLE_PERMISSION.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    private fun rolePermissionCodes(
        organisationId: UUID,
        roleCode: String,
    ): Set<String> =
        dsl
            .select(PERMISSION.PERMISSION_CODE)
            .from(ROLE_PERMISSION)
            .join(ROLE)
            .on(ROLE_PERMISSION.ROLE_ID.eq(ROLE.ID))
            .join(PERMISSION)
            .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
            .where(ROLE.ORGANISATION_ID.eq(organisationId))
            .and(ROLE.ROLE_CODE.eq(roleCode))
            .fetch(PERMISSION.PERMISSION_CODE)
            .filterNotNull()
            .toSet()

    private companion object {
        const val MIGRATION =
            "db/migration/V23__seeded_admin_roles_hold_every_permission_of_their_scope.sql"
        val LEGACY_PLATFORM_CODES =
            listOf(
                "tenant.create",
                "tenant.submit_for_approval",
                "tenant.approve",
                "tenant.activate",
                "tenant.suspend",
                "tenant.deprovision",
                "tenant.update_draft",
                "tenant.reject",
                "tenant.reactivate",
                "tenant.bootstrap_retry",
            )
    }
}
