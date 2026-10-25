package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.common.application.ActorRef
import com.finaxis.platform.common.persistence.ActorRefLookup
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.api.boundedPageOffset
import com.finaxis.platform.iam.application.query.BranchAssignmentDetail
import com.finaxis.platform.iam.application.query.BranchAssignmentFilter
import com.finaxis.platform.iam.application.query.BranchAssignmentSummary
import com.finaxis.platform.iam.application.query.GlobalUserDetail
import com.finaxis.platform.iam.application.query.IamAssignmentQueries
import com.finaxis.platform.iam.application.query.IamRoleQueries
import com.finaxis.platform.iam.application.query.IamUserQueries
import com.finaxis.platform.iam.application.query.MembershipDetail
import com.finaxis.platform.iam.application.query.MembershipFilter
import com.finaxis.platform.iam.application.query.MembershipSummary
import com.finaxis.platform.iam.application.query.RoleAssignmentDetail
import com.finaxis.platform.iam.application.query.RoleAssignmentFilter
import com.finaxis.platform.iam.application.query.RoleAssignmentSummary
import com.finaxis.platform.iam.application.query.RoleDetail
import com.finaxis.platform.iam.application.query.RoleFilter
import com.finaxis.platform.iam.application.query.RolePermissionDetail
import com.finaxis.platform.iam.application.query.RolePermissionFilter
import com.finaxis.platform.iam.application.query.RolePermissionSummary
import com.finaxis.platform.iam.application.query.RoleSummary
import com.finaxis.platform.iam.application.query.UserInTenantDetail
import com.finaxis.platform.iam.application.query.UserInTenantFilter
import com.finaxis.platform.iam.application.query.UserInTenantSummary
import com.finaxis.platform.iam.application.query.deriveInvitationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION_VIEW_REQUIREMENT
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Result
import org.jooq.SortField
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * jOOQ implementation of the IAM query ports.
 * Executes database reads for users, roles, assignments, role grants, and memberships. The
 * permission catalogue reads live in [JooqIamPermissionQueries].
 */
@Component
class JooqIamAdministrationQueries(
    private val dsl: DSLContext,
) : IamUserQueries,
    IamRoleQueries,
    IamAssignmentQueries {
    private val roleColumns = RoleSummaryColumns(dsl)
    private val actors = ActorRefLookup(dsl)
    private val invitations = InvitationDispatchReader(dsl)

    /** The maker of the membership [record] row, resolved with one lookup. */
    private fun makerOf(
        organisationId: UUID,
        record: Record,
    ): ActorRef? =
        record.get(USER_ORGANISATION_MEMBERSHIP.CREATED_BY)?.let { id ->
            actors.find(organisationId, listOf(id))[id]
        }

    /** Maps [this] page of rows with the makers of all of them resolved in one lookup. */
    private fun <R : Record, T> Result<R>.mapWithMakers(
        organisationId: UUID,
        map: (R, Map<UUID, ActorRef>) -> T,
    ): List<T> {
        val makers =
            actors.find(
                organisationId,
                this.map { it.get(USER_ORGANISATION_MEMBERSHIP.CREATED_BY) },
            )
        return this.map { map(it, makers) }
    }

    override fun searchUsers(
        organisationId: UUID,
        filter: UserInTenantFilter,
    ): ApiPage<UserInTenantSummary> {
        var condition: Condition = USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId)
        filter.userStatus?.let { condition = condition.and(USER_ACCOUNT.STATUS.eq(it)) }
        filter.membershipStatus?.let {
            condition = condition.and(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS.eq(it))
        }
        filter.q?.let { q ->
            val query = "%$q%"
            condition =
                condition.and(
                    USER_ACCOUNT.USERNAME
                        .likeIgnoreCase(query)
                        .or(USER_ACCOUNT.EMAIL.likeIgnoreCase(query))
                        .or(USER_ACCOUNT.DISPLAY_NAME.likeIgnoreCase(query)),
                )
        }

        val total =
            dsl
                .fetchCount(
                    USER_ORGANISATION_MEMBERSHIP
                        .join(USER_ACCOUNT)
                        .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID)),
                    condition,
                ).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)

        val items =
            dsl
                .select(
                    USER_ACCOUNT.ID,
                    USER_ACCOUNT.USERNAME,
                    USER_ACCOUNT.EMAIL,
                    USER_ACCOUNT.DISPLAY_NAME,
                    USER_ACCOUNT.STATUS,
                    USER_ACCOUNT.CREATED_AT,
                    USER_ORGANISATION_MEMBERSHIP.ID,
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE,
                    USER_ORGANISATION_MEMBERSHIP.CREATED_AT,
                    USER_ORGANISATION_MEMBERSHIP.CREATED_BY,
                ).from(USER_ORGANISATION_MEMBERSHIP)
                .join(USER_ACCOUNT)
                .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID))
                .where(condition)
                .orderBy(
                    userSortOrder(filter),
                    USER_ACCOUNT.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch()
                .mapWithMakers(organisationId) { row, makers -> row.toUserInTenantSummary(makers) }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findUserInTenant(
        organisationId: UUID,
        userId: UUID,
    ): UserInTenantDetail? =
        dsl
            .select(
                USER_ACCOUNT.ID,
                USER_ACCOUNT.USERNAME,
                USER_ACCOUNT.EMAIL,
                USER_ACCOUNT.DISPLAY_NAME,
                USER_ACCOUNT.STATUS,
                USER_ACCOUNT.CREATED_AT,
                USER_ORGANISATION_MEMBERSHIP.ID,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE,
                USER_ORGANISATION_MEMBERSHIP.CREATED_AT,
                USER_ORGANISATION_MEMBERSHIP.CREATED_BY,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .join(USER_ACCOUNT)
            .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID))
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ACCOUNT.ID.eq(userId))
            .fetchOne { it.toUserInTenantDetail(makerOf(organisationId, it)) }

    override fun findUserById(userId: UUID): GlobalUserDetail? =
        dsl
            .select(USER_ACCOUNT.ID, USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(userId))
            .fetchOne { record ->
                GlobalUserDetail(
                    id = requireNotNull(record.get(USER_ACCOUNT.ID)),
                    userStatus = requireNotNull(record.get(USER_ACCOUNT.STATUS)),
                )
            }

    override fun findMembershipById(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipDetail? =
        dsl
            .select(
                USER_ORGANISATION_MEMBERSHIP.ID,
                USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                USER_ORGANISATION_MEMBERSHIP.USER_ID,
                USER_ACCOUNT.USERNAME,
                USER_ACCOUNT.EMAIL,
                USER_ACCOUNT.DISPLAY_NAME,
                USER_ACCOUNT.STATUS,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE,
                USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID,
                USER_ORGANISATION_MEMBERSHIP.CREATED_AT,
                USER_ORGANISATION_MEMBERSHIP.UPDATED_AT,
                USER_ORGANISATION_MEMBERSHIP.CREATED_BY,
                *invitations.fields.toTypedArray(),
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .join(USER_ACCOUNT)
            .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID))
            .leftJoin(invitations.table)
            .on(DSL.trueCondition())
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .and(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .fetchOne { record ->
                val detail = record.toMembershipDetail(makerOf(organisationId, record))
                detail.copy(
                    invitation =
                        deriveInvitationStatus(detail.membershipStatus, invitations.of(record)),
                )
            }

    override fun searchMemberships(
        organisationId: UUID,
        filter: MembershipFilter,
    ): ApiPage<MembershipSummary> {
        val condition = membershipSearchCondition(organisationId, filter)

        val total =
            dsl
                .fetchCount(
                    USER_ORGANISATION_MEMBERSHIP
                        .join(USER_ACCOUNT)
                        .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID)),
                    condition,
                ).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(
                    USER_ORGANISATION_MEMBERSHIP.ID,
                    USER_ORGANISATION_MEMBERSHIP.USER_ID,
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE,
                    USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID,
                    USER_ORGANISATION_MEMBERSHIP.CREATED_AT,
                    USER_ACCOUNT.USERNAME,
                    USER_ACCOUNT.EMAIL,
                    USER_ACCOUNT.DISPLAY_NAME,
                    BRANCH.BRANCH_NAME,
                    USER_ORGANISATION_MEMBERSHIP.CREATED_BY,
                ).from(USER_ORGANISATION_MEMBERSHIP)
                .join(USER_ACCOUNT)
                .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID))
                .leftJoin(BRANCH)
                .on(USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID.eq(BRANCH.ID))
                .where(condition)
                .orderBy(
                    membershipSortOrder(filter),
                    USER_ORGANISATION_MEMBERSHIP.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch()
                .mapWithMakers(organisationId) { row, makers -> row.toMembershipSummary(makers) }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    private fun membershipSearchCondition(
        organisationId: UUID,
        filter: MembershipFilter,
    ): Condition {
        var condition: Condition = USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId)
        filter.membershipStatus?.let {
            condition = condition.and(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS.eq(it))
        }
        filter.membershipType?.let {
            condition = condition.and(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE.eq(it))
        }
        filter.userId?.let {
            condition = condition.and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(it))
        }
        filter.q?.let { q ->
            val query = "%$q%"
            condition =
                condition.and(
                    USER_ACCOUNT.USERNAME
                        .likeIgnoreCase(query)
                        .or(USER_ACCOUNT.EMAIL.likeIgnoreCase(query))
                        .or(USER_ACCOUNT.DISPLAY_NAME.likeIgnoreCase(query)),
                )
        }
        return condition
    }

    override fun searchBranchAssignments(
        organisationId: UUID,
        filter: BranchAssignmentFilter,
        restrictToBranchIds: Set<UUID>?,
    ): ApiPage<BranchAssignmentSummary> {
        var condition: Condition = USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId)
        restrictToBranchIds?.let {
            condition = condition.and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.`in`(it))
        }
        filter.branchId?.let { condition = condition.and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(it)) }
        filter.assignmentType?.let {
            condition =
                condition.and(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE.eq(it))
        }
        filter.status?.let { condition = condition.and(USER_BRANCH_ASSIGNMENT.STATUS.eq(it)) }
        filter.userId?.let { condition = condition.and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(it)) }

        val total = dsl.fetchCount(USER_BRANCH_ASSIGNMENT, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(
                    USER_BRANCH_ASSIGNMENT.ID,
                    USER_BRANCH_ASSIGNMENT.USER_ID,
                    USER_BRANCH_ASSIGNMENT.BRANCH_ID,
                    USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE,
                    USER_BRANCH_ASSIGNMENT.STATUS,
                    USER_BRANCH_ASSIGNMENT.ASSIGNED_AT,
                    USER_ACCOUNT.USERNAME,
                    USER_ACCOUNT.DISPLAY_NAME,
                    BRANCH.BRANCH_NAME,
                ).from(USER_BRANCH_ASSIGNMENT)
                .join(USER_ACCOUNT)
                .on(USER_BRANCH_ASSIGNMENT.USER_ID.eq(USER_ACCOUNT.ID))
                .join(BRANCH)
                .on(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(BRANCH.ID))
                .where(condition)
                .orderBy(
                    assignmentSortOrder(filter),
                    USER_BRANCH_ASSIGNMENT.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch { it.toBranchAssignmentSummary() }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findBranchAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): BranchAssignmentDetail? =
        dsl
            .select(
                USER_BRANCH_ASSIGNMENT.asterisk(),
                USER_ACCOUNT.USERNAME,
                USER_ACCOUNT.DISPLAY_NAME,
                BRANCH.BRANCH_NAME,
            ).from(USER_BRANCH_ASSIGNMENT)
            .join(USER_ACCOUNT)
            .on(USER_BRANCH_ASSIGNMENT.USER_ID.eq(USER_ACCOUNT.ID))
            .join(BRANCH)
            .on(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(BRANCH.ID))
            .where(USER_BRANCH_ASSIGNMENT.ID.eq(id))
            .and(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .fetchOne { joined ->
                val record = joined.into(USER_BRANCH_ASSIGNMENT)
                BranchAssignmentDetail(
                    id = requireNotNull(record.id),
                    organisationId = requireNotNull(record.organisationId),
                    userId = requireNotNull(record.userId),
                    branchId = requireNotNull(record.branchId),
                    assignmentType = requireNotNull(record.assignmentType),
                    status = requireNotNull(record.status),
                    assignedAt = requireNotNull(record.assignedAt).toInstant(),
                    assignedBy = record.assignedBy,
                    revokedAt = record.revokedAt?.toInstant(),
                    revokedBy = record.revokedBy,
                    createdAt = requireNotNull(record.createdAt).toInstant(),
                    updatedAt = requireNotNull(record.updatedAt).toInstant(),
                    username = requireNotNull(joined.get(USER_ACCOUNT.USERNAME)),
                    displayName = requireNotNull(joined.get(USER_ACCOUNT.DISPLAY_NAME)),
                    branchName = requireNotNull(joined.get(BRANCH.BRANCH_NAME)),
                )
            }

    override fun searchRoles(
        organisationId: UUID,
        filter: RoleFilter,
    ): ApiPage<RoleSummary> {
        var condition: Condition = ROLE.ORGANISATION_ID.eq(organisationId)
        // An archived role is final and listed only on request: a status filter shows it, and no
        // filter hides it (ADR 0035). `idx_role_organisation_status` serves either shape.
        condition =
            filter.status?.let { condition.and(ROLE.STATUS.eq(it)) }
                ?: condition.and(ROLE.STATUS.ne(RoleStatus.ARCHIVED.name))
        filter.systemRole?.let { condition = condition.and(ROLE.SYSTEM_ROLE.eq(it)) }
        filter.q?.let { q ->
            val query = "%$q%"
            condition =
                condition.and(
                    ROLE.ROLE_CODE
                        .likeIgnoreCase(query)
                        .or(ROLE.ROLE_NAME.likeIgnoreCase(query)),
                )
        }

        val total = dsl.fetchCount(ROLE, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val page =
            dsl
                .select(
                    ROLE.ID,
                    ROLE.ROLE_CODE,
                    ROLE.ROLE_NAME,
                    ROLE.SYSTEM_ROLE,
                    ROLE.STATUS,
                ).from(ROLE)
                .where(condition)
                .orderBy(
                    roleSortOrder(roleSortField(filter.sortBy), filter.sortDir),
                    ROLE.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch()
        // One bounded query per derived column for the page's roles, so paging and totals stay on
        // `role` alone and the statement count does not grow with the page size.
        val roleIds = page.map { requireNotNull(it[ROLE.ID]) }
        val gaps = roleColumns.missingViewPermissions(organisationId, roleIds)
        val permissionCounts = roleColumns.permissionCounts(organisationId, roleIds)
        val userCounts = roleColumns.activeUserCounts(organisationId, roleIds)
        val items =
            page.map { record ->
                val id = requireNotNull(record.get(ROLE.ID))
                RoleSummary(
                    id = id,
                    roleCode = requireNotNull(record.get(ROLE.ROLE_CODE)),
                    roleName = requireNotNull(record.get(ROLE.ROLE_NAME)),
                    systemRole = requireNotNull(record.get(ROLE.SYSTEM_ROLE)),
                    status = requireNotNull(record.get(ROLE.STATUS)),
                    missingViewPermissions = gaps[id].orEmpty(),
                    permissionCount = permissionCounts[id] ?: 0,
                    activeUserCount = userCounts[id] ?: 0,
                )
            }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findRoleById(
        organisationId: UUID,
        id: UUID,
    ): RoleDetail? =
        dsl
            .selectFrom(ROLE)
            .where(ROLE.ID.eq(id))
            .and(ROLE.ORGANISATION_ID.eq(organisationId))
            .fetchOne { record ->
                RoleDetail(
                    id = requireNotNull(record.id),
                    organisationId = requireNotNull(record.organisationId),
                    roleCode = requireNotNull(record.roleCode),
                    roleName = requireNotNull(record.roleName),
                    description = record.description,
                    systemRole = requireNotNull(record.systemRole),
                    status = requireNotNull(record.status),
                    missingViewPermissions =
                        roleColumns
                            .missingViewPermissions(organisationId, listOf(id))[id]
                            .orEmpty(),
                    createdAt = requireNotNull(record.createdAt).toInstant(),
                    updatedAt = requireNotNull(record.updatedAt).toInstant(),
                )
            }

    override fun searchRoleAssignments(
        organisationId: UUID,
        filter: RoleAssignmentFilter,
        restrictToBranchIds: Set<UUID>?,
    ): ApiPage<RoleAssignmentSummary> {
        var condition: Condition = USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId)
        restrictToBranchIds?.let {
            condition =
                condition
                    .and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq(BRANCH_SCOPE))
                    .and(USER_ROLE_ASSIGNMENT.BRANCH_ID.`in`(it))
        }
        filter.userId?.let { condition = condition.and(USER_ROLE_ASSIGNMENT.USER_ID.eq(it)) }
        filter.roleId?.let { condition = condition.and(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(it)) }
        filter.branchId?.let { condition = condition.and(USER_ROLE_ASSIGNMENT.BRANCH_ID.eq(it)) }
        filter.scopeType?.let { condition = condition.and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq(it)) }
        filter.status?.let { condition = condition.and(USER_ROLE_ASSIGNMENT.STATUS.eq(it)) }

        val total = dsl.fetchCount(USER_ROLE_ASSIGNMENT, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(
                    USER_ROLE_ASSIGNMENT.ID,
                    USER_ROLE_ASSIGNMENT.USER_ID,
                    USER_ROLE_ASSIGNMENT.ROLE_ID,
                    USER_ROLE_ASSIGNMENT.BRANCH_ID,
                    USER_ROLE_ASSIGNMENT.SCOPE_TYPE,
                    USER_ROLE_ASSIGNMENT.STATUS,
                ).from(USER_ROLE_ASSIGNMENT)
                .where(condition)
                .orderBy(USER_ROLE_ASSIGNMENT.ASSIGNED_AT.desc(), USER_ROLE_ASSIGNMENT.ID.desc())
                .limit(filter.size)
                .offset(offset)
                .fetch { record ->
                    RoleAssignmentSummary(
                        id = requireNotNull(record.get(USER_ROLE_ASSIGNMENT.ID)),
                        userId = requireNotNull(record.get(USER_ROLE_ASSIGNMENT.USER_ID)),
                        roleId = requireNotNull(record.get(USER_ROLE_ASSIGNMENT.ROLE_ID)),
                        branchId = record.get(USER_ROLE_ASSIGNMENT.BRANCH_ID),
                        scopeType = requireNotNull(record.get(USER_ROLE_ASSIGNMENT.SCOPE_TYPE)),
                        status = requireNotNull(record.get(USER_ROLE_ASSIGNMENT.STATUS)),
                    )
                }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findRoleAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): RoleAssignmentDetail? =
        dsl
            .selectFrom(USER_ROLE_ASSIGNMENT)
            .where(USER_ROLE_ASSIGNMENT.ID.eq(id))
            .and(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .fetchOne { record ->
                RoleAssignmentDetail(
                    id = requireNotNull(record.id),
                    organisationId = requireNotNull(record.organisationId),
                    userId = requireNotNull(record.userId),
                    roleId = requireNotNull(record.roleId),
                    branchId = record.branchId,
                    scopeType = requireNotNull(record.scopeType),
                    status = requireNotNull(record.status),
                    assignedAt = requireNotNull(record.assignedAt).toInstant(),
                    assignedBy = record.assignedBy,
                    revokedAt = record.revokedAt?.toInstant(),
                    revokedBy = record.revokedBy,
                    createdAt = requireNotNull(record.createdAt).toInstant(),
                    updatedAt = requireNotNull(record.updatedAt).toInstant(),
                )
            }

    override fun listRolePermissions(
        organisationId: UUID,
        roleId: UUID,
        filter: RolePermissionFilter,
    ): ApiPage<RolePermissionSummary> {
        val condition =
            ROLE_PERMISSION.ORGANISATION_ID
                .eq(
                    organisationId,
                ).and(ROLE_PERMISSION.ROLE_ID.eq(roleId))
        val total = dsl.fetchCount(ROLE_PERMISSION, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(
                    ROLE_PERMISSION.ID,
                    ROLE_PERMISSION.ROLE_ID,
                    ROLE_PERMISSION.PERMISSION_ID,
                    PERMISSION.PERMISSION_CODE,
                    ROLE_PERMISSION.GRANTED_AT,
                ).from(ROLE_PERMISSION)
                .join(PERMISSION)
                .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
                .where(condition)
                .orderBy(ROLE_PERMISSION.GRANTED_AT.desc(), ROLE_PERMISSION.ID.desc())
                .limit(filter.size)
                .offset(offset)
                .fetch { record ->
                    RolePermissionSummary(
                        id = requireNotNull(record.get(ROLE_PERMISSION.ID)),
                        roleId = requireNotNull(record.get(ROLE_PERMISSION.ROLE_ID)),
                        permissionId = requireNotNull(record.get(ROLE_PERMISSION.PERMISSION_ID)),
                        permissionCode = requireNotNull(record.get(PERMISSION.PERMISSION_CODE)),
                        grantedAt =
                            requireNotNull(
                                record.get(ROLE_PERMISSION.GRANTED_AT),
                            ).toInstant(),
                    )
                }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findRolePermissionByRoleAndCode(
        organisationId: UUID,
        roleId: UUID,
        permissionCode: String,
    ): RolePermissionDetail? =
        rolePermissionDetail(
            ROLE_PERMISSION.ORGANISATION_ID
                .eq(organisationId)
                .and(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                .and(PERMISSION.PERMISSION_CODE.eq(permissionCode)),
        )

    private fun rolePermissionDetail(condition: Condition): RolePermissionDetail? =
        dsl
            .select(
                ROLE_PERMISSION.ID,
                ROLE_PERMISSION.ORGANISATION_ID,
                ROLE_PERMISSION.ROLE_ID,
                ROLE_PERMISSION.PERMISSION_ID,
                PERMISSION.PERMISSION_CODE,
                ROLE_PERMISSION.GRANTED_AT,
                ROLE_PERMISSION.GRANTED_BY,
                ROLE_PERMISSION.CREATED_AT,
                ROLE_PERMISSION.UPDATED_AT,
            ).from(ROLE_PERMISSION)
            .join(PERMISSION)
            .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
            .where(condition)
            .fetchOne { record ->
                RolePermissionDetail(
                    id = requireNotNull(record.get(ROLE_PERMISSION.ID)),
                    organisationId = requireNotNull(record.get(ROLE_PERMISSION.ORGANISATION_ID)),
                    roleId = requireNotNull(record.get(ROLE_PERMISSION.ROLE_ID)),
                    permissionId = requireNotNull(record.get(ROLE_PERMISSION.PERMISSION_ID)),
                    permissionCode = requireNotNull(record.get(PERMISSION.PERMISSION_CODE)),
                    grantedAt = requireNotNull(record.get(ROLE_PERMISSION.GRANTED_AT)).toInstant(),
                    grantedBy = record.get(ROLE_PERMISSION.GRANTED_BY),
                    createdAt = requireNotNull(record.get(ROLE_PERMISSION.CREATED_AT)).toInstant(),
                    updatedAt = requireNotNull(record.get(ROLE_PERMISSION.UPDATED_AT)).toInstant(),
                )
            }

    private fun roleSortField(sortBy: String?): org.jooq.Field<*> =
        when (sortBy) {
            "roleCode" -> ROLE.ROLE_CODE
            "roleName" -> ROLE.ROLE_NAME
            "status" -> ROLE.STATUS
            else -> ROLE.CREATED_AT
        }

    private fun roleSortOrder(
        field: org.jooq.Field<*>,
        sortDir: String?,
    ): org.jooq.SortField<*> = if (sortDir?.uppercase() == "ASC") field.asc() else field.desc()

    private companion object {
        const val ACTIVE = "ACTIVE"
    }
}

private const val BRANCH_SCOPE = "BRANCH"

private fun userSortOrder(filter: UserInTenantFilter): SortField<*> =
    tenantListSortOrder(
        when (filter.sortBy) {
            "username" -> USER_ACCOUNT.USERNAME
            "displayName" -> USER_ACCOUNT.DISPLAY_NAME
            "email" -> USER_ACCOUNT.EMAIL
            "userStatus" -> USER_ACCOUNT.STATUS
            "membershipStatus" -> USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS
            else -> USER_ACCOUNT.CREATED_AT
        },
        filter.sortBy,
        filter.sortDir,
    )

private fun membershipSortOrder(filter: MembershipFilter): SortField<*> =
    tenantListSortOrder(
        when (filter.sortBy) {
            "membershipStatus" -> USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS
            "membershipType" -> USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE
            else -> USER_ORGANISATION_MEMBERSHIP.CREATED_AT
        },
        filter.sortBy,
        filter.sortDir,
    )

private fun assignmentSortOrder(filter: BranchAssignmentFilter): SortField<*> =
    tenantListSortOrder(
        when (filter.sortBy) {
            "assignmentType" -> USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE
            "status" -> USER_BRANCH_ASSIGNMENT.STATUS
            else -> USER_BRANCH_ASSIGNMENT.ASSIGNED_AT
        },
        filter.sortBy,
        filter.sortDir,
    )

/**
 * The primary order of a tenant list. `sort_dir` applies only with a `sort_by`: without one the
 * list keeps its default key, newest first, whatever `sort_dir` says.
 */
private fun tenantListSortOrder(
    field: Field<*>,
    sortBy: String?,
    sortDir: String?,
): SortField<*> = if (sortBy != null && sortDir?.uppercase() == "ASC") field.asc() else field.desc()

private fun Record.toUserInTenantSummary(makers: Map<UUID, ActorRef>) =
    UserInTenantSummary(
        id = requireNotNull(get(USER_ACCOUNT.ID)),
        username = requireNotNull(get(USER_ACCOUNT.USERNAME)),
        email = requireNotNull(get(USER_ACCOUNT.EMAIL)),
        displayName = requireNotNull(get(USER_ACCOUNT.DISPLAY_NAME)),
        userStatus = requireNotNull(get(USER_ACCOUNT.STATUS)),
        membershipStatus =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS),
            ),
        membershipId = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.ID)),
        membershipType =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE),
            ),
        createdAt = requireNotNull(get(USER_ACCOUNT.CREATED_AT)).toInstant(),
        membershipCreatedAt =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.CREATED_AT),
            ).toInstant(),
        createdBy = get(USER_ORGANISATION_MEMBERSHIP.CREATED_BY)?.let(makers::get),
    )

private fun Record.toMembershipSummary(makers: Map<UUID, ActorRef>) =
    MembershipSummary(
        id = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.ID)),
        userId = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.USER_ID)),
        membershipStatus =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS),
            ),
        membershipType =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE),
            ),
        primaryBranchId =
            get(
                USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID,
            ),
        username = requireNotNull(get(USER_ACCOUNT.USERNAME)),
        email = requireNotNull(get(USER_ACCOUNT.EMAIL)),
        displayName = requireNotNull(get(USER_ACCOUNT.DISPLAY_NAME)),
        primaryBranchName = get(BRANCH.BRANCH_NAME),
        createdAt =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.CREATED_AT),
            ).toInstant(),
        createdBy = get(USER_ORGANISATION_MEMBERSHIP.CREATED_BY)?.let(makers::get),
    )

private fun Record.toBranchAssignmentSummary() =
    BranchAssignmentSummary(
        id = requireNotNull(get(USER_BRANCH_ASSIGNMENT.ID)),
        userId = requireNotNull(get(USER_BRANCH_ASSIGNMENT.USER_ID)),
        branchId = requireNotNull(get(USER_BRANCH_ASSIGNMENT.BRANCH_ID)),
        assignmentType =
            requireNotNull(
                get(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE),
            ),
        status = requireNotNull(get(USER_BRANCH_ASSIGNMENT.STATUS)),
        username = requireNotNull(get(USER_ACCOUNT.USERNAME)),
        displayName = requireNotNull(get(USER_ACCOUNT.DISPLAY_NAME)),
        branchName = requireNotNull(get(BRANCH.BRANCH_NAME)),
        assignedAt =
            requireNotNull(get(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT))
                .toInstant(),
    )

private fun Record.toUserInTenantDetail(maker: ActorRef?) =
    UserInTenantDetail(
        id = requireNotNull(get(USER_ACCOUNT.ID)),
        username = requireNotNull(get(USER_ACCOUNT.USERNAME)),
        email = requireNotNull(get(USER_ACCOUNT.EMAIL)),
        displayName = requireNotNull(get(USER_ACCOUNT.DISPLAY_NAME)),
        userStatus = requireNotNull(get(USER_ACCOUNT.STATUS)),
        membershipStatus =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS),
            ),
        membershipId = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.ID)),
        membershipType =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE),
            ),
        createdAt = requireNotNull(get(USER_ACCOUNT.CREATED_AT)).toInstant(),
        membershipCreatedAt =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.CREATED_AT),
            ).toInstant(),
        createdBy = maker,
    )

private fun Record.toMembershipDetail(maker: ActorRef?) =
    MembershipDetail(
        id = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.ID)),
        organisationId =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID),
            ),
        userId = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.USER_ID)),
        username = requireNotNull(get(USER_ACCOUNT.USERNAME)),
        email = requireNotNull(get(USER_ACCOUNT.EMAIL)),
        displayName = requireNotNull(get(USER_ACCOUNT.DISPLAY_NAME)),
        userStatus = requireNotNull(get(USER_ACCOUNT.STATUS)),
        membershipStatus =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS),
            ),
        membershipType =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE),
            ),
        primaryBranchId =
            get(
                USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID,
            ),
        createdAt =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.CREATED_AT),
            ).toInstant(),
        updatedAt =
            requireNotNull(
                get(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT),
            ).toInstant(),
        createdBy = maker,
    )
