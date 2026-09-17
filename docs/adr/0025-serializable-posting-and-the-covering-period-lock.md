# ADR 0025: Serializable Posting, And The Covering-Period Lock

## Status

Accepted

Date: 2026-09-15

Amends [ADR 0022](0022-accounting-date-and-fiscal-period-concurrency.md). Not a supersession:
0022's fiscal-period date semantics, `FOR SHARE`/`FOR UPDATE` strengths, advisory-lock namespacing,
maker-checker-on-reopen and `lock_timeout` decisions all still stand. What changes is the isolation
the posting path runs at and the mechanism the posting-versus-close guarantee rests on.

Also amends [ADR 0023](0023-posting-idempotency-and-account-locking.md), whose posting lock chain
gains one class: the `organisation` row `FOR SHARE` the engine takes immediately after the
tenant-currency advisory lock. 0023's claim ordering, fingerprint composition and account locking
are untouched.

## Context

Issue #108 asks for two things: that the posting path run at `SERIALIZABLE`, and that the
fiscal-period lock and the status read that decides on it become one statement. Both are right.
The reasons the issue gives for them are, in three specific places, not what PostgreSQL does, and
an ADR that repeated them would record a mechanism this repository does not have. Everything below
was re-measured against the pinned `postgres:18.4`, including — especially — the results that
contradict the issue.

The path is short and its shape is what makes the isolation level bite. `PostingEngine.post` runs
inside the **caller's** transaction (`Propagation.MANDATORY` on `PostingService`), reads the
tenant's functional currency, resolves the posting date, locks the covering fiscal period, claims
the source reference with `INSERT … ON CONFLICT DO NOTHING`, allocates a gapless number from the
tenant's single `reference_sequence` row, then writes the header and the lines and re-reads them.
Four of those steps behave differently above READ COMMITTED, and three of the four differences are
not the ones the issue anticipates.

**The protocol as it stands does not fail open above READ COMMITTED.** Issue #108 says that above
READ COMMITTED the post-lock status read "returns the transaction's original snapshot and the
protocol silently stops working". It does not. `PostgresRowLock.lockForShare` issues
`select 1 … for share`, and that statement is itself a locking read: it aborts before the stale
second statement is ever issued. Measured, with the transaction's snapshot already pinned by an
earlier read and a close committed in between:

    read committed   select 1 from p where id = 1 and org = 7 for share;  ->  1 row
    repeatable read  select 1 from p where id = 1 and org = 7 for share;
                     ERROR:  could not serialize access due to concurrent update
    serializable     select 1 from p where id = 1 and org = 7 for share;
                     ERROR:  could not serialize access due to concurrent update

So the protocol is *noisy*-wrong above READ COMMITTED, not silently-wrong. This distinction is not
pedantry. ADR 0022's own "note the mechanism precisely" paragraph exists because an earlier draft
of that record named the wrong mechanism, and it says so in the text. Justifying this change as
"it fixes a silent stale read" would repeat, in the record that amends 0022, the exact defect 0022
wrote down about itself. The honest justification is narrower and better: the protocol is correct
at READ COMMITTED for one reason (a fresh per-statement snapshot behind the lock) and correct above
it for a completely unrelated reason (the lock statement aborts), neither of which was written
down, and no test can tell that shape apart from a genuinely broken one. One statement means one
mechanism to reason about.

**Collapsing the lock and the read does not restore `EvalPlanQual` above READ COMMITTED.** The
issue proposes the collapse "so `EvalPlanQual` re-reads the locked row and the guarantee comes from
the lock rather than from per-statement snapshots". `EvalPlanQual` is a READ COMMITTED behaviour
only. Measured on the five-column form the adapter now emits, with a concurrent close committed
after this transaction's snapshot:

    read committed   1 | 7 | 2026-08-01 | 2026-08-31 | CLOSED   (EvalPlanQual re-read, real)
    repeatable read  ERROR:  could not serialize access due to concurrent update
    serializable     ERROR:  could not serialize access due to concurrent update

The same statement, against a concurrent change that shrinks `end_date` past the posting date,
returns **zero rows** at READ COMMITTED — `EvalPlanQual` re-evaluates the quals, not merely the
projection — and `40001` above it. Both READ COMMITTED outcomes are safe: `CLOSED` is refused by
`requireOpen`, zero rows by `requireCoveringPeriod`. Above READ COMMITTED the guarantee arrives as
**lock-or-abort, never as a re-read**. The collapse is still the right change; the mechanism has to
be stated as what it is, or the next author reasons from a re-read that will not happen.

**`40001` is the ordinary outcome of same-tenant posting concurrency, not a rare anomaly.** Every
posting in a tenant updates one row — the tenant's `JOURNAL` counter in `reference_sequence` —
because the entry number is gapless. Measured, two concurrent `SERIALIZABLE` transactions each
running the allocator's statement shape:

    A  update seq set next_value = next_value + 1 where org = 7 returning next_value;  ->  1
    B  update seq set next_value = next_value + 1 where org = 7 returning next_value;
       ERROR:  could not serialize access due to concurrent update

That is every overlapping pair of postings in a tenant, whether or not they touch the same
accounts, the same period or the same anything. At READ COMMITTED the same pair *blocks* and then
proceeds; the change is from "wait, then proceed" to "abort, then retry", and the retry is
therefore the tenant's throughput mechanism rather than a safety net. Two in-transaction
mitigations were measured and neither works: allocating the number as the transaction's first
statement still aborts (`ExecUpdate`), and taking a per-tenant `pg_advisory_xact_lock` first still
aborts, because the advisory-lock `SELECT` is itself a snapshot-taking statement — B's snapshot is
fixed *before* it blocks. At REPEATABLE READ and above, waiting is precisely what guarantees the
waiter's snapshot is stale; blocking and snapshot staleness are the same event.

**One worry that nearly became a constraint here is stale, and is recorded so it is not
reintroduced.** An earlier review held that the posting path must avoid `pg_export_snapshot()`
because it force-assigns a top-level transaction id. It has not done that since PostgreSQL 10
(*"Don't force-assign transaction id when exporting a snapshot"*); it exports the virtual xid.
Independently, nothing on the posting path reaches it: `ProofSnapshot.currentSnapshotId` is
injected only into `ControlAccountReconciliationService`, and the posting path calls
`requireStableSnapshot` and nothing else. XID assignment timing is identical at both levels — the
first write either way is the claim `INSERT`. What `SERIALIZABLE` actually adds is SIREAD
predicate-lock tracking, which is a shared-memory cost, not an XID cost. The only true statement is
that posting has no use for an exported snapshot.

## Decision

**The posting path runs at `SERIALIZABLE`, declared in exactly one place.** A single
`SerializablePostingTransaction` bean carries the only `@Transactional(isolation = SERIALIZABLE)`
on any write path in the repository, and product modules enter through a non-transactional
`PostingTransactions` in `accounting.application.posting` — the module's one non-domain
`@NamedInterface` package, so the call is legal under Modulith verification. Spring ships
`validateExistingTransaction = false`, so a declared isolation is dropped without a word when the
annotated method merely joins an already-open transaction; one opener is the only shape that
survives that, and `PostingEngine.post` refuses any transaction weaker than `SERIALIZABLE` with
`accounting.snapshot_isolation_unavailable` because a `MANDATORY` method has no isolation of its
own to declare. An ArchUnit rule keeps the "exactly one place" true.

**The generic seam is module-internal, and that is a framework constraint rather than taste.**
`PostingTransactions` names its types: it takes a non-generic `PostingUnitOfWork` and returns a
`PostingReceipt`. The generic `PostingTransactionBoundary.execute`, which accounting's own write
paths use because they return several different things, lives in `application.ledger` where the
module does not expose it. The first draft put the generic method in the exposed package and it
failed on its first call, in `FiscalPeriodConcurrencyIntegrationTests`, with
`NullPointerException: Cannot invoke "java.lang.Class.getTypeName()" because "type" is null`.
Spring Modulith advises every type a module exposes with `ModuleEntryInterceptor`, which renders
the invoked signature to name its observation; `DefaultObservedModule.render` calls
`FormattableType.of(resolvableType.resolve())`, and `ResolvableType.resolve()` answers `null` for
an unresolvable type variable. It is a defect in Spring Modulith 2.1.0 rather than in the method,
and it would have reached production rather than only the tests, because
`spring-boot-starter-opentelemetry` is a runtime dependency and the interceptor is live wherever
tracing is. `AccountingBoundaryRuleTests` now fails the build if a type variable reappears on an
exposed accounting bean, since nothing else would catch it until the first call in a running
context.

**SERIALIZABLE does not supply the posting-versus-close guarantee, and the `FOR SHARE` row lock
must never be removed on the strength of the isolation level.** This is the most dangerous thing a
reader could take from this record, so it is stated before anything else it might be confused with.
Measured twice: a `SERIALIZABLE` posting that reads the period row *without* `FOR SHARE` committed
its journal into a period a close had already closed, and did so whether the close ran at READ
COMMITTED or at `SERIALIZABLE`. Both runs:

    journals_committed = 1
    period_status      = CLOSED
    (no error raised on either side)

The reason is structural rather than a quirk of the harness. There is exactly one rw-dependency
edge — the posting reads the period, the close writes it — and the close reads nothing the posting
writes, so no dangerous cycle forms and *"posting, then close"* is a perfectly valid serial order.
SSI guarantees that some serial order explains the outcome; it does not guarantee that the order is
the one wall-clock time observed. *"A journal never lands in a period closed before it committed"*
is a **linearizability** requirement, not a serializability one, and **no isolation level supplies
it**. The explicit `FOR SHARE` lock is the entire guarantee, at every isolation level, and it was
the entire guarantee before this change too. A future author who deletes it because "we are
serializable now" reintroduces precisely the bug ADR 0022 exists to prevent.

**The fiscal-period lock and the status read become one statement, and the unlocked-lookup shape is
deleted rather than deprecated.** `FiscalPeriodStateStore.lockForPosting(key)` is replaced by
`lockCoveringForPosting(organisationId, postingDate)`, which carries the tenant predicate, the
inclusive date-range predicate and `FOR SHARE` in one `SELECT`; `lockForStateChange(key)` keeps its
signature and becomes the same shape with `FOR UPDATE`. The boolean-returning `PostgresRowLock`
primitive is deleted outright, because a locking primitive that returns a boolean is exactly what
makes the split representable — ADR 0022 admitted the convention was not forbidden by the types,
and this makes the mistake unwriteable instead of reviewable.
`ex_accounting_fiscal_period_no_overlap` guarantees at most one matching row, so a single
`fetchOne` is total. Note what this does *not* claim: it does not make the status read fresh above
READ COMMITTED. It makes the outcome one of
two — the locked row's committed state, or `40001` — with no third outcome in which a stale status
is labelled as the covering period's.

**The gapless counter stays inside the posting transaction, deliberately.** Moving the allocation
out would remove the `40001` measured above, and would break gaplessness: the property depends on a
rolled-back posting never having committed its increment. Serialising postings per tenant *outside*
the transaction would also remove it, at the price of a distributed lock on the hottest path. The
retry is the chosen cost, and it is chosen with the abort rate known — for overlapping same-tenant
postings it approaches one hundred percent, not "occasional". Any future throughput measurement on
this path must report per-tenant abort rate, not aggregate throughput, or it will measure nothing.

**The close path stays at READ COMMITTED.** `lockForStateChange` gains the collapsed shape but no
isolation change, and at READ COMMITTED the collapsed statement is strictly equivalent for a close:
`EvalPlanQual` hands the second close the first's committed `CLOSED`, which is what
`FiscalPeriodConcurrencyIntegrationTests` S4 asserts. Raising the close was considered and rejected
on evidence: it breaks S4, `FiscalPeriodLifecycleService` catches only `CannotAcquireLockException`
(55P03) and would not catch a `40001`, and — per the measurement above — SSI does not catch the
posting-versus-close anomaly even when both sides are serializable, so the raise buys nothing it
would cost for. The consequence is that one adapter method now serves a serializable reader and a
read-committed writer, which is written down here because it is the kind of asymmetry that is
otherwise discovered.

**`requireFunctionalCurrencyUnchanged`'s post-lock read becomes a *locking* read, and that is what
closes the window this raise would otherwise have opened.** `PostingEngine.post` reads the
functional currency before `postNew` takes `pg_advisory_xact_lock_shared`, and re-reads it under
the lock. At READ COMMITTED the re-read is fresh and the comparison is a real detector. At
`SERIALIZABLE` both reads come from one snapshot, so a plain re-read always agrees and a
`base_currency` change committed in between is invisible. Four measurements, with the snapshot
pinned by earlier work as it always is in production:

    (a) current shape, read committed   read KES, USD commits, re-read USD  ->  check fires
    (b) current shape, serializable     read KES, USD commits, re-read KES  ->  commits in KES
    (c) lock first, plain re-read, ser  read KES, USD commits, re-read KES  ->  commits in KES
    (d) lock first, FOR SHARE, ser      read KES, USD commits, re-read
                                        ERROR:  could not serialize access due to concurrent update

(b) is the regression the raise brings with it, and it is why the raise could not ship without the
repair recorded here.

**How reachable (b) actually is, stated plainly, because the measurements above make it look worse
than it is.** No production flow can move `organisation.base_currency_code` while a tenant is
postable. The column has exactly one writer, the draft amendment in
`JooqOrganisationBranchProvisioningStore`, and `OrganisationProvisioningService.amendDraft` refuses
any organisation that is not `DRAFT` - which is not a postable state. The `base_currency` *setting*
change, which is the flow an operator actually reaches, writes `organisation_setting` and never
touches the column the engine reads. So the interleaving above is produced in the regression test by
a direct `UPDATE`, not by any path a user can take today, and `requireFunctionalCurrencyUnchanged`
was already defence in depth rather than a live detector.

That is an argument about severity, not about whether to fix it, and the fix stands for three
reasons. The raise turned a defensive check into one that *cannot* fire, which is worse than one
that fires rarely: a reader sees a comparison and believes something is guarded. The cost is one
locking read on a path that already takes four lock classes. And #122 proposes per-account
currencies, which is exactly the kind of work that adds a writer to this column against an active
tenant - at which point an unreachable window becomes a reachable one, and the guard wants to
already be there rather than be remembered.

(c) is why the cheaper repair was rejected: hoisting the advisory lock above
the first read — the obvious move, and one of the reviewed designs — does **not** work, because an
advisory lock does not participate in MVCC and so brings neither `EvalPlanQual` nor a `40001` with
it. Only (d), a *locking* read of the organisation row, closes it. So the post-lock read is now
`AccountingTenantLookup.functionalCurrencyForPosting`, a new method on that cross-module port,
implemented by `LifecycleAccountingTenantAdapter` over `OrganisationBranchProvisioningPorts`'
`lockBaseCurrencyCode` - the existing `baseCurrencyCode` select with `.forShare()`, asserting an
active transaction first as every locking adapter in that package does.
`AccountingTenantLookup.functionalCurrencyOf` is unchanged and stays non-locking for its four
read-side callers — `ControlAccountReconciliationService`, `PostingRuleService`,
`RuleBackedPostingLegResolver`, and `PostingEngine.post`'s own pre-claim read — with its KDoc
warning that it must never decide a posting's currency under the lock, the same shape as the
warning `FiscalPeriodStateStore.findCovering` carries.

**The pre-claim read stays non-locking, deliberately.** The fingerprint is computed from it; if the
row changed after this transaction's snapshot, the post-lock `FOR SHARE` raises `40001`, the
transaction aborts, and the retry re-fingerprints against the currency that won. Making the
pre-claim read locking instead would hold the `organisation` row for the whole transaction
**including a replay**, and a replay deliberately takes no locks at all — that is the design of
`PostingEngine.post` and the reason ADR 0023 moved the claim ahead of validation in the first place.

**The comparison, and `PostingErrorCodes.FUNCTIONAL_CURRENCY_CHANGED` with it, are kept — and they
can no longer fire at `SERIALIZABLE`.** Both values come from one snapshot, and a change committed
after that snapshot aborts at the locking read before the comparison is reached, so the code is not
raised on this path in normal operation. The comparison is kept because it states the invariant
legibly where the invariant applies, and because it would fire if this path ever ran below
`SERIALIZABLE` — which `SnapshotIsolationGuard` refuses outright with
`accounting.snapshot_isolation_unavailable` rather than permits. The distinction matters to the
next reader in one specific way: the locking read is the mechanism and the comparison is the
written statement of what the mechanism buys, so deleting the read in the belief that the
comparison is doing the work reopens (b).

**The row lock does not make the advisory lock redundant, which is the wrong conclusion nearest to
hand.** They close different halves of the same check-then-write. A posting reads the currency and
writes a journal; a currency change reads *"has this tenant posted"* and writes the setting. The
`FOR SHARE` closes the posting's read — a change committed after the posting's snapshot aborts it.
The advisory lock closes the change's read — taken exclusively immediately before that question,
it makes the change wait for first postings in flight, which no MVCC mechanism does for it, because
the posting's `journal_entry` insert and the change's `organisation_setting` write share no row.
Remove either and one half of the window reopens. `FunctionalCurrencyLock`'s KDoc is the full
account of the half the advisory lock closes.

**This lands in this pull request rather than in a follow-up, because the raise is what opens the
window.** An earlier revision of this record deferred the repair to issue #121, on the ground that a
cross-module port change plus an ADR 0023 lock-ordering amendment is out of scope for an issue about
isolation. That had the causality backwards, as review pointed out and the repository owner
accepted: the isolation raise is the *cause* of (b), not a change adjacent to it, so shipping the
raise alone would have shipped the regression in the same commit that silenced the only detector
able to notice it — and a branch that asserts something untrue of itself is precisely what this
repository's stacking rule forbids. Issue #121 keeps the analysis it collected and remains the
pointer to it; it no longer owns a fix. The lock-ordering half of the change is recorded in
[ADR 0023](0023-posting-idempotency-and-account-locking.md), which also states why the new class
introduces no cycle and what a concurrent `organisation`-row write other than a currency change now
costs a posting.

**ADR 0022's rejection of a global `SERIALIZABLE` escalation is re-grounded, not reversed.** Issue
#35 rejected escalating the *whole application*, and that rejection stands: a global default would
drag `iam`, `lifecycle` and `notifications` into SIREAD tracking and `40001` risk to buy accounting
a property none of them need, with no retry anywhere. What #108 adopts is a **path-scoped**
escalation, declared on one bean, enforced at the engine, and pinned by an ArchUnit rule that
forbids a non-default isolation anywhere else in `com.finaxis.platform.accounting..`. The sentence
in 0022 was about the blast radius, not about the level, and the level was never the objection.

## Consequences

**This branch raises the isolation with no retry, and it is not independently deployable.** The
retry boundary and the `accounting.posting_retries_exhausted` code arrive in the next branch of
this stack; here `PostingTransactionBoundary.execute` delegates straight through. Two integration
assertions are deliberately weaker on this branch as a result. `PostingIdempotencyIntegrationTests`'
*"concurrent duplicates produce exactly one committed posting"* cannot hold: measured, at
`SERIALIZABLE` the claim's `INSERT … ON CONFLICT DO NOTHING` against a row committed after this
transaction's snapshot raises `40001` at `ExecCheckTupleVisible` rather than quietly doing nothing,
so the follow-up locking read never runs and the loser aborts instead of replaying. The ordinary
replay — a conflicting row committed *before* the snapshot — still works, which is why the retry's
fresh snapshot restores the original assertion in the next branch. L7 in
`GlAccountPostingLockConcurrencyIntegrationTests` relaxes the same way, for the
`reference_sequence` reason above. And the user-visible outcome of the posting-versus-close race
changes shape here: without the retry, that
race surfaces as a raw serialization failure rather than as `accounting.fiscal_period_closed`,
because the transaction aborts at the locking read instead of observing the close and rejecting.
The functional-currency window closes the same way and inherits the same dependency: the locking
read of the `organisation` row aborts with `40001`, and it is the next branch's retry that turns
that abort into a posting fingerprinted against the currency that won. The retry is therefore
load-bearing for the *error message*, not only for throughput. This follows
the precedent ADR 0024 set for its #54 forward dependency: the stack merges as a unit, and saying
so out loud is required by the rule that a branch must not assert something untrue of itself.

**`FinancialTransactionAtomicityFixture` needs no new probe.** No new durable write is added by
this change. The isolation level, the collapsed statement and the guard call all change how an
existing write behaves under contention; none of them creates an effect that could commit or roll
back separately from the journal. The fixture's probe set is unchanged because the set of durable
effects is unchanged.

**The posting transaction still carries no `lock_timeout`, and a retry attempt can block rather
than fail fast.** A queued exclusive close makes new postings wait, because PostgreSQL conflicts an
incoming request against the *pending* queue as well as the granted set, so a posting that arrives
behind a waiting close waits for the close. With a retry above it, that wait is multiplied by the
attempt count. A bound was considered and not added, in this branch or the next: nobody can yet
size it, a five-second bound would break `FiscalPeriodConcurrencyIntegrationTests` and
`GlAccountPostingLockConcurrencyIntegrationTests` (both hold locks for twenty seconds), and it
would silently make 55P03 retryable, since `CannotAcquireLockException` is a
`ConcurrencyFailureException`. Sizing it belongs with the throughput and lock-wait measurement in
**a follow-up issue**, and this paragraph is what that issue starts from.

**Issue #52's accounting controllers must call `PostingTransactions`, with the idempotency
executor outside it.** The boundary refuses an already-open transaction outright, because entering
it from inside one means the transaction it would retry is not its own to re-run. That refusal
lands squarely on the shape #52 would otherwise reach for: `IdempotencyExecutor.execute` is
`@Transactional`, so nesting the boundary under `@IdempotentMutation` in its current form would
open the outer transaction first, the boundary would join it, `SERIALIZABLE` would be silently
dropped, and the retry would sit inside the transaction it retries. Worse than dropping it: a retry
below the executor re-enters `store.acquire`, sees its own uncommitted `IN_PROGRESS` row, finds it
not stale against the five-minute in-progress timeout, and answers `409` rather than retrying.
Failing loudly at the boundary is cheap; discovering that in production is not.

**Three documents described the protocol in terms of READ COMMITTED and all three are corrected in
this change.** ADR 0022 itself, the dates-and-periods guide, and the module boundary. ADR 0024's
condition five — *a guard's limits are written down wherever the guarantee is claimed* — also
applies to its own residual-window paragraph and to the twin of it in the accounting schema: both
reasoned *"under READ COMMITTED"* about a posting path that no longer runs there, and both are
corrected in the same commit. A statement about isolation left true in one document and false in
another is the one a future author will quote back.
