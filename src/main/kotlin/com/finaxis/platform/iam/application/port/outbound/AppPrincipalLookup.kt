package com.finaxis.platform.iam.application.port.outbound

import java.util.UUID

/**
 * User read model required to construct an authenticated application principal.
 */
data class PrincipalUser(
    val id: UUID,
    val keycloakSubject: String,
    val email: String?,
    val fullName: String?,
)

/**
 * Membership read model required to construct an authenticated application principal.
 */
data class PrincipalMembership(
    val id: UUID,
    val userId: UUID,
    val organisationId: UUID,
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
}
