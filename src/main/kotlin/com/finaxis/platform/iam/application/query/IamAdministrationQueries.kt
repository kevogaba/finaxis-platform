package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.web.api.ApiPage
import java.util.UUID

/** Filter parameters for user-in-tenant queries. */
data class UserInTenantFilter(
    val q: String? = null,
    val userStatus: String? = null,
    val membershipStatus: String? = null,
    val page: Int = 0,
    val size: Int = 25,
)

/** Filter parameters for organisation membership queries. */
data class MembershipFilter(
    val q: String? = null,
    val membershipStatus: String? = null,
    val membershipType: String? = null,
    val page: Int = 0,
    val size: Int = 25,
)

/** Filter parameters for branch assignment queries. */
data class BranchAssignmentFilter(
    val branchId: UUID? = null,
    val assignmentType: String? = null,
    val status: String? = null,
    val page: Int = 0,
    val size: Int = 25,
    /**
     * The caller's selected branch: the default for an unfiltered list when the caller may view
     * it (every tenant caller alike); never a gate, it neither narrows nor widens what is visible.
     */
    val pinnedBranchId: UUID? = null,
)

/** Filter parameters for role queries. */
data class RoleFilter(
    val q: String? = null,
    val status: String? = null,
    val systemRole: Boolean? = null,
    val page: Int = 0,
    val size: Int = 25,
    val sortBy: String? = null,
    val sortDir: String? = null,
)

/** Filter parameters for role assignment queries. */
data class RoleAssignmentFilter(
    val userId: UUID? = null,
    val roleId: UUID? = null,
    val branchId: UUID? = null,
    val scopeType: String? = null,
    val status: String? = null,
    val page: Int = 0,
    val size: Int = 25,
)

/** Filter parameters for permission queries. */
data class PermissionFilter(
    val q: String? = null,
    val riskLevel: String? = null,
    val status: String? = null,
    val page: Int = 0,
    val size: Int = 25,
    val sortBy: String? = null,
    val sortDir: String? = null,
)

/** Filter parameters for role-permission grant queries. */
data class RolePermissionFilter(
    val page: Int = 0,
    val size: Int = 25,
)

/** Outbound port for query operations against IAM user and membership data. */
interface IamUserQueries {
    /** Searches users in an organisation. */
    fun searchUsers(
        organisationId: UUID,
        filter: UserInTenantFilter,
    ): ApiPage<UserInTenantSummary>

    /** Finds a user in an organisation without exposing cross-tenant membership data. */
    fun findUserInTenant(
        organisationId: UUID,
        userId: UUID,
    ): UserInTenantDetail?

    /** Finds a global user account by id, whatever tenant memberships it holds. */
    fun findUserById(userId: UUID): GlobalUserDetail?

    /** Searches memberships in an organisation. */
    fun searchMemberships(
        organisationId: UUID,
        filter: MembershipFilter,
    ): ApiPage<MembershipSummary>

    /** Finds detailed membership by id. */
    fun findMembershipById(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipDetail?
}

/** Outbound port for query operations against IAM roles and role permissions. */
interface IamRoleQueries {
    /** Searches roles in an organisation. */
    fun searchRoles(
        organisationId: UUID,
        filter: RoleFilter,
    ): ApiPage<RoleSummary>

    /** Finds detailed role metadata by id. */
    fun findRoleById(
        organisationId: UUID,
        id: UUID,
    ): RoleDetail?

    /** Lists role permissions inside an organisation. */
    fun listRolePermissions(
        organisationId: UUID,
        roleId: UUID,
        filter: RolePermissionFilter,
    ): ApiPage<RolePermissionSummary>

    /** Finds the grant of [permissionCode] on [roleId], the one row a grant just wrote. */
    fun findRolePermissionByRoleAndCode(
        organisationId: UUID,
        roleId: UUID,
        permissionCode: String,
    ): RolePermissionDetail?
}

/** Outbound port for query operations against IAM branch and role assignments. */
interface IamAssignmentQueries {
    /**
     * Searches branch assignments in an organisation. A non-null [restrictToBranchIds] limits the
     * rows, their total and their paging to those branches, in the query itself.
     */
    fun searchBranchAssignments(
        organisationId: UUID,
        filter: BranchAssignmentFilter,
        restrictToBranchIds: Set<UUID>? = null,
    ): ApiPage<BranchAssignmentSummary>

    /** Finds detailed branch assignment by id. */
    fun findBranchAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): BranchAssignmentDetail?

    /**
     * Searches role assignments in an organisation. A non-null [restrictToBranchIds] limits the
     * result to BRANCH-scope assignments on those branches (TENANT-scope rows are excluded), in
     * the query itself, so totals and paging stay exact.
     */
    fun searchRoleAssignments(
        organisationId: UUID,
        filter: RoleAssignmentFilter,
        restrictToBranchIds: Set<UUID>? = null,
    ): ApiPage<RoleAssignmentSummary>

    /** Finds detailed role assignment by id. */
    fun findRoleAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): RoleAssignmentDetail?
}

/** Outbound port for query operations against IAM permissions. */
interface IamPermissionQueries {
    /** Searches system permissions. */
    fun searchPermissions(filter: PermissionFilter): ApiPage<PermissionSummary>

    /** Finds detailed permission metadata by id. */
    fun findPermissionById(id: UUID): PermissionDetail?
}
