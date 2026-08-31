package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.security.RequestPermissionCache
import com.finaxis.platform.iam.domain.OrganisationStatus
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Raised when an application authorization rule denies an action.
 */
class AccessDeniedException(
    @Suppress("UNUSED_PARAMETER") message: String,
) : ForbiddenOperationException()

/**
 * Resource metadata used for domain-specific authorization checks.
 */
data class ResourceRef(
    val resourceType: String,
    val resourceId: UUID,
    val organisationId: UUID,
    val branchId: UUID? = null,
    val warehouseId: UUID? = null,
    val ownerId: UUID? = null,
)

/**
 * Central authorization facade used by application services.
 */
@Service
class AuthorizationService(
    private val membershipSelectionLookup: MembershipSelectionLookup,
    private val effectivePermissionResolver: EffectivePermissionResolver,
    private val requestPermissionCache: RequestPermissionCache,
) {
    /**
     * Returns whether the principal has the permission code in the active membership context.
     */
    fun hasPermission(
        principal: AppPrincipal,
        permissionCode: String,
    ): Boolean = permissionCode in principal.permissions

    /**
     * Requires the permission code in the active membership context.
     */
    fun requirePermission(
        principal: AppPrincipal,
        permissionCode: String,
    ) {
        if (hasPermission(principal, permissionCode)) {
            return
        }
        throw AccessDeniedException("Missing permission: $permissionCode")
    }

    /**
     * Returns whether the principal can use a permission against a resource.
     */
    fun can(
        principal: AppPrincipal,
        permissionCode: String,
        resourceRef: ResourceRef,
    ): Boolean {
        if (!hasPermission(principal, permissionCode)) {
            return false
        }
        return principal.organisationId == resourceRef.organisationId
    }

    /**
     * Requires permission and resource-specific access.
     */
    fun require(
        principal: AppPrincipal,
        permissionCode: String,
        resourceRef: ResourceRef,
    ) {
        if (can(principal, permissionCode, resourceRef)) {
            return
        }
        throw AccessDeniedException(
            "Access denied for ${resourceRef.resourceType}:${resourceRef.resourceId} " +
                "with permission $permissionCode",
        )
    }

    /**
     * Lists effective permission codes for [userId] in [organisationId].
     */
    fun listEffectivePermissions(
        userId: UUID,
        organisationId: UUID,
    ): Set<String> {
        if (!isOrganisationActive(organisationId)) {
            return emptySet()
        }
        val membership =
            membershipSelectionLookup.findMembership(
                userId = userId,
                organisationId = organisationId,
            ) ?: return emptySet()
        return requestPermissionCache.effectivePermissions(membership.membershipId, null)
    }

    /**
     * Lists effective permission codes for [userId] in [organisationId] and [branchId].
     */
    fun listEffectiveBranchPermissions(
        userId: UUID,
        organisationId: UUID,
        branchId: UUID,
    ): Set<String> {
        if (!isOrganisationActive(organisationId)) {
            return emptySet()
        }
        val membership =
            membershipSelectionLookup.findMembership(
                userId = userId,
                organisationId = organisationId,
            ) ?: return emptySet()
        return requestPermissionCache.effectivePermissions(membership.membershipId, branchId)
    }

    /**
     * Requires [userId] to hold a break-glass [permissionCode] in [organisationId], with **no**
     * system-actor exemption and **no** dependency on an active web request.
     *
     * Two deliberate differences from [requirePermission]:
     *
     * Unlike [hasPermission], this does not short-circuit for the system-actor sentinels. That
     * exemption is right for background provisioning and wrong for a ledger control - a batch job
     * would otherwise exercise authority no principal holds.
     *
     * And it resolves through [EffectivePermissionResolver] rather than `RequestPermissionCache`,
     * which is `@RequestScope`. Dereferencing that proxy outside a web request raises
     * `ScopeNotActiveException`, so routing a background caller through the cached path would fail
     * with a scope error rather than evaluating its grant - defeating the point of denying the
     * system-actor bypass in the first place, since the documented alternative is precisely a
     * background job running under a real service identity.
     */
    fun requireBreakGlassPermission(
        userId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        if (permissionCode in breakGlassPermissions(userId, organisationId)) {
            return
        }
        throw AccessDeniedException("Missing break-glass permission: $permissionCode")
    }

    private fun breakGlassPermissions(
        userId: UUID,
        organisationId: UUID,
    ): Set<String> {
        if (!isOrganisationActive(organisationId)) {
            return emptySet()
        }
        val membership =
            membershipSelectionLookup.findMembership(
                userId = userId,
                organisationId = organisationId,
            ) ?: return emptySet()
        return effectivePermissionResolver.effectivePermissions(membership.membershipId, null)
    }

    /**
     * Returns whether [userId] has [permissionCode] in [organisationId].
     */
    fun hasPermission(
        userId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ): Boolean =
        SystemActor.isSystemActor(userId) ||
            permissionCode in listEffectivePermissions(userId, organisationId)

    /**
     * Returns whether [userId] has [permissionCode] in [organisationId] and [branchId].
     */
    fun hasPermission(
        userId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ): Boolean =
        SystemActor.isSystemActor(userId) ||
            permissionCode in
            listEffectiveBranchPermissions(
                userId = userId,
                organisationId = organisationId,
                branchId = branchId,
            )

    /**
     * Requires [userId] to have [permissionCode] in [organisationId].
     */
    fun requirePermission(
        userId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        if (hasPermission(userId, organisationId, permissionCode)) {
            return
        }
        throw AccessDeniedException("Missing permission: $permissionCode")
    }

    /**
     * Requires [userId] to have [permissionCode] in [organisationId] and [branchId].
     */
    fun requirePermission(
        userId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ) {
        if (hasPermission(userId, organisationId, branchId, permissionCode)) {
            return
        }
        throw AccessDeniedException("Missing permission: $permissionCode")
    }

    private fun isOrganisationActive(organisationId: UUID): Boolean =
        membershipSelectionLookup.organisationStatus(organisationId) == OrganisationStatus.ACTIVE
}
