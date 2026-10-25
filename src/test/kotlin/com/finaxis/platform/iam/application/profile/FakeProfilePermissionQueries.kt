package com.finaxis.platform.iam.application.profile

import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.port.outbound.PermissionScope
import com.finaxis.platform.iam.domain.MembershipStatus
import java.util.UUID

/**
 * Scripted [PermissionResolutionQueries] for the profile: only the two reads the profile makes
 * ([grantsByScope] and [directPermissionEffects]) answer; every other read fails the test.
 */
internal class FakeProfilePermissionQueries(
    private val grants: List<PermissionScope> = emptyList(),
    private val effects: List<PermissionEffectAssignment> = emptyList(),
) : PermissionResolutionQueries {
    override fun grantsByScope(membershipId: UUID): List<PermissionScope> = grants

    override fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment> =
        effects

    override fun membershipStatus(membershipId: UUID): MembershipStatus? = unexpected()

    override fun rolePermissionCodes(
        membershipId: UUID,
        branchId: UUID?,
    ): Set<String> = unexpected()

    override fun branchIdsGranting(
        membershipId: UUID,
        permissionCode: String,
    ): Set<UUID> = unexpected()

    override fun lockedBreakGlassGrant(
        membershipId: UUID,
        permissionCode: String,
    ): Boolean = unexpected()

    private fun unexpected(): Nothing = error("The profile must not make this read")
}
