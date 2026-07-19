package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.common.audit.AuditPermissionGuard
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Identity-module adapter for the audit-module [AuditPermissionGuard] port.
 * Delegates the permission check to the identity module's [AuthorizationService].
 */
@Component
class AuditPermissionGuardAdapter(
    private val authorizationService: AuthorizationService,
) : AuditPermissionGuard {
    override fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        authorizationService.requirePermission(actorId, organisationId, permissionCode)
    }
}
