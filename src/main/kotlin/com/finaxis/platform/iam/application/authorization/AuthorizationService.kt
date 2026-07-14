package com.finaxis.platform.iam.application.authorization

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
    message: String,
) : RuntimeException(message)

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
     * Returns whether [userId] has [permissionCode] in [organisationId].
     */
    fun hasPermission(
        userId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ): Boolean = permissionCode in listEffectivePermissions(userId, organisationId)

    /**
     * Returns whether [userId] has [permissionCode] in [organisationId] and [branchId].
     */
    fun hasPermission(
        userId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ): Boolean =
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
