package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.application.GlAccountPostingPolicy
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.ResolvePostingPeriodCommand
import com.finaxis.platform.accounting.application.ResolvedPostingPeriod
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingDates
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingRequestStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Everything the engine needs to record one journal, with the legs still to be supplied.
 *
 * [context] is what the caller claims; the engine reconciles it against the ambient request context
 * and refuses a mismatch, so a caller cannot name another tenant. [source] is the durable lineage
 * and idempotency identity. The legs are not a field but a [LegProvider], because a rule-resolved
 * posting cannot know its legs until the posting date - and therefore the rule version in force -
 * has been resolved, which the engine does.
 */
data class LedgerPostingRequest(
    val context: AccountingContext,
    val source: AccountingSourceReference,
    val eventCode: String,
    val entryType: JournalEntryType,
    val dates: PostingDateRequest = PostingDateRequest(),
    val narrative: String? = null,
    val reversesJournalEntryId: UUID? = null,
    val correctsPostingRequestId: UUID? = null,
)

/**
 * Supplies the legs once the accounting dates are known.
 *
 * The public posting service wraps the posting-rule resolver here; reversal and manual journals
 * wrap an already explicit set of legs. The engine does not know which.
 */
fun interface LegProvider {
    /** Returns the legs to record for a posting whose dates resolved to [dates]. */
    fun legsFor(dates: AccountingDates): ResolvedLegs
}

/**
 * The single synchronous path that turns resolved legs into an immutable, balanced journal.
 *
 * Module-internal: product modules reach it only through
 * [com.finaxis.platform.accounting.application.posting.PostingService]; reversal (#43) and manual
 * journals (#48) are accounting-owned callers. That is what "the same PostingEngine" means
 * mechanically - there is exactly one place a `journal_entry` row is created.
 *
 * It runs inside the **caller's** transaction and requires one to be active. The source
 * mutation, the product sub-ledger effect and the journal therefore commit or roll back together
 * (`INV-12`); nothing here uses a broker, a background job or `REQUIRES_NEW`.
 *
 * The order of the steps is the design, and each is placed where it is for a reason stated in
 * `docs/architecture/accounting-foundation.md`: context and tenant checks before anything is
 * read; period resolution - which takes the shared row lock - before legs are resolved, because
 * the rule version depends on the posting date; the in-memory balance check before the claim, so
 * an unbalanced request never occupies its source reference; the claim before the number, so a
 * duplicate never burns a gapless number; and the verification read after the lines, before
 * return, because a row-level `CHECK` cannot sum children.
 */
class PostingEngine(
    private val contextLookup: AccountingContextLookup,
    private val tenants: AccountingTenantLookup,
    private val periods: PostingPeriodResolver,
    private val accounts: GlAccountStore,
    private val journals: JournalStore,
    private val numbers: JournalNumberAllocator,
    private val clock: Clock,
) {
    /**
     * Records one journal for [request] with the legs [legs] supplies, or records nothing.
     *
     * Returns the existing receipt when the same source reference was already posted with the same
     * fingerprint, so a retry after a lost response is a no-op rather than a duplicate (`INV-7`).
     */
    fun post(
        request: LedgerPostingRequest,
        legs: LegProvider,
    ): PostingReceipt {
        requireActiveTransaction()
        val context = reconcileContext(request.context)
        requireTenantPostable(context)
        val functionalCurrency = requireFunctionalCurrency(context.organisationId)

        val period =
            periods.resolveForPosting(
                ResolvePostingPeriodCommand(
                    organisationId = context.organisationId,
                    actorId = context.actorId,
                    dates = request.dates,
                ),
            )
        val resolved = legs.legsFor(period.dates)
        val settled = settleLegs(context, resolved.legs, functionalCurrency)
        val totals = requireBalanced(settled)
        val fingerprint =
            PostingFingerprint.of(
                request.source,
                request.eventCode,
                request.entryType,
                period.dates,
                functionalCurrency,
                settled,
            )

        val claim =
            journals.claimPostingRequest(
                newRequest(request, context, period, resolved, functionalCurrency, fingerprint),
            )
        return when (claim) {
            is PostingRequestClaim.Existing -> {
                replay(context, claim.request, fingerprint)
            }

            is PostingRequestClaim.Claimed -> {
                writeJournal(
                    Prepared(request, context, period, settled, totals, functionalCurrency),
                    claim.postingRequestId,
                )
            }
        }
    }

    /**
     * Writes header and lines for a claimed request, verifies them, and marks the request posted.
     *
     * `posted_at` is truncated to microseconds before it is written or returned: that is the
     * precision `TIMESTAMPTZ` holds, so the receipt of the original posting and the receipt a
     * retry reads back from the database are the same value rather than differing in nanoseconds.
     */
    private fun writeJournal(
        prepared: Prepared,
        postingRequestId: UUID,
    ): PostingReceipt {
        val request = prepared.request
        val context = prepared.context
        val period = prepared.period
        val settled = prepared.settled
        val totals = prepared.totals
        val functionalCurrency = prepared.functionalCurrency
        val postedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val entryNumber = allocateNumber(context.organisationId)
        val journalEntryId =
            journals.insertJournalEntry(
                NewJournalEntry(
                    organisationId = context.organisationId,
                    branchId = context.branchId,
                    postingRequestId = postingRequestId,
                    fiscalPeriodId = period.period.key.fiscalPeriodId,
                    entryNumber = entryNumber,
                    entryType = request.entryType,
                    reversesJournalEntryId = request.reversesJournalEntryId,
                    dates = period.dates,
                    currencyCode = functionalCurrency,
                    functionalCurrencyCode = functionalCurrency,
                    totalDebitFunctional = totals.debit,
                    totalCreditFunctional = totals.credit,
                    lineCount = settled.size,
                    narrative = request.narrative,
                    postedAt = postedAt,
                    actorId = context.actorId,
                ),
            )
        journals.insertJournalLines(
            settled.mapIndexed { index, leg ->
                NewJournalLine(
                    organisationId = context.organisationId,
                    journalEntryId = journalEntryId,
                    lineNumber = index + 1,
                    leg = leg,
                    branchId = context.branchId,
                    fiscalPeriodId = period.period.key.fiscalPeriodId,
                    postingDate = period.dates.postingDate,
                    functionalCurrencyCode = functionalCurrency,
                    functionalAmount = leg.amount.amount,
                    exchangeRate = UNITY,
                    sourceModule = request.source.sourceModule,
                    actorId = context.actorId,
                )
            },
        )
        verifyHeaderAgainstLines(context.organisationId, journalEntryId, totals, settled.size)
        journals.markPosted(context.organisationId, postingRequestId, postedAt, context.actorId)

        return PostingReceipt(
            postingRequestId = postingRequestId,
            journalEntryId = journalEntryId,
            journalReference = entryNumber.toString(),
            businessDate = period.dates.businessDate,
            postedAt = postedAt,
            lineCount = settled.size,
        )
    }

    /**
     * The caller's context is not a trust boundary. It must agree with the ambient one on tenant
     * and actor, and may not name a branch the request was not made under. A tenant-level claim -
     * no branch - is always acceptable, whatever branch the session selected.
     */
    private fun reconcileContext(claimed: AccountingContext): AccountingContext {
        val ambient = contextLookup.require()
        val branchAgrees = claimed.branchId == null || claimed.branchId == ambient.branchId
        if (claimed.organisationId != ambient.organisationId ||
            claimed.actorId != ambient.actorId ||
            !branchAgrees
        ) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.CONTEXT_MISMATCH,
                safeDetail = "The posting context does not match the active request context.",
            )
        }
        return claimed
    }

    private fun requireTenantPostable(context: AccountingContext) {
        if (!tenants.isOrganisationPostable(context.organisationId)) {
            throw ConflictException(
                code = PostingErrorCodes.ORGANISATION_NOT_POSTABLE,
                safeDetail = "The organisation is not in a state that permits financial activity.",
            )
        }
        val branchId = context.branchId
        if (branchId != null && !tenants.isBranchPostable(context.organisationId, branchId)) {
            throw ConflictException(
                code = PostingErrorCodes.BRANCH_NOT_POSTABLE,
                safeDetail = "The branch is not in a state that permits financial activity.",
            )
        }
    }

    private fun requireFunctionalCurrency(organisationId: UUID): String =
        tenants.functionalCurrencyOf(organisationId)
            ?: throw ConflictException(
                code = PostingErrorCodes.FUNCTIONAL_CURRENCY_UNAVAILABLE,
                safeDetail = "The organisation has no functional currency.",
            )

    /**
     * Validates every leg and returns them at storage scale.
     *
     * Single-currency for now, and deliberately so: ADR 0019 ships no rate table, so a leg in any
     * currency but the functional one is refused rather than converted at a silent rate of one.
     * The rate column is written as `1` because that is the truth for these postings, not a
     * placeholder.
     */
    private fun settleLegs(
        context: AccountingContext,
        legs: List<PostingLeg>,
        functionalCurrency: String,
    ): List<PostingLeg> {
        if (legs.size < MINIMUM_LEGS) {
            throw InvalidOperationException(
                code = PostingErrorCodes.UNBALANCED_POSTING,
                safeDetail = "A journal needs at least two legs.",
            )
        }
        return legs.map { leg ->
            val amount = MoneyPolicy.requireSettled(leg.amount)
            if (amount.currency != functionalCurrency) {
                throw InvalidOperationException(
                    code = PostingErrorCodes.CURRENCY_NOT_SUPPORTED,
                    safeDetail =
                        "Postings are accepted in the functional currency $functionalCurrency " +
                            "only; no exchange rate is configured.",
                )
            }
            requirePostableAccount(context.organisationId, leg.accountId)
            leg.copy(amount = amount)
        }
    }

    private fun requirePostableAccount(
        organisationId: UUID,
        accountId: UUID,
    ) {
        val account =
            accounts.findById(organisationId, accountId)
                ?: throw InvalidOperationException(
                    code = PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
                    safeDetail = "A referenced general-ledger account does not exist.",
                )
        GlAccountPostingPolicy.requirePostable(account)
    }

    private fun requireBalanced(legs: List<PostingLeg>): Totals {
        val debit = legs.filter { it.side == PostingSide.DEBIT }.sumOf { it.amount.amount }
        val credit = legs.filter { it.side == PostingSide.CREDIT }.sumOf { it.amount.amount }
        if (debit.compareTo(credit) != 0 || debit.signum() <= 0) {
            throw InvalidOperationException(
                code = PostingErrorCodes.UNBALANCED_POSTING,
                safeDetail = "Debit and credit totals must be equal and positive.",
            )
        }
        return Totals(debit, credit)
    }

    private fun newRequest(
        request: LedgerPostingRequest,
        context: AccountingContext,
        period: ResolvedPostingPeriod,
        resolved: ResolvedLegs,
        functionalCurrency: String,
        fingerprint: String,
    ) = NewPostingRequest(
        organisationId = context.organisationId,
        branchId = context.branchId,
        sourceModule = request.source.sourceModule,
        sourceEntityType = request.source.sourceType,
        sourceEntityId = request.source.sourceId,
        sourceReference = request.source.idempotencyKey,
        eventCode = request.eventCode,
        fingerprint = fingerprint,
        postingRuleVersionId = resolved.postingRuleVersionId,
        correctsPostingRequestId = request.correctsPostingRequestId,
        dates = period.dates,
        currencyCode = functionalCurrency,
        narrative = request.narrative,
        actorId = context.actorId,
        correlationId = context.correlationId,
        requestId = null,
    )

    /**
     * Answers a duplicate source reference from the row that already holds it.
     *
     * The row is locked, so its state cannot change underneath this decision. A posted request
     * with the same fingerprint is a retry and gets its receipt back; a different fingerprint is a
     * conflicting reuse of the identity and is refused (`INV-7`). A committed row that is still
     * `PENDING` cannot exist - the status flips in the same transaction as the journal - so it is
     * reported as a defect rather than guessed at.
     */
    private fun replay(
        context: AccountingContext,
        existing: ExistingPostingRequest,
        fingerprint: String,
    ): PostingReceipt {
        if (existing.fingerprint != fingerprint) {
            throw ConflictException(
                code = PostingErrorCodes.POSTING_REQUEST_CONFLICT,
                safeDetail =
                    "The source reference was already used for a materially different posting.",
            )
        }
        check(existing.status == PostingRequestStatus.POSTED) {
            "posting_request ${existing.id} is committed as ${existing.status}, which the engine " +
                "never produces: the status flips to POSTED in the same transaction as the journal."
        }
        val journal =
            checkNotNull(journals.findJournalEntryForRequest(context.organisationId, existing.id)) {
                "posting_request ${existing.id} is POSTED but has no journal_entry"
            }
        return PostingReceipt(
            postingRequestId = existing.id,
            journalEntryId = journal.id,
            journalReference = journal.entryNumber.toString(),
            businessDate = journal.businessDate,
            postedAt = journal.postedAt,
            lineCount = journal.lineCount,
        )
    }

    private fun allocateNumber(organisationId: UUID): Long =
        numbers.nextEntryNumber(organisationId)
            ?: throw ConflictException(
                code = PostingErrorCodes.JOURNAL_SEQUENCE_MISSING,
                safeDetail = "The organisation has no JOURNAL reference sequence.",
            )

    /**
     * The `INV-4` enforcement point. A header `CHECK` sees one row; this re-reads the lines the
     * database actually holds and compares them with the header before the transaction can commit.
     * A mismatch is a defect in this engine or its store, never a caller error, so it is a
     * `check` - the transaction rolls back and the journal never becomes visible.
     */
    private fun verifyHeaderAgainstLines(
        organisationId: UUID,
        journalEntryId: UUID,
        totals: Totals,
        lineCount: Int,
    ) {
        val stored = journals.sumLines(organisationId, journalEntryId)
        check(
            stored.debitFunctional.compareTo(totals.debit) == 0 &&
                stored.creditFunctional.compareTo(totals.credit) == 0 &&
                stored.lineCount == lineCount,
        ) {
            "journal_entry $journalEntryId header (${totals.debit}/${totals.credit}/$lineCount) " +
                "does not match its lines (${stored.debitFunctional}/${stored.creditFunctional}/" +
                "${stored.lineCount}); the posting is rolled back"
        }
    }

    private fun requireActiveTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Posting writes a journal that must commit with the caller's source mutation, so it " +
                "requires an active transaction."
        }
    }

    private data class Totals(
        val debit: BigDecimal,
        val credit: BigDecimal,
    )

    /** Everything validated before the claim, handed to the write step as one value. */
    private data class Prepared(
        val request: LedgerPostingRequest,
        val context: AccountingContext,
        val period: ResolvedPostingPeriod,
        val settled: List<PostingLeg>,
        val totals: Totals,
        val functionalCurrency: String,
    )

    private companion object {
        const val MINIMUM_LEGS = 2
        val UNITY: BigDecimal = BigDecimal.ONE
    }
}
