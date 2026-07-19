package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.jooq.tables.references.ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapRecord
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * jOOQ implementation of the [InitialAdministratorBootstrapStore] port.
 */
@Component
class JooqInitialAdministratorBootstrapStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : InitialAdministratorBootstrapStore {
    override fun createDraft(
        organisationId: UUID,
        admin: InitialAdministratorDraft,
        requestedBy: UUID,
    ) {
        val now = now()
        val t = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        dsl
            .insertInto(t)
            .set(t.ORGANISATION_ID, organisationId)
            .set(t.ADMIN_EMAIL, admin.email)
            .set(t.ADMIN_USERNAME, admin.username)
            .set(t.ADMIN_DISPLAY_NAME, admin.displayName)
            .set(t.ADMIN_PHONE_E164, admin.phoneE164)
            .set(t.SEND_APPLICATION_INVITE, admin.sendApplicationInvite)
            .set(t.STATUS, InitialAdministratorBootstrapStatus.DRAFT.name)
            .set(t.ATTEMPTS, 0)
            .set(t.REQUESTED_BY, requestedBy)
            .set(t.CREATED_AT, now)
            .set(t.UPDATED_AT, now)
            .set(t.ROW_VERSION, 0L)
            .execute()
    }

    override fun amendDraft(
        organisationId: UUID,
        admin: InitialAdministratorDraft,
    ) {
        val now = now()
        val t = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        dsl
            .update(t)
            .set(t.ADMIN_EMAIL, admin.email)
            .set(t.ADMIN_USERNAME, admin.username)
            .set(t.ADMIN_DISPLAY_NAME, admin.displayName)
            .set(t.ADMIN_PHONE_E164, admin.phoneE164)
            .set(t.SEND_APPLICATION_INVITE, admin.sendApplicationInvite)
            .set(t.UPDATED_AT, now)
            .set(t.ROW_VERSION, t.ROW_VERSION.plus(1))
            .where(t.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    override fun submit(
        organisationId: UUID,
        actorId: UUID,
    ) {
        val now = now()
        val t = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        dsl
            .update(t)
            .set(t.STATUS, InitialAdministratorBootstrapStatus.PENDING_ACTIVATION.name)
            .set(t.SUBMITTED_BY, actorId)
            .set(t.SUBMITTED_AT, now)
            .set(t.UPDATED_AT, now)
            .set(t.ROW_VERSION, t.ROW_VERSION.plus(1))
            .where(t.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    override fun approve(
        organisationId: UUID,
        actorId: UUID,
    ) {
        val now = now()
        val t = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        dsl
            .update(t)
            .set(t.STATUS, InitialAdministratorBootstrapStatus.QUEUED.name)
            .set(t.APPROVED_BY, actorId)
            .set(t.APPROVED_AT, now)
            .set(t.UPDATED_AT, now)
            .set(t.ROW_VERSION, t.ROW_VERSION.plus(1))
            .where(t.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    override fun reject(organisationId: UUID) {
        val now = now()
        val t = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        dsl
            .update(t)
            .set(t.STATUS, InitialAdministratorBootstrapStatus.DRAFT.name)
            .setNull(t.SUBMITTED_BY)
            .setNull(t.SUBMITTED_AT)
            .setNull(t.APPROVED_BY)
            .setNull(t.APPROVED_AT)
            .set(t.UPDATED_AT, now)
            .set(t.ROW_VERSION, t.ROW_VERSION.plus(1))
            .where(t.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    override fun find(organisationId: UUID): InitialAdministratorBootstrapRecord? =
        dsl
            .selectFrom(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .fetchOne(::mapToRecord)

    override fun updateStatus(
        organisationId: UUID,
        status: InitialAdministratorBootstrapStatus,
        lastFailureCode: String?,
        incrementAttempts: Boolean,
    ) {
        val now = now()
        val t = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        val update =
            dsl
                .update(t)
                .set(t.STATUS, status.name)
                .set(t.LAST_FAILURE_CODE, lastFailureCode)
                .set(t.UPDATED_AT, now)
                .set(t.ROW_VERSION, t.ROW_VERSION.plus(1))

        if (incrementAttempts) {
            update.set(t.ATTEMPTS, t.ATTEMPTS.plus(1))
        }

        update.where(t.ORGANISATION_ID.eq(organisationId)).execute()
    }

    override fun linkResolvedEntities(
        organisationId: UUID,
        userId: UUID?,
        membershipId: UUID?,
        headOfficeId: UUID?,
        roleId: UUID?,
    ) {
        val now = now()
        dsl
            .update(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.USER_ID, userId)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.MEMBERSHIP_ID, membershipId)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.HEAD_OFFICE_ID, headOfficeId)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROLE_ID, roleId)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.UPDATED_AT, now)
            .set(
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION,
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION.plus(1),
            ).where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    private fun mapToRecord(record: Record): InitialAdministratorBootstrapRecord {
        val t = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        return InitialAdministratorBootstrapRecord(
            organisationId = requireNotNull(record.get(t.ORGANISATION_ID)),
            adminEmail = requireNotNull(record.get(t.ADMIN_EMAIL)),
            adminUsername = requireNotNull(record.get(t.ADMIN_USERNAME)),
            adminDisplayName = requireNotNull(record.get(t.ADMIN_DISPLAY_NAME)),
            adminPhoneE164 = record.get(t.ADMIN_PHONE_E164),
            sendApplicationInvite = requireNotNull(record.get(t.SEND_APPLICATION_INVITE)),
            status =
                InitialAdministratorBootstrapStatus.valueOf(
                    requireNotNull(record.get(t.STATUS)),
                ),
            attempts = requireNotNull(record.get(t.ATTEMPTS)),
            requestedBy = requireNotNull(record.get(t.REQUESTED_BY)),
            submittedBy = record.get(t.SUBMITTED_BY),
            approvedBy = record.get(t.APPROVED_BY),
            userId = record.get(t.USER_ID),
            membershipId = record.get(t.MEMBERSHIP_ID),
            headOfficeId = record.get(t.HEAD_OFFICE_ID),
            roleId = record.get(t.ROLE_ID),
            lastFailureCode = record.get(t.LAST_FAILURE_CODE),
            createdAt = requireNotNull(record.get(t.CREATED_AT)).toInstant(),
            submittedAt = record.get(t.SUBMITTED_AT)?.toInstant(),
            approvedAt = record.get(t.APPROVED_AT)?.toInstant(),
            updatedAt = requireNotNull(record.get(t.UPDATED_AT)).toInstant(),
            rowVersion = requireNotNull(record.get(t.ROW_VERSION)),
        )
    }
}
