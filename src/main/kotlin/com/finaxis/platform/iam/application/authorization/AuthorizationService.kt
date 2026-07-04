package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.iam.application.context.AppPrincipal
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Raised when an application authorization rule denies an action.
 */
class AccessDeniedException(message: String) : RuntimeException(message)

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
    fun hasPermission(principal: AppPrincipal, permissionCode: String): Boolean {
        return permissionCode in principal.permissions
    }

    fun requirePermission(principal: AppPrincipal, permissionCode: String) {
        if (hasPermission(principal, permissionCode)) {
            return
        }
        throw AccessDeniedException("Missing permission: $permissionCode")
    }

    fun can(principal: AppPrincipal, permissionCode: String, resourceRef: ResourceRef): Boolean {
        if (!hasPermission(principal, permissionCode)) {
            return false
        }
        return principal.organisationId == resourceRef.organisationId
    }

    fun require(principal: AppPrincipal, permissionCode: String, resourceRef: ResourceRef) {
        if (can(principal, permissionCode, resourceRef)) {
            return
        }
        throw AccessDeniedException(
            "Access denied for ${resourceRef.resourceType}:${resourceRef.resourceId} with permission $permissionCode",
        )
    }
}
