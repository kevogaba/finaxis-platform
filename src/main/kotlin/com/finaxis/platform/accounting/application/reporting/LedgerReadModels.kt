package com.finaxis.platform.accounting.application.reporting

import com.finaxis.platform.accounting.domain.AccountClass
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One account's line in a trial balance.
 *
 * Carries **both** the signed balance and the presentation pair, because the two answer different
 * questions and deriving one from the other at the call site is where a sign convention gets lost.
 * [closingSigned] is the ledger's own convention — debits positive, credits negative (`INV-3`)
 * — and is what arithmetic is done in. [closingDebit] and [closingCredit] are that same number
 * split for a report column, at most one of them non-zero.
 */
data class TrialBalanceLine(
    val accountId: UUID,
    val accountCode: String,
    val accountName: String,
    val accountClass: AccountClass,
    val openingSigned: BigDecimal,
    val debitMovement: BigDecimal,
    val creditMovement: BigDecimal,
    val closingSigned: BigDecimal,
) {
    /**
     * The closing balance in the debit column, or zero when it closes on the credit side.
     */
    val closingDebit: BigDecimal
        get() = if (closingSigned.signum() > 0) closingSigned else BigDecimal.ZERO

    /**
     * The closing balance in the credit column, or zero when it closes on the debit side.
     */
    val closingCredit: BigDecimal
        get() = if (closingSigned.signum() < 0) closingSigned.negate() else BigDecimal.ZERO

    /**
     * True when the account brought nothing in, moved nothing and carries nothing out.
     *
     * Such a line contributes to neither total, so printing it is noise — and it does occur: an
     * account whose postings net to zero over its whole life holds projected rows that sum to a
     * zero opening. Dropping it cannot unbalance the report, because a line that adds zero to both
     * columns is the only kind that can safely be dropped.
     */
    val isDormant: Boolean
        get() =
            openingSigned.signum() == 0 &&
                debitMovement.signum() == 0 &&
                creditMovement.signum() == 0
}

/**
 * A trial balance for one scope, with the totals that prove it balanced.
 *
 * The proof is part of the value rather than the caller's obligation: a trial balance whose columns
 * disagree is not a report with a caveat, it is evidence that something upstream is wrong, and
 * [LedgerReportingService] refuses to return one.
 *
 * [fiscalPeriodId] is set only when the caller named a period rather than dates, so a consumer can
 * tell a statutory period report from an arbitrary window that happens to share its boundaries.
 */
data class TrialBalance(
    val organisationId: UUID,
    val branchId: UUID?,
    val fiscalPeriodId: UUID?,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val lines: List<TrialBalanceLine>,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
) {
    /** True when the two columns agree, which `INV-4` makes true of any sound ledger. */
    val balanced: Boolean
        get() = totalDebit.compareTo(totalCredit) == 0
}

/**
 * One movement on a general-ledger account, with the running balance after it.
 *
 * [runningBalanceSigned] is filled in by the service as it walks the page; the port that reads the
 * rows leaves it at zero, because a running balance is a property of where the row sits in a
 * statement rather than of the journal line itself.
 */
data class LedgerMovement(
    val lineId: UUID,
    val journalEntryId: UUID,
    val entryNumber: Long,
    val entryType: String,
    val postingDate: LocalDate,
    val branchId: UUID?,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val narrative: String?,
    val sourceModule: String,
    val subledgerReference: String?,
    val runningBalanceSigned: BigDecimal = BigDecimal.ZERO,
)

/**
 * One page of an account's ledger: the balance it starts from, the movements, and the balance it
 * ends at.
 *
 * [nextCursor] is null on the last page, so a caller looping until null terminates rather than
 * asking forever. [closingSigned] is what the next page is resumed with, which is why a caller
 * walking forward never needs a second as-of read.
 */
data class AccountLedgerPage(
    val accountId: UUID,
    val branchId: UUID?,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val openingSigned: BigDecimal,
    val movements: List<LedgerMovement>,
    val closingSigned: BigDecimal,
    val nextCursor: LedgerCursor?,
)

/**
 * A keyset position in an account's ledger: the last row of the page just returned.
 *
 * `(posting_date, id)` and not an offset, because `OFFSET` is banned by the pagination contract —
 * at page 5,000 of a statement PostgreSQL still reads and discards everything before it. The pair
 * is a total order because `id` is unique, which is what makes the next page's predicate exact on a
 * day carrying many postings.
 */
data class LedgerCursor(
    val postingDate: LocalDate,
    val lineId: UUID,
)

/**
 * One node of the chart, with its own movement and the movement of the subtree beneath it.
 *
 * [ownSigned] is what posted directly to this account, and is zero for a `HEADER`, which nothing
 * may post to. [subtreeSigned] includes every descendant within the requested root. Keeping both is
 * what stops a roll-up double-counting: a caller that sums [subtreeSigned] over a parent **and**
 * its children counts the children twice, and naming the two differently is the cheapest way to
 * make that mistake visible.
 */
data class AccountRollupNode(
    val accountId: UUID,
    val accountCode: String,
    val accountName: String,
    val accountClass: AccountClass,
    val parentAccountId: UUID?,
    val depth: Int,
    val ownSigned: BigDecimal,
    val subtreeSigned: BigDecimal,
)

/**
 * A journal as a drill-down shows it: the header, its lines, the source that produced it, and both
 * halves of its reversal linkage.
 *
 * [reversesJournalEntryId] and [reversedByJournalEntryId] are the linkage issue #49 asks a
 * drill-down to expose. Neither is a filter: a reversed journal and its reversal both stay in every
 * balance, and the pair exists so a reader can see *why* two equal and opposite journals sit next
 * to each other, not so a report can hide one of them.
 */
data class JournalDetail(
    val journalEntryId: UUID,
    val entryNumber: Long,
    val entryType: String,
    val postingDate: LocalDate,
    val businessDate: LocalDate,
    val branchId: UUID?,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val reversesJournalEntryId: UUID?,
    val reversedByJournalEntryId: UUID?,
    val sourceModule: String,
    val sourceEntityType: String,
    val sourceEntityId: UUID,
    val sourceReference: String,
    val lines: List<JournalDetailLine>,
)

/** One line of a journal as a drill-down shows it. */
data class JournalDetailLine(
    val lineId: UUID,
    val lineNumber: Int,
    val accountId: UUID,
    val accountCode: String,
    val branchId: UUID?,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val narrative: String?,
    val sourceModule: String,
    val subledgerReference: String?,
)
