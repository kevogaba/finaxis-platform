package com.finaxis.platform.iam.application.port.outbound

import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.UserStatus
import java.util.UUID

/**
 * User read model required to construct an authenticated application principal.
 */
data class PrincipalUser(
    val id: UUID,
    val keycloakSubject: String,
    val email: String?,
    val fullName: String?,
    val status: UserStatus = UserStatus.ACTIVE,
)

/**
 * Membership read model required to construct an authenticated application principal.
 */
data class PrincipalMembership(
    val id: UUID,
    val userId: UUID,
    val organisationId: UUID,
    val status: MembershipStatus = MembershipStatus.ACTIVE,
)

/**
 * Outbound application port used by inbound security adapters to load principal data.
 */
interface AppPrincipalLookup {
    /**
     * Finds a principal user by Keycloak subject.
     */
    fun findPrincipalUserByKeycloakSubject(keycloakSubject: String): PrincipalUser?

    /**
     * Finds a principal membership by membership id.
     */
    fun findPrincipalMembershipById(membershipId: UUID): PrincipalMembership?

    /**
     * Returns the current lifecycle status for the selected organisation.
     */
    fun organisationStatus(organisationId: UUID): OrganisationStatus?

    /**
     * Returns whether [branchId] is active, belongs to [organisationId], and is actively assigned
     * to [membershipId]'s user.
     */
    fun hasActiveAssignedBranch(
        membershipId: UUID,
        organisationId: UUID,
        branchId: UUID,
    ): Boolean
}
