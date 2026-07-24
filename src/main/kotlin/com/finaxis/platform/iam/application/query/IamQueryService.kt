package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Application service for executing IAM read-side queries. Enforces tenant scope boundaries,
 * evaluates permissions, and canonicalizes sorting and pagination parameters.
 */
@Service
class IamQueryService(
    private val userQueries: IamUserQueries,
    private val roleQueries: IamRoleQueries,
    private val assignmentQueries: IamAssignmentQueries,
    private val permissionQueries: IamPermissionQueries,
    private val permissionGuard: PermissionGuard,
) {
    /** Searches user summaries, validating caller context and permissions. */
    fun searchUsers(
        organisationId: UUID,
        filter: UserInTenantFilter,
        caller: FoundationCaller,
    ): ApiPage<UserInTenantSummary> {
        verifyTenantScope(organisationId, caller)
        validatePage(filter.page, filter.size)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "user.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "user.view",
                )
            }
        }
        return userQueries.searchUsers(organisationId, filter)
    }

    /** Retrieves detailed user membership metadata, validating caller context and permissions. */
    fun getMembership(
        organisationId: UUID,
        membershipId: UUID,
        caller: FoundationCaller,
    ): MembershipDetail {
        verifyTenantScope(organisationId, caller)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "membership.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "membership.view",
                )
            }
        }
        return userQueries.findMembershipById(organisationId, membershipId)
            ?: throw ResourceNotFoundException(
                safeDetail = "Membership not found: $membershipId",
            )
    }

    /** Searches membership summaries, validating caller context and permissions. */
    fun searchMemberships(
        organisationId: UUID,
        filter: MembershipFilter,
        caller: FoundationCaller,
    ): ApiPage<MembershipSummary> {
        verifyTenantScope(organisationId, caller)
        validatePage(filter.page, filter.size)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "membership.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "membership.view",
                )
            }
        }
        return userQueries.searchMemberships(organisationId, filter)
    }

    /** Searches branch assignment summaries, validating caller context and permissions. */
    fun searchBranchAssignments(
        organisationId: UUID,
        filter: BranchAssignmentFilter,
        caller: FoundationCaller,
    ): ApiPage<BranchAssignmentSummary> {
        verifyTenantScope(organisationId, caller)
        validatePage(filter.page, filter.size)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "branch_assignment.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "branch_assignment.view",
                )
            }
        }
        return assignmentQueries.searchBranchAssignments(organisationId, filter)
    }

    /** Retrieves detailed branch assignment metadata, validating caller context and permissions. */
    fun getBranchAssignment(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): BranchAssignmentDetail {
        verifyTenantScope(organisationId, caller)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "branch_assignment.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "branch_assignment.view",
                )
            }
        }
        return assignmentQueries.findBranchAssignmentById(organisationId, id)
            ?: throw ResourceNotFoundException(
                safeDetail = "Branch assignment not found: $id",
            )
    }

    /** Searches roles within an organisation, validating caller context and permissions. */
    fun searchRoles(
        organisationId: UUID,
        filter: RoleFilter,
        caller: FoundationCaller,
    ): ApiPage<RoleSummary> {
        verifyTenantScope(organisationId, caller)
        validatePage(filter.page, filter.size)
        validateSort(filter.sortBy, filter.sortDir, allowedRoleSorts)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "role.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "role.view",
                )
            }
        }
        return roleQueries.searchRoles(organisationId, filter)
    }

    /** Retrieves detailed role metadata by id, validating caller context and permissions. */
    fun getRole(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): RoleDetail {
        verifyTenantScope(organisationId, caller)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "role.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "role.view",
                )
            }
        }
        return roleQueries.findRoleById(organisationId, id)
            ?: throw ResourceNotFoundException(
                safeDetail = "Role not found: $id",
            )
    }

    /** Searches role assignment summaries, validating caller context and permissions. */
    fun searchRoleAssignments(
        organisationId: UUID,
        filter: RoleAssignmentFilter,
        caller: FoundationCaller,
    ): ApiPage<RoleAssignmentSummary> {
        verifyTenantScope(organisationId, caller)
        validatePage(filter.page, filter.size)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "role_assignment.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "role_assignment.view",
                )
            }
        }
        return assignmentQueries.searchRoleAssignments(organisationId, filter)
    }

    /** Retrieves detailed role assignment metadata, validating caller context and permissions. */
    fun getRoleAssignment(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): RoleAssignmentDetail {
        verifyTenantScope(organisationId, caller)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "role_assignment.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "role_assignment.view",
                )
            }
        }
        return assignmentQueries.findRoleAssignmentById(organisationId, id)
            ?: throw ResourceNotFoundException(
                safeDetail = "Role assignment not found: $id",
            )
    }

    /** Searches system permissions catalog, validating caller context and permissions. */
    fun searchPermissions(
        organisationId: UUID,
        filter: PermissionFilter,
        caller: FoundationCaller,
    ): ApiPage<PermissionSummary> {
        verifyTenantScope(organisationId, caller)
        validatePage(filter.page, filter.size)
        validateSort(filter.sortBy, filter.sortDir, allowedPermissionSorts)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "permission.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "permission.view",
                )
            }
        }
        return permissionQueries.searchPermissions(filter)
    }

    /** Retrieves detailed permission metadata, validating caller context and permissions. */
    fun getPermission(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): PermissionDetail {
        verifyTenantScope(organisationId, caller)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "permission.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "permission.view",
                )
            }
        }
        return permissionQueries.findPermissionById(id)
            ?: throw ResourceNotFoundException(
                safeDetail = "Permission not found: $id",
            )
    }

    /** Lists role permissions in a role, validating caller context and permissions. */
    fun listRolePermissions(
        organisationId: UUID,
        roleId: UUID,
        filter: RolePermissionFilter,
        caller: FoundationCaller,
    ): ApiPage<RolePermissionSummary> {
        verifyTenantScope(organisationId, caller)
        validatePage(filter.page, filter.size)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "role.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "role.view",
                )
            }
        }
        return roleQueries.listRolePermissions(organisationId, roleId, filter)
    }

    /** Retrieves detailed role permission metadata, validating caller context and permissions. */
    fun getRolePermission(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): RolePermissionDetail {
        verifyTenantScope(organisationId, caller)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "role.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "role.view",
                )
            }
        }
        return roleQueries.findRolePermissionById(organisationId, id)
            ?: throw ResourceNotFoundException(
                safeDetail = "Role permission not found: $id",
            )
    }

    private fun verifyTenantScope(
        organisationId: UUID,
        caller: FoundationCaller,
    ) {
        if (caller is TenantCaller && caller.activeOrganisationId != organisationId) {
            throw ResourceNotFoundException(
                safeDetail = "Organisation not found: $organisationId",
            )
        }
    }

    private fun validatePage(
        page: Int,
        size: Int,
    ) {
        require(page >= 0) { "Page number must not be negative" }
        require(size in MINIMUM_PAGE_SIZE..MAXIMUM_PAGE_SIZE) {
            "Page size must be between $MINIMUM_PAGE_SIZE and $MAXIMUM_PAGE_SIZE"
        }
    }

    private fun validateSort(
        sortBy: String?,
        sortDir: String?,
        allowedFields: Set<String>,
    ) {
        if (sortBy != null) {
            require(sortBy in allowedFields) { "Sorting by field '$sortBy' is not allowed" }
        }
        if (sortDir != null) {
            val dir = sortDir.uppercase()
            require(dir == "ASC" || dir == "DESC") { "Sort direction must be ASC or DESC" }
        }
    }

    private companion object {
        const val MINIMUM_PAGE_SIZE = 1
        const val MAXIMUM_PAGE_SIZE = 100
        val allowedRoleSorts = setOf("roleCode", "roleName", "status", "createdAt")
        val allowedPermissionSorts =
            setOf(
                "permissionCode",
                "permissionName",
                "riskLevel",
                "status",
                "createdAt",
            )
    }
}
