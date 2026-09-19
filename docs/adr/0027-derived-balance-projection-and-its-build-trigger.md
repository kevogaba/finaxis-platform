# ADR 0027: The Derived Balance Projection, Its Build Trigger, And Late Arrivals

## Status

Accepted

Date: 2026-09-18

Implements the *"Rollups And Projections"* section of
[the accounting foundation](../architecture/accounting-foundation.md) and `INV-13`. Does not amend
[ADR 0020](0020-immutable-ledger-and-reversal-only-correction.md): the journal stays the sole
system of record and this record adds nothing that could compete with it. Depends on
[ADR 0022](0022-accounting-date-and-fiscal-period-concurrency.md) for the date semantics that
decide the build's trigger, and on
[ADR 0018](0018-financial-transaction-atomicity-invariant.md) for why the build is not in the
posting transaction.

## Context

The accounting foundation approves exactly one projection and states the arithmetic that justifies
it. A monthly trial balance scans roughly 16 million journal lines, which is survivable for a
report. An as-of balance computed by summing an account's lines from inception scans a table
heading for 1.4 billion rows over the seven-year retention, which is not survivable at any latency
a user will wait for. That second gap, and only that gap, is what `gl_account_daily_balance`
exists to close.

Three questions the foundation left open have to be answered before the table can be built, and
each of them is the kind of decision that is expensive to reverse once rows exist:

1. Does a row carry only the day's movement, or a balance carried forward?
2. What triggers the build?
3. What happens when a journal line arrives for a day that has already been projected?

The third is not hypothetical here. `chk_journal_entry_dates` permits
`posting_date <= business_date`, `journal.post_prior_period` exists precisely to authorise a
backdated posting, and
[accounting dates and periods](../architecture/accounting-dates-and-periods.md) records that such
a posting is legal **while close-of-business is running** and deliberately takes no lock on
`business_date`, because close-of-business must not deadlock corrections. Late arrival is a
designed-in behaviour of this ledger, not an edge case.

## Decision

### The row carries a balance carried forward, not only the day's movement

`gl_account_daily_balance` stores, per account, branch, functional currency and posting date that
had movement: the day's debit and credit totals, an `opening_signed_functional` equal to the key's
previous row's closing, and a generated `closing_signed_functional`. An as-of balance is the latest
row with `posting_date <= D` — one backward index range scan stopping at the first row.

The alternative was seriously considered and is worth recording, because it is the simpler design.
A **movement-only** row is invalidated by nothing that happens after it, so a late arrival touches
exactly one row and no other. Its as-of read sums a key's rows up to `D`, which at the design
envelope is around 250 rows a year and ~1,750 over retention — still a thousandfold improvement
over scanning lines, and cheap.

It is rejected for two reasons that are commitments rather than preferences. The schema document
already promises that this table answers an as-of balance *"from one row read per key"*. And
issue #50 builds the sub-ledger statement contract on *"the closest trusted checkpoint plus bounded
deltas"*, which is a checkpoint carrying a cumulative value or it is not a checkpoint at all. A
movement-only projection would force every future product module to re-derive its own cumulative
layer, which is the duplication #50 exists to prevent.

The cost is accepted with its name stated: **a carried-forward balance makes every later row of a
key depend on every earlier one**, so a line arriving on an already-built day invalidates that
key's whole tail. The next two decisions are how that cost is paid.

### The build is triggered by the business date advancing, not by close-of-business completing

The obvious trigger is `CobCompleted`, and it is wrong.

`journal_entry` carries two dates that matter here. `posting_date` is the day the amounts belong
to, and it is the projection's grain. `business_date` is the day the posting was **recorded**. A
build that enumerates journals by `business_date = B` therefore sees every posting made on day `B`
including the backdated ones — which is exactly the set a projection needs, because a backdated
posting is invisible to any enumeration keyed on `posting_date`.

The question is when that set is *closed*. It is not closed at `completeCob`: the date rules allow
a backdated posting while the business date is `CLOSED`, so a journal can still be recorded with
`business_date = B` after close-of-business finished. It is closed once the tenant's business date
has moved off `B`, because from that instant every new posting records a later business date.

So the build is driven by **`BusinessDateService.advance`**.

### Lifecycle owns the trigger; accounting owns the work

The obvious implementation of that trigger is the repository's reference event pattern — externalise
`BusinessDateAdvanced` through the outbox, consume it with a thin `@RabbitListener` inside
accounting, hand off to a JobRunr job. **Accounting may not do any of that**, and the rule saying so
is not incidental:

```
`accounting does not depend on broker or background job infrastructure`
  noClasses().that().resideInAPackage("com.finaxis.platform.accounting..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("org.springframework.amqp..", "io.namastack.outbox..", "org.jobrunr..")
```

`AccountingBoundaryRuleTests` enforces it package-wide, and
[the module boundary](../architecture/accounting-module-boundary.md) states it as rule 4. There is
no relocation that escapes it either: a listener hosted in another module would then have to write
`gl_account_daily_balance`, and the sibling rule *"only accounting persistence adapters may touch
generated accounting jOOQ tables"* forbids that.

The rule is **kept as it stands, not narrowed**, and the trigger is arranged around it:

```
BusinessDateService.advance (lifecycle)
  → enqueues BuildDailyBalanceJobRequest in the same transaction as the advance
  → JobRunr runs it after commit, outside the business-date row lock
  → calls AccountingDayRollover.settleDay(organisationId, businessDate)   [accounting port]
  → DailyBalanceProjectionService (accounting) → its persistence adapter → the projection
```

Every hop is already permitted. Lifecycle's `@ApplicationModule` allows both `accounting` and
`common::jobs`; `AccountingDayRollover` joins `AccountingBusinessDateLookup`,
`AccountingTenantLookup` and `SubledgerProofProvider` on the cross-module port surface accounting's
own package KDoc already describes; and the table is written only by an accounting persistence
adapter. **Phase E therefore changes no architecture rule and no `allowedDependencies` entry.** An
earlier revision of this record said accounting would gain `common::jobs` and its first inbound
messaging adapter; that was wrong, and would have failed `qualityGate` on the first pull request.

The job is scheduled **after** the advance's transaction commits, and the ordering was chosen
rather than inherited. JobRunr's storage provider takes its own connection, so an `enqueue` called
inside a Spring transaction is not rolled back with it — an inline call would therefore leave a
build scheduled for a business date the tenant may never have left, and the build's central claim,
that the set of journals recorded on that date is closed, would be false for exactly that run.

Registering an after-commit synchronisation inverts which failure is possible, and the one it
leaves is already covered: a process that dies between the commit and the callback loses one day's
enqueue, and the next build's trailing business-date re-scan picks that day up. There is no
equivalent safety net in the other direction.

Running after the commit also matters for a second reason: `advance` holds the `business_date` row
lock that every current-dated posting queues behind, and a rebuild inside that transaction would
stall the tenant's whole posting path for as long as the rebuild took.

**The build is never synchronous in the posting path.** `INV-12` fixes what must be inside the
posting transaction; a projection is explicitly outside it. Writing the projection there would put
a per-account write hotspot on every posting, which is the precise reason
[the schema](../database/accounting-erd.md#tables-deliberately-not-created) refuses a stored
`gl_account_balance` at all. Building it synchronously under a different name would be the same
mistake with better branding.

### A late arrival is handled by the ordinary build, and bounded three ways

A build for business date `B` enumerates the journals with `business_date = B`, derives the
affected keys and the earliest affected `posting_date` per key, and **recomputes each affected key
from that date forward**. A backdated correction recorded on `B` into `B - 40` is rebuilt together
with everything after it, by the same code path that handles that day's ordinary postings. There is
no separate repair path, because a repair path that only runs when someone notices is how a
projection becomes a second ledger that diverges silently.

Three bounds make that affordable and correct:

1. **A trailing window of business dates**, one by default, is re-scanned on every build.
   `advance` takes the `business_date` row lock exclusively and a current-dated posting holds it
   shared, so no current-dated posting straddles the advance. A **backdated** posting takes no lock
   on that row at all, so one that read `business_date = B` before the advance may commit after it.
   Re-scanning `B - 1` closes that window, and costs only the keys that moved, because the
   recompute is idempotent by construction.
2. **The rebuild horizon is the oldest open fiscal period**, not a configured number of days. A
   backdated posting requires an open covering period, and a closed period refuses one whatever
   permission the actor holds, so no line can arrive behind a closed period and no recompute needs
   to reach behind one. The bound is therefore a property of the ledger rather than a setting
   someone has to keep right.
3. **A per-tenant advisory lock**, `AdvisoryLockNamespace.ACCOUNTING_DAILY_BALANCE_PROJECTION`,
   serialises builds for one tenant so a retry and a scheduled build cannot interleave on one key.
   Coarse, and right here for the same reason the chart-hierarchy lock is coarse: this is a
   background administrative path, not the posting path.

### The proof is a backstop, and it ships twice

The projection-versus-journal proof compares each stored row against the documented rebuild query
over the same range, with a `FULL OUTER JOIN` so that both failure directions are visible: a row
the journal has and the projection does not is a missed build, and a row the projection has and the
journal does not is a phantom that would be added into a balance. An inner join sees neither. A
sound projection returns zero rows.

It ships as an integration test that fails the build **and** as an operational query runnable
against a live tenant on a named date range, because a proof that exists only in a test suite
cannot answer the question an auditor actually asks, which is about production data on a specific
date.

When the projection and the journal disagree, **the journal wins** and the projection is rebuilt.

## Decision 4 — the as-of read takes its checkpoint behind a watermark

An as-of balance is the latest trusted checkpoint plus a journal delta. The checkpoint is taken at
`min(as-of, P - 1)`, where `P` is the earliest posting date touched by any journal recorded after
the projection's watermark — **not** at the latest posting date the account has a projected row for.

The naive choice is wrong because a posting carries two dates. The projection is complete for the
journals recorded on or before the watermark; a journal recorded after it may be backdated to a
posting date earlier than every projected row. A checkpoint at the latest projected posting date is
then stale, and a delta bounded by *"posting dates after it"* is empty, so the line appears in
neither half and the balance omits it. Because the sub-ledger side of a control-account
reconciliation is always current, the omission surfaces as a `BREAK` attributed to the sub-ledger —
a correct ledger reported as a broken one, which is precisely the failure Decision 2 set out to
avoid.

Retreating the checkpoint costs a wider delta, and the width is bounded by how far back a posting
may be dated. That bound is **the tenant's oldest `OPEN` period**, not a short calendar window. A
`CLOSED` period refuses every posting outright — `PostingPeriodResolver.requireOpen` raises
`PERIOD_CLOSED`, and no permission overrides it — while `journal.post_prior_period` authorises
backdating into a prior period that is *still open*. A tenant leaving last year's period open can
therefore make `P`, and so the delta, reach back that far. Sizing the delta against the oldest open
period is the honest measure; closing periods promptly is what keeps it small.

Two index reads buy the retreat, and **only one of them is O(1)**.
`MAX(built_for_business_date)` is a backward scan of `idx_gl_account_daily_balance_watermark`
stopping at its first row. `MIN(posting_date)` over `business_date >= W` is not: `posting_date` is
an `INCLUDE` payload of `idx_journal_entry_business_date` rather than an ordered key, so PostgreSQL
cannot rewrite the aggregate into a one-row lookup and scans the matching entries index-only
instead. Its cost grows with the journals recorded since the last build — normally one business
day's worth, and bounded by how far behind the build has fallen.

The watermark is read from the projection's own rows rather than from a counter, so it cannot
disagree with what is actually stored: a build that failed halfway leaves a watermark reflecting the
rows it managed to write, and the read is conservative in the right direction as a result. It is
tenant-wide rather than per series, because a series the build did not touch on a business date is
one that had no journals on it — so the tenant-wide maximum is sound for every series, and one
aggregate beats one per key for the tenant-wide reads issue #49 builds on this.

### Rejected: trusting the business date the tenant is currently on

`current_business_date - 1` is free to read and is what the build is *scheduled* for. It is not what
the build has *done*: the job is enqueued after the advance commits, so a failed or delayed build
leaves the assumption false and the read silently short. A watermark that is derived from the rows
themselves cannot be optimistic in that way.

## Consequences

- **An as-of balance becomes a checkpoint plus a bounded delta, not a single row read.** The
  projection is built when the business date advances, so it holds no row for a date the tenant has
  not yet rolled over — which includes *today*, the date a reconciliation is most often run for.
  `LedgerBalanceQuery` is reimplemented as: the safe checkpoint date `S = min(as-of, P - 1)` of
  Decision 4 — never the latest projected date, which a posting backdated onto an already-projected
  day invalidates — each series' closing balance at `S`, plus `SUM(signed_functional_amount)` over
  `journal_line` in `(S, as-of]`. The two sets are disjoint and hold every line dated on or before
  the as-of date, so the answer is exact rather than approximately fresh. With nothing projected,
  `S` is the as-of date and it degrades to the scan it replaces.
  This is the same shape #50 fixes for every future sub-ledger statement, which is the point: the
  general ledger should not answer an as-of question a different way from the ledgers it controls.
- **`LedgerBalanceQuery`'s null branch means "every branch"; the projection's null branch means
  "head office".** The port applies no branch predicate when `branchId` is null, so a tenant-wide
  reconciliation sums every branch's lines; a projection row with `branch_id IS NULL` is a
  head-office or tenant-level posting and nothing else. Reading one as the other would compare a
  head-office-only general-ledger balance against a whole-tenant sub-ledger aggregate, and turn
  every default reconciliation run into a `BREAK` — evidence rows that are wrong rather than
  absent, which is the worse failure. A null branch therefore enumerates every series the account
  has and leaves the journal delta unfiltered; a named branch reads that one series and filters the
  delta to it. A regression test posts to two branches and head office and asserts a tenant-wide
  reconciliation still counts all three.
- Accounting's `@ApplicationModule` `allowedDependencies` and every architecture rule stay exactly
  as they are. Phase E adds one cross-module port to accounting's existing port surface and one
  JobRunr job to lifecycle, which already owns both dependencies.
- A tenant whose business date never advances never gets a projection built. That is correct — no
  day has closed — but it means the projection's freshness is an operational property of the
  business-date pipeline, and #53's monitoring must surface a tenant whose newest projected date
  has fallen behind its business date. Because every as-of read is checkpoint-plus-delta, a stale
  projection makes reads *slower* rather than wrong, which is the right way round for a derived
  structure to fail.
- **A build is one transaction per business date, and holds the tenant's build lock for its whole
  length.** A day that moved many series, or a backdated correction reaching a long way back, is
  therefore one long-running transaction. Per-series transactions would bound that, and were
  rejected: a partial build would be a state nothing records, and the lock would stop serialising
  the thing it exists to serialise. One transaction means a failed build leaves the projection
  exactly as it was and the retry is the same work. The length is bounded by the oldest open fiscal
  period, and the remedy if a deployment needs one is to close periods promptly rather than to split
  the transaction. Build duration is a #53 signal.
- A very old backdated posting into a still-open period causes a proportionally long recompute for
  the keys it touches. Bounded by the oldest open period, and cheap per key, but not free; closing
  periods promptly is what keeps it cheap, which is an operational incentive pointing the right
  way.
- The projection is never a statutory source of truth, is never the only place a number exists,
  and is rebuildable from `journal_line` by the query that ships with it (`INV-13`).

## Alternatives rejected

**A stored current balance on `gl_account`.** A per-account write hotspot on every posting and a
silent divergence risk, and it answers only *"now"* — an as-of balance would still scan history.
Already refused by the schema document; recorded here because it is the first thing anyone
proposes.

**A movement-only daily row.** Discussed above: simpler and immune to late arrivals, but breaks the
one-row-read promise and pushes a cumulative layer into every future product module.

**Building the projection inside the posting transaction.** Violates the reason `INV-12` draws the
transaction boundary where it does, and re-introduces the write hotspot under another name.

**A recurring JobRunr schedule independent of the business date.** The projection's grain is the
tenant's business day, which is a controlled value an operator advances and not a wall-clock day. A
scheduler firing at midnight UTC would build days the tenant has not closed and miss days it closed
late. The advance already happens; inventing a second clock would make two things that must agree.

**Narrowing the broker ban so accounting could host the listener itself.** Exempting
`accounting.adapter.inbound.messaging..` from `AccountingBoundaryRuleTests` would buy the reference
event pattern at the cost of weakening a first-class quality gate, and would need a non-vacuity
assertion naming the exempted classes so the exemption could not silently widen later. It was
rejected because the alternative costs nothing: lifecycle already holds every dependency the
trigger needs, and a direct port call between two modules in one JVM gains nothing from a broker
hop that only re-enters the same process. A rule is easier to keep than to re-tighten.

**Detecting late arrivals with a `created_at` watermark over `journal_line`.** A watermark over a
time column is not safe against transactions that start before it and commit after it, and closing
that gap needs a lag that is itself a guess. `business_date` needs no guess: the set is closed by
an event, not by the passage of time.
