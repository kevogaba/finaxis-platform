package com.finaxis.platform.lifecycle

import com.finaxis.platform.common.application.ForbiddenOperationException
import java.util.UUID

/**
 * Public lifecycle-module port for permission-code authorization. Implemented by the identity
 * module so that lifecycle application services can enforce permission codes without depending
 * on the identity module directly (which would create a module cycle).
 *
 * Every `require...` check also requires the view permissions the catalogue pairs with a mutation
 * permission code (`permission_view_requirement`, ADR 0030 decision 4), at the
 * same scope: tenant, target branch or the platform organisation. The adapter applies the pairing
 * centrally, so a caller names only the mutation code, and a refusal is always a
 * [com.finaxis.platform.common.application.MissingPermissionException] naming the first missing
 * code, the mutation code first. A view or context code is checked alone.
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

    /**
     * Answers where [actorId] holds the view permission [permissionCode] in [organisationId]:
     * [BranchVisibility.AllBranches] for a tenant-wide grant (a tenant-scope role or a direct
     * allow), otherwise the set of branch ids carrying a branch-scope grant, which is empty when
     * the actor holds the permission nowhere (also for an inactive organisation or membership,
     * and for a direct deny). The same rule as the effective permission set, projected per branch,
     * so a target-aware read agrees with the check a mutation makes at that branch.
     */
    fun branchVisibility(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ): BranchVisibility
}

/**
 * The branches on which a caller holds a view permission (ADR 0030, decision 5). Branch
 * resources are read at the branch they concern, so a read is allowed by a tenant-wide grant or
 * by a grant on that branch, and a list is restricted to [branchIds] in the query itself.
 */
sealed interface BranchVisibility {
    /** True when the caller may see [branchId]. */
    fun canSee(branchId: UUID): Boolean

    /**
     * The branch ids a list is restricted to, or null for every branch. Refuses with 403 when the
     * caller holds the view nowhere, so a list route is never open to a caller with no grant.
     */
    fun requireListRestriction(): Set<UUID>?

    /** A tenant-wide grant: every branch of the organisation is visible. */
    data object AllBranches : BranchVisibility {
        override fun canSee(branchId: UUID): Boolean = true

        override fun requireListRestriction(): Set<UUID>? = null
    }

    /** Branch-scope grants only: exactly [branchIds] are visible, none when it is empty. */
    data class Branches(
        val branchIds: Set<UUID>,
    ) : BranchVisibility {
        override fun canSee(branchId: UUID): Boolean = branchId in branchIds

        override fun requireListRestriction(): Set<UUID>? =
            branchIds.takeUnless { it.isEmpty() } ?: throw ForbiddenOperationException()
    }
}
