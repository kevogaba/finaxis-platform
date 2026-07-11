package com.finaxis.platform.iam.application.port.outbound

import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import java.util.UUID

/**
 * Organisation details shown in the authenticated user's profile.
 */
data class ProfileOrganisation(
    val id: UUID,
    val code: String,
    val name: String,
    val status: OrganisationStatus,
)

/**
 * Active membership details shown in the authenticated user's profile.
 */
data class ProfileMembership(
    val id: UUID,
    val status: MembershipStatus,
)

/**
 * Branch assignment details shown in the authenticated user's profile.
 */
data class ProfileBranch(
    val id: UUID,
    val code: String,
    val name: String,
    val status: String,
)

/**
 * Role assignment details shown for audit and administration convenience.
 */
data class ProfileRole(
    val id: UUID,
    val code: String,
    val name: String,
    val status: RoleStatus,
)

/**
 * Outbound application port for authenticated user profile read models.
 */
interface UserProfileLookup {
    /**
     * Loads the selected organisation details.
     */
    fun organisation(organisationId: UUID): ProfileOrganisation?

    /**
     * Loads the selected membership details.
     */
    fun membership(membershipId: UUID): ProfileMembership?

    /**
     * Lists branches assigned to the selected membership.
     */
    fun assignedBranches(membershipId: UUID): List<ProfileBranch>

    /**
     * Lists roles assigned to the selected membership.
     */
    fun assignedRoles(membershipId: UUID): List<ProfileRole>
}
