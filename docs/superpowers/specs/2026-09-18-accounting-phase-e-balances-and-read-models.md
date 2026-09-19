# Accounting Phase E: balances, read models and the sub-ledger query contract

Status: approved. Date: 2026-09-18.

## Context

Epic #55 sequences the accounting foundation so that product ledgers are never built on an
unstable general ledger. Phases A to D are merged: the fiscal calendar and chart of accounts
(`V6`), the immutable journal kernel (`V7`, `V13`), versioned posting rules (`V8`), control
accounts and their reconciliation evidence (`V9`, `V11`), and manual journals (`V10`, `V12`), all
posting through one synchronous engine that runs at `SERIALIZABLE` behind a covering-period lock.

Phase E is the read side and the one projection that makes it affordable:

| Issue | Delivers |
| --- | --- |
| #47 | `gl_account_daily_balance`, its build, its rebuild and its drift proof |
| #49 | Trial balance, GL account ledger and journal drill-down read models |
| #50 | The statement and balance contract future product sub-ledgers must implement |
| #51 | Balance Sheet and Income Statement read models |

This spec records the decisions those four issues implement, so each is a transcription rather
than a design exercise. The column-level schema lives in
[the accounting schema](../../database/accounting-erd.md); the invariants and the query patterns
live in [the accounting foundation](../../architecture/accounting-foundation.md). Where this spec
and those documents disagree, they are wrong and are corrected in the same change — that is the
rule both of them state.

## What Phase E does not do

- **No REST endpoints.** `/api/v1/accounting/**` is issue #52 in Phase F. Phase E ships
  application services, ports and value objects that #52 adapts; nothing in Phase E adds a
  controller, a DTO or an OpenAPI operation.
- **No product sub-ledger tables.** #50 delivers a contract, a document and reusable value objects.
  A speculative `member_ledger` or `savings_transaction` table in accounting would violate `INV-16`
  and pre-empt a module that does not exist.
- **No report designer.** #51 produces two named statements from account classification, not a
  general reporting framework.
- **No second projection.** The foundation approves exactly one, and a second needs arithmetic
  showing its query is infeasible without it.

## Decisions

### D1 — The projection is `gl_account_daily_balance`, sparse and carried-forward

Grain: one row per `(organisation_id, gl_account_id, branch_id, currency_code, posting_date)` that
had movement. Each row carries the day's debit and credit totals **and** an
`opening_signed_functional` carried forward from the key's previous row, so a closing balance is a
stored generated column and an as-of balance is one backward index range scan stopping at the first
row.

The alternative — storing only the day's movement and summing rows up to the as-of date — was
considered and rejected. It is simpler and immune to late arrivals, but the schema document already
commits to *"one row read per key"*, and #50's statement contract is built on *"the closest
trusted checkpoint plus bounded deltas"*, which requires a cumulative value.

**The projection is a checkpoint, not an answer.** It is built when the business date advances, so
it has no row for a date the tenant has not rolled over — including today, which is the date an
as-of balance is most often asked for. Every read therefore composes a projected checkpoint with a
journal scan over everything the projection does not yet hold, and the journal stays authoritative
for that tail. Reading the projection alone would have answered yesterday's balance for today's
question and filed control-account `BREAK`s that were artefacts of the read.

**And the checkpoint is not taken at the latest projected row.** The projection holds the journals
recorded up to its watermark, so a journal recorded since may be backdated to a posting date earlier
than every projected row — invisible to a checkpoint taken there *and* to a delta bounded by
*"posting dates after it"*. The checkpoint is taken strictly before the earliest posting date any
journal recorded since the watermark touches, which makes the two sets disjoint and their union
every line dated on or before the as-of date.

Amounts are **functional** amounts throughout, grouped by `functional_currency_code`. The
transaction currency is not a grouping key: a tenant's books are kept in one functional unit, and
summing across transaction currencies adds incompatible units.

Full column, constraint and index definitions, the rebuild query and the drift proof are in
[the accounting schema](../../database/accounting-erd.md#column-definitions-for-the-issue-47-table).

### D2 — The build is triggered by the business date advancing, and lifecycle hosts the trigger

**When.** On `BusinessDateService.advance`, not on `completeCob`, and the reason is the date rules
rather than convenience. `docs/architecture/accounting-dates-and-periods.md` states that a
backdated posting into a still-open prior period is legal *even while close-of-business is running*
and takes no lock on `business_date`. So the set of journals recorded on business date `B` is still
open while `B` is `CLOSED`, and closes only once the tenant's business date has moved off `B`.

**Where.** In lifecycle, not in accounting. `AccountingBoundaryRuleTests` bans
`org.springframework.amqp..`, `io.namastack.outbox..` and `org.jobrunr..` from the whole
`com.finaxis.platform.accounting..` package, and the module boundary document states it as rule 4,
so accounting can host neither a `@RabbitListener` nor a JobRunr job. Relocating the listener to
another module does not help either: that module would then have to write
`gl_account_daily_balance`, which the sibling rule confines to accounting's persistence adapters.

```
BusinessDateService.advance (lifecycle)
  → enqueues BuildDailyBalanceJobRequest in the same transaction as the advance
  → JobRunr runs it after commit, outside the business-date row lock
  → AccountingDayRollover.settleDay(organisationId, businessDate)        [accounting port]
  → DailyBalanceProjectionService → accounting persistence adapter → the projection
```

Every hop is already permitted: lifecycle's `@ApplicationModule` allows `accounting` and
`common::jobs`, and `AccountingDayRollover` joins `AccountingBusinessDateLookup`,
`AccountingTenantLookup` and `SubledgerProofProvider` on the cross-module port surface accounting
already publishes. **Phase E changes no architecture rule and no `allowedDependencies` entry.**

The job is scheduled **after** the advance commits, via an after-commit synchronisation. JobRunr's
storage provider takes its own connection, so an inline enqueue would survive a rolled-back advance
and schedule a build for a date the tenant never left. The residual failure — a process dying
between commit and callback — loses one day's enqueue, which the next build's trailing re-scan
picks up. Running after the commit also keeps the rebuild off the `business_date` row lock every
current-dated posting queues behind.

**The build is never synchronous in the posting path.** `INV-12` governs what must be in the
posting transaction; a projection is explicitly not, and putting it there would make every posting
pay a per-account write hotspot — the exact reason `gl_account_balance` is a table deliberately not
created.

### D3 — Late arrivals are handled by the ordinary build, not by a repair path

A build for business date `B` enumerates journals with `journal_entry.business_date = B`, derives
the affected keys and the earliest affected `posting_date` per key, and recomputes each affected
key from that date forward. A backdated correction recorded on `B` is therefore rebuilt by the same
build that handles that day's ordinary postings.

Three supporting rules:

1. The build re-scans a **trailing window of business dates**, one by default, closing the window
   in which a backdated posting that read `business_date = B` commits after the advance. The
   recompute is idempotent, so re-scanning costs only the keys that moved.
2. The rebuild horizon is bounded by the **oldest open fiscal period**: a backdated posting needs
   an open covering period, and a closed period refuses one whatever permission the actor holds.
3. A per-tenant advisory lock (`AdvisoryLockNamespace.ACCOUNTING_DAILY_BALANCE_PROJECTION`, the
   next free namespace) serialises builds for one tenant, so a retry and a scheduled build cannot
   interleave on the same key.

The drift proof is the backstop, not the mechanism. A projection whose correctness depended on a
proof job noticing would be the second independent ledger issue #47 forbids.

### D4 — #49's read models compose the projection with bounded journal scans

| Read model | Opening balance | Movements | Closing |
| --- | --- | --- | --- |
| GL account ledger (Q1, Q3, Q7) | Latest projection row `<= from - 1 day` | Keyset page over `idx_journal_line_account_date` | Opening plus the page's running total |
| Trial balance (Q4) | Per account, a `LATERAL` against the projection | Aggregate over `idx_journal_line_branch_account_date` for the range | Computed, never stored twice |
| Journal drill-down (Q5) | — | `journal_entry` by key, `posting_request` by source | — |

The trial balance reads the projection through a `LATERAL` per account rather than scanning it,
which is why the projection needs no index beyond its unique key. A tenant holds 500 to 2,000
accounts, so the lateral is bounded by a number the chart itself bounds.

**The trial balance proves total debits equal total credits for its scope** before returning, and
fails loudly rather than returning an unbalanced report.

Hierarchy roll-up uses a single `WITH RECURSIVE` descent, matching the chart-of-accounts retrieval
`ChartHierarchyPolicy` already bounds at six levels. No N+1, no materialised path, no closure
table.

#### #49 creates one migration, and it is one index

`idx_journal_line_branch_account_date (organisation_id, branch_id, posting_date, gl_account_id)
INCLUDE (direction, functional_amount)` is named by the schema document as #49's, and both
`posting_request`'s and `journal_line`'s column notes defer branch reporting to it. Issue #49's
text expects zero migrations but permits at most one, and an index is not a persisted projection,
so the index is created by #49's own migration rather than smuggled into #47's.

### D5 — Keyset pagination already exists at the application layer; the web envelope is #52's

The foundation document says #51 owns adding a keyset cursor variant to `ApiPage`. That is
corrected here, because it is doubly wrong:

- **It is not needed by Phase E.** Accounting already paginates by keyset at the application layer
  — `ChartOfAccountsService`, `PostingLineageService` and `ControlAccountReconciliationService` all
  take a cursor and a page size bounded by `PaginationProperties.maxPageSize`. #49 and #50 follow
  that existing contract and never use `OFFSET`.
- **It is not reachable.** `ApiPage` lives in `common::web-api`, which is not in accounting's
  `allowedDependencies`, by design: a page envelope is a web concern and accounting has no web
  adapter until #52.

The ownership therefore moves to **#52**, which introduces accounting's controllers and is the
first change that needs an envelope at all. #49 makes that correction to the foundation document in
the same commit as the read models that prompted it.

### D6 — #50 ships a contract, not a ledger

Deliverables: `docs/architecture/subledger-statements-and-balances.md`, reusable value objects and
port interfaces for statement retrieval, and a benchmark fixture proving checkpoint-plus-delta
correctness including reversals. A future product module owns its own rows and its own projection;
accounting supplies the shape and the proof obligation.

The contract fixes: opening balance from the closest trusted checkpoint plus bounded deltas; stable
`(posting_date DESC, id DESC)` keyset ordering; running balance computed from the page's own opening
plus that page's movements, never one query per row; mandatory bounded date window; and the
requirement that a product's current-balance projection commit in the same transaction as its ledger
entry and the GL posting when it is correctness-sensitive.

**"The page's own opening" is load-bearing.** On the first page it is the position's opening balance
for the window; on every later page it is the balance the *previous* page closed at, carried back by
the caller. Computing a later page from the window's opening instead resets the running balance at
each keyset boundary and understates it by everything the earlier pages moved — a page that looks
entirely plausible and is wrong. A cursor and its carried balance are therefore one pagination
state, and an implementation should refuse a request carrying one without the other rather than
guess.

The `DESC` ordering above is the general pagination contract. A statement read **forward** for a
running balance is the documented exception and reads ascending, because a running balance only
means anything accumulated from an opening — see `LedgerReportingQueries.movementsForAccount`.

### D7 — #51 classifies from `account_class` and the hierarchy, never from account numbers

`gl_account` already carries `account_class`, `is_contra_account` and a generated `normal_balance`,
plus a parent hierarchy whose depth is bounded. That is sufficient to map a tenant-specific chart
onto Balance Sheet and Income Statement sections without a single account-number constant in code,
which is #51's explicit acceptance criterion.

Proofs: Assets = Liabilities + Equity for the Balance Sheet scope, **where equity includes earnings
not yet closed out** — this platform has no year-end close, so between a tenant's first posting and
one existing, income and expense accounts hold real money and the identity the ledger actually
proves is `assets = liabilities + equity + (income − expenses)`. A reader implementing the bare
three-term form gets a sheet out by exactly the tenant's profit to date. Period income and expense
totals reconcile to the trial balance for the same scope; no account double-counted in a roll-up.

### D8 — Permissions and migrations

`accounting_report.view` and `accounting_report.export` are already seeded by `V5` and already
granted by tenant provisioning, so **Phase E adds no permission migration**. The rebuild and proof
entry points are guarded by `reconciliation.run` and `reconciliation.view`, which is the existing
"prove and repair the ledger's derived state" family.

A permission check is not sufficient on its own for the rebuild. It discards derived financial rows
and recomputes them on one person's authority, which makes it an administrative state-changing
operation, so it is **also audited** through the common audit service — actor, tenant, account,
range, rows rebuilt and a mandatory reason — exactly as `reconciliation.resolve` is. The permission
records that the actor was allowed to; the audit row records that they did, and why.

| Issue | Migration |
| --- | --- |
| #47 | One: `gl_account_daily_balance` |
| #49 | One: `idx_journal_line_branch_account_date` |
| #50 | None |
| #51 | None |

### D9 — `journal_line.source_module` names the module that owns the position, not the requester

A reversal is posted by accounting: `JournalReversalService` uses the constant source module
`accounting`, and `PostingEngine` stamps every line's `source_module` from the request that
produced it. But `mirror()` copies the original line's `subledgerReference` onto each reversal leg.
The result is that a reversed savings deposit leaves the original line under
`('savings', 'SAV-1')` and its reversal under `('accounting', 'SAV-1')`.

`idx_journal_line_subledger` is keyed on `(organisation_id, source_module, subledger_reference, …)`
and every existing query against it — the Q6 plan test and
`SubledgerPositionReferenceIntegrationTests` alike — names the owning module. **Those queries
therefore return the original and not its reversal**, and the position reads as still holding an
amount the general ledger has given back. Control-account totals are unaffected, because they
aggregate by account alone, which makes it worse rather than better: the aggregate agrees while the
drill-down meant to explain a break disagrees with it. Issue #43's own criterion is that read
models include reversals as ordinary immutable entries.

The column's meaning is therefore fixed here as **the module that owns the subsidiary position the
line moved**, falling back to the requesting module when the line moves no position. `PostingLeg`
gains an explicit `subledgerModule` alongside `subledgerReference`, `mirror()` copies both, and
`PostingEngine` takes the line's `source_module` from the leg when it carries a reference.

The alternative — leaving the column alone and making #50's query walk
`journal_entry.reverses_journal_entry_id` — costs the join the partial index exists to avoid, and
loses the range scan Q6's block budget depends on.

This is a correctness fix to merged #43 code rather than Phase E scope, so it is its own pull
request in the stack, before #50 consumes the predicate.

### D10 — Coverage verification is extended to accounting at the same 95% bar

`jacocoTestCoverageVerification` today includes `com/finaxis/platform/iam/**` only, at
`LINE COVEREDRATIO >= 0.95`. Accounting gets its **own** independent rule at the same 0.95, as a
separate verification task rather than by widening the existing `include`: one bundle rule spanning
both modules would let a strong `iam` figure carry a weak accounting one, which is not what
"we still maintain 95%" means. `docs/development/static-analysis.md` records both contracts, and
`CLAUDE.md`'s "JaCoCo coverage verification is scoped to `iam` only" known-follow-up is retired in
the same commit.

## Delivery: a stack of seven pull requests

Each branch rebases onto its base, carries exactly one conventional commit, and passes
`./gradlew qualityGate` on its own base.

| PR | Branch | Base | Contents |
| --- | --- | --- | --- |
| 0 | `…-00-design` | `main` | This spec, ADR 0027, the ERD's #47 column definitions, the foundation-document corrections |
| 1 | `…-47-daily-balance` | PR 0 | The projection migration, the rollover port and its lifecycle job, rebuild, drift proof, the projection-backed `LedgerBalanceQuery`, the Q3 plan test and the atomicity probe |
| 2 | `…-49-read-models` | PR 1 | Trial balance, GL ledger, drill-down, the branch index, the bounded fiscal-period listing, the Q4 plan test, the pagination correction |
| 3 | `…-43-reversal-subledger-module` | PR 2 | D9: the reversal's sub-ledger module, and the regression test that a reversed position nets to zero |
| 4 | `…-50-subledger-contract` | PR 3 | The statement contract, its document and its benchmark fixture |
| 5 | `…-51-financial-statements` | PR 4 | Balance Sheet, Income Statement and their proofs |
| 6 | `…-accounting-coverage-gate` | PR 5 | D10: accounting's 95% coverage rule, once the Phase E surface is complete |

PR 0 exists so that a reviewer can disagree with the projection's shape before any SQL is written,
which is the split `CLAUDE.md` names explicitly: a design record, then the migration that
transcribes it, then the adapter that uses it.

## Related documents

- [Accounting schema](../../database/accounting-erd.md) — the projection's columns and proofs
- [Accounting foundation](../../architecture/accounting-foundation.md) — invariants, query
  patterns, volume assumptions
- [Accounting dates and periods](../../architecture/accounting-dates-and-periods.md) — why the
  build triggers on the advance
- [ADR 0018](../../adr/0018-financial-transaction-atomicity-invariant.md) — why the build is not in
  the posting transaction
- [ADR 0020](../../adr/0020-immutable-ledger-and-reversal-only-correction.md) — why reversals need
  no special handling in a balance
