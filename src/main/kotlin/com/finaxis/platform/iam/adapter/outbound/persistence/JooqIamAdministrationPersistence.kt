package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.port.outbound.IamAdministrationPersistence
import com.finaxis.platform.iam.application.port.outbound.MembershipSnapshot
import com.finaxis.platform.iam.application.port.outbound.RoleSnapshot
import com.finaxis.platform.iam.application.role.RoleScopeType
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.DSLContext
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** jOOQ persistence adapter for organisation-scoped role and role-assignment administration. */
@Component
class JooqIamAdministrationPersistence(
    private val dsl: DSLContext,
    private val clock: Clock,
) : IamAdministrationPersistence {
    override fun roleCodeExists(
        organisationId: UUID,
        roleCode: String,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(ROLE)
                .where(ROLE.ORGANISATION_ID.eq(organisationId))
                .and(ROLE.ROLE_CODE.eq(roleCode)),
        )

    override fun findRole(
        organisationId: UUID,
        roleId: UUID,
    ): RoleSnapshot? =
        dsl
            .select(ROLE.ID, ROLE.ROLE_CODE, ROLE.SYSTEM_ROLE, ROLE.STATUS, ROLE.ROW_VERSION)
            .from(ROLE)
            .where(ROLE.ORGANISATION_ID.eq(organisationId))
            .and(ROLE.ID.eq(roleId))
            .fetchOne { record ->
                RoleSnapshot(
                    requireNotNull(record[ROLE.ID]),
                    requireNotNull(record[ROLE.ROLE_CODE]),
                    requireNotNull(record[ROLE.SYSTEM_ROLE]),
                    RoleStatus.valueOf(requireNotNull(record[ROLE.STATUS])),
                    requireNotNull(record[ROLE.ROW_VERSION]),
                )
            }

    override fun createRole(
        organisationId: UUID,
        roleCode: String,
        roleName: String,
        description: String?,
        actorId: UUID,
    ): UUID {
        val now = now()
        return dsl
            .insertInto(ROLE)
            .set(ROLE.ORGANISATION_ID, organisationId)
            .set(ROLE.ROLE_CODE, roleCode)
            .set(ROLE.ROLE_NAME, roleName)
            .set(ROLE.DESCRIPTION, description)
            .set(ROLE.SYSTEM_ROLE, false)
            .set(ROLE.STATUS, RoleStatus.ACTIVE.name)
            .set(ROLE.CREATED_AT, now)
            .set(ROLE.CREATED_BY, actorId)
            .set(ROLE.UPDATED_AT, now)
            .set(ROLE.UPDATED_BY, actorId)
            .returning(ROLE.ID)
            .fetchOne()
            ?.id
            ?: error("Insert into role returned no generated identifier.")
    }

    override fun updateRole(
        organisationId: UUID,
        roleId: UUID,
        roleName: String?,
        description: String?,
        rowVersion: Long,
        actorId: UUID,
    ) {
        var update =
            dsl
                .update(ROLE)
                .set(ROLE.UPDATED_AT, now())
                .set(ROLE.UPDATED_BY, actorId)
                .set(ROLE.ROW_VERSION, rowVersion + 1)
        roleName?.let { update = update.set(ROLE.ROLE_NAME, it) }
        description?.let { update = update.set(ROLE.DESCRIPTION, it) }
        val updated =
            update
                .where(ROLE.ORGANISATION_ID.eq(organisationId))
                .and(ROLE.ID.eq(roleId))
                .and(ROLE.ROW_VERSION.eq(rowVersion))
                .execute()
        requireUpdated(updated, roleId)
    }

    override fun setRoleStatus(
        organisationId: UUID,
        roleId: UUID,
        status: RoleStatus,
        rowVersion: Long,
        actorId: UUID,
    ) {
        val updated =
            dsl
                .update(ROLE)
                .set(ROLE.STATUS, status.name)
                .set(ROLE.UPDATED_AT, now())
                .set(ROLE.UPDATED_BY, actorId)
                .set(ROLE.ROW_VERSION, rowVersion + 1)
                .where(ROLE.ORGANISATION_ID.eq(organisationId))
                .and(ROLE.ID.eq(roleId))
                .and(ROLE.ROW_VERSION.eq(rowVersion))
                .execute()
        requireUpdated(updated, roleId)
    }

    override fun permissionIdByCode(permissionCode: String): UUID? =
        dsl
            .select(PERMISSION.ID)
            .from(PERMISSION)
            .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
            .fetchOne(PERMISSION.ID)

    override fun permissionRiskLevel(permissionCode: String): String? =
        dsl
            .select(PERMISSION.RISK_LEVEL)
            .from(PERMISSION)
            .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
            .fetchOne(PERMISSION.RISK_LEVEL)

    override fun grantPermission(
        organisationId: UUID,
        roleId: UUID,
        permissionId: UUID,
        actorId: UUID,
    ): Boolean {
        val now = now()
        return dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
            .set(ROLE_PERMISSION.ROLE_ID, roleId)
            .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
            .set(ROLE_PERMISSION.GRANTED_AT, now)
            .set(ROLE_PERMISSION.GRANTED_BY, actorId)
            .set(ROLE_PERMISSION.CREATED_AT, now)
            .set(ROLE_PERMISSION.CREATED_BY, actorId)
            .set(ROLE_PERMISSION.UPDATED_AT, now)
            .set(ROLE_PERMISSION.UPDATED_BY, actorId)
            .onConflict(
                ROLE_PERMISSION.ORGANISATION_ID,
                ROLE_PERMISSION.ROLE_ID,
                ROLE_PERMISSION.PERMISSION_ID,
            ).doNothing()
            .execute() > 0
    }

    override fun removePermission(
        organisationId: UUID,
        roleId: UUID,
        permissionId: UUID,
        actorId: UUID,
    ): Boolean =
        dsl
            .deleteFrom(ROLE_PERMISSION)
            .where(ROLE_PERMISSION.ORGANISATION_ID.eq(organisationId))
            .and(ROLE_PERMISSION.ROLE_ID.eq(roleId))
            .and(ROLE_PERMISSION.PERMISSION_ID.eq(permissionId))
            .execute() > 0

    override fun membership(
        organisationId: UUID,
        userId: UUID,
    ): MembershipSnapshot? =
        dsl
            .select(
                USER_ORGANISATION_MEMBERSHIP.ID,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
            .fetchOne { record ->
                MembershipSnapshot(
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ID]),
                    MembershipStatus.valueOf(
                        requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS]),
                    ),
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE]),
                )
            }

    override fun organisationStatus(organisationId: UUID): OrganisationStatus? =
        dsl
            .select(ORGANISATION.STATUS)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne(ORGANISATION.STATUS)
            ?.let(OrganisationStatus::valueOf)

    override fun hasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
                .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(userId))
                .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(branchId))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE)),
        )

    override fun activeRoleAssignment(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleScopeType,
        branchId: UUID?,
    ): UUID? =
        dsl
            .select(USER_ROLE_ASSIGNMENT.ID)
            .from(USER_ROLE_ASSIGNMENT)
            .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId))
            .and(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(roleId))
            .and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq(scopeType.name))
            .and(branchCondition(branchId))
            .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
            .fetchOne(USER_ROLE_ASSIGNMENT.ID)

    override fun assignRole(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleScopeType,
        branchId: UUID?,
        actorId: UUID,
    ): UUID {
        val now = now()
        val createdId =
            dsl
                .insertInto(USER_ROLE_ASSIGNMENT)
                .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, organisationId)
                .set(USER_ROLE_ASSIGNMENT.USER_ID, userId)
                .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
                .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, branchId)
                .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, scopeType.name)
                .set(USER_ROLE_ASSIGNMENT.STATUS, ACTIVE)
                .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
                .set(USER_ROLE_ASSIGNMENT.ASSIGNED_BY, actorId)
                .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
                .set(USER_ROLE_ASSIGNMENT.CREATED_BY, actorId)
                .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
                .set(USER_ROLE_ASSIGNMENT.UPDATED_BY, actorId)
                .onConflict()
                .doNothing()
                .returning(USER_ROLE_ASSIGNMENT.ID)
                .fetchOne()
                ?.id
        return if (createdId != null) {
            createdId
        } else {
            requireNotNull(
                activeRoleAssignment(organisationId, userId, roleId, scopeType, branchId),
            ) {
                "Role assignment could not be created."
            }
        }
    }

    override fun revokeRole(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleScopeType,
        branchId: UUID?,
        actorId: UUID,
    ): Boolean =
        dsl
            .update(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.STATUS, REVOKED)
            .set(USER_ROLE_ASSIGNMENT.REVOKED_AT, now())
            .set(USER_ROLE_ASSIGNMENT.REVOKED_BY, actorId)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now())
            .set(USER_ROLE_ASSIGNMENT.UPDATED_BY, actorId)
            .set(USER_ROLE_ASSIGNMENT.ROW_VERSION, USER_ROLE_ASSIGNMENT.ROW_VERSION.plus(1))
            .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId))
            .and(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(roleId))
            .and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq(scopeType.name))
            .and(branchCondition(branchId))
            .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
            .execute() > 0

    override fun membershipIdsWithRole(
        organisationId: UUID,
        roleId: UUID,
    ): List<UUID> =
        dsl
            .selectDistinct(USER_ORGANISATION_MEMBERSHIP.ID)
            .from(USER_ROLE_ASSIGNMENT)
            .join(USER_ORGANISATION_MEMBERSHIP)
            .on(
                USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(
                    USER_ROLE_ASSIGNMENT.ORGANISATION_ID,
                ),
            ).and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ROLE_ASSIGNMENT.USER_ID))
            .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .and(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(roleId))
            .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
            .fetch(USER_ORGANISATION_MEMBERSHIP.ID)
            .filterNotNull()

    private fun requireUpdated(
        updated: Int,
        roleId: UUID,
    ) {
        if (updated == 0) {
            throw OptimisticLockingFailureException(
                "Role $roleId was changed or is not in organisation.",
            )
        }
    }

    private fun now() = clock.instant().atOffset(ZoneOffset.UTC)

    private fun branchCondition(branchId: UUID?) =
        branchId?.let(USER_ROLE_ASSIGNMENT.BRANCH_ID::eq)
            ?: USER_ROLE_ASSIGNMENT.BRANCH_ID.isNull

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val REVOKED = "REVOKED"
    }
}
