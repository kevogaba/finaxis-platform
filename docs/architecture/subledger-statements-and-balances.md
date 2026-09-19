# Subsidiary-Ledger Statements and Balances

The contract every product-owned subsidiary ledger implements so that statements stay fast as
ledgers grow, and so that a member's statement and the control account it rolls into cannot
disagree.

Issue #50. Companion to [the accounting foundation](accounting-foundation.md), which holds the
invariants, and to [the accounting schema](../database/accounting-erd.md), which holds the general
ledger's own version of these reads.

No product sub-ledger exists yet. This document, the port it describes and the conformance suite
that enforces it exist **before** the first one, which is the point: the alternative is three
product modules each inventing a balance-history model, and a reconciliation team discovering the
differences one break at a time.

## What accounting owns and what a product module owns

| | Owns |
| --- | --- |
| **Product module** | Its ledger rows, its positions, its projections, its transaction boundaries, and the two reads below |
| **Accounting** | The shape of a statement, the bounds a request is held to, the running-balance computation, and the conformance suite |

A product module implements
[`SubledgerStatementProvider`](../../src/main/kotlin/com/finaxis/platform/accounting/SubledgerStatementProvider.kt)
and gets a statement. It never touches accounting's persistence and accounting never touches its.
This mirrors `SubledgerProofProvider`, which answers *"what did your ledger total"* for a
reconciliation; this one answers *"what happened to this position"* for a statement.

## The five balances, named once

Ambiguity here is expensive, because the same English word means different numbers to a developer
and an auditor. These are the definitions the port and the suite use.

| Term | Definition |
| --- | --- |
| **Opening balance** | The position's balance at the **close of the day before** the window opens |
| **Movement** | One signed change to the position, dated by its `posting_date` |
| **Running balance** | Opening plus every movement up to and including this one, in statement order |
| **Closing balance** | Opening plus every movement in the window — equivalently, the as-of balance at the window's end date |
| **Current balance** | The as-of balance at the tenant's current business date |

All of them are **signed in the general ledger's convention**: debits positive, credits negative
(`INV-3`). A savings deposit of 1,000 is `-1000`, because it increases what the institution owes the
member. This is not a presentation choice — it is what makes a statement's closing balance and a
reconciliation's aggregate the same number, so comparing them is a subtraction rather than a rule
about which account classes invert.

A statement that presents debit and credit columns derives them from the sign at the edge, the way
[`TrialBalanceLine`](../../src/main/kotlin/com/finaxis/platform/accounting/application/reporting/LedgerReadModels.kt)
does. Deriving the sign from the columns is how a convention gets lost.

## The obligations

### 1. An opening balance reads a bounded number of rows

This is the rule the whole contract exists for, and the one worth stating precisely, because the
general ledger and a sub-ledger are bounded by different things.

An **account's** balance is bounded by the *tenant's* history — every line every member ever posted
to it. At the design envelope that is a scan toward a billion rows, which is why
`gl_account_daily_balance` exists.

A **position's** balance is bounded by *that position's* history. A savings account with fifty
movements a year holds five hundred rows after a decade, and summing those is one index range scan.

So the obligation is the bound, not the mechanism:

> **An opening balance must not grow with the tenant's ledger.**

A module whose positions stay small may sum from inception and be done. A module with high-velocity
positions — a teller's cash drawer, a pooled suspense position — needs a checkpoint it maintains,
plus the movements after it. Choosing the checkpoint before it is needed buys a maintenance
obligation for nothing; choosing it after is how a statement endpoint becomes a timeout.

### 2. A checkpoint is taken where no unrecorded movement can reach it

Where a module does keep a checkpoint, this is the part that is easy to get subtly wrong, and the
general ledger got it wrong first — see
[`DailyBalanceReader`](../../src/main/kotlin/com/finaxis/platform/accounting/application/balances/DailyBalanceReader.kt)
for the long version.

A movement carries two dates: the date it is **dated at** and the date it was **recorded**. A
checkpoint covers the movements recorded up to the point it was built. A movement recorded *after*
it may be **backdated** to a date the checkpoint already covers — and then:

- the checkpoint does not contain it, because it was built before the movement existed;
- a delta bounded by *"dates after the checkpoint"* does not contain it either.

Both halves miss it. The statement omits a movement the ledger holds, silently, and because the
control account is computed from the ledger rather than the checkpoint, the difference surfaces as a
reconciliation break attributed to whichever side is looked at second.

The safe checkpoint is therefore the latest date **no unrecorded movement can reach**: strictly
before the earliest date any movement recorded since the checkpoint was built is dated at. The
general ledger computes this as two O(1) index probes. How far it retreats is bounded by how far
back a movement may be dated at all, which for accounting is bounded by the open fiscal periods.

`a movement backdated behind a checkpoint still appears` in the conformance suite is this case.

### 3. Reversals and corrections are movements, never filters

A reversal is an ordinary movement of opposite sign. It nets out of the closing balance by
arithmetic, and both halves stay visible on the statement.

A provider that filtered reversed movements out would show a member a history their money did not
have, and would disagree with the control account by exactly the amount it hid. `INV-6` makes this
true of the general ledger; the same reasoning applies one ledger down.

`a reversal nets out rather than being hidden` in the conformance suite is this case.

### 4. Ordering is total, stable, and ascending

`(posting_date, id)` ascending.

**Total**, because `id` is unique — so a page boundary on a day carrying many movements cannot
repeat or drop one.

**Ascending**, which is the deliberate exception to the platform's `(posting_date DESC, id DESC)`
default. A running balance only means anything read forward from an opening balance, so a page
arriving newest-first could not carry one. The same index serves both directions, and descending
stays the default everywhere no running balance is carried.

### 5. Pagination is keyset, never `OFFSET`

`OFFSET` is banned by the platform's pagination contract, and a statement makes the reason vivid: at
page 500 of a long-lived account, PostgreSQL still reads and discards every earlier row, so the cost
of a page grows with how deep the reader has walked.

A cursor is `(posting_date, id)` and is compared as a **row value** — `(posting_date, id) > (?, ?)`
— rather than as the expanded `date > d OR (date = d AND id > i)`. The expanded form is what
silently drops or repeats rows when a reader gets one of the branches wrong, and PostgreSQL turns
the row value into the same index range either way.

### 6. The running balance is computed once per page, never per row

Opening plus the movements so far, accumulated in one pass.

This is the rule an implementation gets wrong under deadline, and the failure is invisible in a
small test: a per-row balance query returns the same numbers and costs one round trip per line, so
it is correct on ten movements and unusable on ten thousand.

A module does not write this loop.
[`SubledgerStatementAssembler`](../../src/main/kotlin/com/finaxis/platform/accounting/application/subledger/SubledgerStatements.kt)
does it, and takes a carried balance so that walking a long window never needs a second as-of read:
each page opens where the previous one closed.

### 7. The window is mandatory and bounded

`StatementWindowPolicy.MAXIMUM_WINDOW_DAYS` is 366 — a full financial year and a comparative annual
statement, and the point past which a request stops being a statement and becomes an export.

`INV-15` is not a style rule here. A statement is the request most likely to be written without a
bound: *"show me everything"* is what a member asks for and what a support tool offers.

Bounds are validated **before** a provider is called, so an implementation never has to defend
itself against an unbounded page.

## Consistency and atomicity

Where a module keeps a current-balance projection that authorises or settles anything — an available
balance a withdrawal is checked against, a loan outstanding a payoff is quoted from — that
projection commits **in the same transaction** as the ledger entry and the general-ledger posting.
This is `INV-12` and
[the financial-transaction atomicity invariant](financial-transaction-atomicity.md): a projection
that can lag the ledger it authorises against is a projection that authorises a withdrawal the
ledger will refuse.

A projection used only for **reporting** may be asynchronous, on two conditions that are not
negotiable: its staleness is documented, and it is never read on an authorisation or settlement
path. `gl_account_daily_balance` is the worked example — built on the business-date advance, never
authoritative, rebuildable from the journal, and when the two disagree the journal wins.

Every projection is **rebuildable from the immutable ledger entries** by a documented query that
ships with it (`INV-13`). A projection whose rebuild query does not exist is a second ledger.

## Retention and archival

Archived history must never break a statutory statement. Two consequences:

- A position's **checkpoint survives the archival of the movements behind it**, or the opening
  balance becomes unrecoverable once the movements are gone. A checkpoint is the cheapest form of
  retention there is — one row per position per period against thousands of movements.
- An archived window is **stated as archived**, not as empty. A statement that silently returns no
  movements for a period whose data has been moved is indistinguishable from a period in which
  nothing happened, and the two have opposite meanings to an auditor.

## The conformance suite

[`SubledgerStatementContract`](../../src/test/kotlin/com/finaxis/platform/accounting/subledger/SubledgerStatementContract.kt)
is an abstract test class. A product module extends it, points it at its own provider and its own
writes, and inherits every obligation above as an executable assertion.

| Test | Obligation |
| --- | --- |
| `an opening balance is everything dated on or before the day the window opens` | 1 — and the definition of "opening" |
| `a statement's arithmetic holds and its closing balance is the balance at the window's end` | 6, and the closing/as-of equivalence |
| `a running balance is carried across pages and no movement repeats or is dropped` | 4, 5, 6 |
| `a movement backdated behind a checkpoint still appears` | 2 |
| `a reversal nets out rather than being hidden` | 3 |

A contract stated only in a document is one each module interprets slightly differently, and the
differences surface as a member's statement disagreeing with a control account. This one is run.

### It is run against a real implementation

Accounting ships
[`JooqGeneralLedgerPositionStatements`](../../src/main/kotlin/com/finaxis/platform/accounting/adapter/outbound/persistence/JooqGeneralLedgerPositionStatements.kt),
the **general ledger's own** view of a position, and `GeneralLedgerStatementContractTests` runs the
suite against it. That is not accounting owning a sub-ledger — it does not. It exists because:

- **A reconciliation break needs it.** When a control account and a sub-ledger disagree, the
  question is *which position*, then *which movement*. This answers both from the journal, which is
  the side accounting can speak for. The two statements are meant to be **compared, never
  substituted**; a product module implementing its provider by delegating here would be proving the
  ledger against itself.
- **A contract with no implementation is a document.** Running the suite against a real one, on
  PostgreSQL, is what makes the obligations demonstrated rather than asserted.

Both of its reads are range scans of `idx_journal_line_subledger`, whose key is
`(organisation_id, source_module, subledger_reference, posting_date, id)`.

## Performance targets

Measured by `AccountingQueryPlanTests` against the fixture
[the foundation](accounting-foundation.md#explain-validation) fixes, and asserted rather than
described.

| Pattern | Shape | Budget |
| --- | --- | --- |
| Q2 | One position's opening balance, no lower date bound | 900 |
| Q6 | One position's movements inside a window, one page | 500 |
| Q7 | A keyset page deep in history | 200 |

A pull request that raises a budget says why in its body, and the number here changes in the same
commit as the number in the test.

## The template a product sub-ledger issue follows

A future savings, share or loan issue states these, in this order. Anything it cannot answer is a
design question it has not finished.

1. **The position.** What is one position, and what is its stable reference? That string goes on
   `journal_line.subledger_reference`, and the module's own name goes on `source_module`.
2. **The control class.** Which `ControlSubledgerKind` do its positions roll into, and therefore
   which control account?
3. **The movements.** What is one movement, what makes it immutable, and how does a correction
   happen — a reversing movement, never an update.
4. **The opening-balance strategy.** Sum from inception, or a checkpoint? State the expected
   movements per position per year and which of the two that implies. If a checkpoint: how is it
   built, and where is it taken so that obligation 2 holds?
5. **The authorisation projection, if any.** What does it authorise, and what proves it commits with
   the ledger entry and the GL posting?
6. **The rebuild query.** The documented statement that recomputes every projection from the
   immutable entries.
7. **The conformance test.** The class extending `SubledgerStatementContract`.
8. **The index.** The one serving the statement's two reads, with its `EXPLAIN` plan and a budget
   added to the table above.
9. **Retention.** What is archived, when, and what keeps a statutory statement answerable after it.

## Consuming issues

| Issue | Consumes |
| --- | --- |
| #51 | The balance definitions, for financial statements |
| #52 | The keyset cursor's web envelope, once accounting has controllers |
| Future product sub-ledgers | All of it |
