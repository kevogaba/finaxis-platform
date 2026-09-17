# ADR 0022: Accounting Date Semantics And Fiscal-Period Posting Concurrency

## Status

Accepted

Date: 2026-08-31

Amended by [ADR 0025](0025-serializable-posting-and-the-covering-period-lock.md). Every decision
below still stands except two, and both are marked where they appear: the protocol is now one
statement rather than an unlocked lookup followed by a lock, and the posting path now runs at
`SERIALIZABLE` rather than at READ COMMITTED. What does **not** change is the `FOR SHARE` row lock,
which 0025 measured to be the entire posting-versus-close guarantee at every isolation level.

## Context

Issue #35 makes accounting-date and fiscal-period behaviour deterministic under concurrent posting
and close activity, before the first accounting table exists. Getting this wrong is expensive in a
specific way: a journal committed into a period an operator believes is closed corrupts a
statutory report, and nothing in the ledger records that it happened.

There is no `fiscal` concept in the repository yet. The nearest existing analogue is
`business_date` — a singleton row per organisation, guarded by optimistic `row_version`
compare-and-set rather than locks, with its status held as a plain string.

Locking prior art is thin and, importantly, unnamespaced. There are exactly two advisory-lock call
sites: a blocking `pg_advisory_xact_lock` serialising tenant-settings writers, and a bounded-wait
`pg_try_advisory_xact_lock` in the idempotency store. Both hash arbitrary text into the **same**
single-`bigint` key space via `hashtextextended(text, 0)`, so nothing structurally separates a
settings key from an idempotency key today. There are no `FOR UPDATE`/`FOR SHARE` statements
anywhere, and no explicit isolation levels — everything runs at PostgreSQL's default READ
COMMITTED. (That last was true when this ADR was written. Issue #91 has since made the
control-account proof `REPEATABLE READ`; see the consequences below for why that does not touch
this protocol. Issue #108 then raised the posting path itself to `SERIALIZABLE`, which does touch
it — see [ADR 0025](0025-serializable-posting-and-the-covering-period-lock.md) and the paragraph
recording it below.)

## Decision

**Row locks, not a per-period advisory lock.** `pg_advisory_xact_lock` is exclusive-only, so keying
it on the fiscal period would serialise *every posting in that period* behind one lock — a silent
throughput cliff that would only be discovered under load. A posting takes `SELECT … FOR SHARE` on
the period row; a close or reopen takes `SELECT … FOR UPDATE`. Many postings therefore proceed
concurrently while a close waits for them.

**`FOR SHARE`, not `FOR KEY SHARE`.** The weaker mode does not conflict with `FOR NO KEY UPDATE`,
which a plain `UPDATE` takes, so a future close path that forgot its explicit lock would slip
straight past a key-share lock. Scenario S5 exists specifically to prove a bare `UPDATE` still
blocks.

**The protocol is: one statement that locks the covering period and reads its columns.** (Amended
by ADR 0025; as originally recorded it was an unlocked lookup, then a lock, then a decision taken
on the status read under the lock.) The decision never uses the status from any unlocked read.
`FiscalPeriodStateStore.lockCoveringForPosting` carries the tenant predicate, the date-range
predicate and `FOR SHARE` in one `SELECT` and returns the columns of the row it locked, so there is
no second statement for the value to go stale between. As originally shipped the split was a
convention rather than an impossibility — `findCovering` also returns a status, so a caller could
decide from the unlocked read and then take the lock for nothing — and ADR 0025 removed the
boolean-returning primitive that made that shape writeable at all.

**The guarantee is lock-or-abort, and it does not depend on the isolation level.** (Amended by ADR
0025; this paragraph originally read *"this is correct only at READ COMMITTED, and that is
load-bearing rather than incidental"*.) At READ COMMITTED the locking statement takes a fresh
snapshot and `EvalPlanQual` re-evaluates the row and its quals, so it returns the latest committed
state or no row at all. Above READ COMMITTED the same statement raises `40001` instead. Neither
outcome is a stale status labelled as the covering period's, which is the only property the
protocol ever needed.

Note the mechanism precisely, because an earlier draft of this ADR named the wrong one, and because
naming the wrong one is the mistake ADR 0025 had to avoid repeating. As originally shipped,
`PostgresRowLock` selected `1` and returned a boolean and the status arrived from a separate
statement, so the guarantee rested on per-statement snapshots plus the lock holding writers off
between the two — not on `EvalPlanQual` re-reading the locked row, which would have been the
mechanism only if the lock and the read were one statement. Under ADR 0025 they **are** one
statement, so `EvalPlanQual` is the mechanism at READ COMMITTED; above it there is no re-read at
all and the statement aborts. What was never true, at any point, is that the split protocol failed
*silently* above READ COMMITTED: `select 1 … for share` is itself a locking read and aborts before
the stale second statement runs. `a posting locks at SERIALIZABLE while a close still locks at READ
COMMITTED` asserts both levels from inside a transaction that has actually taken the lock, so a
downgrade on either side breaks a named test; an explicit `@Transactional(isolation = ...)`
elsewhere in accounting is caught by the ArchUnit rule ADR 0025 adds. Global `SERIALIZABLE`
escalation is still rejected outright, per the issue — that rejection is about the blast radius of
a platform-wide default, and ADR 0025 adopts a path-scoped escalation on one bean instead, which is
a different question.

**Only the posting date selects a fiscal period.** Five values exist and their roles are fixed:
`recordedAt` is technical and drives nothing; `businessDate` is the tenant's controlled date, read
from the `business_date` table and never from a clock; `transactionDate` is the source module's
event date and is descriptive; `valueDate` carries the interest or float effect and may fall before
or after the posting date; **`postingDate`** alone selects the period.

**Posting rules.** A future posting date is rejected — there is no forward-dated general ledger. A
current-dated posting requires no extra permission. A backdated posting requires
`journal.post_prior_period` **and** an open covering period. A closed period rejects the posting
**regardless of permission**, because reopening is an explicit, audited operation and never an
implicit consequence of holding a code. A missing covering period rejects: periods are provisioned,
never conjured by a posting. Finally, a non-open business date rejects a *current-dated* posting
while still permitting a backdated one, so close-of-business does not deadlock corrections.

**The business date is read without a lock.** A single `business_date` row must never become the
ledger's serialisation point. The posting captures `postingDate` at the start of its transaction; a
concurrent `advance` may commit meanwhile, and the posting still commits with the date it captured.
That is correct: yesterday's period remains open until it is closed. The invariant the row lock
protects is narrower and stronger — *no journal is ever committed into a period the same
transaction observed as closed*.

**New advisory-lock domains use the two-`int4` form.** PostgreSQL keeps the single-`bigint` and
two-`int4` key spaces structurally separate, appearing in `pg_locks` with `objsubid` 1 and 2. This
was verified empirically against `postgres:18.4` rather than taken from documentation, and
`AdvisoryLockNamespaceIntegrationTests` keeps that verification executable. A new domain therefore
cannot collide with either existing call site by construction rather than by hash luck.
`AdvisoryLockNamespace` is the registry.

## Consequences

**The protocol no longer depends on the isolation level.** (Amended by ADR 0025; this consequence
originally read *"the protocol depends on READ COMMITTED"* and warned that raising the level would
silently break the posting-versus-close guarantee.) The guard is the single-statement lock-and-read,
and the `FOR SHARE` lock is the entire guarantee — measured, a serializable posting that reads the
period row *without* it commits into an already-closed period, whether the close runs at READ
COMMITTED or at `SERIALIZABLE`. That invariant is a linearizability requirement and no isolation
level supplies it. The named test still pins the level the posting path declares, and this ADR
together with 0025 is the explanation it points at.

**One path has since raised it, deliberately, and it is not this one.** Issue #91 made
`ControlAccountReconciliationService.run` `REPEATABLE READ`, because a proof compares two
aggregates that have to describe one instant and per-statement snapshots are exactly what makes
them describe two. That is the opposite requirement to the protocol above, and the two do not meet:
a proof takes no fiscal-period lock, performs no lock-then-re-read, and posts nothing — it reads
`journal_line` and asks a `SubledgerProofProvider`, then writes one evidence row. `a posting locks
at SERIALIZABLE while a close still locks at READ COMMITTED` still asserts the posting path's
isolation and still guards it. This is the review this ADR asked for, recorded rather than waved
through; what it concluded — **raising isolation on a path that locks a fiscal period and re-reads
it remains forbidden** — is withdrawn by ADR 0025 and replaced by the paragraph below. Raising it
on a read-only proof that does neither is what `INV-14` requires. See
`docs/architecture/accounting-module-boundary.md`.

**And the posting path has since raised it too, deliberately, which is what the paragraph above
forbade.** Issue #108 made the posting path `SERIALIZABLE`, declared on one
`SerializablePostingTransaction` bean and enforced at `PostingEngine.post`, at the same time as it
collapsed the lock and the status read into one statement. The prohibition above rested on the
premise that the post-lock re-read needs a fresh snapshot; with the lock and the read in one
statement there is no second read to go stale, and above READ COMMITTED the statement aborts with
`40001` rather than returning anything at all. Recorded here the way #91 was: the review happened,
the measurements are in
[ADR 0025](0025-serializable-posting-and-the-covering-period-lock.md), and the `FOR SHARE` lock is
untouched because it — not the isolation level — is what makes the guarantee.

**A long posting transaction delays a close.** `FOR SHARE` means a close waits for every in-flight
posting. That is the intended trade — a close that raced past an in-flight posting would be worse —
but it makes posting-transaction duration a latency budget for period close. Bound it in #41, and
set `lock_timeout` on the close path in #39. The close path stays at READ COMMITTED under ADR 0025,
deliberately; were it ever raised, it would gain a second failure mode on top of the 55P03 the
`lock_timeout` produces, because under SSI a close can also abort with `40001`, which
`FiscalPeriodLifecycleService`'s `CannotAcquireLockException` catch would not see.

**A 32-bit `objid` can collide within one advisory class.** The consequence is false sharing: two
unrelated keys serialise against each other. That costs throughput and never correctness, which is
the right way round.

**The two existing single-key advisory domains remain unnamespaced.** Tenant settings and
idempotency still share one space, so a settings key that happened to be a UUID string could in
principle collide with an idempotency key. Migrating them is deliberately out of scope here; it is
recorded so it is a known gap rather than an unexamined one.

**`SOFT_CLOSED` is deliberately absent** from `FiscalPeriodStatus`. The distinction it would draw
— postings blocked for ordinary users but open to a privileged few — is already expressed by
`CLOSED` plus `journal.post_prior_period`, so it adds a state without adding a capability. The
four states the enum does carry are the set
[the accounting schema](../database/accounting-erd.md) adopts and
`chk_accounting_fiscal_period_status` enforces.

**`PostingPeriodResolver` and `FiscalPeriodStateChangeGuard` became Spring beans in issue #36**,
in the same change as `JooqFiscalPeriodStateStore`. They could not be beans before it: with no
`FiscalPeriodStateStore` adapter behind them, registering either failed application-context
startup for the whole platform rather than only for accounting — which is how this was discovered.
A test pinned their absence until the adapter existed and now asserts their presence.

**The concurrency proof ran against a stand-in table until issue #36**, because
`accounting_fiscal_period` did not exist. Only the location of the status byte was substituted; the
lock statements, their strengths, the protocol order, the READ COMMITTED re-read, the transaction
manager, the pool and the database were always production. `V6` created the table, the stand-in was
deleted, and the compiler is what forced every scenario onto the real adapter. (*The READ COMMITTED
re-read* names the protocol as it stood before issue #108, which collapsed the lock and the read
into one statement and raised the posting path to `SERIALIZABLE`; the sentence is left as written
because it describes what that proof actually exercised at the time.)

**`updateStatus` carries the actor.** It populates `accounting_fiscal_period.updated_by` so the row
says who last moved it. That is a mirror for convenience: the authoritative record of a transition,
and the one the reopen actor-identity check reads, is `fiscal_period_transition_log.created_by`.

**The bound is reached through an application-owned port.** `TransactionLockBound` is declared in
`accounting/application` and implemented by the jOOQ `TransactionLockTimeout` component. An earlier
revision had `FiscalPeriodLifecycleService` import the adapter class directly, which pointed the
dependency the wrong way — an application service does not get to know that the bound is a
PostgreSQL `SET LOCAL`, any more than it knows a period lives in a jOOQ table. It already takes its
period storage and maker resolution as ports; this is the third.

**The close path is bounded by a transaction-scoped `lock_timeout`**, shipped by issue #39 and
configured by `finaxis.accounting.fiscal-period-close-lock-timeout` (10 seconds by default). A
close queues behind every in-flight posting into its period, which is the correct outcome;
unbounded, a long posting transaction delays it forever and the caller simply hangs. `SET LOCAL`
scopes the bound to the transaction, so it reverts at commit and no other statement on the pooled
connection inherits it. A close that cannot acquire in time fails with
`accounting.fiscal_period_lock_timeout` rather than hanging.

That translation is from **`CannotAcquireLockException`**, not `QueryTimeoutException`. PostgreSQL
raises `SQLSTATE 55P03` when `lock_timeout` expires, and Spring's PostgreSQL error codes list
`55P03` under `cannotAcquireLockCodes`; `QueryTimeoutException` is a *sibling* under
`TransientDataAccessException`, never a supertype. An earlier revision caught the wrong one, so the
catch was unreachable and the published code could not be raised by any path. The mapping is now
pinned by a test that holds a real shared lock and lets a real `lock_timeout` expire against it.

**Maker-checker on reopen is an actor-identity check, not a second permission.** The catalogue `V5`
seeded gives fiscal periods four codes — `view`, `open`, `close`, `reopen` — with no
`submit`/`approve` pair. `INV-10` asks for *"a checker whose identity is persisted and who is not
the maker"*, and both halves already exist: `fiscal_period.close` and `fiscal_period.reopen` are
separate `CRITICAL` codes, and `fiscal_period_transition_log` persists the actor of every
transition. So reopening requires the break-glass code, a mandatory reason, an audit record, and an
actor who is not the one who closed. `fiscal_period.reopen` is deliberately absent from every
default role bundle, so a tenant that wants it grants it to a named actor.

**Locking carries the same different-actor control, for a stronger reason.** Nothing transitions
out of `LOCKED`, so one actor closing and then locking would permanently freeze a tenant's books
with no second pair of eyes and no recovery short of hand-written SQL. Gating the reversible
operation three ways and the irreversible one only by permission had it backwards.

**And locking goes through the break-glass check, not the ordinary tenant one.** There is no
`fiscal_period.lock` code, so the transition is authorised by `fiscal_period.close` — but
`requireTenantPermission` authorises the system-actor sentinels *before* consulting any grant, which
is right for background provisioning and wrong here: a batch path would otherwise permanently
finalise a tenant's books with no principal holding the `CRITICAL` authority. `reopen` was already
checked this way and a lock is not the weaker act. Reusing `close` is a compromise the catalogue
freeze forces, not a claim the two are equivalent; a dedicated `fiscal_period.lock` code is the
recommended follow-up, and until it exists no role should hold `fiscal_period.close` unless it is
also trusted to lock.

**The different-actor lookup runs under the period's row lock, not before it.** This is the one
ordering detail easy to get wrong, because the check reads the *transition log* rather than the
period row, so it looks independent of the lock. It is not. Resolved before the lock, the answer
can go stale between check and write: an actor B that passes against closer A can have the period
reopened and re-closed by B in the interim, and the original request then completes against a period
whose latest closer is B — self-approval reached through the front door. Holding the lock closes the
window for the log too, because every state change goes through `FiscalPeriodStateChangeGuard`, so a
log row for this period can only be written by a transaction that first took the same lock. The
service therefore runs permission and request-shape checks first, then takes the lock, then
evaluates every state- or history-dependent control against what it read under it.

**Every transition audits, in the same transaction as the state change.** `record`, not
`recordIndependently`: an independent audit commits immediately, so a transition whose enclosing
transaction later rolled back would leave a permanent `SUCCESS` row for something that did not
happen. The prior-period posting audit uses the independent form for a different reason — there
the audited fact is *authority was exercised*, and the journal's own outcome is audited
elsewhere — but where the audited fact **is** the transition, the two must stand or fall
together.
