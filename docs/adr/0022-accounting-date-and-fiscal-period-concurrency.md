# ADR 0022: Accounting Date Semantics And Fiscal-Period Posting Concurrency

## Status

Accepted

Date: 2026-08-31

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
COMMITTED.

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

**The protocol is: unlocked lookup, then lock, then decide on the status read under the lock.** The
decision never uses the status from the lookup. `FiscalPeriodStateStore.lockForPosting` returns a
freshly read snapshot rather than a boolean precisely so the correct value is the one nearest to
hand. It is not, however, unrepresentable: `findCovering` also returns a status, so a caller can
still decide from the unlocked read and then take the lock for nothing. Making it impossible would
mean `findCovering` returning a key without a status; until then this is a convention the resolver
follows and reviewers must check.

**This is correct only at READ COMMITTED, and that is load-bearing rather than incidental.** Under
READ COMMITTED every statement takes a fresh snapshot, so the read issued *after* the lock
observes the latest committed row — including a close that committed since this transaction's own
earlier lookup. Under REPEATABLE READ that second read would return the transaction's original
snapshot, and the protocol would be wrong.

Note the mechanism precisely, because an earlier draft of this ADR named the wrong one:
`PostgresRowLock` selects `1` and returns a boolean, and the status arrives from a separate
statement. So the guarantee rests on per-statement snapshots plus the lock holding writers off
between the two statements — not on `EvalPlanQual` re-reading the locked row, which would be the
mechanism only if the lock and the read were one statement. `the locking read runs at READ
COMMITTED` asserts the isolation from inside a transaction that has taken the lock, so escalating
it breaks a named test; an explicit `@Transactional(isolation = ...)` on a future posting service
would still need catching by review. Global `SERIALIZABLE` escalation is rejected outright, per the issue.

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

**The protocol depends on READ COMMITTED.** Anyone raising the isolation level for an unrelated
reason would silently break the posting-versus-close guarantee. The named test is the guard, and
this ADR is the explanation the test points at.

**A long posting transaction delays a close.** `FOR SHARE` means a close waits for every in-flight
posting. That is the intended trade — a close that raced past an in-flight posting would be worse —
but it makes posting-transaction duration a latency budget for period close. Bound it in #41, and
set `lock_timeout` on the close path in #39.

**A 32-bit `objid` can collide within one advisory class.** The consequence is false sharing: two
unrelated keys serialise against each other. That costs throughput and never correctness, which is
the right way round.

**The two existing single-key advisory domains remain unnamespaced.** Tenant settings and
idempotency still share one space, so a settings key that happened to be a UUID string could in
principle collide with an idempotency key. Migrating them is deliberately out of scope here; it is
recorded so it is a known gap rather than an unexamined one.

**`SOFT_CLOSED` is deliberately absent** from `FiscalPeriodStatus`. The canonical design record
(#30) does not adopt it, and adding a state the schema will not carry would be inventing design
ahead of the authority that owns it.

**`PostingPeriodResolver` and `FiscalPeriodStateChangeGuard` are not Spring beans yet.** Their
`FiscalPeriodStateStore` dependency has no adapter until #36, and registering them early fails
application-context startup for the whole platform rather than only for accounting — which is how
this was discovered. Issue #36 adds the adapter and the wiring together; a test pins the absence of
the bean until then.

**The concurrency proof currently runs against a stand-in table.** Because
`accounting_fiscal_period` does not exist yet, the production `PostgresRowLock` is bound to an
existing tenant-scoped table. Only the location of the status byte is substituted: the lock
statements, their strengths, the protocol order, the READ COMMITTED re-read, the transaction
manager, the pool and the database are all production. Issue #36 must delete the stand-in and
repoint these tests at the real table — if it does not, the proof quietly stops covering the thing
it claims to.
