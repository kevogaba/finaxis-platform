package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.api.boundedPageOffset
import com.finaxis.platform.iam.application.query.BranchAssignmentDetail
import com.finaxis.platform.iam.application.query.BranchAssignmentFilter
import com.finaxis.platform.iam.application.query.BranchAssignmentSummary
import com.finaxis.platform.iam.application.query.IamAssignmentQueries
import com.finaxis.platform.iam.application.query.IamPermissionQueries
import com.finaxis.platform.iam.application.query.IamRoleQueries
import com.finaxis.platform.iam.application.query.IamUserQueries
import com.finaxis.platform.iam.application.query.MembershipDetail
import com.finaxis.platform.iam.application.query.MembershipFilter
import com.finaxis.platform.iam.application.query.MembershipSummary
import com.finaxis.platform.iam.application.query.PermissionDetail
import com.finaxis.platform.iam.application.query.PermissionFilter
import com.finaxis.platform.iam.application.query.PermissionSummary
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
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * jOOQ implementation of the IAM query ports.
 * Executes database reads for users, roles, assignments, permissions, and memberships.
 */
@Component
class JooqIamAdministrationQueries(
    private val dsl: DSLContext,
) : IamUserQueries,
    IamRoleQueries,
    IamAssignmentQueries,
    IamPermissionQueries {
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
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                ).from(USER_ORGANISATION_MEMBERSHIP)
                .join(USER_ACCOUNT)
                .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID))
                .where(condition)
                .orderBy(USER_ACCOUNT.CREATED_AT.desc(), USER_ACCOUNT.ID.desc())
                .limit(filter.size)
                .offset(offset)
                .fetch { record ->
                    UserInTenantSummary(
                        id = requireNotNull(record.get(USER_ACCOUNT.ID)),
                        username = requireNotNull(record.get(USER_ACCOUNT.USERNAME)),
                        email = requireNotNull(record.get(USER_ACCOUNT.EMAIL)),
                        displayName = requireNotNull(record.get(USER_ACCOUNT.DISPLAY_NAME)),
                        userStatus = requireNotNull(record.get(USER_ACCOUNT.STATUS)),
                        membershipStatus =
                            requireNotNull(
                                record.get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS),
                            ),
                    )
                }

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
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .join(USER_ACCOUNT)
            .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID))
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ACCOUNT.ID.eq(userId))
            .fetchOne { record ->
                UserInTenantDetail(
                    id = requireNotNull(record.get(USER_ACCOUNT.ID)),
                    username = requireNotNull(record.get(USER_ACCOUNT.USERNAME)),
                    email = requireNotNull(record.get(USER_ACCOUNT.EMAIL)),
                    displayName = requireNotNull(record.get(USER_ACCOUNT.DISPLAY_NAME)),
                    userStatus = requireNotNull(record.get(USER_ACCOUNT.STATUS)),
                    membershipStatus =
                        requireNotNull(
                            record.get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS),
                        ),
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
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .join(USER_ACCOUNT)
            .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID))
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .and(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .fetchOne { record ->
                MembershipDetail(
                    id = requireNotNull(record.get(USER_ORGANISATION_MEMBERSHIP.ID)),
                    organisationId =
                        requireNotNull(
                            record.get(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID),
                        ),
                    userId = requireNotNull(record.get(USER_ORGANISATION_MEMBERSHIP.USER_ID)),
                    username = requireNotNull(record.get(USER_ACCOUNT.USERNAME)),
                    email = requireNotNull(record.get(USER_ACCOUNT.EMAIL)),
                    displayName = requireNotNull(record.get(USER_ACCOUNT.DISPLAY_NAME)),
                    userStatus = requireNotNull(record.get(USER_ACCOUNT.STATUS)),
                    membershipStatus =
                        requireNotNull(
                            record.get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS),
                        ),
                    membershipType =
                        requireNotNull(
                            record.get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE),
                        ),
                    primaryBranchId =
                        record.get(
                            USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID,
                        ),
                    createdAt =
                        requireNotNull(
                            record.get(USER_ORGANISATION_MEMBERSHIP.CREATED_AT),
                        ).toInstant(),
                    updatedAt =
                        requireNotNull(
                            record.get(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT),
                        ).toInstant(),
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
                ).from(USER_ORGANISATION_MEMBERSHIP)
                .join(USER_ACCOUNT)
                .on(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID))
                .where(condition)
                .orderBy(
                    USER_ORGANISATION_MEMBERSHIP.CREATED_AT.desc(),
                    USER_ORGANISATION_MEMBERSHIP.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch { record ->
                    MembershipSummary(
                        id = requireNotNull(record.get(USER_ORGANISATION_MEMBERSHIP.ID)),
                        userId = requireNotNull(record.get(USER_ORGANISATION_MEMBERSHIP.USER_ID)),
                        membershipStatus =
                            requireNotNull(
                                record.get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS),
                            ),
                        membershipType =
                            requireNotNull(
                                record.get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE),
                            ),
                        primaryBranchId =
                            record.get(
                                USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID,
                            ),
                    )
                }

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
    ): ApiPage<BranchAssignmentSummary> {
        var condition: Condition = USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId)
        filter.branchId?.let { condition = condition.and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(it)) }
        filter.assignmentType?.let {
            condition =
                condition.and(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE.eq(it))
        }
        filter.status?.let { condition = condition.and(USER_BRANCH_ASSIGNMENT.STATUS.eq(it)) }

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
                ).from(USER_BRANCH_ASSIGNMENT)
                .where(condition)
                .orderBy(
                    USER_BRANCH_ASSIGNMENT.ASSIGNED_AT.desc(),
                    USER_BRANCH_ASSIGNMENT.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch { record ->
                    BranchAssignmentSummary(
                        id = requireNotNull(record.get(USER_BRANCH_ASSIGNMENT.ID)),
                        userId = requireNotNull(record.get(USER_BRANCH_ASSIGNMENT.USER_ID)),
                        branchId = requireNotNull(record.get(USER_BRANCH_ASSIGNMENT.BRANCH_ID)),
                        assignmentType =
                            requireNotNull(
                                record.get(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE),
                            ),
                        status = requireNotNull(record.get(USER_BRANCH_ASSIGNMENT.STATUS)),
                    )
                }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findBranchAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): BranchAssignmentDetail? =
        dsl
            .selectFrom(USER_BRANCH_ASSIGNMENT)
            .where(USER_BRANCH_ASSIGNMENT.ID.eq(id))
            .and(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .fetchOne { record ->
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
                )
            }

    override fun searchRoles(
        organisationId: UUID,
        filter: RoleFilter,
    ): ApiPage<RoleSummary> {
        var condition: Condition = ROLE.ORGANISATION_ID.eq(organisationId)
        filter.status?.let { condition = condition.and(ROLE.STATUS.eq(it)) }
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
        val items =
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
                .fetch { record ->
                    RoleSummary(
                        id = requireNotNull(record.get(ROLE.ID)),
                        roleCode = requireNotNull(record.get(ROLE.ROLE_CODE)),
                        roleName = requireNotNull(record.get(ROLE.ROLE_NAME)),
                        systemRole = requireNotNull(record.get(ROLE.SYSTEM_ROLE)),
                        status = requireNotNull(record.get(ROLE.STATUS)),
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
                    createdAt = requireNotNull(record.createdAt).toInstant(),
                    updatedAt = requireNotNull(record.updatedAt).toInstant(),
                )
            }

    override fun searchRoleAssignments(
        organisationId: UUID,
        filter: RoleAssignmentFilter,
    ): ApiPage<RoleAssignmentSummary> {
        var condition: Condition = USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId)
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

    override fun searchPermissions(filter: PermissionFilter): ApiPage<PermissionSummary> {
        var condition: Condition = DSL.noCondition()
        filter.status?.let { condition = condition.and(PERMISSION.STATUS.eq(it)) }
        filter.riskLevel?.let { condition = condition.and(PERMISSION.RISK_LEVEL.eq(it)) }
        filter.q?.let { q ->
            val query = "%$q%"
            condition =
                condition.and(
                    PERMISSION.PERMISSION_CODE
                        .likeIgnoreCase(query)
                        .or(PERMISSION.PERMISSION_NAME.likeIgnoreCase(query)),
                )
        }

        val total = dsl.fetchCount(PERMISSION, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(
                    PERMISSION.ID,
                    PERMISSION.PERMISSION_CODE,
                    PERMISSION.PERMISSION_NAME,
                    PERMISSION.MODULE_CODE,
                    PERMISSION.RISK_LEVEL,
                    PERMISSION.STATUS,
                ).from(PERMISSION)
                .where(condition)
                .orderBy(
                    permissionSortOrder(permissionSortField(filter.sortBy), filter.sortDir),
                    PERMISSION.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch { record ->
                    PermissionSummary(
                        id = requireNotNull(record.get(PERMISSION.ID)),
                        permissionCode = requireNotNull(record.get(PERMISSION.PERMISSION_CODE)),
                        permissionName = requireNotNull(record.get(PERMISSION.PERMISSION_NAME)),
                        moduleCode = requireNotNull(record.get(PERMISSION.MODULE_CODE)),
                        riskLevel = requireNotNull(record.get(PERMISSION.RISK_LEVEL)),
                        status = requireNotNull(record.get(PERMISSION.STATUS)),
                    )
                }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findPermissionById(id: UUID): PermissionDetail? =
        dsl
            .selectFrom(PERMISSION)
            .where(PERMISSION.ID.eq(id))
            .fetchOne { record ->
                PermissionDetail(
                    id = requireNotNull(record.id),
                    permissionCode = requireNotNull(record.permissionCode),
                    permissionName = requireNotNull(record.permissionName),
                    moduleCode = requireNotNull(record.moduleCode),
                    description = record.description,
                    riskLevel = requireNotNull(record.riskLevel),
                    status = requireNotNull(record.status),
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

    override fun findRolePermissionById(
        organisationId: UUID,
        id: UUID,
    ): RolePermissionDetail? =
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
            .where(ROLE_PERMISSION.ID.eq(id))
            .and(ROLE_PERMISSION.ORGANISATION_ID.eq(organisationId))
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

    private fun permissionSortField(sortBy: String?): org.jooq.Field<*> =
        when (sortBy) {
            "permissionCode" -> PERMISSION.PERMISSION_CODE
            "permissionName" -> PERMISSION.PERMISSION_NAME
            "riskLevel" -> PERMISSION.RISK_LEVEL
            "status" -> PERMISSION.STATUS
            else -> PERMISSION.CREATED_AT
        }

    private fun permissionSortOrder(
        field: org.jooq.Field<*>,
        sortDir: String?,
    ): org.jooq.SortField<*> = if (sortDir?.uppercase() == "ASC") field.asc() else field.desc()
}
