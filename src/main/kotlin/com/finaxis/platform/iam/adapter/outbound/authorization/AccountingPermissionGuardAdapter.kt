package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Identity implementation of the accounting permission port, so accounting can enforce permission
 * codes without depending on the identity module. Mirrors [LifecyclePermissionGuardAdapter],
 * including the central rule that a mutation code is checked together with every view code the
 * catalogue pairs with it (ADR 0030, decision 4), so the first accounting route inherits it.
 */
@Component
class AccountingPermissionGuardAdapter(
    private val authorizationService: AuthorizationService,
) : AccountingPermissionGuard {
    override fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        authorizationService.requirePermissionWithViews(actorId, organisationId, permissionCode)
    }

    override fun requireBreakGlassPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        // Deliberately NOT requirePermission, which returns true for the system-actor sentinels
        // before consulting any grant; and deliberately not listEffectivePermissions either, which
        // resolves through the @RequestScope permission cache and would raise a scope error for
        // exactly the background service identity this path exists to serve.
        authorizationService.requireBreakGlassPermissionWithViews(
            actorId,
            organisationId,
            permissionCode,
        )
    }

    override fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ) {
        authorizationService.requirePermissionWithViews(
            actorId,
            organisationId,
            branchId,
            permissionCode,
        )
    }
}
