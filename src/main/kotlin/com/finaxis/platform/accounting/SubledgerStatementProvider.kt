package com.finaxis.platform.accounting

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One subsidiary-ledger position a statement can be asked for: a member's savings account, a loan,
 * a share holding.
 *
 * [ownerModule] and [reference] together are exactly how `idx_journal_line_subledger` is keyed, and
 * that is not a coincidence: they are the same pair the owning module stamps on
 * `journal_line.source_module` and `journal_line.subledger_reference` when it posts. That is what
 * lets a reader move between a product statement and the general-ledger lines behind it without
 * either side knowing the other's primary keys.
 *
 * The module is part of the identity rather than derived from [kind], because a kind is what a
 * position *is* and a module is who keeps it. A tenant could run two modules answering for
 * different savings products, and `SAV-0001` would then mean two positions rather than one.
 *
 * [kind] identifies which control account the position rolls into, so a statement and a
 * reconciliation speak about the same class of ledger. A tenant has at most one control account per
 * class, which is why the class is enough for that purpose.
 */
data class SubledgerPosition(
    val organisationId: UUID,
    val ownerModule: String,
    val kind: ControlSubledgerKind,
    val reference: String,
)

/**
 * A statement request: one position, one closed date window, one page.
 *
 * The window is **mandatory and bounded**, and that is the point rather than an inconvenience.
 * `INV-15` requires every accounting-shaped query to be bounded, and a statement is the request
 * most likely to be written without a bound — *"show me everything"* is what a member asks for and
 * what a support tool offers. At the design envelope that is a scan toward a billion rows.
 *
 * [cursor] resumes a walk; [pageSize] is capped by the platform's
 * `finaxis.pagination.max-page-size` and validated by
 * [com.finaxis.platform.accounting.application.subledger.StatementWindowPolicy] before a provider
 * is called, so an implementation never has to defend itself against an unbounded page.
 */
data class SubledgerStatementQuery(
    val position: SubledgerPosition,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val branchId: UUID? = null,
    val cursor: SubledgerStatementCursor? = null,
    val pageSize: Int? = null,
)

/**
 * A keyset position in a statement: the last movement of the page just returned.
 *
 * `(posting_date, id)` and never an offset. `OFFSET` is banned by the platform's pagination
 * contract for the reason a statement makes vivid: at page 500 of a long-lived savings account,
 * PostgreSQL still reads and discards every earlier row, so the cost of a page grows with how deep
 * the reader has walked. The pair is a total order because the id is unique, which is what keeps
 * the next page exact on a day carrying many movements.
 *
 * [recordedThrough] is a **third, independent field**: a watermark fixed once when a walk's first
 * page has no cursor yet, and carried forward unchanged on every later page of the *same* walk. It
 * is not part of the keyset order and never moves the walk itself — `(postingDate, movementId)`
 * still does that. Its job is to let a later page detect that the ledger recorded something
 * *behind* what the walk has already consumed: `(posting_date, id) > cursor` can only move forward
 * through ascending posting dates, so a movement backdated onto an already-served day sorts before
 * the cursor and a keyset page alone can never reach it. See
 * [SubledgerStatementProvider.recordedBehindConsumedRange] for how that arrival is detected —
 * against **this whole cursor**, not just [postingDate], because the date alone cannot tell "behind
 * the cursor" apart from "later in the same day's rows, still ahead of [movementId]" — and
 * [com.finaxis.platform.accounting.application.subledger.SubledgerStatementAssembler] for where the
 * watermark is generated and carried.
 *
 * The type is an opaque **provider-defined string**, not a row identifier, and that distinction is
 * load-bearing. An earlier version of this field was the first page's own `uuidv7()` id, compared
 * against later rows' ids directly — plausible, because `uuidv7()` looks time-ordered, and wrong,
 * because it orders by when an id is *generated* inside its inserting transaction, not by when that
 * transaction *commits* and the row becomes visible to another session. A transaction that starts
 * before the watermark is minted and stays open past it can still generate an id that sorts before
 * the watermark, and if that transaction then backdates its row onto an already-consumed page, an
 * id comparison never catches it. [SubledgerStatementProvider.currentWatermark] and
 * [SubledgerStatementProvider.recordedBehindConsumedRange] together define what this field actually
 * holds and how it is compared; a provider's own choice of encoding only has to satisfy that
 * contract, not carry a UUID's meaning.
 *
 * The default `= ""` exists only so a provider constructing a cursor for its own
 * [SubledgerMovementPage.nextCursor] — which does not know about the watermark — compiles without
 * naming a third argument; it is a harmless placeholder there because
 * [com.finaxis.platform.accounting.application.subledger.SubledgerStatementAssembler] always
 * overwrites it with the walk's real watermark before the cursor reaches a caller. A provider must
 * never read this field for its own purposes.
 */
data class SubledgerStatementCursor(
    val postingDate: LocalDate,
    val movementId: UUID,
    val recordedThrough: String = "",
)

/**
 * One movement on a subsidiary-ledger position, in the general ledger's sign convention.
 *
 * [signedAmount] is **debits positive, credits negative** (`INV-3`), the same convention
 * [SubledgerAggregate.balance] uses, so a statement's closing balance and a reconciliation's
 * aggregate are the same number and comparing them is a subtraction rather than a rule about
 * account classes. A savings deposit of 1,000 is therefore `-1000`: it increases what the
 * institution owes the member.
 *
 * [runningBalanceSigned] is filled in by
 * [com.finaxis.platform.accounting.application.subledger.SubledgerStatementAssembler] as it walks
 * the page. A provider leaves it at zero — a running balance is a property of where a row sits in a
 * statement, not of the movement itself, and a provider that computed one per row would be issuing
 * the query per line this contract exists to forbid.
 */
data class SubledgerMovement(
    val movementId: UUID,
    val postingDate: LocalDate,
    val signedAmount: BigDecimal,
    val currencyCode: String,
    val narrative: String?,
    val branchId: UUID? = null,
    val journalEntryId: UUID? = null,
    val runningBalanceSigned: BigDecimal = BigDecimal.ZERO,
)

/**
 * One page of movements as a provider returns them, oldest first.
 *
 * Ascending, which is the deliberate exception to the platform's `(posting_date DESC, id DESC)`
 * default: a running balance only means anything read forward from an opening balance, so a page
 * arriving newest-first could not carry one. The same index serves both directions, and descending
 * stays the default everywhere a running balance is not being carried.
 *
 * [nextCursor] is null on the last page, so a caller looping until null terminates rather than
 * asking forever.
 */
data class SubledgerMovementPage(
    val movements: List<SubledgerMovement>,
    val nextCursor: SubledgerStatementCursor?,
)

/**
 * Accounting-owned port a product module implements so its positions can be stated (`INV-15`).
 *
 * The sibling of [SubledgerProofProvider]: that one answers *"what did your ledger total"* for a
 * reconciliation, this one answers *"what happened to this position, and what did it start from"*
 * for a statement. Both are read-only, both are scoped by parameter rather than by ambient context,
 * and neither reaches into the other module's persistence.
 *
 * ## What the implementation owes
 *
 * **An opening balance that reads a bounded number of rows.** [openingBalanceAt] is asked for the
 * close of the day before a window starts, which for a member's account of ten years' standing is a
 * question about ten years of movements.
 *
 * What "bounded" means here is worth stating precisely, because the general ledger's answer and a
 * sub-ledger's are bounded by different things. An account's balance is bounded by the *tenant's*
 * history — every line every member ever posted to it — which is why `gl_account_daily_balance`
 * exists. A **position's** balance is bounded by that position's own history, and a savings account
 * with fifty movements a year holds five hundred rows after a decade. Summing those is one index
 * range scan and entirely reasonable.
 *
 * So the obligation is the bound, not the mechanism: an opening balance must not grow with the
 * tenant's ledger. A module whose positions stay small may sum from inception and be done. A module
 * with high-velocity positions — a teller's cash drawer, a pooled suspense position — needs a
 * checkpoint it maintains, plus the movements after it, which is the shape
 * `gl_account_daily_balance` demonstrates for the general ledger. Choosing the second before it is
 * needed is a maintenance obligation bought for nothing; choosing the first after it is needed is
 * how a statement endpoint becomes a timeout.
 *
 * **A checkpoint, where one is used, taken where no unrecorded movement can reach it.** This is the
 * part that is easy to get subtly wrong, and
 * [com.finaxis.platform.accounting.application.balances.DailyBalanceReader] documents the general
 * ledger's version at length. A position's checkpoint is only safe for dates strictly before the
 * earliest date any movement recorded *since the checkpoint was built* is dated at. A movement
 * backdated onto a day the checkpoint already covers is otherwise invisible to the checkpoint and
 * to the delta alike, and the statement omits it silently.
 *
 * **Movements that include reversals and corrections rather than hiding them.** A reversal is an
 * ordinary movement of opposite sign; it nets out of the closing balance by arithmetic. A provider
 * that filtered reversed movements out would show a member a history their money did not have, and
 * would disagree with the control account by exactly the amount it hid.
 *
 * **Ordering that is total and stable.** `(posting_date, id)` ascending, so a page boundary cannot
 * repeat or drop a movement on a day carrying many.
 *
 * ## What accounting owes in return
 *
 * The window and page bounds are validated before a provider is called, the running balance is
 * computed once per page by the assembler, and the statement's shape is fixed here so that every
 * product module's statement reads the same way. A module supplies four reads - an opening balance,
 * a page of movements, a watermark and a staleness check - and gets a statement.
 */
interface SubledgerStatementProvider {
    /** A stable name recorded against statements this provider answers, e.g. `savings`. */
    val providerName: String

    /** Whether this provider owns the subsidiary ledger for [kind]. Pure, cheap and constant. */
    fun supports(kind: ControlSubledgerKind): Boolean

    /**
     * The position's signed balance at the close of [asOfDate], or zero when it had none.
     *
     * Asked once per statement, for the day before the window opens. See the class KDoc for the two
     * obligations this carries: a checkpoint plus a bounded delta, and a checkpoint taken where no
     * unrecorded movement can reach it.
     */
    fun openingBalanceAt(
        position: SubledgerPosition,
        branchId: UUID?,
        asOfDate: LocalDate,
    ): BigDecimal

    /**
     * One page of the position's movements inside the query's window, oldest first.
     *
     * The page size is already bounded when this is called, so an implementation applies it as a
     * `LIMIT` rather than re-validating it.
     */
    fun movements(query: SubledgerStatementQuery): SubledgerMovementPage

    /**
     * An opaque token standing for everything this provider's current transaction can see, right
     * now — the walk's watermark, minted once.
     *
     * Asked **once, on a walk's first page**, before [movements] is called for it, exactly the way
     * [recordedBehindConsumedRange] is asked before [movements] on every later page. The assembler
     * carries the returned token forward unchanged, on every subsequent page's
     * [SubledgerStatementCursor.recordedThrough], for [recordedBehindConsumedRange] to compare
     * against.
     *
     * This method and [movements]' own first-page read must run inside the **same transaction**,
     * at `REPEATABLE_READ` isolation or stronger, for the same reason given on
     * [recordedBehindConsumedRange]: PostgreSQL fixes a `REPEATABLE READ` transaction's snapshot at
     * its first statement and holds it for the transaction's whole life, so calling both from
     * inside one transaction is what makes the token mean *exactly* "what the first page's own
     * movements read saw" — no more, no less. A token minted from a snapshot the first page's own
     * read does not share would be wrong in either direction: an earlier snapshot would later flag
     * movements the first page legitimately served, and a later one would let through exactly the
     * omission this mechanism exists to catch.
     *
     * An implementation may enforce the isolation requirement itself — as this module's own
     * general-ledger provider does, with `SnapshotIsolationGuard` — rather than trust every caller
     * to remember it. On PostgreSQL, `pg_current_snapshot()::text` is exactly this token:
     * printable, storable, and comparable against a row's own visibility later through
     * `pg_visible_in_snapshot`, without requiring the minting transaction to still be open —
     * unlike `pg_export_snapshot()`, whose export is only adoptable by another session while the
     * exporting transaction remains open, which a multi-page walk spanning several requests cannot
     * promise.
     */
    fun currentWatermark(): String

    /**
     * Whether the ledger holds a movement, at or before [cursor]'s own position in the keyset
     * order, that was not yet visible when [cursor]'s watermark was minted and can therefore never
     * be reached by a later page.
     *
     * Asked **once per page after the first**, before [movements] is called for it — the assembler
     * calls this and refuses the page rather than serving it if it returns `true`. That ordering
     * exists because `(posting_date, id) > cursor` — the same row-value comparison [movements]
     * itself uses — is the only thing that can move a keyset walk forward: a movement whose own
     * `(posting_date, id)` sorts at or before [cursor] can never satisfy that comparison on any
     * later page, no matter how that page's predicate is written. Detecting that a movement in
     * exactly that position arrived after the walk began is therefore the only way to avoid
     * silently serving a statement that is short by exactly that movement — the defect
     * [SubledgerStatementCursor.recordedThrough] and this method exist to close.
     *
     * [cursor] is taken **whole, not as a bare posting date**, because the date alone cannot tell
     * "behind the cursor" apart from "later in the cursor's own day, still ahead of it". A busy
     * position posts several movements a day; an ordinary one recorded on the cursor's day with an
     * id greater than [SubledgerStatementCursor.movementId] is exactly what the very next
     * [movements] call is *for* — forward progress, not a defect — and a check that compared dates
     * alone would refuse it as if it were the backdated case this method exists to catch. The exact
     * test is the row value `(posting_date, id) <= (cursor.postingDate, cursor.movementId)`: the
     * same comparison [movements]' own keyset predicate makes, simply inverted.
     *
     * [SubledgerStatementCursor.recordedThrough] on [cursor] is [currentWatermark]'s token from the
     * walk's first page: "recorded after the watermark" means **not visible under that token**,
     * checked against **commit visibility**, never against id order. An earlier version of this
     * method compared `id > watermark` directly, reasoning that `uuidv7()` ids are time-ordered so
     * generation order stands in for commit order. It does not: `uuidv7()` orders by when
     * `DEFAULT uuidv7()` ran inside the inserting transaction, not by when that transaction
     * committed and the row became visible to another session. A transaction that starts before
     * the watermark is minted and stays open past it generates an id that still sorts before the
     * watermark, and if it then backdates its row onto an already-consumed page, an id comparison
     * never catches it — the same silent omission this method exists to close, reopened by the gap
     * between an id being assigned and its insert becoming visible. On PostgreSQL,
     * `pg_visible_in_snapshot` answers the right question directly, from a row's `xmin` and the
     * stored token, with no such gap: a transaction's *commit* is what changes what a later
     * snapshot can see, regardless of when that transaction happened to acquire its id.
     *
     * This method must run inside the **same transaction** [movements] is about to be asked from,
     * at `REPEATABLE_READ` isolation or stronger — not merely at the same moment, one that shares
     * one snapshot with it. Two independent `READ COMMITTED` reads each take their own snapshot,
     * and a movement that commits between them is neither caught as stale nor included in the
     * page: it is simply invisible to whichever read ran first. See
     * `SubledgerStatementAssembler.statementOf` for where the two calls are made and the
     * obligation this places on a caller, and `ManualJournalService.get` for the identical
     * obligation elsewhere in this module. An
     * implementation may enforce this itself — as this module's own general-ledger provider does,
     * with `SnapshotIsolationGuard` — rather than trust every caller to remember it.
     *
     * The answer is bounded the same way [movements] and [openingBalanceAt] are: a range read over
     * this position's own history, never the tenant's.
     */
    fun recordedBehindConsumedRange(
        position: SubledgerPosition,
        branchId: UUID?,
        cursor: SubledgerStatementCursor,
    ): Boolean
}
