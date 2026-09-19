# Accounting module boundary

> The `accounting` module owns the general ledger. Product modules reach it only through the
> posting API, and accounting never reaches back into identity or lifecycle. See
> [ADR 0020](../adr/0020-immutable-ledger-and-reversal-only-correction.md) and
> [the accounting foundation](accounting-foundation.md).

At this stage the module is **contracts, the fiscal calendar, the chart of accounts with their
lifecycles and maker-checker controls, the journal schema, and the synchronous posting engine
behind `PostingService`**. Posting rules (#44, #45) resolve a product module's intent to legs, so the public API
posts end to end for any event a tenant has configured a rule for. The boundary existed before any table, deliberately,
so later work cannot accidentally couple a product module to ledger persistence.

## What this module owns

- The general ledger: chart of accounts, fiscal calendar, posting requests, journals and journal
  lines — all designed in [the accounting schema](../database/accounting-erd.md). The chart of
  accounts and the fiscal calendar exist; the journal tables do not yet.
- The public posting API product modules consume.
- The narrow ports foundation modules implement on accounting's behalf.

It does **not** own product subsidiary ledgers. A savings position belongs to `savings`, a loan
position to `loans`. Accounting holds no foreign key into a product-owned table.

## What crosses the boundary

Two named interfaces, and nothing else:

| Named interface | Contains |
| --- | --- |
| `accounting::posting` | `PostingService`, its commands, `PostingReceipt`, `PostingErrorCodes` |
| `accounting::domain` | `MonetaryAmount`, `PostingSide`, `AccountingContext`, `AccountingSourceReference` |

A consuming module declares both:

```java
allowedDependencies = { "accounting::posting", "accounting::domain", ... }
```

`accounting.application.port.outbound`, `accounting.adapter..` and `accounting.config` carry **no**
`@NamedInterface` and are therefore module-internal. `AccountingBoundaryRuleTests` enforces that.

## Ports accounting declares and other modules implement

This is the repository's established inversion: the **consumer** declares the port, the **provider**
ships the adapter and lists the consumer in its own `allowedDependencies`. It is why `iam` depends
on `lifecycle` and no module depends on `iam`.

| Port (declared in `accounting`) | Implemented by | Adapter |
| --- | --- | --- |
| `AccountingPermissionGuard` | `iam` | `iam.adapter.outbound.authorization.AccountingPermissionGuardAdapter` |
| `AccountingBusinessDateLookup` | `lifecycle` | `lifecycle.adapter.outbound.accounting.LifecycleAccountingBusinessDateAdapter` |
| `AccountingTenantLookup` | `lifecycle` | `lifecycle.adapter.outbound.accounting.LifecycleAccountingTenantAdapter` |
| `SubledgerProofProvider` | future product modules | none yet - a test provider proves the seam |

`AccountingTenantLookup` also answers the tenant's **functional currency**, read from
`organisation.base_currency_code`. That column, not the `base_currency` tenant setting, is the
currency of record for the ledger: it is set at provisioning, carries the ISO 4217 check, and can
only be amended while the organisation is a draft, so it is fixed before a journal can exist. The
port answers it two ways, deliberately: `functionalCurrencyOf` is a plain read, for the callers that
only report or resolve against the currency, and `functionalCurrencyForPosting` is a locking read of
the same column - the only one a posting may decide its currency from once it holds the
tenant-currency lock. The paragraph below says why the distinction exists.

Two ports run the other way, both declared in the accounting root package and implemented inside
accounting, and both called by lifecycle — which already depends on `accounting` for the ports
above, so neither creates a module edge.

`AccountingLedgerActivity` lets lifecycle ask *"has this tenant posted?"* before it accepts a
`base_currency` setting change — the functional-currency freeze the accounting foundation requires.

`AccountingDayRollover` lets lifecycle tell accounting that a tenant's business date has moved on,
so accounting can settle the derived balances for the day just left. **This is the shape it is
because of rule 4 below.** The natural implementation would have accounting consume the
`BusinessDateAdvanced` event with its own listener and hand off to a JobRunr job; accounting may do
neither, and no other module may write `gl_account_daily_balance` either. So lifecycle — which
already holds `common::jobs` — enqueues the job inside the advance's transaction and calls this port
from it after that transaction commits, outside the `business_date` row lock every current-dated
posting queues behind. The ban stays absolute rather than being narrowed for a special case. See
[ADR 0027](../adr/0027-derived-balance-projection-and-its-build-trigger.md).

The same port also hands lifecycle the lock that makes the answer usable. Asking and then writing
are one transaction and the tenant's first posting is another, and nothing about MVCC makes either
see the other - so `lockFunctionalCurrencyForChange` is taken immediately before the question, and
a posting takes the same tenant-scoped lock shared as the first thing `postNew` does. Lifecycle
still reasons about nothing but a boolean; the serialisation is accounting's, on accounting's side
of the port.

Raising the posting path to `SERIALIZABLE` does **not** make that advisory lock redundant, and it
is the one conclusion a reader is most likely to draw. An advisory lock does not participate in
MVCC, so it brings neither an `EvalPlanQual` re-read nor a `40001` with it: measured, a serializable
posting that takes the shared lock and then plainly re-reads the currency gets its own snapshot's
stale value and commits. The lock is what makes the two sides serialise; the isolation level is
what made the engine's post-lock *comparison* of the currency stop firing.

That regression is **closed**, in the same change that caused it, by the second method on this port.
`functionalCurrencyForPosting` is a `SELECT base_currency_code … FOR SHARE` on the organisation row,
taken in the statement immediately after the advisory lock, and measured to raise `40001` in exactly
the interleaving a plain re-read commits through. The two mechanisms close different halves and
neither replaces the other: the row lock closes the *posting's* read of the currency, the advisory
lock closes the *change's* read of *"has this tenant posted"*. The measurements are in
[ADR 0025](../adr/0025-serializable-posting-and-the-covering-period-lock.md); the lock chain the new
class joins, and why it introduces no cycle, are in
[ADR 0023](../adr/0023-posting-idempotency-and-account-locking.md). Issue #121 holds the original
analysis and is the useful pointer to it; it is not an outstanding gap.

Both provider modules gained `"accounting"` in their `allowedDependencies`. The resulting module
graph stays acyclic: `iam → lifecycle`, `iam → accounting`, `lifecycle → accounting`,
`lifecycle → notifications`, everything → `common`, persistence → `jooq`.

The ports are deliberately narrow. `AccountingTenantLookup` returns booleans rather than lifecycle
state enums, and `AccountingBusinessDateLookup` returns a `postingAllowed` flag rather than a status
string, so a new lifecycle state cannot silently change accounting behaviour — it is denied until
someone decides otherwise in the adapter.

**`AccountingBusinessDateLookup` answers two ways for the same reason `AccountingTenantLookup`
does.** `currentBusinessDate` is a plain read, for read-side callers and for the pre-claim read the
idempotency fingerprint is computed from; `currentBusinessDateForPosting` is the same select with
`FOR SHARE`, and it is the only one a posting may decide close-of-business eligibility from once it
holds its claim. Only a *current-dated* posting calls it: a backdated correction stays legal while
the day is closing, so it neither needs the row nor may hold it, since holding it would make
close-of-business queue behind exactly the corrections it must not deadlock. Two methods rather than
a boolean parameter, because the two have different
preconditions — the second requires an active transaction and leaves a lock behind — and a flag
would let a read-side caller take that lock by accident.

That distinction is not cosmetic. Until issue #125 there was only the plain read, and the posting
path decided the close-of-business gate from a snapshot pinned before a concurrent `startCob` could
be seen, so a current-dated journal committed into a closing day. `SERIALIZABLE` catches nothing
there — one rw-dependency edge, no cycle — and the shared row lock is the entire guarantee, exactly
as it is for the covering period and the functional currency. The operational consequence is that
every mutation of `business_date` now waits for in-flight postings and is bounded by
`finaxis.lifecycle.business-date-lock-timeout`. See
[ADR 0026](../adr/0026-real-time-gates-on-the-serializable-posting-path.md).

**`AccountingPermissionGuard.requireBreakGlassPermission` had the same defect and is repaired the
same way.** It resolved through `iam`'s cache-first `EffectivePermissionResolver` from inside the
posting transaction, so a revocation was either answered from a stale cache entry or — after the
eviction a revocation performs — from a stale snapshot. It now answers from a locking, cache-free
read of the one code. The port's signature is unchanged; only what `iam` does behind it changed.

## Business date access

Accounting needs the tenant business date, and before this module there was **no cross-module way
to read it**: `BusinessDateStore` lives in `lifecycle.application` and was called only by
`BusinessDateService`. Three options were considered.

**Chosen — accounting declares the port, lifecycle ships the adapter.** Matches the repository's
inversion convention, and points the arrow the way the future needs it: close-of-business (issue
#39) will want lifecycle to ask accounting whether every period is closed. With
`accounting → lifecycle` that later need would be a hard cycle.

**Rejected — a new `lifecycle::businessdate` named interface with accounting depending on
lifecycle.** Reverses the convention and creates exactly the cycle above.

**Rejected — moving business date into `common`.** It would drag the store, its jOOQ adapter, its
history store, its permission checks and its close-of-business state machine into a package with no
`@ApplicationModule` and no authorization. Business date is an organisation lifecycle concern with
its own state machine.

One wrinkle worth knowing: `BusinessDateService` keeps its `OPEN` constant private, so
`LifecycleAccountingBusinessDateAdapter` duplicates the literal.
`LifecycleAccountingAdapterIntegrationTests` drives a real business date through close-of-business
and asserts the adapter follows, so the duplication cannot drift undetected.

## Clock and identifier generation

Accounting takes **no port** for either, and this was decided rather than overlooked.

`java.time.Clock` *is* the platform abstraction: a JDK type, injected as the single bean from
`config.ApplicationConfiguration`, substitutable in tests with `Clock.fixed(...)`. `uuidV7()` from
`common.id` is already the only permitted generator, guarded by `IdentifierGenerationRuleTests`.
Wrapping either in an accounting-owned port would add indirection with no boundary benefit.

## Architecture tests guarding this boundary

`AccountingBoundaryRuleTests`:

1. Nothing outside accounting depends on its adapters, config or outbound ports.
2. Only accounting persistence adapters may touch generated accounting jOOQ tables — written now,
   against a reserved type-name set, so it starts guarding the moment issue #36 creates the first
   table.
3. Accounting does not depend on `iam`, `lifecycle` or `notifications`.
4. Accounting does not depend on AMQP, the outbox or JobRunr — no broker and no background job may
   sit in the posting critical path.
5. Accounting `domain` stays free of framework and persistence types.

`ModuleDependencyRuleTests` also gained two repository-wide rules this issue asked for: no
production code may depend on `RabbitTemplate`, and only messaging adapters may depend on
`org.springframework.amqp` at all. Both passed on first run, which is the point — they lock in an
invariant the codebase already held rather than forcing a change.

## Adding a product module that posts

1. Declare `"accounting::posting"` and `"accounting::domain"` in the module's `allowedDependencies`.
2. Call `PostingService.post(...)` from the application service that owns the business transaction,
   inside the same `@Transactional` boundary as the source mutation.
3. Express **posting intent** — `PostingIntent.Facts` where possible — never GL account ids.
4. Register the new durable effects as probes in `FinancialTransactionAtomicityFixture` and prove
   they commit or roll back together. See
   [financial transaction atomicity](financial-transaction-atomicity.md).
5. Add the module's BIAN mapping line and landscape section, per
   [the BIAN service landscape](bian-service-landscape.md).

## Not yet implemented

| Missing | Issue |
| --- | --- |
| Ledger balance projections and daily rollups | #47 |
| Trial balance, GL ledger and financial-statement read models | #49, #50, #51 |
| Accounting REST adapters | #52 |

Phase A and B items that this table used to list — the fiscal-calendar and chart-of-accounts
schema (#36), the accounting permission catalogue (#34) and the fiscal-period concurrency
semantics (#35) — have shipped, together with the GL-account domain (#37), the chart-of-accounts
FSM (#38) and the fiscal-period lifecycle (#39).

One consequence of that state is worth stating plainly: `HexagonalArchitectureTest`'s web-adapter
allow-list will need extending when the first accounting controller lands in #52 — there is no
accounting web adapter today, so no edit was needed here.

## The posting engine

`PostingEngine` lives in `accounting.application.ledger`, which carries no `@NamedInterface` and is
therefore module-internal: product modules reach it only through `PostingService`, and accounting's
own callers - reversal (#43) and manual journals (#48) - call it directly. There is exactly one
place a `journal_entry` row is created, which is what *"the same PostingEngine"* means mechanically.

It runs inside the **caller's** transaction (`Propagation.MANDATORY` on the public service) and in
this order: reconcile the caller's `AccountingContext` against the ambient one; check the
organisation and branch are postable and read the functional currency; resolve the dates and lock
the fiscal period through `PostingPeriodResolver`; ask the `LegProvider` for the legs, now that the
posting date - and so the rule version - is known; settle every leg through `MoneyPolicy`, refuse
any currency but the functional one, and check every account through `GlAccountPostingPolicy`;
prove the set balances in memory; claim the source reference with `INSERT … ON CONFLICT DO
NOTHING` and answer a duplicate from the locked existing row; allocate the gapless number from
`reference_sequence`; write the header and the lines; **re-read the lines and compare them with the
header** - the two totals, the line count, and each line's denormalised branch, fiscal period,
posting date and currency codes - the `INV-4` enforcement point; mark the request `POSTED`.

Every failure before the claim leaves nothing behind. Every failure after it rolls the claim back
with the caller's transaction, so a rejected request never occupies its source reference.

**A product module enters through `PostingTransactions`, not through a transaction of its
own.** It lives in `accounting.application.posting` - the module's only non-domain
`@NamedInterface` package, so calling it is legal under Modulith verification where calling
anything in `application.ledger` is not - and it is lambda-shaped:
`postingTransactions.execute("...") { productMutation(); postingService.post(cmd) }`. It opens
nothing itself; it delegates to the module-internal `PostingTransactionBoundary`, and one call
later `SerializablePostingTransaction` opens the single
`@Transactional(isolation = SERIALIZABLE)` on any write path in the repository, and an ArchUnit
rule keeps it the only one. `PostingEngine.post` then refuses any transaction weaker than
`SERIALIZABLE` with `accounting.snapshot_isolation_unavailable`, because `post` is
`Propagation.MANDATORY` and a joining method has no isolation of its own to declare - Spring drops
a declared isolation silently on a joined transaction, so the guard is the only enforcement that
path has. The boundary also refuses outright to run inside an already-open transaction, because a
transaction it did not open is not one it can re-run - joining would drop `SERIALIZABLE` silently
and put the retry inside the very transaction it retries. See
[ADR 0025](../adr/0025-serializable-posting-and-the-covering-period-lock.md).

**Everything inside the boundary lambda must be replayable**, because a serialization failure is
re-run by re-running the lambda. No `REQUIRES_NEW`, no JobRunr enqueue, no broker publish, no
`registerSynchronization`: each of those either escapes the transaction being retried or fires once
per attempt for a unit of work that happened once. The product module's own mutation belongs
*inside* the lambda, which is what keeps `INV-12` true - the journal and the mutation it accounts
for commit or roll back together, and the retry re-runs both.

## Posting rules

`PostingRuleService` owns the configuration that turns a product module's intent into legs, and
`RuleBackedPostingLegResolver` is the engine's `PostingLegResolver`. One resolution path, per
ADR 0020: the candidates are every `posting_rule` for the event; the winner is the most specific
selector match on product class and functional currency, with a tie failing fast as
`accounting.posting_rule_ambiguous`; the version is the one approved `posting_rule_version` whose
window covers the **posting date**, which `ex_posting_rule_version_no_overlap` guarantees is at most
one; and `PostingRulePolicy.allocate` turns the facts into legs - each leg its percentage of its
fact, rounded `HALF_EVEN` at the minor unit, the residual leg absorbing the remainder (`INV-2`).
The engine records the version on `posting_request.posting_rule_version_id`.

The version lifecycle is the chart-of-accounts control applied to configuration: `DRAFT →
PENDING_APPROVAL → ACTIVE`, rejection back to `DRAFT` with a reason, approval by an actor who is not
the most recent submitter resolved from `posting_rule_version_transition_log` under the rule's row
lock, and `posting_rule.create`, `posting_rule.create_version` and `posting_rule.approve` audited.
Activation supersedes the rule's current head by closing its window the day before the successor
takes effect, in the same transaction. The successor is compared against every window
`ex_posting_rule_version_no_overlap` covers - `ACTIVE`, `SUPERSEDED` and `RETIRED` alike - rather
than against an `ACTIVE` head alone, so a successor reaching back into a window the rule has already
governed is refused by name with `POSTING_RULE_WINDOW_INVALID` instead of arriving as an exclusion
violation and a 500. Only an open-ended head can be closed to make room for a successor; a window
already closed by an earlier supersession or by retirement is settled history. Legs and
`effective_from` are editable in `DRAFT` only.

`PostingRuleService.dryRun` resolves an intent through the same resolver **and judges the legs
through the same `PostingLegsPolicy` the engine uses**, in a read-only transaction, so an
administrator can test a configuration without a journal and get the answer a posting would give.
It used to stop at resolution, which made the method's name a half-truth: facts denominated in a
currency the tenant does not post in, or a rule naming an account deactivated since its version was
approved, dry-ran clean and posted red (issue #95). `PostingLegsPolicy` is a pure object - no store,
no lock, no bean - holding the minimum-leg count, per-leg amount settlement, the functional-currency
check, account postability and the balance, in that order; the engine hands it the accounts it has
already locked, the dry run hands it an unlocked read memoised per account id.

**This is a behaviour change.** `dryRun` can now raise `accounting.currency_not_supported`,
`accounting.account_not_postable` and `accounting.unbalanced_posting` where it previously returned
legs, so any caller that read a green dry run as "the configuration resolves" will see new failures.
No published API contract moves with it: there is no REST adapter for posting rules yet (#52), so
the only callers are in-process. `PostingRuleDryRunCommand.mode` chooses how a defect is delivered -
`STRICT` (the default) throws exactly what the posting would throw, `REPORT_PROBLEM` returns it on
`PostingRuleDryRunResult.problem` instead. Lenient reports the **first** problem, not all of them:
there is one validator and it stops at the first defect, which is the price of the dry run and the
posting sharing a single validation path. It covers the eligibility pass only - a rule that does not
resolve, or resolves ambiguously, still throws in either mode, because there is then no version and
no legs for a result to carry.

What the dry run deliberately does **not** judge is where and when the posting would happen. Tenant
and branch postability, fiscal-period status and posting-date admissibility stay with the engine,
because each of them would refuse a preview an administrator is entitled to take: a tenant is not
yet `ACTIVE` while its accounting is being configured, a period's status is decided only under the
posting lock, and a rule taking effect tomorrow - or a preview at close of business - would be
refused on its posting date. Those are properties of a posting, not of the configuration under test.

`PostingRuleService.previewVersion` is the second half of issue #95 and a **separate operation, not
a flag on `dryRun`**. The two ask different questions. `dryRun` asks *what is in force*: it selects
the rule from the intent's event code and product class and the version from the posting date, so
by construction only an approved version can answer it - `PostingRuleVersion.governs` requires
`isApproved`, which is right, because a posting must never reach a version nobody approved. That
left the configuration a checker most wants to test unreachable: a `DRAFT` or `PENDING_APPROVAL`
version could not be exercised at all, and a checker approved on the legs and percentages alone.
`previewVersion` asks *what would this named version do*: the caller names a version id, and the
legs are allocated directly, bypassing selection. One operation with a flag would have returned one
type whose meaning depended on an argument, and a reader of the result could no longer tell which
question had been asked; two operations keep "in force" and "under test" distinguishable, which is
what the issue asks for.

It takes **facts, not an intent**. The event code and product class of a `PostingIntent.Facts` are
consumed only by `PostingRulePolicy.select` and the posting date only by `governs`, both of which
preview bypasses, while `PostingRulePolicy.allocate` takes legs and facts and nothing else.
Accepting them would let a caller describe an event the named version's rule does not answer and
receive a confident reply about a fiction; they are read from the version's own rule and reported
back on `PostingRuleVersionPreview.rule` instead. **Every status is previewable** - `DRAFT` and
`PENDING_APPROVAL` for the checker, `ACTIVE` without asserting a posting date, and `SUPERSEDED` or
`RETIRED` forensically - and the whole `PostingRuleVersion` comes back rather than its id, so a
closed version's legs always arrive beside the status and window that say they are not current.

Bypassing selection costs the diagnostics selection performs, and one of them matters enough to be
a field. The currency dimension of a selector is matched against the tenant's functional currency,
so a rule pinned to `USD` in a `KES` tenant is well formed, allocates perfectly and **can never
fire**; `dryRun` catches that only by accident, as `POSTING_RULE_NOT_FOUND` from finding no rule at
all. `PostingRuleVersionPreview.selectorMatchesFunctionalCurrency` says it on purpose. Read the
name literally: it judges the selector's currency dimension and nothing else, so it stays `true`
for a rule `select` would refuse as `accounting.posting_rule_ambiguous`, for an event code no
product module emits, and for a window covering no postable date. `false` means the version can
certainly never be selected; `true` means only that this one way of never being selected does not
apply. It is not a reachability verdict, and an earlier name that read like one was changed for
saying more than it knew.

The legs are judged by the same pass, plus one check `dryRun` has no reason to run:
`PostingRulePolicy.requireWellFormed` first - the same check `approve` runs - so a half-finished
draft gives the checker the answer submission would rather than partial legs, and then
`PostingLegsPolicy` under the same `PostingRuleDryRunMode` - **defaulted the other way**, to
`REPORT_PROBLEM`. `dryRun` mimics a posting, so throwing what the posting would throw is its honest
default; a named `DRAFT` version cannot post at all, so preview has no posting to mimic, and its
caller is a checker deciding whether to approve. Throwing would hand them an exception and no legs,
hiding the very thing they opened the preview to look at. `STRICT` is one argument away for a caller
that wants the posting's own refusal. The consequence of `requireWellFormed` is worth stating too:
preview is not a live editing companion, because a version whose legs are not yet well formed is
refused rather than previewed. Well-formedness and allocation throw in both modes, as they do for
`dryRun`, since a version with no allocatable legs has nothing for a result to carry a problem
about. The read is `REPEATABLE READ` and asserts it through `SnapshotIsolationGuard`, for the reason
`ManualJournalService.get` does: the version, its rule, its legs and its accounts are four
statements, and under `READ COMMITTED` an amendment committing between them would pair one version's
metadata with another version's legs. **No lock is taken**, deliberately - an account reported
postable can be deactivated a moment later, which is why `approve` revalidates every account under
`lockForPosting`. A preview is evidence for an approver, never a guarantee to build an approval gate
on. It is gated by `posting_rule.view`, checked before any store read, and reads the version
tenant-scoped, so an id from another tenant is refused exactly as one that never existed.

## Control accounts and reconciliation

A control account **is** a GL account (ADR 0020): `gl_account.is_control_account` and
`control_subledger_kind`, with `V9`'s `CHECK`s making a control account postable, single-kind and
closed to manual posting. `ChartOfAccountsService` refuses the same combinations first, by name, and
`GlAccountPostingPolicy.requireManualPostingAllowed` refuses a control account outright - a
hand-written line into one breaks its reconciliation by definition.

`ControlAccountReconciliationService` is the detective control of `INV-14`. A run reads the GL side
through `LedgerBalanceQuery` - `SUM(signed_functional_amount)` over the account's lines as of a
date, optionally within a branch - asks the one `SubledgerProofProvider` that supports the account's
kind for the sub-ledger aggregate in the same sign convention, and writes a
`control_account_reconciliation_run` row: `MATCHED` within the run's tolerance (zero by default),
otherwise `BREAK`. It never reaches into a product module's persistence and never touches a journal;
a break is corrected by a reversal or a fresh posting and a later run proves it. `resolve` signs a
break off, `CRITICAL`, with a reason, by an actor other than the runner, and is audited.

The provider port is the seam a future savings, loans or shares module implements: *"what did your
ledger total in this scope as of this date"*, one aggregate back, nothing else crossing. A kind with
no provider is reported as `accounting.subledger_provider_missing` rather than silently matched.
End-of-day and period-close orchestration hooks in by calling `run`; nothing here schedules it.

Three properties of that port are contract rather than convention, and each closes a way a proof
could record a verdict that means nothing:

- **One snapshot, both sides.** `run` is `REPEATABLE READ`, and `SubledgerProofQuery.snapshotId`
  carries a PostgreSQL exported snapshot the provider is obliged to read from — automatically if it
  reads on accounting's transaction, by `SET TRANSACTION SNAPSHOT` if it reads on a connection of
  its own. Under `READ COMMITTED` the two aggregates are separate snapshots, and a posting landing
  between them invents a `BREAK` or, worse, offsets a real one into a `MATCHED`. `ProofSnapshot`
  asks the database what isolation is actually in force rather than trusting the annotation, which
  Spring drops silently when the method joins a transaction already open.
- **The name is checked at startup.** `SubledgerProofProvider.PROVIDER_NAME_PATTERN` is
  `chk_control_account_reconciliation_run_provider` verbatim, and
  `SubledgerProofProviderRegistry` refuses a malformed name — or two providers claiming one
  class — when the context builds. A provider called `Savings Ledger` otherwise starts, answers
  both reads and fails at the insert.
- **The scope is validated before either read.** An unknown branch is
  `accounting.branch_not_in_organisation` up front, through
  `AccountingTenantLookup.branchBelongsTo`. Existence, not postability: a proof is of a date that
  has happened, so a branch closed since then is a legitimate subject of one.

## Manual journals

`ManualJournalService` is the one legitimate way to name general-ledger accounts by hand, and the
one that offers no way around the engine. A `manual_journal` draft carries explicit debit and credit
lines, a mandatory reason and its own transition log (`V10`); nothing about it touches
`posting_request`, `journal_entry` or `journal_line`. `DRAFT → PENDING_APPROVAL → POSTED`, rejection
back to `DRAFT` with a reason, cancellation from `DRAFT`. **Approval is the posting**: `APPROVE`
runs the `PostingEngine` with the lines as legs, `entry_type = 'MANUAL'`, the draft's id as the
durable source, and writes the produced journal's id onto the draft in the same compare-and-set that
moves it to `POSTED` - so the draft, its log, the `journal.approve` audit event and the ledger rows
commit together or not at all. The client never sets `POSTED` and never supplies the journal
identity.

Controls: `journal.create_manual` (`HIGH`, audited) to draft, `journal.submit` to submit,
`journal.approve` (`CRITICAL`, audited) to approve or reject; the approver is not the most recent
submitter, resolved from `manual_journal_transition_log` under the header's row lock; and every
line's account has opted into manual posting and is not a control account, which
`GlAccountPostingPolicy.requireManualPostingAllowed` enforces and `V9`'s `CHECK` backs. With this
the `HighRiskOperationAuditCoverageTests` ratchet reaches zero: every `HIGH` and `CRITICAL`
accounting permission has a real audit call site.

Three properties of the draft aggregate are contract rather than convention (issue #92), and each
closes a way a draft could be changed or reviewed on a footing the actor did not have:

- **An amendment carries the row version it was prepared on.**
  `AmendManualJournalCommand.expectedRowVersion` is required, and is compared against the header
  under its row lock. The earlier check read the locked row's version and compared it against
  itself, which is always equal: two makers editing from one view merely serialised, and the second
  silently overwrote the first's whole header and line set while `MANUAL_JOURNAL_STALE` advertised a
  protection that could not fire. The comparison runs before any line is touched, so a refused
  amendment leaves the draft and its `row_version` untouched. An amendment that lands is audited as
  `journal.amend_manual` (`HIGH`), naming the version it was prepared from: it is the one
  manual-journal operation that leaves no `manual_journal_transition_log` row, and it can rewrite
  every amount, every account and the external reference that the later `journal.approve` event
  reports.
- **`get` reads header and lines from one snapshot.** It is `REPEATABLE READ`, guarded by
  `SnapshotIsolationGuard`, because it is the representation a checker reviews before approving.
  Under `READ COMMITTED` an amendment committing between the two statements pairs an old header —
  old title, old narrative, old version — with a new line set, so a checker could approve amounts
  they never saw beside a reason that no longer describes them. The guard asks the database what
  isolation is in force rather than trusting the annotation, which Spring drops silently when the
  read joins a transaction that is already open.
- **A document number has a column.** `manual_journal.external_reference` (`V12`) is one bounded,
  non-blank, optional field, so the memo number or bank advice an adjustment answers to can be
  searched and reported on instead of being buried in the narrative. It is not copied onto
  `journal_entry` — that table has no such column and `V7` is frozen — so the posted journal stays
  answerable through the draft it came from, and the `journal.approve` audit event carries it for
  an investigator starting from the audit trail. Bounded in **characters**, not UTF-16 units, on
  both sides: `chk_manual_journal_external_reference` counts with `char_length` and
  `ManualJournalPolicy` with `codePointCount`, so the contract the maker reads is the one the
  column enforces. The constraint's non-blank half is `~ '[^[:space:]]'` rather than
  `btrim(...) <> ''`, because `btrim` strips spaces only and a tab-only reference would otherwise
  pass the column while the service refused it.

The rules that need nothing but the draft — the version comparison, the reference bound, the line
count, contiguity, settled amounts, balance — live in `ManualJournalPolicy` beside the service
rather than inside it, for the reason `PostingRulePolicy` and `GlAccountPostingPolicy` do: a rule
about money whose only exercise costs a Spring context, a container start and a permission grant is
a rule whose edges do not get tested. `ManualJournalService` keeps what needs a collaborator — that
an account exists and accepts manual posting, who the maker was, what the FSM permits.

## Reversal

`JournalReversalService`, reached through `PostingService.reverse`, is the only correction of posted
history (ADR 0020, `INV-6`). It writes a **new** journal with `entry_type = 'REVERSAL'`, lines that
mirror the original's with the side flipped and the same positive amounts, and
`reverses_journal_entry_id` pointing at the original - through the same engine as every other
journal, so the period lock, account eligibility, balance check, idempotency claim, gapless number
and verification read all apply. The original row is never touched.

The controls it adds over an ordinary posting, in order: `journal.reverse` (`CRITICAL`); a
mandatory reason; a per-journal advisory lock in `AdvisoryLockNamespace.ACCOUNTING_JOURNAL_REVERSAL`
- a row lock on `journal_entry` needs the `UPDATE` privilege issue #54 revokes; under that lock, the
original exists, is not itself a reversal and has no reversal yet; and the reversing actor is not
the actor who posted the original, resolved from `journal_entry.created_by` - the same
maker-from-the-record shape the fiscal-period and GL-account services use, because the catalogue
has no `journal.reverse_request` code to build a submit/approve pair from. `uq_journal_entry_reversal_once`
is the database backstop for the concurrent case, and every reversal writes a `journal.reverse`
audit event in the same transaction as the journal.

A reversal cannot be reversed. Undoing one is a fresh posting that names the original request in
`corrects_posting_request_id`, which keeps the correction lineage on the mutable request and *"has
this journal been reversed"* a one-index answer.

## Idempotency and lineage

There is exactly one idempotency mechanism at the domain layer:
`UNIQUE (organisation_id, source_module, source_reference)` on `posting_request` (`INV-7`), claimed
with `INSERT … ON CONFLICT DO NOTHING`. The database is the authority; no in-memory lock or cache
takes part. Under a concurrent duplicate the second insert waits on the first's uncommitted row and
then sees one of three outcomes. The first rolled back, so the second proceeds as the only
claimant. The first committed *before* this transaction's snapshot, so the existing row is locked
`FOR UPDATE` and answered from - the ordinary replay. Or the first committed *after* it, which at
`SERIALIZABLE` - where the posting path now runs - raises `40001` at the insert rather than quietly
doing nothing, so the follow-up locking read never runs at all. The third outcome is not a defect
and not a lost replay: the retry re-opens the transaction with a fresh snapshot that includes the
winner, and the replay happens there. `INV-7` is what survives either way, because at most one
journal is ever committed for a source reference.

The replay case is proved against PostgreSQL by `PostingIdempotencyIntegrationTests`, sequentially
and under two racing callers. The rolled-back case rests on the semantics of `ON CONFLICT DO
NOTHING` against an uncommitted row and is **not** covered by a test yet: proving it needs the
first transaction held open until the second is demonstrably blocked, which the current latch does
not guarantee.

A duplicate is answered by comparing `request_fingerprint` - a SHA-256 over the source triple, the
event, the dates, the currency and the sorted legs, never the raw payload:

| Same reference and … | Outcome |
| --- | --- |
| Same fingerprint, request `POSTED` | The existing receipt is returned; nothing is written |
| Different fingerprint | `accounting.posting_request_conflict`; nothing is written |
| Request not `POSTED` | `IllegalStateException` - unrepresentable, because the status flips in the same transaction as the journal |

The HTTP `Idempotency-Key` handled by `api_idempotency_record` is a different layer with a
different key, and the two are never conflated.

Lineage is queryable in both directions through `PostingLineageService`, gated on `journal.view`:
forward from a source module's reference or business entity to the request, journal and lines it
produced; backward from a journal to the request that caused it. The entity listing is keyset
paginated over the time-ordered request id and capped at the platform page-size ceiling. Every
answer stops at the descriptive `source_entity_id`: accounting holds no foreign key into a product
module (`INV-16`), so the last hop into the product's own records is the product module's to make.

## Related documents

- [Accounting foundation](accounting-foundation.md)
- [Accounting schema](../database/accounting-erd.md)
- [Financial transaction atomicity](financial-transaction-atomicity.md)
- [BIAN service landscape](bian-service-landscape.md)
