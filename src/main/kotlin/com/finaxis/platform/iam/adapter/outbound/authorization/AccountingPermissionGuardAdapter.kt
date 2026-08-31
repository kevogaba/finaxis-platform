package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Identity implementation of the accounting permission port, so accounting can enforce permission
 * codes without depending on the identity module. Mirrors [LifecyclePermissionGuardAdapter].
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
        authorizationService.requirePermission(actorId, organisationId, permissionCode)
    }

    override fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ) {
        authorizationService.requirePermission(actorId, organisationId, branchId, permissionCode)
    }
}
