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

This record shipped with a stated forward dependency — the isolation raise landed first, with no
retry — and the branch that follows discharges it. The Spring Retry boundary, the
`accounting.posting_retries_exhausted` code and the two integration assertions that were weakened
for one branch are described below as what exists, not as what is coming. Following
[ADR 0024](0024-journal-line-append-guard-and-trigger-policy.md), the amendment is folded into this
record rather than written as a second one: a decision this short-lived would be read against the
protocol it belongs to either way.

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

**The stack merges as a unit, and the two assertions the isolation raise weakened are restored by
the retry.** Neither branch is independently deployable: the first raises the isolation with no
retry, which regresses same-tenant posting concurrency, and the second is the retry that pays for
it. `PostingIdempotencyIntegrationTests`' *"concurrent duplicates produce exactly one committed
posting"* is back to asserting that both callers hold the receipt of the one journal. It could not
hold on the first branch: measured, at `SERIALIZABLE` the claim's `INSERT … ON CONFLICT DO NOTHING`
against a row committed after this transaction's snapshot raises `40001` at
`ExecCheckTupleVisible` rather than quietly doing nothing, so the follow-up locking read never runs
and the loser aborts instead of replaying. The retry is what restores it, and the mechanism is
specific: a retried attempt opens a new transaction and therefore takes a **fresh snapshot** that
includes the winner's commit, which turns the conflicting row into the ordinary replay case — a row
committed *before* the snapshot — so the claim reports zero rows inserted and the locking read
finds it. L7 in `GlAccountPostingLockConcurrencyIntegrationTests` is restored the same way, back to
asserting that both postings commit with distinct journal numbers, for the `reference_sequence`
reason above. The same fresh snapshot also restores the *error message* of the posting-versus-close
race: with no retry that race surfaced as a raw serialization failure, because the transaction
aborts at the locking read instead of observing the close and rejecting; with one, the next attempt
reads the committed `CLOSED` and the caller gets `accounting.fiscal_period_closed`. The retry is
therefore load-bearing for what the caller is told, not only for throughput.

**The retry boundary is `PostingTransactionBoundary.execute`, and it carries no transaction advice,
so the nesting is structural rather than a dependence on advisor precedence.** `@Retryable` and
`@Recover` sit on the boundary bean in `application.ledger`; the transaction is opened one call
later, on `SerializablePostingTransaction`, and the two annotations never appear on one method.
Calls arriving through the exposed `PostingTransactions.execute` are retried too, because that
class delegates to the boundary bean through its proxy — one advisor, one budget, no second
annotation for a product module to get wrong. The structure is what makes it correct, and the
reason is measured: a serialization failure can be raised by `COMMIT` itself, as
*"could not serialize access due to read/write dependencies among transactions — Reason code:
Canceled on identification as a pivot, during commit attempt"*. That exception is thrown **by** the
transaction interceptor, so only strictly outer advice can ever see it, and it never reaches jOOQ's
`ExecuteListener`, so no statement-level translator sees it either. Co-locating the annotations and
pinning `@EnableRetry`'s order numerically would be correct today and one `@Order` attribute away
from silently never retrying the commit-time case — which is the case the boundary exists for.
`@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)` is still written, on
`AccountingModuleConfiguration`, so the intent survives a refactor that puts the annotations back
together; nothing here depends on it. The separation has a second, operational consequence: the
transaction has committed or rolled back and its pooled connection has been **returned** before any
backoff sleeps, which matters because HikariCP is at its unconfigured default of ten connections
and a backoff that pinned one would be an outage rather than a delay. The annotation is
`org.springframework.retry.annotation.Retryable`, not the `org.springframework.resilience`
annotation that spring-context also ships: the two differ only by package, and only the former has
`@Recover`, which is the whole of translating an exhausted budget into a named code. The retry
lives on the internal boundary rather than on the exposed `PostingTransactions` for the same reason
the generic seam does — the exposed package must stay free of type variables, and a retry budget is
an internal detail the module's public contract gains nothing by naming.

**One SQLSTATE arrives as two Spring exception classes, so the retry matches on their supertype.**
`retryFor` is `ConcurrencyFailureException`. A statement-level `40001` travels jOOQ's
`DefaultExceptionTranslatorExecuteListener` → `SQLErrorCodeSQLExceptionTranslator("PostgreSQL")` →
`CannotSerializeTransactionException`. A commit-time `40001` never reaches jOOQ at all:
`JdbcTransactionManager.translateException` handles it, and in Spring 7 the default translator is
`SQLExceptionSubclassTranslator` unless a user `sql-error-codes.xml` exists — this repository has
none — whose `instanceof` chain misses pgjdbc's `PSQLException` and falls through to
`SQLStateSQLExceptionTranslator` → `CannotAcquireLockException`. Retrying on
`CannotSerializeTransactionException` alone would therefore compile, pass review, and silently miss
every SSI pivot. `ConcurrencyFailureException` is the only common supertype, and it also covers
`40P01`'s `DeadlockLoserDataAccessException`. `noRetryFor` excludes
`OptimisticLockingFailureException`, which extends it but is a different decision: a row-version
conflict is a stale write, not a serialization race, and re-running it would just lose again.
Accounting's own `ConflictException` is an `ApplicationException`, outside this hierarchy
altogether, so `accounting.fiscal_period_closed` propagates un-retried with no exclusion needed. A
classification test pins both translator outcomes, so a Spring upgrade that changes either one
breaks a test rather than the retry.

**The retry budget is sized by argument, not by measurement, and the deferred throughput issue owns
it.** `PostingRetryPolicy` fixes five attempts and a uniform-random backoff between 20 and 250
milliseconds — `delay` plus `maxDelay` with no `multiplier`, which is what selects Spring Retry's
uniform policy rather than an exponential one. The shape follows from where the aborts come from:
the dominant source is the per-tenant `reference_sequence` row every posting updates, so losers
arrive in correlated bursts, and unjittered retries would move in lockstep and collide again. Five
attempts because that counter is a genuine capacity ceiling rather than a transient: a tenant whose
posting arrival rate exceeds what five attempts absorb should get a fast, named `409` an operator
can alert on, not an unbounded loop holding a request thread. The worst-case un-jittered sleep is
about one second, far inside the five-minute idempotency in-progress timeout, so a retrying request
can never have its `Idempotency-Key` reclaimed as stale underneath it. None of those numbers is a
measurement, and nobody has bounded the probability that N concurrent postings exhaust together;
sizing them belongs with the throughput and lock-wait work in **issue #119**. When the
budget is spent the boundary throws `ConflictException(accounting.posting_retries_exhausted)`,
which reaches `409` through the existing `ApplicationException` path with no edit to
`ApiExceptionHandler`. A sustained rate of that code is a capacity signal for the counter, not a
defect; a posting racing a close is answered with `accounting.fiscal_period_closed` instead.

**A retried backdated posting leaves up to five independent `journal.post_prior_period` audit rows,
and an audit query counting authorities must group rather than count.** `recordPriorPeriodAuthority`
writes with `recordIndependently` (`REQUIRES_NEW`) precisely so the row survives the rollback of a
posting that fails *after* the gate, and a retry re-runs the gate, so one logical backdated posting
can leave one row per attempt — bounded by `PostingRetryPolicy.MAX_ATTEMPTS`. Every one of those
rows is true. The documented meaning of the row is *authority was exercised on a posting the ledger
accepted as admissible*, not *a journal was committed*, and each attempt independently located,
locked and validated the covering period before reaching it. The operational consequence is that
counting rows over-counts contended backdated postings: group by
`(actorId, tenantId, metadata.sourceModule, metadata.sourceReference)` to count authorities
exercised, and the metadata carries `sourceModule` and `sourceReference` for exactly that.

That key is the `INV-7` idempotency identity, `(organisation_id, source_module, source_reference)`,
and nothing weaker will do. An earlier revision of this record named
`(actorId, tenantId, resourceId, metadata.postingDate)`, which does not discriminate: two
*different* backdated postings by one operator, into one period, on one posting date produce
identical tuples — and a batch correction run by a single operator is exactly that shape. Grouping
on it therefore merges separate exercises of break-glass authority, which is the opposite of what
the record is for. The source reference does discriminate, because it is "one logical posting"
expressed as data: identical on every attempt of one posting, different for any other.

The posting request's id looks like the obvious key and is wrong. `posting_request.id` is
`UUID PRIMARY KEY DEFAULT uuidv7()`, and a retry rolls its claim back and re-inserts, so every
attempt is allocated a fresh id. Keying on it would put each attempt in its own group — the
over-counting the grouping exists to prevent, arrived at from the other side, and worse than the
weak key because a reader has no reason to doubt it.

Recording the source reference also buys something with no connection to retries at all: the row now
answers *which posting this authority was exercised for*. Previously it could not — an auditor
holding a `journal.post_prior_period` row could narrow it to a fiscal period and a posting date and
no further, with no way to reach the journal or the originating business event. No attempt number is
recorded and none should be added casually — moving the write after commit would destroy the
property it exists for, and a thread-local attempt counter would introduce ambient state into an
application layer that has none, to improve an audit query. An integration test asserts the bound
and, since the key is only worth documenting if it discriminates, asserts that two distinct
backdated postings sharing an actor, a tenant, a period and a posting date stay two authorities.

**A retry releases the covering `FOR SHARE` lock, so a close queued behind an in-flight posting can
now win, and the user-visible outcome of an ordinary close changes.** ADR 0022's operational trade
is that many postings proceed concurrently while a close waits for them; the retry punches a hole
in it, and an operator told that closes queue behind postings will be surprised by both halves of
it. Before, a close blocked until every in-flight posting committed, and its `lock_timeout` — ten
seconds by default — either outlasted them or produced `accounting.fiscal_period_lock_timeout`. Now
an aborting posting **rolls back**, releasing its `FOR SHARE` lock, and then sleeps 20 to 250
milliseconds before its next attempt. A close waiting on that lock acquires it in the gap and
commits; the posting's retry, at a fresh snapshot, finds the period `CLOSED`. So a close that used
to time out now succeeds, and a posting that was going to succeed comes back
`accounting.fiscal_period_closed` — the same request, flipped from a receipt to a rejection by a
race it did not lose the first time. Both outcomes are correct and neither can corrupt the ledger:
the posting is refused before it writes, and no journal lands in a period closed before it
committed. But the change is real and it is user-visible on the ordinary path, not only under
pathological load, which is why it is written down here rather than left to a support ticket.

**The retry re-runs the whole enclosing use case, and that is correct only because every effect on
this path is transactional.** The boundary is lambda-shaped, so a product module's own mutation
runs inside the transaction the boundary opened and is re-run with it (`INV-12`); a facade that
retried only the posting would re-run one third of a unit of work whose other two thirds had
already rolled back. That is safe exactly as long as nothing on the path escapes the transaction,
and the claim was checked against the code rather than assumed: across
`com.finaxis.platform.accounting` there is no `registerSynchronization`, no
`@TransactionalEventListener`, no after-commit hook, no JobRunr enqueue, no `RabbitTemplate` or
other AMQP use and no outbox write — the only textual matches are the boundary's own KDoc saying it
must stay that way. Accounting's lifecycles declare no `eventFactories` at all, so nothing here
externalizes a domain event even through the outbox. The one deliberate non-transactional effect is
the prior-period audit row above, whose duplication is bounded, true and documented. **Adding any
other non-transactional effect inside the boundary lambda re-opens this design and requires
amending this record** — a JobRunr enqueue, a broker publish, a `REQUIRES_NEW` write or an
after-commit hook would each happen more than once for a contended posting, and nothing in the
build would say so.

**`AccountingBoundaryRuleTests`' "no broker or background job may sit in the posting critical path"
is not violated by the retry.** That rule forbids `org.springframework.amqp..`,
`io.namastack.outbox..` and `org.jobrunr..` inside accounting, and spring-retry is none of them:
the retry re-runs the same synchronous transaction on the caller's own thread, introduces no queue,
no scheduler and no second thread, and the caller's request is still answered by the call that made
it. What it adds is latency on a contended posting, bounded by the budget above. A companion rule
pins `PostingTransactionBoundary` as the only `@Retryable` class in accounting, so a second,
differently-configured retry boundary cannot appear unnoticed and quietly acquire a different
budget for the same failure.

**`FinancialTransactionAtomicityFixture` needs no new probe.** No new durable write is added by
this change. The isolation level, the collapsed statement and the guard call all change how an
existing write behaves under contention; none of them creates an effect that could commit or roll
back separately from the journal. The fixture's probe set is unchanged because the set of durable
effects is unchanged.

**The posting transaction still carries no `lock_timeout`, and a retry attempt can block rather
than fail fast.** A queued exclusive close makes new postings wait, because PostgreSQL conflicts an
incoming request against the *pending* queue as well as the granted set, so a posting that arrives
behind a waiting close waits for the close. The retry sitting above it multiplies that wait by the
attempt count: an attempt that blocks rather than failing fast spends the whole wait before it can
even lose, and five of them can do so in sequence. A bound was considered and added in neither
branch of this stack: nobody can yet size it, a five-second bound would break
`FiscalPeriodConcurrencyIntegrationTests` and
`GlAccountPostingLockConcurrencyIntegrationTests` (both hold locks for twenty seconds), and it
would silently make 55P03 retryable, since `CannotAcquireLockException` is a
`ConcurrencyFailureException`. Sizing it belongs with the throughput and lock-wait measurement in
**issue #121**, and this paragraph is what that issue started from.

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
