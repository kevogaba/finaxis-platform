package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.application.shownTo
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.requireValidPage
import com.finaxis.platform.common.web.api.requireValidSort
import com.finaxis.platform.lifecycle.BranchVisibility
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
    @GatedRead
    fun searchUsers(
        organisationId: UUID,
        filter: UserInTenantFilter,
        caller: FoundationCaller,
    ): ApiPage<UserInTenantSummary> {
        verifyTenantScope(organisationId, caller)
        requireValidPage(filter.page, filter.size)
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
        requireValidSort(filter.sortBy, filter.sortDir, allowedUserSorts)
        return userQueries.searchUsers(organisationId, filter).let { page ->
            page.copy(items = page.items.map { it.withMakerFor(caller) })
        }
    }

    /** Retrieves user metadata within an organisation, validating caller scope and permission. */
    @GatedRead
    fun getUserInTenant(
        organisationId: UUID,
        userId: UUID,
        caller: FoundationCaller,
    ): UserInTenantDetail {
        verifyTenantScope(organisationId, caller)
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
        return userQueries.findUserInTenant(organisationId, userId)?.withMakerFor(caller)
            ?: throw ResourceNotFoundException(
                safeDetail = "User not found: $userId",
            )
    }

    /**
     * Retrieves a global user account for a platform caller holding `user.view` in the platform
     * organisation. It does not need a platform membership: the platform user lifecycle routes
     * act on any user account, so they read their result back here.
     */
    @GatedRead
    fun getGlobalUser(
        userId: UUID,
        caller: PlatformCaller,
    ): GlobalUserDetail {
        permissionGuard.requirePlatformPermission(caller.actorId, "user.view")
        return userQueries.findUserById(userId)
            ?: throw ResourceNotFoundException(
                safeDetail = "User account was not found.",
            )
    }

    /** Retrieves detailed user membership metadata, validating caller context and permissions. */
    @GatedRead
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
        return findMembership(organisationId, membershipId).withMakerFor(caller)
    }

    private fun findMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipDetail =
        userQueries.findMembershipById(organisationId, membershipId)
            ?: throw ResourceNotFoundException(
                safeDetail = "Membership not found: $membershipId",
            )

    /** Searches membership summaries, validating caller context and permissions. */
    @GatedRead
    fun searchMemberships(
        organisationId: UUID,
        filter: MembershipFilter,
        caller: FoundationCaller,
    ): ApiPage<MembershipSummary> {
        verifyTenantScope(organisationId, caller)
        requireValidPage(filter.page, filter.size)
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
        requireValidSort(filter.sortBy, filter.sortDir, allowedMembershipSorts)
        return userQueries.searchMemberships(organisationId, filter).let { page ->
            page.copy(items = page.items.map { it.withMakerFor(caller) })
        }
    }

    /**
     * Searches branch assignment summaries, validating caller context and permissions. A tenant
     * caller sees the assignments of the branches it holds `branch_assignment.view` on (all of
     * them for a tenant-wide grant), restricted in the query; an explicit branch outside that set,
     * or no grant at all, is 403. Without an explicit branch the list defaults to the selected
     * branch if the caller may view it, for every tenant caller alike, unless a `user_id` is
     * given.
     */
    @GatedRead
    fun searchBranchAssignments(
        organisationId: UUID,
        filter: BranchAssignmentFilter,
        caller: FoundationCaller,
    ): ApiPage<BranchAssignmentSummary> {
        verifyTenantScope(organisationId, caller)
        requireValidPage(filter.page, filter.size)
        var restriction: Set<UUID>? = null
        var branchId = filter.branchId
        when (caller) {
            is TenantCaller -> {
                val visibility = branchAssignmentVisibility(caller, organisationId)
                restriction = visibility.requireListRestriction()
                if (branchId != null && !visibility.canSee(branchId)) {
                    throw ForbiddenOperationException()
                }
                // One rule for every tenant caller: with no explicit branch the list defaults to
                // the selected branch when the caller may view it, and is every viewable branch
                // otherwise. It only ever narrows; visibility alone decides what may be seen.
                branchId = branchId ?: filter.defaultBranch { visibility.canSee(it) }
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "branch_assignment.view",
                )
                branchId = branchId ?: filter.defaultBranch { true }
            }
        }
        requireValidSort(filter.sortBy, filter.sortDir, allowedBranchAssignmentSorts)
        return assignmentQueries.searchBranchAssignments(
            organisationId,
            filter.copy(branchId = branchId),
            restriction,
        )
    }

    /**
     * Retrieves detailed branch assignment metadata, validating caller context and permissions. A
     * tenant-wide holder gets 404 for an unknown id; a branch-scoped holder gets 403 for an unknown
     * id and for an assignment on a branch it holds nothing on, so existence is no oracle.
     */
    @GatedRead
    fun getBranchAssignment(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): BranchAssignmentDetail {
        verifyTenantScope(organisationId, caller)
        val visibility =
            when (caller) {
                is TenantCaller -> {
                    branchAssignmentVisibility(caller, organisationId)
                }

                is PlatformCaller -> {
                    permissionGuard.requirePlatformPermission(
                        caller.actorId,
                        "branch_assignment.view",
                    )
                    BranchVisibility.AllBranches
                }
            }
        val detail = assignmentQueries.findBranchAssignmentById(organisationId, id)
        return when {
            detail == null && visibility == BranchVisibility.AllBranches -> {
                throw ResourceNotFoundException(safeDetail = "Branch assignment not found: $id")
            }

            detail == null || !visibility.canSee(detail.branchId) -> {
                throw ForbiddenOperationException()
            }

            else -> {
                detail
            }
        }
    }

    private fun branchAssignmentVisibility(
        caller: TenantCaller,
        organisationId: UUID,
    ): BranchVisibility =
        permissionGuard.branchVisibility(caller.actorId, organisationId, "branch_assignment.view")

    /** Searches roles within an organisation, validating caller context and permissions. */
    @GatedRead
    fun searchRoles(
        organisationId: UUID,
        filter: RoleFilter,
        caller: FoundationCaller,
    ): ApiPage<RoleSummary> {
        verifyTenantScope(organisationId, caller)
        requireValidPage(filter.page, filter.size)
        requireValidSort(filter.sortBy, filter.sortDir, allowedRoleSorts)
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
        val page = roleQueries.searchRoles(organisationId, filter)
        return if (permissionGuard.listsEveryRoleAssignment(caller, organisationId)) {
            page
        } else {
            page.copy(items = page.items.map { it.copy(activeUserCount = null) })
        }
    }

    /** Retrieves detailed role metadata by id, validating caller context and permissions. */
    @GatedRead
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

    /**
     * Searches role assignment summaries, validating caller context and permissions. A tenant-wide
     * `role_assignment.view` sees every row; a branch-scoped holder sees only BRANCH-scope rows on
     * the branches it holds the view on, restricted in the query, so any `scope_type` other than
     * BRANCH (like every enum-like filter, matched exactly) is an empty page. A `branch_id`
     * outside that set, or no grant at all, is 403.
     */
    @GatedRead
    fun searchRoleAssignments(
        organisationId: UUID,
        filter: RoleAssignmentFilter,
        caller: FoundationCaller,
    ): ApiPage<RoleAssignmentSummary> {
        verifyTenantScope(organisationId, caller)
        requireValidPage(filter.page, filter.size)
        val restriction =
            when (caller) {
                is TenantCaller -> {
                    val visibility = roleAssignmentVisibility(caller, organisationId)
                    val restriction = visibility.requireListRestriction()
                    if (filter.branchId?.let { !visibility.canSee(it) } == true) {
                        throw ForbiddenOperationException()
                    }
                    restriction
                }

                is PlatformCaller -> {
                    permissionGuard.requirePlatformPermission(
                        caller.actorId,
                        "role_assignment.view",
                    )
                    null
                }
            }
        return assignmentQueries.searchRoleAssignments(organisationId, filter, restriction)
    }

    /**
     * Retrieves detailed role assignment metadata, validating caller context and permissions. A
     * BRANCH-scope row is read with the view at that branch; a TENANT-scope row needs the
     * tenant-wide view. A branch-scoped holder gets 403 for an unknown id and for any row it may
     * not see, a tenant-wide holder gets 404 for an unknown id.
     */
    @GatedRead
    fun getRoleAssignment(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): RoleAssignmentDetail {
        verifyTenantScope(organisationId, caller)
        val visibility =
            when (caller) {
                is TenantCaller -> {
                    roleAssignmentVisibility(caller, organisationId)
                }

                is PlatformCaller -> {
                    permissionGuard.requirePlatformPermission(
                        caller.actorId,
                        "role_assignment.view",
                    )
                    BranchVisibility.AllBranches
                }
            }
        val detail = assignmentQueries.findRoleAssignmentById(organisationId, id)
        return when {
            detail == null && visibility == BranchVisibility.AllBranches -> {
                throw ResourceNotFoundException(safeDetail = "Role assignment not found: $id")
            }

            detail == null || !canSeeRoleAssignment(visibility, detail) -> {
                throw ForbiddenOperationException()
            }

            else -> {
                detail
            }
        }
    }

    private fun canSeeRoleAssignment(
        visibility: BranchVisibility,
        detail: RoleAssignmentDetail,
    ): Boolean =
        visibility == BranchVisibility.AllBranches ||
            (detail.scopeType == BRANCH_SCOPE && detail.branchId?.let(visibility::canSee) == true)

    private fun roleAssignmentVisibility(
        caller: TenantCaller,
        organisationId: UUID,
    ): BranchVisibility =
        permissionGuard.branchVisibility(caller.actorId, organisationId, "role_assignment.view")

    /** Searches system permissions catalog, validating caller context and permissions. */
    @GatedRead
    fun searchPermissions(
        organisationId: UUID,
        filter: PermissionFilter,
        caller: FoundationCaller,
    ): ApiPage<PermissionSummary> {
        verifyTenantScope(organisationId, caller)
        requireValidPage(filter.page, filter.size)
        requireValidSort(filter.sortBy, filter.sortDir, allowedPermissionSorts)
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
    @GatedRead
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
    @GatedRead
    fun listRolePermissions(
        organisationId: UUID,
        roleId: UUID,
        filter: RolePermissionFilter,
        caller: FoundationCaller,
    ): ApiPage<RolePermissionSummary> {
        verifyTenantScope(organisationId, caller)
        requireValidPage(filter.page, filter.size)
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

    /**
     * Retrieves the grant of [permissionCode] on [roleId] by its key, validating caller context
     * and `role.view`: the read-back of a grant, asking for exactly the row the mutation wrote
     * (never "the first page of the role's grants", which a role with many grants could miss).
     */
    @GatedRead
    fun getRolePermissionByCode(
        organisationId: UUID,
        roleId: UUID,
        permissionCode: String,
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
        return roleQueries.findRolePermissionByRoleAndCode(organisationId, roleId, permissionCode)
            ?: throw ResourceNotFoundException(
                safeDetail = "Role permission not found: $permissionCode",
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

    internal companion object {
        const val BRANCH_SCOPE = "BRANCH"

        /** The `sort_by` values the tenant user list accepts; the store must order by each. */
        internal val allowedUserSorts =
            setOf("createdAt", "username", "displayName", "email", "userStatus", "membershipStatus")

        /** The `sort_by` values the membership list accepts; the store must order by each. */
        internal val allowedMembershipSorts =
            setOf("createdAt", "membershipStatus", "membershipType")

        /** The `sort_by` values the tenant branch-assignment list accepts. */
        internal val allowedBranchAssignmentSorts = setOf("assignedAt", "assignmentType", "status")
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

/**
 * The selected branch as the list's default, when the caller may see it. None once the caller
 * names a user: that asks for the user's assignments everywhere, and a default branch would
 * silently drop the rest.
 */
private inline fun BranchAssignmentFilter.defaultBranch(canSee: (UUID) -> Boolean): UUID? =
    pinnedBranchId?.takeIf { userId == null && canSee(it) }

/**
 * True when the caller may list every role assignment of the organisation, which is what a role's
 * `active_user_count` counts: a tenant-wide `role_assignment.view` (the platform organisation's,
 * for a platform caller). A branch-scoped holder sees only some of those rows, so the count is
 * withheld rather than leak the others.
 */
private fun PermissionGuard.listsEveryRoleAssignment(
    caller: FoundationCaller,
    organisationId: UUID,
): Boolean =
    when (caller) {
        is TenantCaller -> {
            branchVisibility(caller.actorId, organisationId, "role_assignment.view")
        }

        is PlatformCaller -> {
            branchVisibility(caller.actorId, PlatformOrganisation.ID, "role_assignment.view")
        }
    } is BranchVisibility.AllBranches

// A maker is shown to a tenant caller only when they belong to the tenant, so a platform operator
// who acted for it is not named to its own users; a platform caller sees every maker.
private fun UserInTenantSummary.withMakerFor(caller: FoundationCaller) =
    copy(createdBy = createdBy.shownTo(caller is PlatformCaller))

private fun UserInTenantDetail.withMakerFor(caller: FoundationCaller) =
    copy(createdBy = createdBy.shownTo(caller is PlatformCaller))

private fun MembershipSummary.withMakerFor(caller: FoundationCaller) =
    copy(createdBy = createdBy.shownTo(caller is PlatformCaller))

private fun MembershipDetail.withMakerFor(caller: FoundationCaller) =
    copy(createdBy = createdBy.shownTo(caller is PlatformCaller))
