package com.finaxis.platform.lifecycle

import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import org.jooq.DSLContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Shared integration-test fixture: provisions an ACTIVE organisation through the real
 * provisioning saga, then grants an actor the TENANT_ADMIN role directly via jOOQ so
 * permission-gated application services can be called successfully in tests with no
 * HTTP/controller layer in front of them.
 */
class TenantAdminOrganisationFixture(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val dsl: DSLContext,
) {
    /** Creates an ACTIVE organisation and grants [actorId] its TENANT_ADMIN role. */
    fun createActiveOrganisation(
        labelPrefix: String,
        actorId: UUID,
    ): UUID {
        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "$labelPrefix-${uuidV7()}",
                        displayName = "$labelPrefix Organisation",
                        legalName = "$labelPrefix Organisation Limited",
                        registrationNumber = "${labelPrefix.uppercase()}-${uuidV7()}",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = actorId,
                    ),
                ).organisationId
        organisationProvisioningService.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId),
        )
        organisationProvisioningService.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId),
        )
        grantTenantAdmin(organisationId, actorId)
        return organisationId
    }

    /** Grants [actorId] the org's TENANT_ADMIN role via a real membership + role assignment. */
    fun grantTenantAdmin(
        organisationId: UUID,
        actorId: UUID,
    ) {
        grantRole(
            organisationId = organisationId,
            actorId = actorId,
            roleCode = "TENANT_ADMIN",
            missingRoleMessage = "TENANT_ADMIN role was not provisioned for the organisation.",
        )
    }

    /** Grants [actorId] the platform org's PLATFORM_SUPER_ADMIN role. */
    fun grantPlatformSuperAdmin(actorId: UUID) {
        grantRole(
            organisationId = PlatformOrganisation.ID,
            actorId = actorId,
            roleCode = "PLATFORM_SUPER_ADMIN",
            missingRoleMessage = "PLATFORM_SUPER_ADMIN role was not seeded for the platform org.",
        )
    }

    /** Grants [actorId] the platform org's read-only PLATFORM_SUPPORT role. */
    fun grantPlatformSupport(actorId: UUID) {
        grantRole(
            organisationId = PlatformOrganisation.ID,
            actorId = actorId,
            roleCode = "PLATFORM_SUPPORT",
            missingRoleMessage = "PLATFORM_SUPPORT role was not seeded for the platform org.",
        )
    }

    /**
     * Grants [actorId] a dedicated platform role holding exactly [permissionCodes] and nothing
     * else, so a test can prove a route works with its advertised permission alone.
     */
    fun grantPlatformPermissionsOnly(
        actorId: UUID,
        vararg permissionCodes: String,
    ) = grantPermissionsOnly(PlatformOrganisation.ID, actorId, "PLATFORM_NARROW", permissionCodes)

    /**
     * Grants [actorId] a dedicated role in [organisationId] holding exactly [permissionCodes] and
     * nothing else, so a test can prove a tenant route refuses a member who lacks its permission.
     */
    fun grantTenantPermissionsOnly(
        organisationId: UUID,
        actorId: UUID,
        vararg permissionCodes: String,
    ) = grantPermissionsOnly(organisationId, actorId, "TENANT_NARROW", permissionCodes)

    /**
     * Grants [actorId] a dedicated role holding exactly [permissionCodes], assigned at BRANCH scope
     * on [branchId] only, so a test can prove a route works for a branch-scoped maker who holds
     * nothing tenant-wide.
     */
    fun grantBranchPermissionsOnly(
        organisationId: UUID,
        branchId: UUID,
        actorId: UUID,
        vararg permissionCodes: String,
    ) = grantPermissionsOnly(organisationId, actorId, "BRANCH_NARROW", permissionCodes, branchId)

    private fun grantPermissionsOnly(
        organisationId: UUID,
        actorId: UUID,
        roleCodePrefix: String,
        permissionCodes: Array<out String>,
        branchId: UUID? = null,
    ) {
        val now = OffsetDateTime.now()
        val roleId =
            requireNotNull(
                dsl
                    .insertInto(ROLE)
                    .set(ROLE.ORGANISATION_ID, organisationId)
                    .set(ROLE.ROLE_CODE, "${roleCodePrefix}_${uuidV7()}")
                    .set(ROLE.ROLE_NAME, "Narrow test role")
                    .set(ROLE.SYSTEM_ROLE, false)
                    .set(ROLE.STATUS, "ACTIVE")
                    .set(ROLE.CREATED_AT, now)
                    .set(ROLE.CREATED_BY, SystemActor.ID)
                    .set(ROLE.UPDATED_AT, now)
                    .set(ROLE.UPDATED_BY, SystemActor.ID)
                    .returning(ROLE.ID)
                    .fetchOne()
                    ?.id,
            )
        permissionCodes.forEach { code ->
            val permissionId =
                requireNotNull(
                    dsl
                        .select(PERMISSION.ID)
                        .from(PERMISSION)
                        .where(PERMISSION.PERMISSION_CODE.eq(code))
                        .fetchOne(PERMISSION.ID),
                ) { "$code must exist in the seeded catalogue" }
            dsl
                .insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
                .set(ROLE_PERMISSION.ROLE_ID, roleId)
                .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
                .set(ROLE_PERMISSION.GRANTED_AT, now)
                .set(ROLE_PERMISSION.GRANTED_BY, SystemActor.ID)
                .set(ROLE_PERMISSION.CREATED_AT, now)
                .set(ROLE_PERMISSION.CREATED_BY, SystemActor.ID)
                .set(ROLE_PERMISSION.UPDATED_AT, now)
                .set(ROLE_PERMISSION.UPDATED_BY, SystemActor.ID)
                .execute()
        }
        assignRole(organisationId, actorId, roleId, branchId)
    }

    private fun grantRole(
        organisationId: UUID,
        actorId: UUID,
        roleCode: String,
        missingRoleMessage: String,
    ) {
        val roleId =
            requireNotNull(
                dsl
                    .select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.ORGANISATION_ID.eq(organisationId))
                    .and(ROLE.ROLE_CODE.eq(roleCode))
                    .fetchOne(ROLE.ID),
            ) { missingRoleMessage }
        assignRole(organisationId, actorId, roleId)
    }

    private fun assignRole(
        organisationId: UUID,
        actorId: UUID,
        roleId: UUID,
        branchId: UUID? = null,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, uuidV7())
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, actorId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "ADMIN")
            // As the real bootstrap does: the first administrator is created by the system actor.
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_BY, SystemActor.ID)
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ID, uuidV7())
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, actorId)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, if (branchId == null) "TENANT" else "BRANCH")
            .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .execute()
    }
}

/**
 * Binds a mock request context for the duration of [block], then restores the prior context.
 * Required because the real [com.finaxis.platform.lifecycle.PermissionGuard] implementation
 * touches a Spring `@RequestScope` bean that can only resolve while a request context is bound to
 * the thread, and this codebase has no HTTP/controller layer driving these services yet.
 */
inline fun <T> withRequestContext(block: () -> T): T {
    val previous = RequestContextHolder.getRequestAttributes()
    RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
    try {
        return block()
    } finally {
        if (previous != null) {
            RequestContextHolder.setRequestAttributes(previous)
        } else {
            RequestContextHolder.resetRequestAttributes()
        }
    }
}
