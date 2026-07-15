package com.finaxis.platform.iam.application.port.outbound

import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
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
    /**
     * Finds the application user id for a Keycloak subject.
     */
    fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID?

    /**
     * Finds the user's membership in an organisation.
     */
    fun findMembership(
        userId: UUID,
        organisationId: UUID,
    ): MembershipSelection?

    /**
     * Returns the current lifecycle status for an organisation.
     */
    fun organisationStatus(organisationId: UUID): OrganisationStatus?

    /**
     * Lists branch ids assigned to a membership.
     */
    fun findAssignedBranchIds(membershipId: UUID): List<UUID>

    /**
     * Returns whether the membership is assigned to the branch.
     */
    fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean
}
