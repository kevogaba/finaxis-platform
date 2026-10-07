package com.finaxis.platform.architecture.fixtures.readgate.guard

import java.util.UUID

/** A guard in the shape accounting uses: its own interface, named `...PermissionGuard`. */
interface FixtureAreaPermissionGuard {
    /** Requires the actor to hold the code. */
    fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    )
}
