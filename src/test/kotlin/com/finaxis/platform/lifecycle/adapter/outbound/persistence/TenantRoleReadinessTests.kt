package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
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
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
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
}
