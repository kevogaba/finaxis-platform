package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE_HISTORY
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.lifecycle.application.BusinessDateHistoryEntry
import com.finaxis.platform.lifecycle.application.BusinessDateHistoryPage
import com.finaxis.platform.lifecycle.application.BusinessDateHistoryRecord
import com.finaxis.platform.lifecycle.application.BusinessDateHistoryStore
import com.finaxis.platform.lifecycle.application.BusinessDateSnapshot
import com.finaxis.platform.lifecycle.application.BusinessDateStore
import com.finaxis.platform.lifecycle.application.OrganisationSettingsStore
import com.finaxis.platform.lifecycle.application.StoredSetting
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

    override fun currentSetting(
        organisationId: UUID,
        key: String,
    ): StoredSetting? = findCurrentSetting(organisationId, key)

    private fun findCurrentSetting(
        organisationId: UUID,
        key: String,
    ): StoredSetting? =
        dsl
            .select(
                ORGANISATION_SETTING.SETTING_KEY,
                ORGANISATION_SETTING.SETTING_VALUE,
                ORGANISATION_SETTING.VALUE_TYPE,
                ORGANISATION_SETTING.IS_SENSITIVE,
            ).from(ORGANISATION_SETTING)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.SETTING_KEY.eq(key))
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .fetchOne(::storedSetting)

    override fun currentSettingsList(organisationId: UUID): List<StoredSetting> =
        dsl
            .select(
                ORGANISATION_SETTING.SETTING_KEY,
                ORGANISATION_SETTING.SETTING_VALUE,
                ORGANISATION_SETTING.VALUE_TYPE,
                ORGANISATION_SETTING.IS_SENSITIVE,
            ).from(ORGANISATION_SETTING)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .fetch(::storedSetting)

    override fun upsertSetting(
        organisationId: UUID,
        key: String,
        value: String,
        valueType: String,
        sensitive: Boolean,
        actorId: UUID,
    ): StoredSetting? {
        lockSettingKey(organisationId, key)
        val before = findCurrentSetting(organisationId, key)
        val now = now()
        closeCurrentSetting(organisationId, key, actorId, now)
        dsl
            .insertInto(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.ID, uuidV7())
            .set(ORGANISATION_SETTING.ORGANISATION_ID, organisationId)
            .set(ORGANISATION_SETTING.SETTING_KEY, key)
            .set(
                ORGANISATION_SETTING.SETTING_VALUE,
                JSONB.jsonb(objectMapper.writeValueAsString(value)),
            ).set(ORGANISATION_SETTING.VALUE_TYPE, valueType)
            .set(ORGANISATION_SETTING.IS_SENSITIVE, sensitive)
            .set(ORGANISATION_SETTING.EFFECTIVE_FROM, now)
            .set(ORGANISATION_SETTING.CREATED_AT, now)
            .set(ORGANISATION_SETTING.CREATED_BY, actorId)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_BY, actorId)
            .execute()
        return before
    }

    override fun deactivateSetting(
        organisationId: UUID,
        key: String,
        actorId: UUID,
    ): Boolean {
        lockSettingKey(organisationId, key)
        return closeCurrentSetting(organisationId, key, actorId, now()) == 1
    }

    private fun storedSetting(record: org.jooq.Record): StoredSetting {
        val key = requireNotNull(record.get(ORGANISATION_SETTING.SETTING_KEY))
        val value =
            objectMapper.readValue(
                requireNotNull(record.get(ORGANISATION_SETTING.SETTING_VALUE)).data(),
                String::class.java,
            )
        return StoredSetting(
            key = key,
            value = value,
            valueType = requireNotNull(record.get(ORGANISATION_SETTING.VALUE_TYPE)),
            sensitive = record.get(ORGANISATION_SETTING.IS_SENSITIVE) ?: false,
        )
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
    ): Int =
        dsl
            .update(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.EFFECTIVE_TO, now)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_BY, actorId)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.SETTING_KEY.eq(key))
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .execute()

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

    override fun initialize(
        organisationId: UUID,
        initialDate: LocalDate,
        actorId: UUID,
    ): Boolean {
        val exists =
            dsl.fetchExists(
                dsl
                    .selectOne()
                    .from(BUSINESS_DATE)
                    .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId)),
            )
        if (exists) return false
        dsl
            .insertInto(BUSINESS_DATE)
            .set(BUSINESS_DATE.ID, uuidV7())
            .set(BUSINESS_DATE.ORGANISATION_ID, organisationId)
            .set(BUSINESS_DATE.CURRENT_BUSINESS_DATE, initialDate)
            .set(BUSINESS_DATE.STATUS, "OPEN")
            .set(BUSINESS_DATE.LAST_ADVANCED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(BUSINESS_DATE.ADVANCED_BY, actorId)
            .execute()
        return true
    }

    override fun changeStatus(
        organisationId: UUID,
        newStatus: String,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean =
        dsl
            .update(BUSINESS_DATE)
            .set(BUSINESS_DATE.STATUS, newStatus)
            .set(BUSINESS_DATE.ROW_VERSION, expectedRowVersion + 1)
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
            .and(BUSINESS_DATE.ROW_VERSION.eq(expectedRowVersion))
            .execute() == 1

    override fun startCob(
        organisationId: UUID,
        cobDate: LocalDate,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean =
        dsl
            .update(BUSINESS_DATE)
            .set(BUSINESS_DATE.STATUS, "CLOSING")
            .set(BUSINESS_DATE.CURRENT_COB_DATE, cobDate)
            .set(BUSINESS_DATE.ROW_VERSION, expectedRowVersion + 1)
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
            .and(BUSINESS_DATE.ROW_VERSION.eq(expectedRowVersion))
            .execute() == 1

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

/** jOOQ adapter for the append-only business-date / COB status change history. */
@Component("businessDateHistoryStore")
class JooqBusinessDateHistoryStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : BusinessDateHistoryStore {
    override fun append(entry: BusinessDateHistoryEntry) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(BUSINESS_DATE_HISTORY)
            .set(BUSINESS_DATE_HISTORY.ID, uuidV7())
            .set(BUSINESS_DATE_HISTORY.ORGANISATION_ID, entry.organisationId)
            .set(BUSINESS_DATE_HISTORY.EVENT_TYPE, entry.eventType)
            .set(BUSINESS_DATE_HISTORY.FROM_STATUS, entry.fromStatus)
            .set(BUSINESS_DATE_HISTORY.TO_STATUS, entry.toStatus)
            .set(BUSINESS_DATE_HISTORY.FROM_BUSINESS_DATE, entry.fromBusinessDate)
            .set(BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE, entry.toBusinessDate)
            .set(BUSINESS_DATE_HISTORY.ACTOR_ID, entry.actorId)
            .set(BUSINESS_DATE_HISTORY.REASON, entry.reason)
            .set(BUSINESS_DATE_HISTORY.OCCURRED_AT, entry.occurredAt.atOffset(ZoneOffset.UTC))
            .set(BUSINESS_DATE_HISTORY.CREATED_AT, now)
            .set(BUSINESS_DATE_HISTORY.CREATED_BY, entry.actorId)
            .execute()
    }

    override fun list(
        organisationId: UUID,
        page: Int,
        size: Int,
    ): BusinessDateHistoryPage {
        val total =
            dsl
                .selectCount()
                .from(BUSINESS_DATE_HISTORY)
                .where(BUSINESS_DATE_HISTORY.ORGANISATION_ID.eq(organisationId))
                .fetchOne(0, Long::class.java) ?: 0L
        val items =
            dsl
                .select(
                    BUSINESS_DATE_HISTORY.EVENT_TYPE,
                    BUSINESS_DATE_HISTORY.FROM_STATUS,
                    BUSINESS_DATE_HISTORY.TO_STATUS,
                    BUSINESS_DATE_HISTORY.FROM_BUSINESS_DATE,
                    BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE,
                    BUSINESS_DATE_HISTORY.ACTOR_ID,
                    BUSINESS_DATE_HISTORY.REASON,
                    BUSINESS_DATE_HISTORY.OCCURRED_AT,
                ).from(BUSINESS_DATE_HISTORY)
                .where(BUSINESS_DATE_HISTORY.ORGANISATION_ID.eq(organisationId))
                .orderBy(BUSINESS_DATE_HISTORY.OCCURRED_AT.desc())
                .limit(size)
                .offset(page * size)
                .fetch(::historyRecord)
        return BusinessDateHistoryPage(items, total)
    }

    private fun historyRecord(record: org.jooq.Record): BusinessDateHistoryRecord =
        BusinessDateHistoryRecord(
            eventType = requireNotNull(record.get(BUSINESS_DATE_HISTORY.EVENT_TYPE)),
            fromStatus = record.get(BUSINESS_DATE_HISTORY.FROM_STATUS),
            toStatus = requireNotNull(record.get(BUSINESS_DATE_HISTORY.TO_STATUS)),
            fromBusinessDate = record.get(BUSINESS_DATE_HISTORY.FROM_BUSINESS_DATE),
            toBusinessDate = requireNotNull(record.get(BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE)),
            actorId = record.get(BUSINESS_DATE_HISTORY.ACTOR_ID),
            reason = record.get(BUSINESS_DATE_HISTORY.REASON),
            occurredAt = requireNotNull(record.get(BUSINESS_DATE_HISTORY.OCCURRED_AT)).toInstant(),
        )
}
