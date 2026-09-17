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
