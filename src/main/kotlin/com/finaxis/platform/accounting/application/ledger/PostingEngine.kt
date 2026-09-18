package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.application.FunctionalCurrencyLock
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.RequiredSnapshotIsolation
import com.finaxis.platform.accounting.application.ResolvedPostingPeriod
import com.finaxis.platform.accounting.application.SnapshotIsolationGuard
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.port.outbound.PostingMetadataLookup
import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingDates
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingRequestStatus
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Everything the engine needs to record one journal, with the legs still to be supplied.
 *
 * [context] is what the caller claims; the engine reconciles it against the ambient request context
 * and refuses a mismatch, so a caller cannot name another tenant. [source] is the durable lineage
 * and idempotency identity. The legs are not a field but a [LegProvider], because a rule-resolved
 * posting cannot know its legs until the posting date - and therefore the rule version in force -
 * has been resolved, which the engine does, and only for a genuinely new posting (see
 * [PostingEngine.post]). [financialFacts] is what lets the idempotency fingerprint discriminate on
 * amount even though the legs cannot be resolved before the claim: the caller's own asserted
 * amounts, known before any rule ever runs. Empty for reversal and manual journals, whose source
 * reference already derives from an immutable record and so can never legitimately name two
 * different amounts (see [PostingFingerprint]). [productClass] is likewise a selector dimension the
 * fingerprint must see: it can route the same event code to a different posting rule
 * ([com.finaxis.platform.accounting.domain.PostingRuleSelector]), so two requests differing only in
 * it are not the same posting even when every financial fact matches.
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
    val financialFacts: List<FinancialFact> = emptyList(),
    val productClass: String? = null,
)

/**
 * Supplies the legs once the accounting dates are known.
 *
 * The public posting service wraps the posting-rule resolver here; reversal and manual journals
 * wrap an already explicit set of legs. The engine does not know which, and - since issue #89 -
 * does not call this at all for a request that turns out to replay one already posted.
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
 * **The claim comes first, before anything that can drift.** [post] reconciles the caller's
 * context, validates a correction's target (if any) exists and has been reversed - immutable,
 * append-only facts that can only ever go from false to true, never back, so checking them is
 * never what breaks a replay - resolves the accounting dates - a pure function of the tenant
 * business date, which cannot itself go stale - computes the idempotency fingerprint from those
 * dates and the caller's own inputs, and claims `(organisation, source_module, source_reference)`,
 * all before it asks whether the fiscal period is open, whether the organisation or branch may
 * currently post, what the legs are, or whether the accounts they reference are eligible. A
 * faithful retry of an already committed posting is answered by [replay] from that claim alone: it
 * never re-enters any of that validation, so a period that has since closed, an account that has
 * since been deactivated, a posting rule that has since changed, or a tenant that has since been
 * suspended can never turn a successful retry into a spurious failure (see
 * `docs/adr/0023-posting-idempotency-and-account-locking.md`). Only a request the claim finds
 * genuinely new - [postNew] - runs the state that *can* drift, in this order: the tenant's
 * functional-currency lock, taken shared and first so that a tenant's very first posting cannot
 * interleave with a change to the currency it is denominated in; a locking re-read of the
 * organisation row, which is what stops this posting acting on a currency another transaction has
 * already replaced; tenant and branch
 * postability; the period lock, which takes the shared lock issue #35 requires before the legs are
 * resolved, because the rule version depends on the posting date; a lock on every distinct account
 * the legs reference, in ascending id order, which is what closes the account-eligibility race
 * from the posting side (`docs/adr/0023-...`); and the in-memory eligibility pass over the legs -
 * currency, account postability and the balance - which is [PostingLegsPolicy], shared verbatim
 * with the administrator's dry run so the two cannot disagree (issue #95). The verification
 * read happens after the lines, before return, because a row-level `CHECK` cannot sum children.
 *
 * **Lock ordering**, stated once because a new flow has to be checked against it. A posting
 * acquires: the tenant-currency advisory lock (shared), the `organisation` row (`FOR SHARE`), the
 * covering `accounting_fiscal_period` row (`FOR SHARE`), every `gl_account` row the legs name
 * (`FOR SHARE`, ascending id), and finally the tenant's `reference_sequence` row, which the
 * gapless allocator updates. No cycle is introduced by the organisation row, and the reason is
 * worth writing down rather than assuming: nothing that takes the tenant-currency advisory lock
 * ever locks the organisation row exclusively. The `base_currency` *setting* change takes that
 * advisory lock exclusively and then writes `organisation_setting`, never `organisation`; the only
 * writer of `organisation.base_currency_code` is the draft amendment, which refuses any
 * organisation that is not `DRAFT` and so can never overlap a posting at all.
 */
class PostingEngine(
    private val contextLookup: AccountingContextLookup,
    private val metadata: PostingMetadataLookup,
    private val tenants: AccountingTenantLookup,
    private val currency: FunctionalCurrencyLock,
    private val periods: PostingPeriodResolver,
    private val accounts: GlAccountStore,
    private val journals: JournalStore,
    private val journalReads: JournalReadStore,
    private val numbers: JournalNumberAllocator,
    private val clock: Clock,
    private val snapshots: SnapshotIsolationGuard,
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
        // Second statement, and before the claim, for three reasons. The claim's
        // `ON CONFLICT DO NOTHING` is itself a statement whose semantics change with the isolation
        // level, so guarding after it would guard a decision already taken under unknown rules.
        // After the claim, whether a misconfigured caller is refused would depend on whether this
        // request happens to be a replay, and a misconfiguration that fails the first time and
        // succeeds on every retry is the worst signal available. And because this engine always
        // runs inside the caller's transaction - `MANDATORY` on the public service - the
        // transaction being judged is the *caller's*, the one holding the source mutation that
        // must commit with the journal or not at all (`INV-12`), so it has to be judged before
        // this method writes anything into it. Do not move this call later as an
        // optimisation: at `REPEATABLE READ` and above the guard's own `current_setting` read pins
        // the transaction snapshot, and moving it changes what every read after it sees.
        snapshots.requireStableSnapshot(RequiredSnapshotIsolation.SERIALIZABLE, "A posting")
        val context = reconcileContext(request.context)
        requireValidCorrectionTarget(context.organisationId, request.correctsPostingRequestId)
        val dates = periods.resolveDates(context.organisationId, request.dates)
        val functionalCurrency = requireFunctionalCurrency(context.organisationId)
        val fingerprint =
            PostingFingerprint.of(
                request,
                context.branchId,
                dates,
                functionalCurrency,
            )

        val claim =
            journals.claimPostingRequest(
                newRequest(request, context, dates, functionalCurrency, fingerprint),
            )
        return when (claim) {
            is PostingRequestClaim.Existing -> {
                replay(context, claim.request, fingerprint)
            }

            is PostingRequestClaim.Claimed -> {
                postNew(request, context, dates, functionalCurrency, legs, claim.postingRequestId)
            }
        }
    }

    /**
     * Validates and writes a genuinely new posting, now that this transaction alone holds the
     * source reference.
     *
     * Nothing here runs for a replay, and that is the point: a faithful retry of an already
     * committed posting must return its receipt even when the organisation or branch has since been
     * suspended, the fiscal period has since closed, an account has since been deactivated, or the
     * posting rule that resolved it has since changed. None of that is re-decided on a replay
     * because none of it is re-decided-able - the journal already exists. Everything checked here
     * is state that can move backward over time; [requireValidCorrectionTarget] is checked in
     * [post] instead, before the claim, precisely because it cannot.
     */
    private fun postNew(
        request: LedgerPostingRequest,
        context: AccountingContext,
        dates: AccountingDates,
        functionalCurrency: String,
        legs: LegProvider,
        postingRequestId: UUID,
    ): PostingReceipt {
        // First lock of the chain, and shared, so postings do not wait on each other. It is a
        // genuinely new posting that can be a tenant's first, and therefore the one a concurrent
        // base_currency change must not interleave with; a replay takes no lock here at all. The
        // locking re-read on the next line does not make this redundant - see
        // [requireFunctionalCurrencyUnchanged] for which half of the race each one closes.
        currency.lockForPosting(context.organisationId)
        requireFunctionalCurrencyUnchanged(context.organisationId, functionalCurrency)
        requireTenantPostable(context)
        val period =
            periods.lockAndValidate(
                context.organisationId,
                context.actorId,
                dates,
                request.source,
            )
        val resolved = legs.legsFor(period.dates)
        val lockedAccounts = lockAccounts(context.organisationId, resolved.legs)
        // [lockAccounts] has already refused every account it could not read, so this map is total
        // over the legs and [PostingLegsPolicy]'s missing-account branch cannot fire here. That
        // branch exists for the dry run, which looks accounts up without locking them.
        val settled =
            PostingLegsPolicy.settle(resolved.legs, functionalCurrency, lockedAccounts::get)

        return writeJournal(
            Prepared(
                request = request,
                context = context,
                period = period,
                settled = settled,
                functionalCurrency = functionalCurrency,
                postingRuleVersionId = resolved.postingRuleVersionId,
            ),
            postingRequestId,
        )
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
        val context = prepared.context
        val period = prepared.period
        val settled = prepared.settled
        val postedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val entryNumber = allocateNumber(context.organisationId)
        val journalEntryId =
            journals.insertJournalEntry(
                newJournalEntry(prepared, postingRequestId, entryNumber, postedAt),
            )
        journals.insertJournalLines(newJournalLines(prepared, journalEntryId))
        verifyHeaderAgainstLines(context.organisationId, journalEntryId, prepared)
        journals.markPosted(
            context.organisationId,
            postingRequestId,
            postedAt,
            prepared.postingRuleVersionId,
            context.actorId,
        )

        return PostingReceipt(
            postingRequestId = postingRequestId,
            journalEntryId = journalEntryId,
            journalReference = entryNumber.toString(),
            businessDate = period.dates.businessDate,
            postedAt = postedAt,
            lineCount = settled.legs.size,
        )
    }

    private fun newJournalEntry(
        prepared: Prepared,
        postingRequestId: UUID,
        entryNumber: Long,
        postedAt: Instant,
    ) = NewJournalEntry(
        organisationId = prepared.context.organisationId,
        branchId = prepared.context.branchId,
        postingRequestId = postingRequestId,
        fiscalPeriodId = prepared.period.period.key.fiscalPeriodId,
        entryNumber = entryNumber,
        entryType = prepared.request.entryType,
        reversesJournalEntryId = prepared.request.reversesJournalEntryId,
        dates = prepared.period.dates,
        currencyCode = prepared.functionalCurrency,
        functionalCurrencyCode = prepared.functionalCurrency,
        totalDebitFunctional = prepared.settled.totalDebit,
        totalCreditFunctional = prepared.settled.totalCredit,
        lineCount = prepared.settled.legs.size,
        narrative = prepared.request.narrative,
        postedAt = postedAt,
        actorId = prepared.context.actorId,
    )

    private fun newJournalLines(
        prepared: Prepared,
        journalEntryId: UUID,
    ) = prepared.settled.legs.mapIndexed { index, leg ->
        NewJournalLine(
            organisationId = prepared.context.organisationId,
            journalEntryId = journalEntryId,
            lineNumber = index + 1,
            leg = leg,
            branchId = prepared.context.branchId,
            fiscalPeriodId = prepared.period.period.key.fiscalPeriodId,
            postingDate = prepared.period.dates.postingDate,
            functionalCurrencyCode = prepared.functionalCurrency,
            functionalAmount = leg.amount.amount,
            // `1` because that is the truth for these postings, not a placeholder: a leg reaches
            // here only after [PostingLegsPolicy] refused every currency but the functional one.
            exchangeRate = UNITY,
            // The module that OWNS the position, which is the requesting module except where a leg
            // says otherwise. A reversal is the one that says otherwise: accounting requests it and
            // a product module owns what it moves, so taking the requester's module here would file
            // the two halves of a reversed position under different keys of
            // `idx_journal_line_subledger` (`INV-14`, and the ERD's definition of this column).
            sourceModule = leg.subledgerModule ?: prepared.request.source.sourceModule,
            actorId = prepared.context.actorId,
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

    /**
     * Reads the functional currency again under the tenant-currency lock, with a **locking** read
     * of the organisation row, and refuses a posting whose two reads disagree.
     *
     * The locking read is what closes the window; the comparison is what states the invariant.
     * They are not the same mechanism and it is worth being exact about which does what, because
     * the shape that looks right - a plain re-read and a comparison - was measured not to work.
     *
     * The currency is read *before* the claim, because the idempotency fingerprint is computed
     * from it, and that read is necessarily unlocked: the claim is what a replay is answered from,
     * and a replay must take no tenant-wide lock. A `base_currency` change can therefore commit
     * between that read and [postNew]'s shared advisory lock. At `SERIALIZABLE`, which is what this
     * path runs at, a plain re-read cannot see it - both reads come from one snapshot and always
     * agree - so the posting would commit the tenant's very first journal in the superseded
     * currency. Measured, not assumed. The advisory lock does not rescue it either:
     * `pg_advisory_xact_lock_shared` does not participate in MVCC, so there is no `EvalPlanQual`
     * re-read to correct the second value and no `40001` to abort on; and hoisting the lock above
     * the first read leaves the re-read just as stale, because the snapshot was already fixed.
     *
     * [AccountingTenantLookup.functionalCurrencyForPosting] is a locking read of that row, and it
     * is the only shape measured to close this. A change committed after this transaction's
     * snapshot makes it raise a serialization failure, the transaction aborts - taking its claim
     * with it - and the retry fingerprints against the currency that won. That is the whole fix,
     * and it happens before the comparison below is ever reached.
     *
     * The pre-claim read stays non-locking on purpose. Making *it* the locking one would hold the
     * organisation row for the whole transaction including replays, and a replay deliberately
     * takes no locks at all - that is the design of [post].
     *
     * **The advisory lock stays, and is not made redundant by the row lock.** They close different
     * halves of the same race and neither subsumes the other. The row lock closes the *posting's*
     * read: it stops this transaction acting on a currency another transaction has already
     * replaced. The advisory lock closes the *change's* read: it makes a `base_currency` change
     * wait for in-flight first postings before it asks "has this tenant posted", which is the
     * adjacency [com.finaxis.platform.accounting.application.FunctionalCurrencyLock] calls the fix.
     * Remove it and a change would read an empty `journal_entry`, commit, and leave the tenant
     * declaring a currency its first journal was never written under - a race the row lock never
     * sees, because the two transactions never touch the same row in conflicting modes.
     *
     * The comparison, then, can no longer fire at `SERIALIZABLE`: both values come from one
     * snapshot, and a change committed after that snapshot aborts the locking read before this is
     * reached. It is kept because it states the invariant legibly at the point the invariant is
     * decided, and because it *would* fire below `SERIALIZABLE`, which [SnapshotIsolationGuard]
     * forbids this path from running at. Reachable only for a tenant's first posting in any case -
     * after that the currency is frozen and a change is refused outright.
     */
    private fun requireFunctionalCurrencyUnchanged(
        organisationId: UUID,
        readBeforeLock: String,
    ) {
        val underLock =
            tenants.functionalCurrencyForPosting(organisationId)
                ?: throw ConflictException(
                    code = PostingErrorCodes.FUNCTIONAL_CURRENCY_UNAVAILABLE,
                    safeDetail = "The organisation has no functional currency.",
                )
        if (underLock != readBeforeLock) {
            throw ConflictException(
                code = PostingErrorCodes.FUNCTIONAL_CURRENCY_CHANGED,
                safeDetail =
                    "The organisation's functional currency changed while this posting was " +
                        "being recorded; retry it.",
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
     * Takes the posting-time lock on every distinct account the legs reference, in ascending id
     * order, and returns each locked snapshot keyed by its id.
     *
     * The order is the whole design. Every transaction that locks more than one `gl_account` row -
     * only this one does - takes them in the same ascending order, so two postings that reference
     * the same accounts can never deadlock each other, and this lock is always the last one a
     * posting takes, after the fiscal-period lock. It closes the account-eligibility race from the
     * posting side: [GlAccountStore.lockForPosting] takes a shared lock that excludes, and is
     * excluded by, [GlAccountStore.lockForStateChange]'s exclusive lock, so an account cannot be
     * deactivated - or have its code, class or usage changed - between this call and the journal
     * line that follows it (`docs/adr/0023-posting-idempotency-and-account-locking.md`).
     */
    private fun lockAccounts(
        organisationId: UUID,
        legs: List<PostingLeg>,
    ): Map<UUID, GlAccount> =
        legs
            .map { it.accountId }
            .distinct()
            .sorted()
            .associateWith { accountId ->
                accounts.lockForPosting(organisationId, accountId)
                    ?: throw InvalidOperationException(
                        code = PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
                        safeDetail = "A referenced general-ledger account does not exist.",
                    )
            }

    /**
     * Refuses a correction that names a target this tenant does not hold, or whose journal has not
     * been reversed.
     *
     * `uq_posting_request_corrects` already guarantees a request is replaced at most once; this is
     * the sequencing half, that a replacement may only be posted after its target's journal was
     * actually reversed.
     *
     * Called from [post], **before** the claim - unlike every check [postNew] runs - because both
     * facts it tests are monotonic: a target either exists or it never will, and once reversed it
     * stays reversed forever (there is no un-reversal). Checking either fact can therefore never
     * turn a faithful replay into a spurious failure, which is exactly the property that makes
     * [postNew]'s other checks unsafe to run before the claim. Placement matters for a second
     * reason too: `claimPostingRequest`'s insert writes `corrects_posting_request_id`, and
     * `fk_posting_request_corrects` would otherwise reject a nonexistent target as a raw
     * constraint violation instead of this named [ResourceNotFoundException].
     */
    private fun requireValidCorrectionTarget(
        organisationId: UUID,
        correctsPostingRequestId: UUID?,
    ) {
        val targetId = correctsPostingRequestId ?: return
        journalReads.findPostingRequest(organisationId, targetId)
            ?: throw ResourceNotFoundException(
                code = PostingErrorCodes.CORRECTION_TARGET_NOT_FOUND,
                safeDetail = "The posting request named as corrected does not exist.",
            )
        val targetJournal = journalReads.findJournalEntryForRequest(organisationId, targetId)
        if (targetJournal == null ||
            journalReads.findReversalOf(organisationId, targetJournal.id) == null
        ) {
            throw ConflictException(
                code = PostingErrorCodes.CORRECTION_TARGET_NOT_REVERSED,
                safeDetail = "The posting request named as corrected has not been reversed.",
            )
        }
    }

    /**
     * The claim row, including the two lineage columns - which come from different places on
     * purpose.
     *
     * `correlation_id` is read off the caller's [AccountingContext]: it is part of what the caller
     * *asserts* about the posting, and a product module that wants to correlate a posting with work
     * it did elsewhere is entitled to say so. `request_id` is read from the ambient context
     * instead, because it is a fact about the request that happens to be in flight and nothing a
     * savings deposit knows or should be asked to supply. Putting it on `AccountingContext` would
     * oblige every product module to pass a value it does not hold, which in practice means null
     * and a lineage that is still broken.
     *
     * Null here for any posting with no request behind it - a background job, a broker listener -
     * which is why the column is nullable.
     */
    private fun newRequest(
        request: LedgerPostingRequest,
        context: AccountingContext,
        dates: AccountingDates,
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
        correctsPostingRequestId = request.correctsPostingRequestId,
        dates = dates,
        currencyCode = functionalCurrency,
        narrative = request.narrative,
        actorId = context.actorId,
        correlationId = context.correlationId,
        requestId = metadata.currentRequestId(),
    )

    /**
     * Answers a duplicate source reference from the row that already holds it.
     *
     * The row is locked, so its state cannot change underneath this decision. A posted request
     * with the same fingerprint is a retry and gets its receipt back; a different fingerprint is a
     * conflicting reuse of the identity and is refused (`INV-7`). A committed row that is still
     * `PENDING` cannot exist - the status flips in the same transaction as the journal - so it is
     * reported as a defect rather than guessed at.
     *
     * Never calls [LegProvider.legsFor] and never touches the period, the accounts or the tenant's
     * postability: none of it is re-decided for a replay, because the fingerprint - computed from
     * the caller's own inputs, never from resolved legs - is already the proof that this is the
     * same request (`docs/adr/0023-posting-idempotency-and-account-locking.md`).
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
     *
     * Money is not the only thing a line copies from its header. `journal_line` also denormalises
     * branch, fiscal period, posting date and both currency codes, and the reporting reads filter
     * and group on those columns directly rather than joining the header back in - so a line that
     * balanced but landed in the wrong period or branch would be invisible here and wrong
     * everywhere downstream. The schema cannot catch it: the foreign keys tie a line to a *valid*
     * period and branch, never to *its header's*. Verifying them costs one extra aggregate column
     * each in a statement this already runs.
     */
    private fun verifyHeaderAgainstLines(
        organisationId: UUID,
        journalEntryId: UUID,
        prepared: Prepared,
    ) {
        val settled = prepared.settled
        val lineCount = settled.legs.size
        val stored = journals.sumLines(organisationId, journalEntryId, headerDimensions(prepared))
        check(
            stored.debitFunctional.compareTo(settled.totalDebit) == 0 &&
                stored.creditFunctional.compareTo(settled.totalCredit) == 0 &&
                stored.lineCount == lineCount,
        ) {
            "journal_entry $journalEntryId header (${settled.totalDebit}/" +
                "${settled.totalCredit}/$lineCount) does not match its lines " +
                "(${stored.debitFunctional}/${stored.creditFunctional}/" +
                "${stored.lineCount}); the posting is rolled back"
        }
        val mismatched = stored.divergentLines.mismatched
        check(mismatched.isEmpty()) {
            "journal_entry $journalEntryId has lines that disagree with their header on " +
                "${mismatched.joinToString()}; the posting is rolled back"
        }
    }

    /** The dimensions the header was built from, which every line must have copied. */
    private fun headerDimensions(prepared: Prepared) =
        JournalLineDimensions(
            branchId = prepared.context.branchId,
            fiscalPeriodId = prepared.period.period.key.fiscalPeriodId,
            postingDate = prepared.period.dates.postingDate,
            currencyCode = prepared.functionalCurrency,
            functionalCurrencyCode = prepared.functionalCurrency,
        )

    private fun requireActiveTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Posting writes a journal that must commit with the caller's source mutation, so it " +
                "requires an active transaction."
        }
    }

    /** Everything validated after the claim, handed to the write step as one value. */
    private data class Prepared(
        val request: LedgerPostingRequest,
        val context: AccountingContext,
        val period: ResolvedPostingPeriod,
        val settled: SettledLegs,
        val functionalCurrency: String,
        val postingRuleVersionId: UUID?,
    )

    private companion object {
        val UNITY: BigDecimal = BigDecimal.ONE
    }
}
