package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.lifecycle.BranchVisibility
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.application.MakerCheckerPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The pending-approval counts behind an approvals badge (#169, ADR 0038). Deliberately not an
 * inbox: one bounded `COUNT(*)` per resource, using only the approve codes that already exist, so a
 * caller can ask *how many* and then list the resource with its own route.
 *
 * Every count is **an upper bound on what the caller can approve**. It leaves out what the
 * maker-checker rule would refuse for being the caller's own (and honours a tenant's switch that
 * lifts the rule), but it does not replay every refusal: a branch the caller amended, the platform
 * checker's bootstrap-only window and an organisation that is not active still refuse an approval
 * the count included.
 */
@Service
class ApprovalCountQueryService(
    private val store: PendingApprovalCountStore,
    private val permissionGuard: PermissionGuard,
    private val makerChecker: MakerCheckerPolicy,
) {
    /**
     * Counts, for a tenant caller, the pending memberships (`user.approve`, which implies
     * `membership.view`) and branches (`branch.approve`, which implies `branch.view`) it may
     * approve. A resource the caller may not approve has no count at all; a caller who may approve
     * neither is refused with the named 403 of the first resource (`user.approve`, or the view it
     * implies), as every other gated read is.
     *
     * Branch approval is tenant-wide only: a branch-scope grant counts only while its branch is
     * `ACTIVE` and a pending branch has none, so a holder scoped to branches sees no branch count.
     */
    @Transactional(readOnly = true)
    @GatedRead
    fun tenantPendingCounts(caller: TenantCaller): TenantPendingCounts {
        val organisationId = caller.activeOrganisationId
        val memberships = holding { mayApproveMemberships(caller) }
        val branches = holding { mayApproveBranches(caller) }
        if (memberships.refusal != null && branches.refusal != null) throw memberships.refusal
        val mayApproveMemberships = memberships.refusal == null
        val mayApproveBranches = branches.refusal == null
        return TenantPendingCounts(
            memberships =
                if (mayApproveMemberships) {
                    store.pendingMemberships(
                        organisationId,
                        caller.actorId.takeIf {
                            makerChecker.requiredForUserInvites(
                                organisationId,
                            )
                        },
                    )
                } else {
                    null
                },
            branches =
                if (mayApproveBranches) {
                    store.pendingBranches(
                        organisationId,
                        caller.actorId.takeIf {
                            makerChecker.requiredForBranchCreation(organisationId)
                        },
                    )
                } else {
                    null
                },
        )
    }

    /**
     * Counts the pending tenants a platform caller may approve (`tenant.approve` in the platform
     * organisation, which implies `tenant.view`). The platform checker is held to the maker rule
     * whatever a tenant switches, so its own requests and submissions are always excluded.
     */
    @Transactional(readOnly = true)
    @GatedRead
    fun platformPendingCounts(caller: PlatformCaller): PlatformPendingCounts {
        permissionGuard.requirePlatformPermission(caller.actorId, TENANT_APPROVE)
        return PlatformPendingCounts(store.pendingTenants(caller.actorId))
    }

    private fun mayApproveMemberships(caller: TenantCaller) =
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            USER_APPROVE,
        )

    /** Tenant-wide only: a branch-scoped holder is refused as one who holds it nowhere. */
    private fun mayApproveBranches(caller: TenantCaller) {
        val visibility =
            permissionGuard.mutationBranchVisibility(
                caller.actorId,
                caller.activeOrganisationId,
                BRANCH_APPROVE,
            )
        if (visibility != BranchVisibility.AllBranches) {
            throw MissingPermissionException(BRANCH_APPROVE)
        }
    }

    /** What a check refused with, or null when it passed. */
    private class Held(
        val refusal: MissingPermissionException?,
    )

    private fun holding(check: () -> Unit): Held =
        try {
            check()
            Held(null)
        } catch (refusal: MissingPermissionException) {
            Held(refusal)
        }

    private companion object {
        const val USER_APPROVE = "user.approve"
        const val BRANCH_APPROVE = "branch.approve"
        const val TENANT_APPROVE = "tenant.approve"
    }
}
