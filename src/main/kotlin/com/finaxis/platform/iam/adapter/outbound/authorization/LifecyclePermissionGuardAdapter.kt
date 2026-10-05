package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.lifecycle.BranchVisibility
import com.finaxis.platform.lifecycle.PermissionGuard
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Identity-module implementation of the lifecycle [PermissionGuard] port. Resolves the actor's
 * effective permission codes for the organisation and throws a named
 * [com.finaxis.platform.common.application.MissingPermissionException] when the required code
 * is absent.
 *
 * Every check also requires the view codes the catalogue pairs with a mutation code, at the
 * same scope (ADR 0030, decision 4): this adapter is the one place the rule lives, so no call
 * site types a view and none can forget it. A view or context code has no pairing and is checked
 * alone.
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
        authorizationService.requirePermissionWithViews(actorId, organisationId, permissionCode)
    }

    override fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        authorizationService.requirePermissionWithViews(actorId, organisationId, permissionCode)
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

    override fun requirePlatformPermission(
        actorId: UUID,
        permissionCode: String,
    ) {
        authorizationService.requirePermissionWithViews(
            actorId,
            PlatformOrganisation.ID,
            permissionCode,
        )
    }

    override fun branchVisibility(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ): BranchVisibility =
        authorizationService.branchVisibility(actorId, organisationId, permissionCode)
}
