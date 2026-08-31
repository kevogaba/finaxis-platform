package com.finaxis.platform.accounting

import java.util.UUID

/**
 * Accounting-owned port for permission-code authorization, implemented by the identity module.
 *
 * Declared here rather than reusing [com.finaxis.platform.lifecycle.PermissionGuard] because
 * accounting must not depend on lifecycle: lifecycle already depends on accounting for
 * [AccountingBusinessDateLookup], and the reverse edge would be a module cycle. Mirrors the
 * existing inversion where lifecycle declares its permission port and identity supplies the
 * adapter.
 */
interface AccountingPermissionGuard {
    /**
     * Requires [actorId] to hold [permissionCode] in the tenant scope of [organisationId], throwing
     * a forbidden-operation failure when the permission is absent.
     */
    fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    )

    /**
     * Requires [actorId] to hold [permissionCode] for [branchId] within [organisationId], throwing
     * a forbidden-operation failure when the permission is absent.
     */
    fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    )
}
