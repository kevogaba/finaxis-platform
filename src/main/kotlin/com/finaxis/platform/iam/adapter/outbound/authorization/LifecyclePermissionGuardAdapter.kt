package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.lifecycle.PermissionGuard
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Identity-module implementation of the lifecycle [PermissionGuard] port. Resolves the actor's
 * effective permission codes for the organisation and throws when the required code is absent.
 */
@Component
class LifecyclePermissionGuardAdapter(
    private val authorizationService: AuthorizationService,
) : PermissionGuard {
    override fun requirePermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        authorizationService.requirePermission(actorId, organisationId, permissionCode)
    }

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

    override fun requirePlatformPermission(
        actorId: UUID,
        permissionCode: String,
    ) {
        authorizationService.requirePermission(actorId, PlatformOrganisation.ID, permissionCode)
    }
}
