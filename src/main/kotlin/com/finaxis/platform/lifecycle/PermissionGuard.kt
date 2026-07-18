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
}
