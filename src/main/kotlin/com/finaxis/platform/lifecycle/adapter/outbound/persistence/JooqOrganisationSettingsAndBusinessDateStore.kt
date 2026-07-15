package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.lifecycle.application.BusinessDateSnapshot
import com.finaxis.platform.lifecycle.application.BusinessDateStore
import com.finaxis.platform.lifecycle.application.OrganisationSettingsStore
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * jOOQ adapter for post-provisioning organisation settings updates. Settings are effective-dated:
 * updating a key closes its currently open row and inserts a new one.
 */
@Component("organisationSettingsStore")
class JooqOrganisationSettingsStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : OrganisationSettingsStore {
    override fun currentSettings(
        organisationId: UUID,
        keys: Set<String>,
    ): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        return dsl
            .select(ORGANISATION_SETTING.SETTING_KEY, ORGANISATION_SETTING.SETTING_VALUE)
            .from(ORGANISATION_SETTING)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.SETTING_KEY.`in`(keys))
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .fetch()
            .associate { record -> settingEntry(record) }
    }

    override fun updateSettings(
        organisationId: UUID,
        updates: Map<String, String>,
        actorId: UUID,
    ) {
        val now = now()
        updates.forEach { (key, value) ->
            // Serializes concurrent writers on the same (organisation, key) for the life of the
            // enclosing transaction: without this, two concurrent updates can each close the row
            // they read and insert their own replacement, leaving two rows with effective_to = null
            // (the schema only makes (organisation_id, setting_key, effective_from) unique).
            lockSettingKey(organisationId, key)
            closeCurrentSetting(organisationId, key, actorId, now)
            insertSetting(organisationId, key, value, actorId, now)
        }
    }

    private fun lockSettingKey(
        organisationId: UUID,
        key: String,
    ) {
        dsl.execute(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            "$organisationId:$key",
        )
    }

    private fun settingEntry(record: org.jooq.Record2<String?, JSONB?>): Pair<String, String> {
        val key = requireNotNull(record.get(ORGANISATION_SETTING.SETTING_KEY))
        val value =
            objectMapper.readValue(
                requireNotNull(record.get(ORGANISATION_SETTING.SETTING_VALUE)).data(),
                String::class.java,
            )
        return key to value
    }

    private fun closeCurrentSetting(
        organisationId: UUID,
        key: String,
        actorId: UUID,
        now: java.time.OffsetDateTime,
    ) {
        dsl
            .update(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.EFFECTIVE_TO, now)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_BY, actorId)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.SETTING_KEY.eq(key))
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .execute()
    }

    private fun insertSetting(
        organisationId: UUID,
        key: String,
        value: String,
        actorId: UUID,
        now: java.time.OffsetDateTime,
    ) {
        dsl
            .insertInto(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.ID, uuidV7())
            .set(ORGANISATION_SETTING.ORGANISATION_ID, organisationId)
            .set(ORGANISATION_SETTING.SETTING_KEY, key)
            .set(
                ORGANISATION_SETTING.SETTING_VALUE,
                JSONB.jsonb(objectMapper.writeValueAsString(value)),
            ).set(ORGANISATION_SETTING.VALUE_TYPE, "STRING")
            .set(ORGANISATION_SETTING.EFFECTIVE_FROM, now)
            .set(ORGANISATION_SETTING.CREATED_AT, now)
            .set(ORGANISATION_SETTING.CREATED_BY, actorId)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_BY, actorId)
            .execute()
    }

    private fun now() = clock.instant().atOffset(ZoneOffset.UTC)
}

/** jOOQ adapter for advancing the controlled, optimistically-locked organisation business date. */
@Component("businessDateStore")
class JooqBusinessDateStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : BusinessDateStore {
    override fun current(organisationId: UUID): BusinessDateSnapshot? =
        dsl
            .select(
                BUSINESS_DATE.CURRENT_BUSINESS_DATE,
                BUSINESS_DATE.STATUS,
                BUSINESS_DATE.ROW_VERSION,
            ).from(BUSINESS_DATE)
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
            .fetchOne(::businessDateSnapshot)

    override fun advance(
        organisationId: UUID,
        newDate: LocalDate,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean {
        val updated =
            dsl
                .update(BUSINESS_DATE)
                .set(BUSINESS_DATE.CURRENT_BUSINESS_DATE, newDate)
                .set(BUSINESS_DATE.LAST_ADVANCED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .set(BUSINESS_DATE.ADVANCED_BY, actorId)
                .set(BUSINESS_DATE.ROW_VERSION, expectedRowVersion + 1)
                .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                .and(BUSINESS_DATE.ROW_VERSION.eq(expectedRowVersion))
                .execute()
        return updated == 1
    }

    private companion object {
        fun businessDateSnapshot(record: org.jooq.Record): BusinessDateSnapshot =
            BusinessDateSnapshot(
                currentBusinessDate =
                    requireNotNull(record.get(BUSINESS_DATE.CURRENT_BUSINESS_DATE)),
                status = requireNotNull(record.get(BUSINESS_DATE.STATUS)),
                rowVersion = requireNotNull(record.get(BUSINESS_DATE.ROW_VERSION)),
            )
    }
}
