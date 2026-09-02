package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.manual.ManualJournalDraftContent
import com.finaxis.platform.accounting.application.manual.ManualJournalStore
import com.finaxis.platform.accounting.application.manual.NewManualJournal
import com.finaxis.platform.accounting.domain.ManualJournal
import com.finaxis.platform.accounting.domain.ManualJournalLine
import com.finaxis.platform.accounting.domain.ManualJournalStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.jooq.tables.records.ManualJournalLineRecord
import com.finaxis.platform.jooq.tables.records.ManualJournalRecord
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL_LINE
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** The `manual_journal` and `manual_journal_line` adapter. Every statement carries the tenant. */
@Component
@Suppress("TooManyFunctions")
class JooqManualJournalStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : ManualJournalStore {
    override fun find(
        organisationId: UUID,
        journalId: UUID,
    ): ManualJournal? =
        dsl
            .selectFrom(MANUAL_JOURNAL)
            .where(MANUAL_JOURNAL.ORGANISATION_ID.eq(organisationId))
            .and(MANUAL_JOURNAL.ID.eq(journalId))
            .fetchOne()
            ?.let(::toJournal)

    override fun lockForStateChange(
        organisationId: UUID,
        journalId: UUID,
    ): ManualJournal? {
        requireActiveTransaction("Locking a manual journal")
        return dsl
            .selectFrom(MANUAL_JOURNAL)
            .where(MANUAL_JOURNAL.ORGANISATION_ID.eq(organisationId))
            .and(MANUAL_JOURNAL.ID.eq(journalId))
            .forUpdate()
            .fetchOne()
            ?.let(::toJournal)
    }

    override fun create(journal: NewManualJournal): ManualJournal {
        val now = OffsetDateTime.now(clock)
        return dsl
            .insertInto(MANUAL_JOURNAL)
            .set(MANUAL_JOURNAL.ORGANISATION_ID, journal.organisationId)
            .set(MANUAL_JOURNAL.BRANCH_ID, journal.branchId)
            .set(MANUAL_JOURNAL.TITLE, journal.title)
            .set(MANUAL_JOURNAL.NARRATIVE, journal.narrative)
            .set(MANUAL_JOURNAL.STATUS, ManualJournalStatus.DRAFT.name)
            .set(MANUAL_JOURNAL.TRANSACTION_DATE, journal.transactionDate)
            .set(MANUAL_JOURNAL.VALUE_DATE, journal.valueDate)
            .set(MANUAL_JOURNAL.POSTING_DATE, journal.postingDate)
            .set(MANUAL_JOURNAL.CREATED_AT, now)
            .set(MANUAL_JOURNAL.CREATED_BY, journal.actorId)
            .set(MANUAL_JOURNAL.UPDATED_AT, now)
            .set(MANUAL_JOURNAL.UPDATED_BY, journal.actorId)
            .returning()
            .fetchOne()!!
            .let(::toJournal)
    }

    override fun updateDraft(
        organisationId: UUID,
        journalId: UUID,
        content: ManualJournalDraftContent,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean =
        dsl
            .update(MANUAL_JOURNAL)
            .set(MANUAL_JOURNAL.BRANCH_ID, content.branchId)
            .set(MANUAL_JOURNAL.TITLE, content.title)
            .set(MANUAL_JOURNAL.NARRATIVE, content.narrative)
            .set(MANUAL_JOURNAL.TRANSACTION_DATE, content.transactionDate)
            .set(MANUAL_JOURNAL.VALUE_DATE, content.valueDate)
            .set(MANUAL_JOURNAL.POSTING_DATE, content.postingDate)
            .set(MANUAL_JOURNAL.UPDATED_AT, OffsetDateTime.now(clock))
            .set(MANUAL_JOURNAL.UPDATED_BY, actorId)
            .set(MANUAL_JOURNAL.ROW_VERSION, MANUAL_JOURNAL.ROW_VERSION.plus(1))
            .where(MANUAL_JOURNAL.ORGANISATION_ID.eq(organisationId))
            .and(MANUAL_JOURNAL.ID.eq(journalId))
            .and(MANUAL_JOURNAL.STATUS.eq(ManualJournalStatus.DRAFT.name))
            .and(MANUAL_JOURNAL.ROW_VERSION.eq(expectedRowVersion))
            .execute() == 1

    @Suppress("LongParameterList")
    override fun updateStatus(
        organisationId: UUID,
        journalId: UUID,
        from: ManualJournalStatus,
        to: ManualJournalStatus,
        reason: String?,
        journalEntryId: UUID?,
        actorId: UUID,
    ): Boolean {
        requireActiveTransaction("Moving a manual journal")
        val update =
            dsl
                .update(MANUAL_JOURNAL)
                .set(MANUAL_JOURNAL.STATUS, to.name)
                .set(MANUAL_JOURNAL.STATUS_REASON, reason)
                .set(MANUAL_JOURNAL.UPDATED_AT, OffsetDateTime.now(clock))
                .set(MANUAL_JOURNAL.UPDATED_BY, actorId)
                .set(MANUAL_JOURNAL.ROW_VERSION, MANUAL_JOURNAL.ROW_VERSION.plus(1))
        val withEntry =
            if (journalEntryId != null) {
                update.set(MANUAL_JOURNAL.JOURNAL_ENTRY_ID, journalEntryId)
            } else {
                update
            }
        return withEntry
            .where(MANUAL_JOURNAL.ORGANISATION_ID.eq(organisationId))
            .and(MANUAL_JOURNAL.ID.eq(journalId))
            .and(MANUAL_JOURNAL.STATUS.eq(from.name))
            .execute() == 1
    }

    override fun findLines(
        organisationId: UUID,
        journalId: UUID,
    ): List<ManualJournalLine> =
        dsl
            .selectFrom(MANUAL_JOURNAL_LINE)
            .where(MANUAL_JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
            .and(MANUAL_JOURNAL_LINE.MANUAL_JOURNAL_ID.eq(journalId))
            .orderBy(MANUAL_JOURNAL_LINE.LINE_NUMBER)
            .fetch(::toLine)

    override fun replaceLines(
        organisationId: UUID,
        journalId: UUID,
        lines: List<ManualJournalLine>,
        actorId: UUID,
    ) {
        requireActiveTransaction("Replacing manual-journal lines")
        dsl
            .deleteFrom(MANUAL_JOURNAL_LINE)
            .where(MANUAL_JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
            .and(MANUAL_JOURNAL_LINE.MANUAL_JOURNAL_ID.eq(journalId))
            .execute()
        if (lines.isEmpty()) {
            return
        }
        val now = OffsetDateTime.now(clock)
        val insert =
            dsl.insertInto(
                MANUAL_JOURNAL_LINE,
                MANUAL_JOURNAL_LINE.ORGANISATION_ID,
                MANUAL_JOURNAL_LINE.MANUAL_JOURNAL_ID,
                MANUAL_JOURNAL_LINE.LINE_NUMBER,
                MANUAL_JOURNAL_LINE.GL_ACCOUNT_ID,
                MANUAL_JOURNAL_LINE.DIRECTION,
                MANUAL_JOURNAL_LINE.AMOUNT,
                MANUAL_JOURNAL_LINE.CURRENCY_CODE,
                MANUAL_JOURNAL_LINE.NARRATIVE,
                MANUAL_JOURNAL_LINE.CREATED_AT,
                MANUAL_JOURNAL_LINE.CREATED_BY,
                MANUAL_JOURNAL_LINE.UPDATED_AT,
                MANUAL_JOURNAL_LINE.UPDATED_BY,
            )
        lines.forEach { line ->
            insert.values(
                organisationId,
                journalId,
                line.lineNumber,
                line.accountId,
                line.side.name,
                line.amount,
                line.currencyCode,
                line.narrative,
                now,
                actorId,
                now,
                actorId,
            )
        }
        insert.execute()
    }

    private fun toJournal(record: ManualJournalRecord) =
        ManualJournal(
            id = record.id!!,
            organisationId = record.organisationId!!,
            branchId = record.branchId,
            title = record.title!!,
            narrative = record.narrative!!,
            status = ManualJournalStatus.valueOf(record.status!!),
            statusReason = record.statusReason,
            transactionDate = record.transactionDate,
            valueDate = record.valueDate,
            postingDate = record.postingDate,
            journalEntryId = record.journalEntryId,
            createdBy = record.createdBy,
            rowVersion = record.rowVersion!!,
        )

    private fun toLine(record: ManualJournalLineRecord) =
        ManualJournalLine(
            lineNumber = record.lineNumber!!,
            accountId = record.glAccountId!!,
            side = PostingSide.valueOf(record.direction!!),
            amount = record.amount!!,
            currencyCode = record.currencyCode!!,
            narrative = record.narrative,
        )

    private fun requireActiveTransaction(operation: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "$operation takes a row lock or writes rows that must commit with their transaction."
        }
    }
}
