package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.domain.AccountingDates
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingRequestStatus
import com.finaxis.platform.accounting.domain.PostingSide
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * A posting request before the database has given it an identity: the durable source lineage and
 * the dates the engine resolved. Never carries an `id`; the database default generates it.
 *
 * Carries no `postingRuleVersionId`. Issue #89 moved the idempotency claim ahead of leg
 * resolution, so the exact rule version - which the legs alone determine - is not yet known when
 * this is built; [JournalStore.markPosted] writes it once resolution has happened, for a request
 * this transaction actually claimed.
 */
data class NewPostingRequest(
    val organisationId: UUID,
    val branchId: UUID?,
    val sourceModule: String,
    val sourceEntityType: String,
    val sourceEntityId: UUID,
    val sourceReference: String,
    val eventCode: String,
    val fingerprint: String,
    val correctsPostingRequestId: UUID?,
    val dates: AccountingDates,
    val currencyCode: String,
    val narrative: String?,
    val actorId: UUID,
    val correlationId: String?,
    val requestId: String?,
)

/** A previously claimed request, read under `FOR UPDATE`. */
data class ExistingPostingRequest(
    val id: UUID,
    val status: PostingRequestStatus,
    val fingerprint: String,
)

/**
 * The outcome of the idempotency claim.
 *
 * [Claimed] means this transaction inserted the row and owns the posting. [Existing] means another
 * transaction already committed a row for the same source reference; it is returned locked, so the
 * caller decides between replay and conflict against a row that cannot change underneath it.
 */
sealed interface PostingRequestClaim {
    /** The row was inserted by this transaction. */
    data class Claimed(
        val postingRequestId: UUID,
    ) : PostingRequestClaim

    /** A committed row already existed for the source reference. */
    data class Existing(
        val request: ExistingPostingRequest,
    ) : PostingRequestClaim
}

/** A journal header before the database has given it an identity. */
data class NewJournalEntry(
    val organisationId: UUID,
    val branchId: UUID?,
    val postingRequestId: UUID,
    val fiscalPeriodId: UUID,
    val entryNumber: Long,
    val entryType: JournalEntryType,
    val reversesJournalEntryId: UUID?,
    val dates: AccountingDates,
    val currencyCode: String,
    val functionalCurrencyCode: String,
    val totalDebitFunctional: BigDecimal,
    val totalCreditFunctional: BigDecimal,
    val lineCount: Int,
    val narrative: String?,
    val postedAt: Instant,
    val actorId: UUID,
)

/** One journal line before insertion; the engine numbers lines from one in leg order. */
data class NewJournalLine(
    val organisationId: UUID,
    val journalEntryId: UUID,
    val lineNumber: Int,
    val leg: PostingLeg,
    val branchId: UUID?,
    val fiscalPeriodId: UUID,
    val postingDate: java.time.LocalDate,
    val functionalCurrencyCode: String,
    val functionalAmount: BigDecimal,
    val exchangeRate: BigDecimal,
    val sourceModule: String,
    val actorId: UUID,
)

/**
 * The dimensions every line of a journal copies from the header it belongs to.
 *
 * `journal_line` denormalises branch, fiscal period, posting date and both currency codes so the
 * reporting reads can filter and group on the line alone, without joining the header back in. That
 * duplication is only safe while the copies agree, and nothing in the schema enforces it: the
 * foreign keys tie a line to a *valid* branch and period, not to *its header's* branch and period.
 */
data class JournalLineDimensions(
    val branchId: UUID?,
    val fiscalPeriodId: UUID,
    val postingDate: java.time.LocalDate,
    val currencyCode: String,
    val functionalCurrencyCode: String,
)

/**
 * How many stored lines disagree with their header, one count per denormalised dimension.
 *
 * Counted per dimension rather than as a single flag so a failure can name what diverged, which is
 * the difference between a diagnosable defect and a rolled-back transaction with no explanation.
 */
data class DivergentLineCounts(
    val branch: Int,
    val fiscalPeriod: Int,
    val postingDate: Int,
    val currency: Int,
    val functionalCurrency: Int,
) {
    /** The dimensions at least one line got wrong, named for a failure message. */
    val mismatched: List<String>
        get() =
            buildList {
                if (branch > 0) add("branch")
                if (fiscalPeriod > 0) add("fiscal period")
                if (postingDate > 0) add("posting date")
                if (currency > 0) add("currency")
                if (functionalCurrency > 0) add("functional currency")
            }
}

/** What the database says a journal's lines sum to, read back inside the posting transaction. */
data class JournalTotals(
    val debitFunctional: BigDecimal,
    val creditFunctional: BigDecimal,
    val lineCount: Int,
    val divergentLines: DivergentLineCounts,
)

/** A posting request as its callers see it: the durable lineage and where it got to. */
data class PostingRequestView(
    val id: UUID,
    val organisationId: UUID,
    val branchId: UUID?,
    val sourceModule: String,
    val sourceEntityType: String,
    val sourceEntityId: UUID,
    val sourceReference: String,
    val eventCode: String,
    val status: PostingRequestStatus,
    val postingRuleVersionId: UUID?,
    val correctsPostingRequestId: UUID?,
    val postingDate: java.time.LocalDate,
    val businessDate: java.time.LocalDate,
    val narrative: String?,
    val postedAt: Instant?,
    val requestedBy: UUID?,
    val correlationId: String?,
    val requestId: String?,
)

/** A posted journal as the engine and its callers see it. */
data class JournalEntryView(
    val id: UUID,
    val organisationId: UUID,
    val branchId: UUID?,
    val postingRequestId: UUID,
    val fiscalPeriodId: UUID,
    val entryNumber: Long,
    val entryType: JournalEntryType,
    val reversesJournalEntryId: UUID?,
    val postingDate: java.time.LocalDate,
    val businessDate: java.time.LocalDate,
    val currencyCode: String,
    val functionalCurrencyCode: String,
    val totalDebitFunctional: BigDecimal,
    val lineCount: Int,
    val narrative: String?,
    val postedAt: Instant,
    val postedBy: UUID?,
)

/** A posted journal line as the engine and its callers see it. */
data class JournalLineView(
    val lineNumber: Int,
    val accountId: UUID,
    val side: PostingSide,
    val amount: BigDecimal,
    val currencyCode: String,
    val functionalAmount: BigDecimal,
    val narrative: String?,
    val subledgerReference: String?,
)

/**
 * Write port over `posting_request`, `journal_entry` and `journal_line`, plus the one read the
 * engine's replay needs.
 *
 * The only accounting code allowed to touch those generated tables is its persistence adapter, so
 * everything the engine needs is stated here in domain terms. Every method is organisation-scoped
 * by parameter rather than by ambient context, so a caller cannot forget the tenant.
 *
 * Every write requires an active transaction: a claim taken on an autocommit connection is a lock
 * released before the caller can use it.
 */
interface JournalStore {
    /**
     * Claims the source reference with `INSERT … ON CONFLICT DO NOTHING`, and when the row already
     * exists locks it `FOR UPDATE` and returns it.
     *
     * Never a bare insert: a unique violation aborts the whole transaction, including the product
     * module's own writes, so a duplicate could never be answered with the existing receipt.
     */
    fun claimPostingRequest(request: NewPostingRequest): PostingRequestClaim

    /** Inserts the header and returns its generated id. */
    fun insertJournalEntry(entry: NewJournalEntry): UUID

    /** Inserts every line in one statement. */
    fun insertJournalLines(lines: List<NewJournalLine>)

    /**
     * Re-reads the lines of a journal for the `INV-4` verification read: sums them, counts them,
     * and counts those whose denormalised dimensions disagree with [header].
     *
     * The header is passed in rather than its dimensions being read back out, because the
     * comparison is `IS DISTINCT FROM` and belongs in the same statement as the aggregates: a
     * nullable branch makes a value-by-value comparison in Kotlin get NULL wrong, and comparing
     * every line against one expected value detects lines disagreeing with *each other* as a
     * by-product. Answering it in the aggregate the read already performs costs one extra column
     * per dimension - no extra statement, no extra scan, and the same snapshot.
     */
    fun sumLines(
        organisationId: UUID,
        journalEntryId: UUID,
        header: JournalLineDimensions,
    ): JournalTotals

    /**
     * Moves the request to `POSTED`, stamps `posted_at`, and records the rule version that
     * resolved its legs.
     *
     * [postingRuleVersionId] is written here rather than at the claim, because the claim happens
     * before legs - and therefore the rule version - are resolved (issue #89). Null for a
     * reversal or a manual journal, exactly as the column has always documented.
     */
    fun markPosted(
        organisationId: UUID,
        postingRequestId: UUID,
        postedAt: Instant,
        postingRuleVersionId: UUID?,
        actorId: UUID,
    )

    /** Finds the journal a request produced, or null while it has none. */
    fun findJournalEntryForRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): JournalEntryView?
}

/**
 * Read port over the journal tables for callers that only look: lineage, reversal, and later the
 * read models. Separate from [JournalStore] so the write port stays small enough to reason about
 * and a reader can be handed nothing that writes.
 */
interface JournalReadStore {
    /** Finds a journal by id within a tenant, or null. */
    fun findJournalEntry(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalEntryView?

    /**
     * Finds the journal a request produced, or null while it has none. Declared on both ports on
     * purpose: the engine's replay and every reader need it, and one adapter method serves both.
     */
    fun findJournalEntryForRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): JournalEntryView?

    /** Finds the reversal of [journalEntryId], or null when it has not been reversed. */
    fun findReversalOf(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalEntryView?

    /** Finds one request by id within a tenant, or null. */
    fun findPostingRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): PostingRequestView?

    /** Finds the request holding a source module's durable reference, or null. */
    fun findPostingRequestBySource(
        organisationId: UUID,
        sourceModule: String,
        sourceReference: String,
    ): PostingRequestView?

    /**
     * The requests one business entity produced, newest first, from [beforeId] exclusive, at most
     * [pageSize] rows. Keyset over the time-ordered `id`, never `OFFSET` (`INV-15`).
     */
    fun listPostingRequestsForEntity(
        organisationId: UUID,
        sourceModule: String,
        sourceEntityType: String,
        sourceEntityId: UUID,
        beforeId: UUID?,
        pageSize: Int,
    ): List<PostingRequestView>

    /** The lines of one journal in line order. Bounded by construction: a journal has few lines. */
    fun findJournalLines(
        organisationId: UUID,
        journalEntryId: UUID,
    ): List<JournalLineView>
}

/**
 * Allocates the gapless, tenant-scoped journal number from `reference_sequence`.
 *
 * `UPDATE … RETURNING` under the row lock, never a PostgreSQL sequence: auditors require gapless
 * numbering and a sequence loses it on every rollback. The lock serialises journal creation per
 * tenant, which is the design envelope's accepted cost.
 */
fun interface JournalNumberAllocator {
    /** Returns the next number, or null when the tenant has no `JOURNAL` counter at all. */
    fun nextEntryNumber(organisationId: UUID): Long?
}
