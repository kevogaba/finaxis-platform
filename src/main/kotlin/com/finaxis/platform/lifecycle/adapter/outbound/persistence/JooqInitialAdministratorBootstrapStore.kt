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
        dsl
            .insertInto(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID, organisationId)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_EMAIL, admin.email)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_USERNAME, admin.username)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_DISPLAY_NAME, admin.displayName)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_PHONE_E164, admin.phoneE164)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SEND_APPLICATION_INVITE, admin.sendApplicationInvite)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.STATUS, InitialAdministratorBootstrapStatus.DRAFT.name)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ATTEMPTS, 0)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.REQUESTED_BY, requestedBy)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.CREATED_AT, now)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.UPDATED_AT, now)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION, 0L)
            .execute()
    }

    override fun amendDraft(
        organisationId: UUID,
        admin: InitialAdministratorDraft,
    ) {
        val now = now()
        dsl
            .update(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_EMAIL, admin.email)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_USERNAME, admin.username)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_DISPLAY_NAME, admin.displayName)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_PHONE_E164, admin.phoneE164)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SEND_APPLICATION_INVITE, admin.sendApplicationInvite)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.UPDATED_AT, now)
            .set(
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION,
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION.plus(1)
            )
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    override fun submit(
        organisationId: UUID,
        actorId: UUID,
    ) {
        val now = now()
        dsl
            .update(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.STATUS, InitialAdministratorBootstrapStatus.PENDING_ACTIVATION.name)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SUBMITTED_BY, actorId)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SUBMITTED_AT, now)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.UPDATED_AT, now)
            .set(
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION,
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION.plus(1)
            )
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    override fun approve(
        organisationId: UUID,
        actorId: UUID,
    ) {
        val now = now()
        dsl
            .update(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.STATUS, InitialAdministratorBootstrapStatus.QUEUED.name)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.APPROVED_BY, actorId)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.APPROVED_AT, now)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.UPDATED_AT, now)
            .set(
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION,
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION.plus(1)
            )
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    override fun reject(organisationId: UUID) {
        val now = now()
        dsl
            .update(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.STATUS, InitialAdministratorBootstrapStatus.DRAFT.name)
            .setNull(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SUBMITTED_BY)
            .setNull(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SUBMITTED_AT)
            .setNull(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.APPROVED_BY)
            .setNull(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.APPROVED_AT)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.UPDATED_AT, now)
            .set(
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION,
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION.plus(1)
            )
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    override fun find(organisationId: UUID): InitialAdministratorBootstrapRecord? {
        return dsl
            .selectFrom(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .fetchOne(::mapToRecord)
    }

    override fun updateStatus(
        organisationId: UUID,
        status: InitialAdministratorBootstrapStatus,
        lastFailureCode: String?,
    ) {
        val now = now()
        dsl
            .update(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.STATUS, status.name)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.LAST_FAILURE_CODE, lastFailureCode)
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.UPDATED_AT, now)
            .set(
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION,
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION.plus(1)
            )
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    override fun incrementAttempts(organisationId: UUID) {
        val now = now()
        dsl
            .update(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .set(
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ATTEMPTS,
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ATTEMPTS.plus(1)
            )
            .set(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.UPDATED_AT, now)
            .set(
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION,
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION.plus(1)
            )
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .execute()
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
                ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION.plus(1)
            )
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    private fun mapToRecord(record: Record): InitialAdministratorBootstrapRecord {
        return InitialAdministratorBootstrapRecord(
            organisationId = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID)),
            adminEmail = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_EMAIL)),
            adminUsername = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_USERNAME)),
            adminDisplayName = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_DISPLAY_NAME)),
            adminPhoneE164 = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ADMIN_PHONE_E164),
            sendApplicationInvite = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SEND_APPLICATION_INVITE)),
            status = InitialAdministratorBootstrapStatus.valueOf(
                requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.STATUS))
            ),
            attempts = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ATTEMPTS)),
            requestedBy = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.REQUESTED_BY)),
            submittedBy = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SUBMITTED_BY),
            approvedBy = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.APPROVED_BY),
            userId = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.USER_ID),
            membershipId = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.MEMBERSHIP_ID),
            headOfficeId = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.HEAD_OFFICE_ID),
            roleId = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROLE_ID),
            lastFailureCode = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.LAST_FAILURE_CODE),
            createdAt = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.CREATED_AT)).toInstant(),
            submittedAt = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SUBMITTED_AT)?.toInstant(),
            approvedAt = record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.APPROVED_AT)?.toInstant(),
            updatedAt = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.UPDATED_AT)).toInstant(),
            rowVersion = requireNotNull(record.get(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ROW_VERSION))
        )
    }
}
