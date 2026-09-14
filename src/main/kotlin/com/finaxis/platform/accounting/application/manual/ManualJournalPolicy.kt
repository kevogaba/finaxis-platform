package com.finaxis.platform.accounting.application.manual

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.ManualJournal
import com.finaxis.platform.accounting.domain.ManualJournalLine
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException

/**
 * The rules of a manual-journal draft that need nothing but the draft, per `INV-6` and `INV-1`.
 *
 * No Spring, no persistence, no permission guard: given a header and its lines, these say whether
 * the content is storable and postable at all. `ManualJournalService` keeps the rules that need a
 * collaborator - that an account exists and accepts manual posting, who the maker was, what the
 * FSM permits - and calls these for the rest.
 *
 * Kept here rather than private to the service for the reason
 * [com.finaxis.platform.accounting.domain.PostingRulePolicy] and
 * [com.finaxis.platform.accounting.application.GlAccountPostingPolicy] are: a rule about money
 * that can only be exercised through a Spring context, a Testcontainers database and a permission
 * grant is a rule whose edges do not get tested. These are exercised directly.
 */
object ManualJournalPolicy {
    /** A manual journal needs both sides, so two lines is the floor. */
    const val MINIMUM_LINES = 2

    /**
     * An adjustment a human keyed and a checker reads.
     *
     * The cap keeps creation's per-account lookups, the batch insert, and the reads that load the
     * whole set under a row lock all bounded, which the repository requires of every accounting
     * query.
     */
    const val MAXIMUM_LINES = 200

    /** `chk_manual_journal_external_reference`'s upper bound. A document number, not prose. */
    const val MAXIMUM_EXTERNAL_REFERENCE = 100

    /**
     * The amendment was prepared against the draft as it stands, not against an earlier view.
     *
     * The expected version comes from the *caller*, which is the whole point: read under the lock
     * it would always equal itself, and comparing a value to itself protects nobody. A mismatch
     * means someone else amended between the maker's read and their write, and the second write is
     * refused rather than silently replacing a header and line set it never saw.
     */
    fun requireCurrentVersion(
        journal: ManualJournal,
        expectedRowVersion: Long,
    ) {
        if (journal.rowVersion != expectedRowVersion) {
            throw staleEdit()
        }
    }

    /**
     * The one refusal every stale-edit path raises, so they cannot drift apart.
     *
     * Two call sites reach it: the comparison under the header's lock, and the store's
     * compare-and-set, which the locked caller cannot lose but a future unlocked one could. A
     * caller that reloads and retries on `MANUAL_JOURNAL_STALE` must get the same answer from
     * both, and two hand-written copies of one message is how that stops being true.
     */
    fun staleEdit(): ConflictException =
        ConflictException(
            code = PostingErrorCodes.MANUAL_JOURNAL_STALE,
            safeDetail = "The manual journal changed while it was being amended; reload and retry.",
        )

    /**
     * An external reference is absent, or it is something the column can hold and a report can
     * group on.
     *
     * Absent is the ordinary case - plenty of adjustments answer to no outside document. A blank
     * string is not: it is indistinguishable from having supplied nothing, while still occupying a
     * column someone will filter by, so it is refused rather than stored and later explained.
     * `chk_manual_journal_external_reference` enforces the same rule; this states it first, so the
     * maker gets a contract instead of a constraint name.
     */
    fun requireStorableExternalReference(reference: String?) {
        if (reference == null) {
            return
        }
        // codePointCount, not length: the constraint bounds char_length(), which counts
        // characters, so measuring UTF-16 units would refuse a reference of supplementary code
        // points - every emoji is one - that the column stores happily, and the contract the
        // maker reads would not be the one enforced.
        val characters = reference.codePointCount(0, reference.length)
        if (reference.isBlank() || characters > MAXIMUM_EXTERNAL_REFERENCE) {
            throw InvalidOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_EXTERNAL_REFERENCE_INVALID,
                safeDetail =
                    "An external reference is 1 to $MAXIMUM_EXTERNAL_REFERENCE characters and " +
                        "cannot be blank.",
            )
        }
    }

    /** A draft carries a reason and a title; neither is optional and neither may be blank. */
    fun requireNarrated(
        title: String,
        narrative: String,
    ) {
        if (narrative.isBlank() || title.isBlank()) {
            throw InvalidOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_REASON_REQUIRED,
                safeDetail = "A manual journal needs a title and a reason.",
            )
        }
    }

    /** Between [MINIMUM_LINES] and [MAXIMUM_LINES] lines, numbered `1..n` without gaps. */
    fun requireContiguous(lines: List<ManualJournalLine>) {
        val contiguous = lines.map { it.lineNumber }.toSet() == (1..lines.size).toSet()
        if (lines.size > MAXIMUM_LINES) {
            throw InvalidOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_LINES_INVALID,
                safeDetail = "A manual journal carries at most $MAXIMUM_LINES lines.",
            )
        }
        if (lines.size < MINIMUM_LINES || !contiguous) {
            throw InvalidOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_LINES_INVALID,
                safeDetail =
                    "A manual journal needs at least two lines numbered 1..n without gaps.",
            )
        }
    }

    /**
     * Every line is a settled amount before it reaches the draft table.
     *
     * `manual_journal_line.amount` is `NUMERIC(23, 6)`, so PostgreSQL would silently round a value
     * carrying more precision and approval would then accept the rounded number as valid. Settling
     * here means the maker's amount is either stored exactly or refused, never quietly changed.
     */
    fun requireSettledAmounts(lines: List<ManualJournalLine>) {
        lines.forEach { line ->
            MoneyPolicy.requireSettled(MonetaryAmount(line.amount, line.currencyCode))
        }
    }

    /** Debits equal credits. The engine proves it again at posting; a maker learns of it here. */
    fun requireBalanced(lines: List<ManualJournalLine>) {
        val debit = lines.filter { it.side == PostingSide.DEBIT }.sumOf { it.amount }
        val credit = lines.filter { it.side == PostingSide.CREDIT }.sumOf { it.amount }
        if (debit.compareTo(credit) != 0) {
            throw InvalidOperationException(
                code = PostingErrorCodes.UNBALANCED_POSTING,
                safeDetail = "Debit and credit totals must be equal.",
            )
        }
    }
}
