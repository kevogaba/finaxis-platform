package com.finaxis.platform.iam.application.port.outbound

import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.PermissionEffect
import java.util.UUID

/**
 * Direct permission assignment read by the effective-permission resolver.
 */
data class PermissionEffectAssignment(
    val code: String,
    val effect: PermissionEffect,
)

/**
 * One permission code and where a membership holds it: [Scope.TENANT] (every branch) or
 * [Scope.BRANCH] (only the ACTIVE branches in [branchIds]).
 *
 * [branchIds] is non-null, sorted and non-empty exactly for [Scope.BRANCH], and `null` for
 * [Scope.TENANT].
 */
data class PermissionScope(
    val code: String,
    val scope: Scope,
    val branchIds: List<UUID>? = null,
) {
    /** Reach of a grant. */
    enum class Scope {
        /** Held across the whole tenant. */
        TENANT,

        /** Held only on specific branches. */
        BRANCH,
    }
}

/**
 * Outbound application port for resolving membership-scoped permissions.
 */
interface PermissionResolutionQueries {
    /**
     * Reads the current membership status.
     */
    fun membershipStatus(membershipId: UUID): MembershipStatus?

    /**
     * Reads active permission codes granted through tenant-scoped roles, plus roles scoped to
     * [branchId] when a branch is selected. Branch-scoped role grants for other branches are
     * excluded.
     */
    fun rolePermissionCodes(
        membershipId: UUID,
        branchId: UUID?,
    ): Set<String>

    /**
     * Reads direct allow and deny permission assignments.
     */
    fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment>

    /**
     * Reads the ids of the ACTIVE branches on which [membershipId] holds [permissionCode] through
     * an ACTIVE branch-scoped role assignment, an ACTIVE role and an ACTIVE catalogue entry. The
     * same joins as [rolePermissionCodes] for a selected branch, projecting the branch instead of
     * the code, and ignoring tenant-scope assignments (a tenant-wide grant is answered by the
     * tenant permission set, not by this).
     *
     * A branch that is not ACTIVE (draft, pending, suspended, closed or archived) is never
     * returned, whatever grants it carries (issue #242): a branch-scope grant counts only while
     * its branch is ACTIVE, which is also the only state in which the branch can be selected.
     * This is the one place that rule lives; [rolePermissionCodes] does not apply it, because a
     * selected branch is already required to be ACTIVE and its result is cached across requests.
     */
    fun branchIdsGranting(
        membershipId: UUID,
        permissionCode: String,
    ): Set<UUID>

    /**
     * Reads every code [membershipId] holds through an ACTIVE role assignment, an ACTIVE role and
     * an ACTIVE catalogue entry, with the reach of each grant: one [PermissionScope.Scope.TENANT]
     * entry per code held through a tenant-scope assignment, and one
     * [PermissionScope.Scope.BRANCH] entry per code held through branch-scope assignments, listing
     * the branches in ascending order. A code held both ways has both entries; the caller decides
     * which wins.
     *
     * The branch half is [branchIdsGranting] over every code, with the same rule: a branch that is
     * not ACTIVE is never listed, so a code granted only on a suspended branch is absent (issue
     * #242). Direct `membership_permission` overrides are not applied here ([directPermissionEffects]
     * reads them). The result is bounded by the catalogue size times the membership's branches,
     * it is tenant-filtered through the membership, and it is always read fresh: it is never
     * stored under the effective-permission cache key.
     *
     * Entries are ordered by code, then tenant before branch.
     */
    fun grantsByScope(membershipId: UUID): List<PermissionScope>

    /**
     * Decides whether [membershipId] holds [permissionCode] **under a shared lock on every row the
     * answer depends on**, for break-glass checks reachable from a `SERIALIZABLE` transaction.
     *
     * Same rule as [membershipStatus] plus [rolePermissionCodes] plus [directPermissionEffects]
     * would produce for one code - an ACTIVE membership, granted by an active role assignment
     * through an active role and an active catalogue entry, or directly allowed, and not directly
     * denied - and deliberately not expressed by calling them. Two differences carry the whole
     * point:
     *
     * - **It locks.** Every row it reads is taken `FOR SHARE` and held until the caller's
     *   transaction ends, so a revocation that commits after the caller's snapshot makes this read
     *   raise `40001` instead of answering from the grant as it stood before. Above
     *   `READ COMMITTED` nothing weaker can: the revoker writes a row the posting only reads, which
     *   is one rw-dependency edge and no cycle, so SSI is satisfied by the serial order
     *   *"posting, then revocation"* and lets both commit. *"A revocation takes effect
     *   immediately"* is a real-time ordering claim, and only a lock supplies one.
     * - **It never consults the effective-permission cache.** A cached answer is by construction a
     *   pre-revocation answer, and a break-glass control that can be satisfied from a cache is not
     *   a control. The cost is three statements per backdated posting, on a path that already takes
     *   five lock classes; ordinary permission checks keep the cache untouched.
     *
     * Requires an active transaction; the locks are held to its end. Scoped to one code on purpose:
     * locking the rows behind a membership's whole effective set would hold far more of `iam` than
     * the decision needs.
     *
     * See `docs/adr/0026-real-time-gates-on-the-serializable-posting-path.md`.
     */
    fun lockedBreakGlassGrant(
        membershipId: UUID,
        permissionCode: String,
    ): Boolean
}
