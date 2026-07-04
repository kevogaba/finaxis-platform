package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.iam.application.context.AppPrincipal
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
class AuthorizationService {
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
}
