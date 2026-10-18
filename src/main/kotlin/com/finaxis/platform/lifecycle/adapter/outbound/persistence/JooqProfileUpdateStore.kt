package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.MembershipEditTarget
import com.finaxis.platform.lifecycle.application.ProfileUpdateStore
import com.finaxis.platform.lifecycle.application.UpdateMembershipCommand
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** jOOQ adapter for profile and membership edits. */
@Component
class JooqProfileUpdateStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : ProfileUpdateStore {
    override fun lockUserStatus(userId: UUID): String? =
        dsl
            .select(USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(userId))
            .forUpdate()
            .fetchOne(USER_ACCOUNT.STATUS)

    override fun updateUserProfile(
        userId: UUID,
        displayName: String?,
        phoneE164: String?,
        actorId: UUID,
    ) {
        val now = OffsetDateTime.now(clock)
        var update =
            dsl
                .update(USER_ACCOUNT)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, actorId)
                .set(USER_ACCOUNT.ROW_VERSION, USER_ACCOUNT.ROW_VERSION.plus(1))
        displayName?.let { update = update.set(USER_ACCOUNT.DISPLAY_NAME, it) }
        phoneE164?.let { update = update.set(USER_ACCOUNT.PHONE_E164, it) }
        update.where(USER_ACCOUNT.ID.eq(userId)).execute()
    }

    override fun lockMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipEditTarget? =
        dsl
            .select(
                USER_ORGANISATION_MEMBERSHIP.USER_ID,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .forUpdate()
            .fetchOne { record ->
                MembershipEditTarget(
                    userId = requireNotNull(record.get(USER_ORGANISATION_MEMBERSHIP.USER_ID)),
                    status =
                        requireNotNull(record.get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)),
                    type =
                        requireNotNull(record.get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE)),
                )
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
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE"))
                .and(BRANCH.STATUS.eq("ACTIVE")),
        )

    override fun activeBranchExists(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(BRANCH)
                .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                .and(BRANCH.ID.eq(branchId))
                .and(BRANCH.STATUS.eq("ACTIVE")),
        )

    override fun updateMembership(
        command: UpdateMembershipCommand,
        now: OffsetDateTime,
    ) {
        var update =
            dsl
                .update(USER_ORGANISATION_MEMBERSHIP)
                .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
                .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_BY, command.actorId)
                .set(
                    USER_ORGANISATION_MEMBERSHIP.ROW_VERSION,
                    USER_ORGANISATION_MEMBERSHIP.ROW_VERSION.plus(1),
                )
        command.membershipType?.let {
            update = update.set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, it)
        }
        if (command.changesPrimaryBranch) {
            update =
                update.set(USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID, command.primaryBranchId)
        }
        update
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(command.organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.ID.eq(command.membershipId))
            .execute()
    }
}
