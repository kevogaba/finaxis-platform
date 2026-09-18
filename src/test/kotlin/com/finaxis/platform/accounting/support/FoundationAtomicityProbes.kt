package com.finaxis.platform.accounting.support

import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE_HISTORY
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT_DAILY_BALANCE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.jooq.tables.references.ORGANISATION_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.jooq.tables.references.REFERENCE_SEQUENCE
import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID

/**
 * Probes for the durable effects that exist today, including the three journal tables issue #41
 * writes and the reference-sequence counter it increments.
 *
 * `outbox_record` and `event_publication` are created by their starters outside Flyway and so have
 * no generated jOOQ metadata - those two probes use raw SQL deliberately.
 */
object FoundationAtomicityProbes {
    /** All rows for one organisation setting key, whether currently effective or closed. */
    fun organisationSettingRows(
        organisationId: UUID,
        key: String,
    ): AtomicityProbe =
        AtomicityProbe("organisation_setting[$key]") { dsl ->
            dsl
                .fetchCount(
                    ORGANISATION_SETTING,
                    ORGANISATION_SETTING.ORGANISATION_ID
                        .eq(organisationId)
                        .and(ORGANISATION_SETTING.SETTING_KEY.eq(key)),
                ).toLong()
        }

    /**
     * Only the currently effective row for one setting key. Distinct from
     * [organisationSettingRows] because a settings update closes the previous row and inserts a
     * replacement: counting all rows cannot see the UPDATE half of that pair rolling back.
     */
    fun openOrganisationSettingRows(
        organisationId: UUID,
        key: String,
    ): AtomicityProbe =
        AtomicityProbe("organisation_setting[$key, effective]") { dsl ->
            dsl
                .fetchCount(
                    ORGANISATION_SETTING,
                    ORGANISATION_SETTING.ORGANISATION_ID
                        .eq(organisationId)
                        .and(ORGANISATION_SETTING.SETTING_KEY.eq(key))
                        .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull),
                ).toLong()
        }

    /** Append-only business-date history rows for one organisation. */
    fun businessDateHistoryRows(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("business_date_history") { dsl ->
            dsl
                .fetchCount(
                    BUSINESS_DATE_HISTORY,
                    BUSINESS_DATE_HISTORY.ORGANISATION_ID.eq(organisationId),
                ).toLong()
        }

    /**
     * Whether the tenant's `business_date` row already carries [expected] — 0 before, 1 after.
     *
     * The other business-date probes count *appended* rows. This one watches the UPDATE to the
     * existing row, which is the advance's primary durable effect. Without it, a visibility test
     * proves only that the appended history, audit and outbox rows stay hidden until commit: if
     * the update itself were committed on an independent transaction, all three would still be
     * invisible and the test would pass.
     */
    fun advancedBusinessDateRows(
        organisationId: UUID,
        expected: LocalDate,
    ): AtomicityProbe =
        AtomicityProbe("business_date[current = $expected]") { dsl ->
            dsl
                .fetchCount(
                    BUSINESS_DATE,
                    BUSINESS_DATE.ORGANISATION_ID
                        .eq(organisationId)
                        .and(BUSINESS_DATE.CURRENT_BUSINESS_DATE.eq(expected)),
                ).toLong()
        }

    /** Audit rows for one organisation, action and outcome. */
    fun auditEventRows(
        organisationId: UUID,
        action: String,
        outcome: String,
    ): AtomicityProbe =
        AtomicityProbe("audit_event[$action, $outcome]") { dsl ->
            dsl
                .fetchCount(
                    AUDIT_EVENT,
                    AUDIT_EVENT.ORGANISATION_ID
                        .eq(organisationId)
                        .and(AUDIT_EVENT.ACTION.eq(action))
                        .and(AUDIT_EVENT.OUTCOME.eq(outcome)),
                ).toLong()
        }

    /**
     * Bootstrap-record submission attempts for one organisation.
     *
     * `submitForApproval` writes this row *before* delegating to the transition executor, so it is
     * the only durable effect of that method that a nested transition rejection can leave behind.
     * A rollback test that checks only the transition log and the outbox cannot see it: the
     * rejected transition never wrote either of those, so both assertions hold whether or not the
     * outer write rolled back.
     */
    fun bootstrapSubmissionAttempts(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("organisation_initial_administrator_bootstrap[attempts]") { dsl ->
            dsl
                .select(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ATTEMPTS)
                .from(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
                .where(
                    ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(organisationId),
                ).fetchOne()
                ?.value1()
                ?.toLong() ?: 0L
        }

    /** Posting requests for one organisation. */
    fun postingRequestRows(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("posting_request") { dsl ->
            dsl
                .fetchCount(POSTING_REQUEST, POSTING_REQUEST.ORGANISATION_ID.eq(organisationId))
                .toLong()
        }

    /** Journal headers for one organisation. */
    fun journalEntryRows(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("journal_entry") { dsl ->
            dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId)).toLong()
        }

    /** Journal lines for one organisation. */
    fun journalLineRows(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("journal_line") { dsl ->
            dsl.fetchCount(JOURNAL_LINE, JOURNAL_LINE.ORGANISATION_ID.eq(organisationId)).toLong()
        }

    /**
     * The tenant's next journal number. The one UPDATE the posting path performs on a pre-existing
     * row; counting appended rows cannot see it roll back, so it gets a probe of its own.
     */
    fun journalSequenceValue(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("reference_sequence[JOURNAL]") { dsl ->
            dsl
                .select(REFERENCE_SEQUENCE.NEXT_VALUE)
                .from(REFERENCE_SEQUENCE)
                .where(REFERENCE_SEQUENCE.ORGANISATION_ID.eq(organisationId))
                .and(REFERENCE_SEQUENCE.SEQUENCE_CODE.eq("JOURNAL"))
                .fetchOne(REFERENCE_SEQUENCE.NEXT_VALUE) ?: 0L
        }

    /** Manual-journal drafts for one organisation. */
    fun manualJournalRows(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("manual_journal") { dsl ->
            dsl
                .fetchCount(MANUAL_JOURNAL, MANUAL_JOURNAL.ORGANISATION_ID.eq(organisationId))
                .toLong()
        }

    /** Manual-journal draft lines for one organisation. */
    fun manualJournalLineRows(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("manual_journal_line") { dsl ->
            dsl
                .fetchCount(
                    MANUAL_JOURNAL_LINE,
                    MANUAL_JOURNAL_LINE.ORGANISATION_ID.eq(organisationId),
                ).toLong()
        }

    /** Append-only manual-journal transition log rows for one organisation. */
    fun manualJournalTransitionLogRows(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("manual_journal_transition_log") { dsl ->
            dsl
                .fetchCount(
                    MANUAL_JOURNAL_TRANSITION_LOG,
                    MANUAL_JOURNAL_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId),
                ).toLong()
        }

    /**
     * Daily-balance projection rows for one organisation.
     *
     * Present so that a test can assert the projection **does not move** inside a posting
     * transaction. It is a derived structure built after the fact by the business-date rollover
     * (`INV-13`, ADR 0027), so a posting that wrote one would have put a per-account write hotspot
     * into the `SERIALIZABLE` posting path - which is the precise thing `gl_account_balance` was
     * refused for. A probe that stays flat across a posting is the evidence for that, and a probe
     * that returns to its starting value across a failed *build* is the evidence the build is
     * atomic in its own right.
     */
    fun glAccountDailyBalanceRows(organisationId: UUID): AtomicityProbe =
        AtomicityProbe("gl_account_daily_balance") { dsl ->
            dsl
                .fetchCount(
                    GL_ACCOUNT_DAILY_BALANCE,
                    GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(organisationId),
                ).toLong()
        }

    /** The draft's status, which approval moves in the same transaction as the journal write. */
    fun manualJournalStatus(journalId: UUID): AtomicityProbe =
        AtomicityProbe("manual_journal[posted]") { dsl ->
            dsl
                .fetchCount(
                    MANUAL_JOURNAL,
                    MANUAL_JOURNAL.ID.eq(journalId).and(MANUAL_JOURNAL.STATUS.eq("POSTED")),
                ).toLong()
        }

    /** Append-only organisation lifecycle transition log rows for one aggregate. */
    fun organisationTransitionLogRows(entityId: UUID): AtomicityProbe =
        AtomicityProbe("organisation_transition_log") { dsl ->
            dsl
                .fetchCount(
                    ORGANISATION_TRANSITION_LOG,
                    ORGANISATION_TRANSITION_LOG.ENTITY_ID.eq(entityId),
                ).toLong()
        }

    /**
     * Namastack outbox rows whose serialized payload mentions [aggregateId]. Raw SQL because
     * `outbox_record` is starter-created outside Flyway and has no generated jOOQ metadata.
     */
    fun outboxRecordRows(aggregateId: String): AtomicityProbe =
        AtomicityProbe("outbox_record[$aggregateId]") { dsl ->
            dsl
                .fetchValue(
                    "SELECT COUNT(*) FROM outbox_record WHERE payload LIKE '%' || ? || '%'",
                    aggregateId,
                )?.toString()
                ?.toLong() ?: 0L
        }

    /**
     * Spring Modulith event-publication rows. Expected to stay at zero while no
     * `@ApplicationModuleListener` exists; the probe's job is to fail loudly the day one is added
     * without considering its transaction boundary.
     */
    fun eventPublicationRows(): AtomicityProbe =
        AtomicityProbe("event_publication") { dsl ->
            dsl.fetchValue("SELECT COUNT(*) FROM event_publication")?.toString()?.toLong() ?: 0L
        }
}
