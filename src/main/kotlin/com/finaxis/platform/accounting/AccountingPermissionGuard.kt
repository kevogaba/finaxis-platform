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
     * Requires [actorId] to hold a **break-glass** [permissionCode] in the tenant scope of
     * [organisationId], with no system-actor exemption.
     *
     * Distinct from [requireTenantPermission] because the platform's ordinary permission check
     * short-circuits to allow for the system-actor sentinels, which is deliberate for background
     * provisioning but wrong for a ledger control: a batch job would exercise prior-period posting
     * authority that no principal holds, and a tenant administrator who revoked that permission
     * could neither observe nor prevent it. Accounting therefore fails closed here, and a batch
     * that legitimately needs the authority is given a real service identity holding the code.
     */
    fun requireBreakGlassPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    )

    /**
     * Requires [actorId] to hold [permissionCode] for [branchId] within [organisationId], throwing
     * a forbidden-operation failure when the permission is absent.
     *
     * Note that a tenant-scoped grant satisfies this check for any [branchId]: the underlying
     * scope condition is `TENANT OR (BRANCH AND branch_id = ?)`, and nothing yet validates that
     * the branch belongs to the organisation. Tightening that is tracked for issue #52.
     */
    fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    )
}
