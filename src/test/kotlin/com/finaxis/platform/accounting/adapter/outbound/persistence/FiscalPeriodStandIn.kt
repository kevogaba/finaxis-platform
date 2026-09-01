package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.FiscalPeriodKey
import com.finaxis.platform.accounting.application.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.FiscalPeriodStatus
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Stands in for `accounting_fiscal_period`, which issue #36 creates.
 *
 * Issue #35 ships fiscal-period *semantics* before the table exists. Mocking the store would make
 * the concurrency tests prove nothing about PostgreSQL, which the issue explicitly forbids — so the
 * production [PostgresRowLock] is instead bound to an existing tenant-scoped table.
 *
 * `organisation_setting` is used because it is tenant-scoped, keyed by a UUID `id`, and carries an
 * arbitrary JSONB value that stands in for exactly one column: `status`. Deliberately **not**
 * routed through `OrganisationSettingsStore`, which would drag in its own
 * `pg_advisory_xact_lock` on the settings key and conflate two lock domains.
 *
 * The only thing substituted is where the status byte lives. The lock statements, their strengths,
 * the lookup-then-lock-then-revalidate order, the READ COMMITTED re-read, the transaction manager,
 * the connection pool and PostgreSQL 18.4 itself are all the production ones. When #36 lands, this
 * file is deleted and a real adapter replaces it; nothing else changes.
 */
class FiscalPeriodStandIn(
    private val dsl: DSLContext,
    private val rowLock: PostgresRowLock,
) : FiscalPeriodStateStore {
    /** Creates a stand-in period row and returns its key. */
    fun createPeriod(
        organisationId: UUID,
        status: FiscalPeriodStatus,
    ): FiscalPeriodKey {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.ID, id)
            .set(ORGANISATION_SETTING.ORGANISATION_ID, organisationId)
            .set(ORGANISATION_SETTING.SETTING_KEY, "$PERIOD_KEY_PREFIX$id")
            .set(ORGANISATION_SETTING.SETTING_VALUE, JSONB.jsonb("\"${status.name}\""))
            .set(ORGANISATION_SETTING.VALUE_TYPE, "STRING")
            .set(ORGANISATION_SETTING.IS_SENSITIVE, false)
            .set(ORGANISATION_SETTING.EFFECTIVE_FROM, now)
            .set(ORGANISATION_SETTING.CREATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .execute()
        return FiscalPeriodKey(organisationId, id)
    }

    /** Records a stand-in journal row, so a test can assert whether a posting committed. */
    fun recordJournal(key: FiscalPeriodKey) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.ID, uuidV7())
            .set(ORGANISATION_SETTING.ORGANISATION_ID, key.organisationId)
            .set(ORGANISATION_SETTING.SETTING_KEY, "$JOURNAL_KEY_PREFIX${uuidV7()}")
            .set(ORGANISATION_SETTING.SETTING_VALUE, JSONB.jsonb("\"${key.fiscalPeriodId}\""))
            .set(ORGANISATION_SETTING.VALUE_TYPE, "STRING")
            .set(ORGANISATION_SETTING.IS_SENSITIVE, false)
            .set(ORGANISATION_SETTING.EFFECTIVE_FROM, now)
            .set(ORGANISATION_SETTING.CREATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .execute()
    }

    /** Counts stand-in journal rows for one organisation. */
    fun journalCount(organisationId: UUID): Int =
        dsl.fetchCount(
            ORGANISATION_SETTING,
            ORGANISATION_SETTING.ORGANISATION_ID
                .eq(organisationId)
                .and(ORGANISATION_SETTING.SETTING_KEY.like("$JOURNAL_KEY_PREFIX%")),
        )

    /** Updates the status with a plain UPDATE, taking no explicit lock. */
    fun updateStatusWithoutLocking(
        key: FiscalPeriodKey,
        newStatus: FiscalPeriodStatus,
    ): Int =
        dsl
            .update(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.SETTING_VALUE, JSONB.jsonb("\"${newStatus.name}\""))
            .where(
                ORGANISATION_SETTING.ID
                    .eq(key.fiscalPeriodId)
                    .and(ORGANISATION_SETTING.ORGANISATION_ID.eq(key.organisationId)),
            ).execute()

    override fun findCovering(
        organisationId: UUID,
        postingDate: LocalDate,
    ): FiscalPeriodSnapshot? =
        dsl
            .select(ORGANISATION_SETTING.ID, ORGANISATION_SETTING.SETTING_VALUE)
            .from(ORGANISATION_SETTING)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.SETTING_KEY.like("$PERIOD_KEY_PREFIX%"))
            .fetchOne()
            ?.let { snapshot(organisationId, it.value1()!!, it.value2()!!) }

    override fun lockForPosting(key: FiscalPeriodKey): FiscalPeriodSnapshot? =
        readUnderLock(
            key,
        ) { id, org ->
            rowLock.lockForShare(
                ORGANISATION_SETTING,
                ORGANISATION_SETTING.ID,
                ORGANISATION_SETTING.ORGANISATION_ID,
                id,
                org,
            )
        }

    override fun lockForStateChange(key: FiscalPeriodKey): FiscalPeriodSnapshot? =
        readUnderLock(key) { id, org ->
            rowLock.lockForUpdate(
                ORGANISATION_SETTING,
                ORGANISATION_SETTING.ID,
                ORGANISATION_SETTING.ORGANISATION_ID,
                id,
                org,
            )
        }

    override fun updateStatus(
        key: FiscalPeriodKey,
        newStatus: FiscalPeriodStatus,
    ): Boolean = updateStatusWithoutLocking(key, newStatus) == 1

    /**
     * Takes the lock, then re-reads. The re-read is the whole point: under READ COMMITTED the
     * locking statement observes the latest committed row, so a status change that committed
     * between the unlocked lookup and this call is seen rather than missed.
     */
    private fun readUnderLock(
        key: FiscalPeriodKey,
        lock: (UUID, UUID) -> Boolean,
    ): FiscalPeriodSnapshot? {
        if (!lock(key.fiscalPeriodId, key.organisationId)) {
            return null
        }
        // Both the lock and this re-read carry the tenant predicate, and the snapshot's
        // organisation id is taken from the ROW rather than echoed back from the caller's key.
        // Echoing it would label another tenant's period with the caller's organisation, which is
        // exactly how a cross-tenant write passes a downstream tenant check.
        return dsl
            .select(ORGANISATION_SETTING.SETTING_VALUE, ORGANISATION_SETTING.ORGANISATION_ID)
            .from(ORGANISATION_SETTING)
            .where(
                ORGANISATION_SETTING.ID
                    .eq(key.fiscalPeriodId)
                    .and(ORGANISATION_SETTING.ORGANISATION_ID.eq(key.organisationId)),
            ).fetchOne()
            ?.let { snapshot(it.value2()!!, key.fiscalPeriodId, it.value1()!!) }
    }

    private fun snapshot(
        organisationId: UUID,
        id: UUID,
        value: JSONB,
    ) = FiscalPeriodSnapshot(
        key = FiscalPeriodKey(organisationId, id),
        startDate = PERIOD_START,
        endDate = PERIOD_END,
        status = FiscalPeriodStatus.valueOf(value.data().trim('"')),
    )

    private companion object {
        const val PERIOD_KEY_PREFIX = "test.fiscal_period."
        const val JOURNAL_KEY_PREFIX = "test.journal."
        val PERIOD_START: LocalDate = LocalDate.of(2026, 8, 1)
        val PERIOD_END: LocalDate = LocalDate.of(2026, 8, 31)
    }
}
