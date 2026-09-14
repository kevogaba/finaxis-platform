package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.ledger.DivergentLineCounts
import com.finaxis.platform.accounting.application.ledger.ExistingPostingRequest
import com.finaxis.platform.accounting.application.ledger.JournalEntryView
import com.finaxis.platform.accounting.application.ledger.JournalLineDimensions
import com.finaxis.platform.accounting.application.ledger.JournalLineView
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.ledger.JournalStore
import com.finaxis.platform.accounting.application.ledger.JournalTotals
import com.finaxis.platform.accounting.application.ledger.NewJournalEntry
import com.finaxis.platform.accounting.application.ledger.NewJournalLine
import com.finaxis.platform.accounting.application.ledger.NewPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingRequestClaim
import com.finaxis.platform.accounting.application.ledger.PostingRequestView
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.PostingRequestStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.jooq.keys.UQ_POSTING_REQUEST_SOURCE
import com.finaxis.platform.jooq.tables.records.JournalEntryRecord
import com.finaxis.platform.jooq.tables.records.PostingRequestRecord
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The `posting_request`, `journal_entry` and `journal_line` adapter behind [JournalStore] and
 * [JournalReadStore].
 *
 * Every statement carries the tenant predicate, and the two journal tables are only ever
 * **inserted**: there is no `UPDATE` or `DELETE` here for them to be written by, which is the
 * application-level half of `INV-5` until issue #54's `REVOKE` makes it physical. The one row
 * this class updates is the mutable `posting_request`, and only to flip it to `POSTED`.
 */
@Component
@Suppress("TooManyFunctions")
class JooqJournalStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : JournalStore,
    JournalReadStore {
    /**
     * `INSERT … ON CONFLICT DO NOTHING RETURNING id`, then `FOR UPDATE` on the loser's row.
     *
     * When the row already exists - committed by an earlier transaction, or by a concurrent one
     * that the conflict check waited for - the insert returns nothing and the existing row is read
     * under an exclusive lock, so the engine's replay-or-conflict decision is made against a state
     * that cannot change until this transaction ends. A bare insert would raise a unique violation
     * and abort the caller's whole transaction instead.
     */
    override fun claimPostingRequest(request: NewPostingRequest): PostingRequestClaim {
        requireActiveTransaction("Claiming a posting request")
        val now = OffsetDateTime.now(clock)
        val inserted =
            dsl
                .insertInto(POSTING_REQUEST)
                .set(POSTING_REQUEST.ORGANISATION_ID, request.organisationId)
                .set(POSTING_REQUEST.BRANCH_ID, request.branchId)
                .set(POSTING_REQUEST.SOURCE_MODULE, request.sourceModule)
                .set(POSTING_REQUEST.SOURCE_ENTITY_TYPE, request.sourceEntityType)
                .set(POSTING_REQUEST.SOURCE_ENTITY_ID, request.sourceEntityId)
                .set(POSTING_REQUEST.SOURCE_REFERENCE, request.sourceReference)
                .set(POSTING_REQUEST.EVENT_CODE, request.eventCode)
                .set(POSTING_REQUEST.REQUEST_FINGERPRINT, request.fingerprint)
                .set(POSTING_REQUEST.CORRECTS_POSTING_REQUEST_ID, request.correctsPostingRequestId)
                .set(POSTING_REQUEST.BUSINESS_DATE, request.dates.businessDate)
                .set(POSTING_REQUEST.TRANSACTION_DATE, request.dates.transactionDate)
                .set(POSTING_REQUEST.VALUE_DATE, request.dates.valueDate)
                .set(POSTING_REQUEST.POSTING_DATE, request.dates.postingDate)
                .set(POSTING_REQUEST.CURRENCY_CODE, request.currencyCode)
                .set(POSTING_REQUEST.NARRATIVE, request.narrative)
                .set(POSTING_REQUEST.STATUS, PostingRequestStatus.PENDING.name)
                .set(POSTING_REQUEST.CORRELATION_ID, request.correlationId)
                .set(POSTING_REQUEST.REQUEST_ID, request.requestId)
                .set(POSTING_REQUEST.CREATED_AT, now)
                .set(POSTING_REQUEST.CREATED_BY, request.actorId)
                .set(POSTING_REQUEST.UPDATED_AT, now)
                .set(POSTING_REQUEST.UPDATED_BY, request.actorId)
                .onConflictOnConstraint(UQ_POSTING_REQUEST_SOURCE)
                .doNothing()
                .returning(POSTING_REQUEST.ID)
                .fetchOne()
        if (inserted != null) {
            return PostingRequestClaim.Claimed(inserted.id!!)
        }
        val existing =
            dsl
                .select(
                    POSTING_REQUEST.ID,
                    POSTING_REQUEST.STATUS,
                    POSTING_REQUEST.REQUEST_FINGERPRINT,
                ).from(POSTING_REQUEST)
                .where(POSTING_REQUEST.ORGANISATION_ID.eq(request.organisationId))
                .and(POSTING_REQUEST.SOURCE_MODULE.eq(request.sourceModule))
                .and(POSTING_REQUEST.SOURCE_REFERENCE.eq(request.sourceReference))
                .forUpdate()
                .fetchOne()
        checkNotNull(existing) {
            "the claim inserted nothing yet no row holds the source reference; ON CONFLICT and " +
                "the locking read disagree, which cannot happen under READ COMMITTED"
        }
        return PostingRequestClaim.Existing(
            ExistingPostingRequest(
                id = existing.value1()!!,
                status = PostingRequestStatus.valueOf(existing.value2()!!),
                fingerprint = existing.value3()!!,
            ),
        )
    }

    override fun insertJournalEntry(entry: NewJournalEntry): UUID {
        requireActiveTransaction("Writing a journal entry")
        return dsl
            .insertInto(JOURNAL_ENTRY)
            .set(JOURNAL_ENTRY.ORGANISATION_ID, entry.organisationId)
            .set(JOURNAL_ENTRY.BRANCH_ID, entry.branchId)
            .set(JOURNAL_ENTRY.POSTING_REQUEST_ID, entry.postingRequestId)
            .set(JOURNAL_ENTRY.FISCAL_PERIOD_ID, entry.fiscalPeriodId)
            .set(JOURNAL_ENTRY.ENTRY_NUMBER, entry.entryNumber)
            .set(JOURNAL_ENTRY.ENTRY_TYPE, entry.entryType.name)
            .set(JOURNAL_ENTRY.REVERSES_JOURNAL_ENTRY_ID, entry.reversesJournalEntryId)
            .set(JOURNAL_ENTRY.BUSINESS_DATE, entry.dates.businessDate)
            .set(JOURNAL_ENTRY.TRANSACTION_DATE, entry.dates.transactionDate)
            .set(JOURNAL_ENTRY.VALUE_DATE, entry.dates.valueDate)
            .set(JOURNAL_ENTRY.POSTING_DATE, entry.dates.postingDate)
            .set(JOURNAL_ENTRY.CURRENCY_CODE, entry.currencyCode)
            .set(JOURNAL_ENTRY.FUNCTIONAL_CURRENCY_CODE, entry.functionalCurrencyCode)
            .set(JOURNAL_ENTRY.TOTAL_DEBIT_FUNCTIONAL, entry.totalDebitFunctional)
            .set(JOURNAL_ENTRY.TOTAL_CREDIT_FUNCTIONAL, entry.totalCreditFunctional)
            .set(JOURNAL_ENTRY.LINE_COUNT, entry.lineCount)
            .set(JOURNAL_ENTRY.NARRATIVE, entry.narrative)
            .set(JOURNAL_ENTRY.POSTED_AT, entry.postedAt.atOffset(ZoneOffset.UTC))
            .set(JOURNAL_ENTRY.CREATED_AT, OffsetDateTime.now(clock))
            .set(JOURNAL_ENTRY.CREATED_BY, entry.actorId)
            .returning(JOURNAL_ENTRY.ID)
            .fetchOne()!!
            .id!!
    }

    override fun insertJournalLines(lines: List<NewJournalLine>) {
        requireActiveTransaction("Writing journal lines")
        if (lines.isEmpty()) {
            return
        }
        val now = OffsetDateTime.now(clock)
        val insert =
            dsl.insertInto(
                JOURNAL_LINE,
                JOURNAL_LINE.ORGANISATION_ID,
                JOURNAL_LINE.JOURNAL_ENTRY_ID,
                JOURNAL_LINE.LINE_NUMBER,
                JOURNAL_LINE.GL_ACCOUNT_ID,
                JOURNAL_LINE.BRANCH_ID,
                JOURNAL_LINE.FISCAL_PERIOD_ID,
                JOURNAL_LINE.POSTING_DATE,
                JOURNAL_LINE.DIRECTION,
                JOURNAL_LINE.CURRENCY_CODE,
                JOURNAL_LINE.AMOUNT,
                JOURNAL_LINE.FUNCTIONAL_CURRENCY_CODE,
                JOURNAL_LINE.FUNCTIONAL_AMOUNT,
                JOURNAL_LINE.EXCHANGE_RATE,
                JOURNAL_LINE.SOURCE_MODULE,
                JOURNAL_LINE.SUBLEDGER_REFERENCE,
                JOURNAL_LINE.NARRATIVE,
                JOURNAL_LINE.CREATED_AT,
                JOURNAL_LINE.CREATED_BY,
            )
        lines.forEach { line ->
            insert.values(
                line.organisationId,
                line.journalEntryId,
                line.lineNumber,
                line.leg.accountId,
                line.branchId,
                line.fiscalPeriodId,
                line.postingDate,
                line.leg.side.name,
                line.leg.amount.currency,
                line.leg.amount.amount,
                line.functionalCurrencyCode,
                line.functionalAmount,
                line.exchangeRate,
                line.sourceModule,
                line.leg.subledgerReference,
                line.leg.narrative,
                now,
                line.actorId,
            )
        }
        insert.execute()
    }

    /**
     * The `INV-4` verification read: what the database holds, not what the engine believes it
     * wrote. `FILTER` aggregates in one statement, so the totals, the count and the per-dimension
     * divergence counts all come from the same snapshot.
     *
     * Each dimension is counted with `IS DISTINCT FROM`, which is NULL-safe in both directions:
     * `branch_id` is nullable, so a plain `<>` would let a NULL line pass against a non-NULL
     * header and vice versa - exactly the mismatch worth catching.
     */
    override fun sumLines(
        organisationId: UUID,
        journalEntryId: UUID,
        header: JournalLineDimensions,
    ): JournalTotals {
        val debit =
            DSL
                .sum(JOURNAL_LINE.FUNCTIONAL_AMOUNT)
                .filterWhere(JOURNAL_LINE.DIRECTION.eq(PostingSide.DEBIT.name))
        val credit =
            DSL
                .sum(JOURNAL_LINE.FUNCTIONAL_AMOUNT)
                .filterWhere(JOURNAL_LINE.DIRECTION.eq(PostingSide.CREDIT.name))
        val divergentBranch = countDiverging(JOURNAL_LINE.BRANCH_ID, header.branchId)
        val divergentPeriod = countDiverging(JOURNAL_LINE.FISCAL_PERIOD_ID, header.fiscalPeriodId)
        val divergentDate = countDiverging(JOURNAL_LINE.POSTING_DATE, header.postingDate)
        val divergentCurrency = countDiverging(JOURNAL_LINE.CURRENCY_CODE, header.currencyCode)
        val divergentFunctional =
            countDiverging(JOURNAL_LINE.FUNCTIONAL_CURRENCY_CODE, header.functionalCurrencyCode)
        val row =
            dsl
                .select(
                    debit,
                    credit,
                    DSL.count(),
                    divergentBranch,
                    divergentPeriod,
                    divergentDate,
                    divergentCurrency,
                    divergentFunctional,
                ).from(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
                .and(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(journalEntryId))
                .fetchOne()!!
        return JournalTotals(
            debitFunctional = row.value1() ?: BigDecimal.ZERO,
            creditFunctional = row.value2() ?: BigDecimal.ZERO,
            lineCount = row.value3(),
            divergentLines =
                DivergentLineCounts(
                    branch = row.value4(),
                    fiscalPeriod = row.value5(),
                    postingDate = row.value6(),
                    currency = row.value7(),
                    functionalCurrency = row.value8(),
                ),
        )
    }

    /** `count(*) FILTER (WHERE field IS DISTINCT FROM expected)`, NULL-safe on both sides. */
    private fun <T> countDiverging(
        field: Field<T>,
        expected: T?,
    ) = DSL.count().filterWhere(field.isDistinctFrom(expected))

    override fun markPosted(
        organisationId: UUID,
        postingRequestId: UUID,
        postedAt: Instant,
        postingRuleVersionId: UUID?,
        actorId: UUID,
    ) {
        requireActiveTransaction("Marking a posting request posted")
        val updated =
            dsl
                .update(POSTING_REQUEST)
                .set(POSTING_REQUEST.STATUS, PostingRequestStatus.POSTED.name)
                .set(POSTING_REQUEST.POSTED_AT, postedAt.atOffset(ZoneOffset.UTC))
                .set(POSTING_REQUEST.POSTING_RULE_VERSION_ID, postingRuleVersionId)
                .set(POSTING_REQUEST.UPDATED_AT, OffsetDateTime.now(clock))
                .set(POSTING_REQUEST.UPDATED_BY, actorId)
                .set(POSTING_REQUEST.ROW_VERSION, POSTING_REQUEST.ROW_VERSION.plus(1))
                .where(POSTING_REQUEST.ORGANISATION_ID.eq(organisationId))
                .and(POSTING_REQUEST.ID.eq(postingRequestId))
                .and(POSTING_REQUEST.STATUS.eq(PostingRequestStatus.PENDING.name))
                .execute()
        check(updated == 1) {
            "posting_request $postingRequestId was not PENDING when the journal was written"
        }
    }

    override fun findJournalEntry(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalEntryView? =
        dsl
            .selectFrom(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_ENTRY.ID.eq(journalEntryId))
            .fetchOne()
            ?.let(::toView)

    override fun findJournalEntryForRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): JournalEntryView? =
        dsl
            .selectFrom(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_ENTRY.POSTING_REQUEST_ID.eq(postingRequestId))
            .fetchOne()
            ?.let(::toView)

    /** Served by `uq_journal_entry_reversal_once`, which also makes the answer at most one row. */
    override fun findReversalOf(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalEntryView? =
        dsl
            .selectFrom(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_ENTRY.REVERSES_JOURNAL_ENTRY_ID.eq(journalEntryId))
            .fetchOne()
            ?.let(::toView)

    override fun findPostingRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): PostingRequestView? =
        dsl
            .selectFrom(POSTING_REQUEST)
            .where(POSTING_REQUEST.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_REQUEST.ID.eq(postingRequestId))
            .fetchOne()
            ?.let(::toRequestView)

    override fun findPostingRequestBySource(
        organisationId: UUID,
        sourceModule: String,
        sourceReference: String,
    ): PostingRequestView? =
        dsl
            .selectFrom(POSTING_REQUEST)
            .where(POSTING_REQUEST.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_REQUEST.SOURCE_MODULE.eq(sourceModule))
            .and(POSTING_REQUEST.SOURCE_REFERENCE.eq(sourceReference))
            .fetchOne()
            ?.let(::toRequestView)

    /**
     * Served by `idx_posting_request_source_entity`; the `id < cursor` predicate walks the
     * time-ordered primary key backwards, so page fifty costs what page one costs.
     */
    @Suppress("LongParameterList")
    override fun listPostingRequestsForEntity(
        organisationId: UUID,
        sourceModule: String,
        sourceEntityType: String,
        sourceEntityId: UUID,
        beforeId: UUID?,
        pageSize: Int,
    ): List<PostingRequestView> =
        dsl
            .selectFrom(POSTING_REQUEST)
            .where(POSTING_REQUEST.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_REQUEST.SOURCE_MODULE.eq(sourceModule))
            .and(POSTING_REQUEST.SOURCE_ENTITY_TYPE.eq(sourceEntityType))
            .and(POSTING_REQUEST.SOURCE_ENTITY_ID.eq(sourceEntityId))
            .and(beforeId?.let { POSTING_REQUEST.ID.lt(it) } ?: DSL.noCondition())
            .orderBy(POSTING_REQUEST.ID.desc())
            .limit(pageSize)
            .fetch(::toRequestView)

    override fun findJournalLines(
        organisationId: UUID,
        journalEntryId: UUID,
    ): List<JournalLineView> =
        dsl
            .select(
                JOURNAL_LINE.LINE_NUMBER,
                JOURNAL_LINE.GL_ACCOUNT_ID,
                JOURNAL_LINE.DIRECTION,
                JOURNAL_LINE.AMOUNT,
                JOURNAL_LINE.CURRENCY_CODE,
                JOURNAL_LINE.FUNCTIONAL_AMOUNT,
                JOURNAL_LINE.NARRATIVE,
                JOURNAL_LINE.SUBLEDGER_REFERENCE,
            ).from(JOURNAL_LINE)
            .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(journalEntryId))
            .orderBy(JOURNAL_LINE.LINE_NUMBER)
            .fetch { row ->
                JournalLineView(
                    lineNumber = row.value1()!!,
                    accountId = row.value2()!!,
                    side = PostingSide.valueOf(row.value3()!!),
                    amount = row.value4()!!,
                    currencyCode = row.value5()!!,
                    functionalAmount = row.value6()!!,
                    narrative = row.value7(),
                    subledgerReference = row.value8(),
                )
            }

    private fun toRequestView(record: PostingRequestRecord) =
        PostingRequestView(
            id = record.id!!,
            organisationId = record.organisationId!!,
            branchId = record.branchId,
            sourceModule = record.sourceModule!!,
            sourceEntityType = record.sourceEntityType!!,
            sourceEntityId = record.sourceEntityId!!,
            sourceReference = record.sourceReference!!,
            eventCode = record.eventCode!!,
            status = PostingRequestStatus.valueOf(record.status!!),
            postingRuleVersionId = record.postingRuleVersionId,
            correctsPostingRequestId = record.correctsPostingRequestId,
            postingDate = record.postingDate!!,
            businessDate = record.businessDate!!,
            narrative = record.narrative,
            postedAt = record.postedAt?.toInstant(),
            requestedBy = record.createdBy,
            correlationId = record.correlationId,
        )

    private fun toView(record: JournalEntryRecord) =
        JournalEntryView(
            id = record.id!!,
            organisationId = record.organisationId!!,
            branchId = record.branchId,
            postingRequestId = record.postingRequestId!!,
            fiscalPeriodId = record.fiscalPeriodId!!,
            entryNumber = record.entryNumber!!,
            entryType = JournalEntryType.valueOf(record.entryType!!),
            reversesJournalEntryId = record.reversesJournalEntryId,
            postingDate = record.postingDate!!,
            businessDate = record.businessDate!!,
            currencyCode = record.currencyCode!!,
            functionalCurrencyCode = record.functionalCurrencyCode!!,
            totalDebitFunctional = record.totalDebitFunctional!!,
            lineCount = record.lineCount!!,
            narrative = record.narrative,
            postedAt = record.postedAt!!.toInstant(),
            postedBy = record.createdBy,
        )

    private fun requireActiveTransaction(operation: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "$operation must run inside the posting transaction."
        }
    }
}
