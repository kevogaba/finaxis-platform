package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.iam.application.context.AppPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Named method-security authorizer for `@PreAuthorize` expressions.
 *
 * Powers expressions such as `@PreAuthorize("@authz.hasPermission(#organisationId,
 * 'branch.create')")`. It evaluates permission codes, never role names, and scopes decisions to
 * the principal's active organisation and branch context.
 */
@Component("authz")
class MethodSecurityAuthorizer {
    /**
     * Returns whether the current principal has [permissionCode] in [organisationId].
     */
    fun hasPermission(
        organisationId: UUID,
        permissionCode: String,
    ): Boolean {
        val principal = currentPrincipal() ?: return false
        return principal.organisationId == organisationId &&
            permissionCode in principal.permissions
    }

    /**
     * Returns whether the current principal has [permissionCode] in [organisationId] and
     * [branchId].
     */
    fun hasPermission(
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ): Boolean {
        val principal = currentPrincipal() ?: return false
        return principal.organisationId == organisationId &&
            principal.branchId == branchId &&
            permissionCode in principal.permissions
    }

    private fun currentPrincipal(): AppPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AppPrincipal
}
