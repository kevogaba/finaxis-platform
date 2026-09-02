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
    val postingRuleVersionId: UUID?,
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

/** What the database says a journal's lines sum to, read back inside the posting transaction. */
data class JournalTotals(
    val debitFunctional: BigDecimal,
    val creditFunctional: BigDecimal,
    val lineCount: Int,
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

    /** Re-reads the lines of a journal and sums them, for the `INV-4` verification read. */
    fun sumLines(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalTotals

    /** Moves the request to `POSTED` and stamps `posted_at`. */
    fun markPosted(
        organisationId: UUID,
        postingRequestId: UUID,
        postedAt: Instant,
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
