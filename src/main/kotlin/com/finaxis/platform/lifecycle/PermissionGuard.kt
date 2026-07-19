package com.finaxis.platform.lifecycle

import java.util.UUID

/**
 * Public lifecycle-module port for permission-code authorization. Implemented by the identity
 * module so that lifecycle application services can enforce permission codes without depending
 * on the identity module directly (which would create a module cycle).
 */
interface PermissionGuard {
    /**
     * Requires [actorId] to hold [permissionCode] within [organisationId]. Implementations throw
     * an authorization exception when the permission is absent.
     */
    fun requirePermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    )

    /**
     * Requires [actorId] to hold [permissionCode] in the tenant scope of [organisationId].
     * Throws an authorization exception when the permission is absent.
     */
    fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    )

    /**
     * Requires [actorId] to hold [permissionCode] in the branch scope of [branchId] within
     * [organisationId]. Throws an authorization exception when the permission is absent.
     */
    fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    )

    /**
     * Requires [actorId] to hold [permissionCode] in the reserved platform organisation context.
     * Throws an authorization exception when the permission is absent or the actor does not hold
     * a platform-level membership.
     */
    fun requirePlatformPermission(
        actorId: UUID,
        permissionCode: String,
    )
}
