package com.finaxis.platform.iam.application.port.outbound

import com.finaxis.platform.iam.domain.MembershipStatus
import java.util.UUID

/**
 * Read model for an authenticated user's organisation membership.
 */
data class MembershipSelection(
    val membershipId: UUID,
    val userId: UUID,
    val organisationId: UUID,
    val status: MembershipStatus,
)

/**
 * Outbound application port for membership and branch assignment lookups.
 */
interface MembershipSelectionLookup {
    fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID?

    fun findMembership(userId: UUID, organisationId: UUID): MembershipSelection?

    fun findAssignedBranchIds(membershipId: UUID): List<UUID>

    fun hasAssignedBranch(membershipId: UUID, branchId: UUID): Boolean
}
