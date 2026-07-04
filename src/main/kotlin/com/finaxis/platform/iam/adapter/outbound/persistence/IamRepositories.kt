package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.domain.AppUser
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationMembership
import com.finaxis.platform.iam.domain.Permission
import com.finaxis.platform.iam.domain.Role
import java.util.UUID
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Component

/**
 * Spring Data JDBC repository for application users.
 */
interface AppUserRepository : CrudRepository<AppUser, UUID> {
    fun findByKeycloakSubject(keycloakSubject: String): AppUser?
}

/**
 * Spring Data JDBC repository for organisation memberships and assigned branch scopes.
 */
interface OrganisationMembershipRepository : CrudRepository<OrganisationMembership, UUID> {
    fun findByUserIdAndOrganisationId(userId: UUID, organisationId: UUID): OrganisationMembership?

    @Query("SELECT branch_id FROM membership_branch_scope WHERE membership_id = :membershipId ORDER BY branch_id")
    fun findAssignedBranchIds(membershipId: UUID): List<UUID>

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
    fun hasAssignedBranch(membershipId: UUID, branchId: UUID): Boolean
}

/**
 * Spring Data JDBC repository for permission catalogue entries.
 */
interface PermissionRepository : CrudRepository<Permission, UUID> {
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
    @Query("SELECT status FROM organisation_membership WHERE id = :membershipId")
    fun membershipStatus(membershipId: UUID): MembershipStatus?

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
    override fun membershipStatus(membershipId: UUID): MembershipStatus? {
        return repository.membershipStatus(membershipId)
    }

    override fun rolePermissionCodes(membershipId: UUID): Set<String> {
        return repository.rolePermissionCodes(membershipId)
    }

    override fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment> {
        return repository.directPermissionEffects(membershipId)
    }
}

/**
 * JDBC outbound adapter for active organisation and branch selection.
 */
@Component
class JdbcMembershipSelectionLookup(
    private val users: AppUserRepository,
    private val memberships: OrganisationMembershipRepository,
) : MembershipSelectionLookup {
    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? {
        return users.findByKeycloakSubject(keycloakSubject)?.id
    }

    override fun findMembership(userId: UUID, organisationId: UUID): MembershipSelection? {
        val membership = memberships.findByUserIdAndOrganisationId(userId, organisationId) ?: return null
        return MembershipSelection(
            membershipId = membership.id,
            userId = membership.userId,
            organisationId = membership.organisationId,
            status = membership.status,
        )
    }

    override fun findAssignedBranchIds(membershipId: UUID): List<UUID> {
        return memberships.findAssignedBranchIds(membershipId)
    }

    override fun hasAssignedBranch(membershipId: UUID, branchId: UUID): Boolean {
        return memberships.hasAssignedBranch(membershipId, branchId)
    }
}
