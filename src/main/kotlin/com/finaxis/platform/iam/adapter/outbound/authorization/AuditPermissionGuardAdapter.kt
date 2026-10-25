package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.common.audit.AuditPermissionGuard
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Identity-module adapter for the audit-module [AuditPermissionGuard] port.
 * Delegates the permission check to the identity module's [AuthorizationService], which requires
 * the code **and every view the catalogue pairs with it** (ADR 0030, decision 4): `audit.export`
 * needs `audit.view`, and for `audit.view` itself, a view that pairs with nothing, the check is
 * the one code it always was.
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
        authorizationService.requirePermissionWithViews(actorId, organisationId, permissionCode)
    }
}
