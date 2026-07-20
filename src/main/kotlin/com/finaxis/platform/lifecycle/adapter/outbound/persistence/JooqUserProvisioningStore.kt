package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.IDENTITY_DISPATCH_LOG
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningAccessStore
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningAccountStore
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningDispatchStore
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningMembershipStore
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** jOOQ adapter for local user invitation, approval, and membership cleanup persistence. */
@Component
class JooqUserProvisioningStore(
    dsl: DSLContext,
    clock: Clock,
) : UserProvisioningStore,
    UserProvisioningAccountStore by JooqUserProvisioningAccountStore(dsl, clock),
    UserProvisioningMembershipStore by JooqUserProvisioningMembershipStore(dsl, clock),
    UserProvisioningAccessStore by JooqUserProvisioningAccessStore(dsl, clock),
    UserProvisioningDispatchStore by JooqUserProvisioningDispatchStore(dsl, clock)

private class JooqUserProvisioningAccountStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : UserProvisioningAccountStore {
    override fun organisationState(organisationId: UUID): OrganisationLifecycleState? =
        dsl
            .select(ORGANISATION.STATUS)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne(ORGANISATION.STATUS)
            ?.let(OrganisationLifecycleState::valueOf)

    override fun findUserIdByEmail(email: String): UUID? =
        dsl
            .select(USER_ACCOUNT.ID)
            .from(USER_ACCOUNT)
            .where(DSL.lower(USER_ACCOUNT.EMAIL).eq(email.lowercase()))
            .fetchOne(USER_ACCOUNT.ID)

    override fun createUserAccount(
        email: String,
        username: String,
        displayName: String,
        phoneE164: String?,
        actorId: UUID,
    ): UUID {
        val userId = uuidV7()
        val now = now(clock)
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, userId)
            .set(USER_ACCOUNT.USERNAME, username)
            .set(USER_ACCOUNT.EMAIL, email)
            .set(USER_ACCOUNT.PHONE_E164, phoneE164)
            .set(USER_ACCOUNT.DISPLAY_NAME, displayName)
            .set(USER_ACCOUNT.STATUS, UserLifecycleState.DRAFT.name)
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, actorId)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, actorId)
            .execute()
        return userId
    }

    override fun userStatus(userId: UUID): UserLifecycleState? =
        dsl
            .select(USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(userId))
            .fetchOne(USER_ACCOUNT.STATUS)
            ?.let(UserLifecycleState::valueOf)

    override fun hasKeycloakIdentity(userId: UUID): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(KEYCLOAK_IDENTITY_LINK)
                .where(KEYCLOAK_IDENTITY_LINK.USER_ID.eq(userId))
                .and(KEYCLOAK_IDENTITY_LINK.UNLINKED_AT.isNull),
        )
}

private class JooqUserProvisioningMembershipStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : UserProvisioningMembershipStore {
    override fun createMembership(
        organisationId: UUID,
        userId: UUID,
        membershipType: MembershipType,
        primaryBranchId: UUID?,
        actorId: UUID,
    ): UUID {
        val membershipId = uuidV7()
        val now = now(clock)
        val inserted =
            dsl
                .insertInto(USER_ORGANISATION_MEMBERSHIP)
                .set(USER_ORGANISATION_MEMBERSHIP.ID, membershipId)
                .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
                .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
                .set(
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                    MembershipLifecycleState.PENDING_APPROVAL.name,
                ).set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, membershipType.name)
                .set(USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID, primaryBranchId)
                .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
                .set(USER_ORGANISATION_MEMBERSHIP.CREATED_BY, actorId)
                .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
                .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_BY, actorId)
                .onConflict(
                    USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                    USER_ORGANISATION_MEMBERSHIP.USER_ID,
                ).doNothing()
                .execute() > 0
        return if (inserted) {
            membershipId
        } else {
            requireNotNull(existingMembershipId(organisationId, userId)) {
                "Membership could not be created."
            }
        }
    }

    override fun saveInvitationPreferences(
        organisationId: UUID,
        membershipId: UUID,
        sendKeycloakInvite: Boolean,
        sendApplicationInvite: Boolean,
        actorId: UUID,
    ) {
        val now = now(clock)
        dsl
            .update(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.PENDING_KEYCLOAK_INVITE, sendKeycloakInvite)
            .set(USER_ORGANISATION_MEMBERSHIP.PENDING_APPLICATION_INVITE, sendApplicationInvite)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_BY, actorId)
            .set(
                USER_ORGANISATION_MEMBERSHIP.ROW_VERSION,
                USER_ORGANISATION_MEMBERSHIP.ROW_VERSION.plus(1),
            ).where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .and(
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS.eq(
                    MembershipLifecycleState.PENDING_APPROVAL.name,
                ),
            ).execute()
    }

    override fun membershipSnapshot(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot? =
        dsl
            .select(
                USER_ORGANISATION_MEMBERSHIP.ID,
                USER_ORGANISATION_MEMBERSHIP.USER_ID,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE,
                USER_ORGANISATION_MEMBERSHIP.PENDING_KEYCLOAK_INVITE,
                USER_ORGANISATION_MEMBERSHIP.PENDING_APPLICATION_INVITE,
                USER_ACCOUNT.EMAIL,
                USER_ACCOUNT.USERNAME,
                USER_ACCOUNT.DISPLAY_NAME,
                USER_ACCOUNT.STATUS,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .join(USER_ACCOUNT)
            .on(USER_ACCOUNT.ID.eq(USER_ORGANISATION_MEMBERSHIP.USER_ID))
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne { record ->
                MembershipProvisioningSnapshot(
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ID]),
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.USER_ID]),
                    MembershipLifecycleState.valueOf(
                        requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS]),
                    ),
                    MembershipType.valueOf(
                        requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE]),
                    ),
                    requireNotNull(record[USER_ACCOUNT.EMAIL]),
                    requireNotNull(record[USER_ACCOUNT.USERNAME]),
                    requireNotNull(record[USER_ACCOUNT.DISPLAY_NAME]),
                    UserLifecycleState.valueOf(requireNotNull(record[USER_ACCOUNT.STATUS])),
                    requireNotNull(
                        record[USER_ORGANISATION_MEMBERSHIP.PENDING_KEYCLOAK_INVITE],
                    ),
                    requireNotNull(
                        record[USER_ORGANISATION_MEMBERSHIP.PENDING_APPLICATION_INVITE],
                    ),
                )
            }

    override fun membershipExists(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = existingMembershipId(organisationId, userId) != null

    private fun existingMembershipId(
        organisationId: UUID,
        userId: UUID,
    ): UUID? =
        dsl
            .select(USER_ORGANISATION_MEMBERSHIP.ID)
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
            .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID)
}

private class JooqUserProvisioningAccessStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : UserProvisioningAccessStore {
    override fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ): BranchLifecycleState? =
        dsl
            .select(BRANCH.STATUS)
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(organisationId))
            .and(BRANCH.ID.eq(branchId))
            .fetchOne(BRANCH.STATUS)
            ?.let(BranchLifecycleState::valueOf)

    override fun roleExists(
        organisationId: UUID,
        roleId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(ROLE)
                .where(ROLE.ORGANISATION_ID.eq(organisationId))
                .and(ROLE.ID.eq(roleId))
                .and(ROLE.STATUS.eq(ACTIVE)),
        )

    override fun findRoleIdByCode(
        organisationId: UUID,
        roleCode: String,
    ): UUID? =
        dsl
            .select(ROLE.ID)
            .from(ROLE)
            .where(ROLE.ORGANISATION_ID.eq(organisationId))
            .and(ROLE.ROLE_CODE.eq(roleCode))
            .and(ROLE.STATUS.eq(ACTIVE))
            .fetchOne(ROLE.ID)

    override fun assignRole(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleAssignmentScopeType,
        branchId: UUID?,
        actorId: UUID,
    ): UUID {
        val id = uuidV7()
        val now = now(clock)
        val created =
            dsl
                .insertInto(USER_ROLE_ASSIGNMENT)
                .set(USER_ROLE_ASSIGNMENT.ID, id)
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
                .execute() > 0
        return if (created) {
            id
        } else {
            requireNotNull(
                activeRoleAssignment(organisationId, userId, roleId, scopeType, branchId),
            )
        }
    }

    override fun hasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_BRANCH_ASSIGNMENT)
                .join(BRANCH)
                .on(BRANCH.ID.eq(USER_BRANCH_ASSIGNMENT.BRANCH_ID))
                .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
                .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(userId))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE))
                .and(BRANCH.STATUS.eq(BranchLifecycleState.ACTIVE.name)),
        )

    override fun hasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_ROLE_ASSIGNMENT)
                .join(ROLE)
                .on(ROLE.ID.eq(USER_ROLE_ASSIGNMENT.ROLE_ID))
                .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
                .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId))
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
                .and(ROLE.STATUS.eq(ACTIVE)),
        )

    override fun revokeBranchAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int {
        val userId = requireMembershipUserId(organisationId, membershipId)
        return dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, REVOKED)
            .set(USER_BRANCH_ASSIGNMENT.REVOKED_AT, now(clock))
            .set(USER_BRANCH_ASSIGNMENT.REVOKED_BY, actorId)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now(clock))
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_BY, actorId)
            .set(USER_BRANCH_ASSIGNMENT.ROW_VERSION, USER_BRANCH_ASSIGNMENT.ROW_VERSION.plus(1))
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(userId))
            .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE))
            .execute()
    }

    override fun revokeRoleAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int {
        val userId = requireMembershipUserId(organisationId, membershipId)
        return dsl
            .update(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.STATUS, REVOKED)
            .set(USER_ROLE_ASSIGNMENT.REVOKED_AT, now(clock))
            .set(USER_ROLE_ASSIGNMENT.REVOKED_BY, actorId)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now(clock))
            .set(USER_ROLE_ASSIGNMENT.UPDATED_BY, actorId)
            .set(USER_ROLE_ASSIGNMENT.ROW_VERSION, USER_ROLE_ASSIGNMENT.ROW_VERSION.plus(1))
            .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId))
            .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
            .execute()
    }

    private fun activeRoleAssignment(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleAssignmentScopeType,
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

    private fun requireMembershipUserId(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID =
        requireNotNull(
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.USER_ID)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                .and(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.USER_ID),
        ) {
            "Membership was not found in the selected organisation."
        }
}

private class JooqUserProvisioningDispatchStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : UserProvisioningDispatchStore {
    override fun recordDispatch(
        organisationId: UUID,
        userId: UUID,
        dispatchType: IdentityDispatchType,
        dispatchKey: String,
        actorId: UUID,
    ) {
        val now = now(clock)
        dsl
            .insertInto(IDENTITY_DISPATCH_LOG)
            .set(IDENTITY_DISPATCH_LOG.ID, uuidV7())
            .set(IDENTITY_DISPATCH_LOG.ORGANISATION_ID, organisationId)
            .set(IDENTITY_DISPATCH_LOG.USER_ID, userId)
            .set(IDENTITY_DISPATCH_LOG.DISPATCH_TYPE, dispatchType.name)
            .set(IDENTITY_DISPATCH_LOG.DISPATCH_KEY, dispatchKey)
            .set(IDENTITY_DISPATCH_LOG.STATUS, PENDING)
            .set(IDENTITY_DISPATCH_LOG.ATTEMPTS, 0)
            .set(IDENTITY_DISPATCH_LOG.CREATED_AT, now)
            .set(IDENTITY_DISPATCH_LOG.CREATED_BY, actorId)
            .set(IDENTITY_DISPATCH_LOG.UPDATED_AT, now)
            .set(IDENTITY_DISPATCH_LOG.UPDATED_BY, actorId)
            .onConflict(IDENTITY_DISPATCH_LOG.DISPATCH_KEY)
            .doNothing()
            .execute()
    }

    override fun linkKeycloakIdentity(
        userId: UUID,
        subject: String,
        realm: String,
        actorId: UUID,
    ) {
        val now = now(clock)
        dsl
            .insertInto(KEYCLOAK_IDENTITY_LINK)
            .set(KEYCLOAK_IDENTITY_LINK.ID, uuidV7())
            .set(KEYCLOAK_IDENTITY_LINK.USER_ID, userId)
            .set(KEYCLOAK_IDENTITY_LINK.PROVIDER, KEYCLOAK)
            .set(KEYCLOAK_IDENTITY_LINK.SUBJECT, subject)
            .set(KEYCLOAK_IDENTITY_LINK.REALM_NAME, realm)
            .set(KEYCLOAK_IDENTITY_LINK.LINKED_AT, now)
            .set(KEYCLOAK_IDENTITY_LINK.CREATED_AT, now)
            .set(KEYCLOAK_IDENTITY_LINK.CREATED_BY, actorId)
            .set(KEYCLOAK_IDENTITY_LINK.UPDATED_AT, now)
            .set(KEYCLOAK_IDENTITY_LINK.UPDATED_BY, actorId)
            .onConflict(KEYCLOAK_IDENTITY_LINK.PROVIDER, KEYCLOAK_IDENTITY_LINK.SUBJECT)
            .doNothing()
            .execute()
    }

    override fun markDispatchSucceeded(
        dispatchKey: String,
        externalRef: String,
    ) {
        dsl
            .update(IDENTITY_DISPATCH_LOG)
            .set(IDENTITY_DISPATCH_LOG.STATUS, SUCCEEDED)
            .set(IDENTITY_DISPATCH_LOG.ATTEMPTS, IDENTITY_DISPATCH_LOG.ATTEMPTS.plus(1))
            .set(IDENTITY_DISPATCH_LOG.LAST_ERROR, null as String?)
            .set(IDENTITY_DISPATCH_LOG.EXTERNAL_REF, externalRef)
            .set(IDENTITY_DISPATCH_LOG.UPDATED_AT, now(clock))
            .set(IDENTITY_DISPATCH_LOG.ROW_VERSION, IDENTITY_DISPATCH_LOG.ROW_VERSION.plus(1))
            .where(IDENTITY_DISPATCH_LOG.DISPATCH_KEY.eq(dispatchKey))
            .execute()
    }

    override fun markDispatchFailed(
        dispatchKey: String,
        error: String,
    ) {
        dsl
            .update(IDENTITY_DISPATCH_LOG)
            .set(IDENTITY_DISPATCH_LOG.STATUS, FAILED)
            .set(IDENTITY_DISPATCH_LOG.ATTEMPTS, IDENTITY_DISPATCH_LOG.ATTEMPTS.plus(1))
            .set(IDENTITY_DISPATCH_LOG.LAST_ERROR, error)
            .set(IDENTITY_DISPATCH_LOG.UPDATED_AT, now(clock))
            .set(IDENTITY_DISPATCH_LOG.ROW_VERSION, IDENTITY_DISPATCH_LOG.ROW_VERSION.plus(1))
            .where(IDENTITY_DISPATCH_LOG.DISPATCH_KEY.eq(dispatchKey))
            .execute()
    }

    override fun dispatchStatus(dispatchKey: String): String? =
        dsl
            .select(IDENTITY_DISPATCH_LOG.STATUS)
            .from(IDENTITY_DISPATCH_LOG)
            .where(IDENTITY_DISPATCH_LOG.DISPATCH_KEY.eq(dispatchKey))
            .fetchOne(IDENTITY_DISPATCH_LOG.STATUS)
}

private fun branchCondition(branchId: UUID?) =
    branchId?.let(USER_ROLE_ASSIGNMENT.BRANCH_ID::eq)
        ?: USER_ROLE_ASSIGNMENT.BRANCH_ID.isNull

private fun now(clock: Clock): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

private const val ACTIVE = "ACTIVE"
private const val FAILED = "FAILED"
private const val KEYCLOAK = "KEYCLOAK"
private const val PENDING = "PENDING"
private const val REVOKED = "REVOKED"
private const val SUCCEEDED = "SUCCEEDED"
