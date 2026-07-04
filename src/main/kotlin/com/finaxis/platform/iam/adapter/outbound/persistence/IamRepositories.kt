package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.port.outbound.AppPrincipalLookup
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.port.outbound.PrincipalMembership
import com.finaxis.platform.iam.application.port.outbound.PrincipalUser
import com.finaxis.platform.iam.domain.AppUser
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationMembership
import com.finaxis.platform.iam.domain.Permission
import com.finaxis.platform.iam.domain.Role
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Spring Data JDBC repository for application users.
 */
interface AppUserRepository : CrudRepository<AppUser, UUID> {
    /**
     * Finds an application user by the Keycloak subject claim.
     */
    fun findByKeycloakSubject(keycloakSubject: String): AppUser?
}

/**
 * Spring Data JDBC repository for organisation memberships and assigned branch scopes.
 */
interface OrganisationMembershipRepository : CrudRepository<OrganisationMembership, UUID> {
    /**
     * Finds the user's membership in the selected organisation.
     */
    fun findByUserIdAndOrganisationId(
        userId: UUID,
        organisationId: UUID,
    ): OrganisationMembership?

    /**
     * Lists branch ids assigned to a membership.
     */
    @Query(
        """
        SELECT branch_id
        FROM membership_branch_scope
        WHERE membership_id = :membershipId
        ORDER BY branch_id
        """,
    )
    fun findAssignedBranchIds(membershipId: UUID): List<UUID>

    /**
     * Returns whether the membership is assigned to the branch.
     */
    @Query(
        """
        SELECT EXISTS (
            SELECT 1
            FROM membership_branch_scope
            WHERE membership_id = :membershipId
              AND branch_id = :branchId
        )
        """,
    )
    fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean
}

/**
 * Spring Data JDBC repository for permission catalogue entries.
 */
interface PermissionRepository : CrudRepository<Permission, UUID> {
    /**
     * Finds a permission catalogue entry by code.
     */
    fun findByCode(code: String): Permission?
}

/**
 * Spring Data JDBC repository for role bundles.
 */
interface RoleRepository : CrudRepository<Role, UUID>

/**
 * Query repository optimized for membership effective-permission resolution.
 */
interface IamPermissionQueryRepository : CrudRepository<OrganisationMembership, UUID> {
    /**
     * Reads the current membership status.
     */
    @Query("SELECT status FROM organisation_membership WHERE id = :membershipId")
    fun membershipStatus(membershipId: UUID): MembershipStatus?

    /**
     * Reads permission codes granted through assigned roles.
     */
    @Query(
        """
        SELECT DISTINCT p.code
        FROM membership_role mr
        JOIN role r ON r.id = mr.role_id
        JOIN role_permission rp ON rp.role_id = r.id
        JOIN permission p ON p.id = rp.permission_id
        WHERE mr.membership_id = :membershipId
          AND r.status = 'ACTIVE'
          AND p.status = 'ACTIVE'
          AND p.deprecated_at IS NULL
        """,
    )
    fun rolePermissionCodes(membershipId: UUID): Set<String>

    /**
     * Reads direct allow and deny permission assignments.
     */
    @Query(
        """
        SELECT p.code AS code, mp.effect AS effect
        FROM membership_permission mp
        JOIN permission p ON p.id = mp.permission_id
        WHERE mp.membership_id = :membershipId
          AND p.status = 'ACTIVE'
          AND p.deprecated_at IS NULL
        """,
    )
    fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment>
}

/**
 * JDBC outbound adapter for the permission resolution port.
 */
@Component
class JdbcPermissionResolutionQueries(
    private val repository: IamPermissionQueryRepository,
) : PermissionResolutionQueries {
    /**
     * Reads the current membership status.
     */
    override fun membershipStatus(membershipId: UUID): MembershipStatus? =
        repository.membershipStatus(membershipId)

    /**
     * Reads permission codes granted through assigned roles.
     */
    override fun rolePermissionCodes(membershipId: UUID): Set<String> =
        repository.rolePermissionCodes(membershipId)

    /**
     * Reads direct allow and deny permission assignments.
     */
    override fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment> =
        repository.directPermissionEffects(membershipId)
}

/**
 * JDBC outbound adapter for active organisation and branch selection.
 */
@Component
class JdbcMembershipSelectionLookup(
    private val users: AppUserRepository,
    private val memberships: OrganisationMembershipRepository,
) : MembershipSelectionLookup {
    /**
     * Finds an application user id by Keycloak subject.
     */
    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? =
        users.findByKeycloakSubject(keycloakSubject)?.id

    /**
     * Finds the user's membership in the selected organisation.
     */
    override fun findMembership(
        userId: UUID,
        organisationId: UUID,
    ): MembershipSelection? {
        val membership =
            memberships.findByUserIdAndOrganisationId(userId, organisationId) ?: return null
        return MembershipSelection(
            membershipId = membership.id,
            userId = membership.userId,
            organisationId = membership.organisationId,
            status = membership.status,
        )
    }

    /**
     * Lists branch ids assigned to the membership.
     */
    override fun findAssignedBranchIds(membershipId: UUID): List<UUID> =
        memberships.findAssignedBranchIds(membershipId)

    /**
     * Returns whether the membership is assigned to the branch.
     */
    override fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean = memberships.hasAssignedBranch(membershipId, branchId)
}

/**
 * JDBC outbound adapter for principal construction lookups.
 */
@Component
class JdbcAppPrincipalLookup(
    private val users: AppUserRepository,
    private val memberships: OrganisationMembershipRepository,
) : AppPrincipalLookup {
    /**
     * Finds a principal user by Keycloak subject.
     */
    override fun findPrincipalUserByKeycloakSubject(keycloakSubject: String): PrincipalUser? =
        users.findByKeycloakSubject(keycloakSubject)?.let { user ->
            PrincipalUser(
                id = user.id,
                keycloakSubject = user.keycloakSubject,
                email = user.email,
                fullName = user.fullName,
            )
        }

    /**
     * Finds a principal membership by membership id.
     */
    override fun findPrincipalMembershipById(membershipId: UUID): PrincipalMembership? =
        memberships.findById(membershipId).orElse(null)?.let { membership ->
            PrincipalMembership(
                id = membership.id,
                userId = membership.userId,
                organisationId = membership.organisationId,
            )
        }
}
