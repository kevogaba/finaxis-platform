package com.finaxis.platform.iam.application.port.outbound

import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.UserStatus
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

/** Organisation membership summary available during pre-context organisation discovery. */
data class OrganisationSelection(
    val membershipId: UUID,
    val organisationId: UUID,
    val tenantCode: String,
    val displayName: String,
    val organisationStatus: OrganisationStatus,
    val membershipStatus: MembershipStatus,
)

/** A bounded page of organisation memberships for an authenticated user. */
data class OrganisationSelectionPage(
    val items: List<OrganisationSelection>,
    val totalItems: Long,
)

/** Branch assignment summary available after selecting an organisation. */
data class BranchSelection(
    val branchId: UUID,
    val branchCode: String,
    val branchName: String,
    val branchStatus: String,
)

/** A bounded page of active branch assignments for a selected membership. */
data class BranchSelectionPage(
    val items: List<BranchSelection>,
    val totalItems: Long,
)

/**
 * Outbound application port for membership and branch assignment lookups.
 */
interface MembershipSelectionLookup {
    /** Lists active organisation memberships for an authenticated user in tenant-code order. */
    fun findOrganisationSelections(
        userId: UUID,
        page: Int,
        size: Int,
    ): OrganisationSelectionPage = OrganisationSelectionPage(emptyList(), 0)

    /** Lists active branch assignments for a selected membership in branch-code order. */
    fun findBranchSelections(
        membershipId: UUID,
        page: Int,
        size: Int,
    ): BranchSelectionPage = BranchSelectionPage(emptyList(), 0)

    /**
     * Finds the application user id for a Keycloak subject.
     */
    fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID?

    /** Returns the current global lifecycle status for an application user. */
    fun userStatus(userId: UUID): UserStatus?

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
