# ADR 0026: Real-Time Gates On The Serializable Posting Path

## Status

Accepted

Date: 2026-09-17

Amends [ADR 0025](0025-serializable-posting-and-the-covering-period-lock.md), whose Consequences
named two instances of one pattern and should have named four. Amends
[ADR 0023](0023-posting-idempotency-and-account-locking.md), whose posting lock chain gains two
classes: the `business_date` row, taken `FOR SHARE` by every posting, and the `iam` grant rows a
**backdated** posting's break-glass gate rests on.

Discharges issues [#123](https://github.com/kevogaba/finaxis-platform/issues/123) and
[#125](https://github.com/kevogaba/finaxis-platform/issues/125). One record rather than two,
because they are one defect with two instances: split across two records the mechanism would be
argued twice, and whichever one a future reader found first would read as though the rule were
about that gate.

## Context

ADR 0025 states the principle in bold and then applies it to one gate:

> `SERIALIZABLE` supplies **serializability, not linearizability**. A transaction whose snapshot
> predates the close is serially equivalent to having run entirely before it, so SSI is satisfied.
> *"A journal never lands in a period closed before it committed"* is a **real-time ordering**
> claim, and no isolation level supplies one — only an explicit lock does.

The posting path has four gates of that shape, not one. Each reads state another transaction can
move, and each decides something whose correctness is a claim about wall-clock order rather than
about serial equivalence. Two were closed when ADR 0025 shipped; two were not, and the record did
not say so.

| gate | what it decides | before this change |
| --- | --- | --- |
| covering fiscal period | may a journal land in this period | `FOR SHARE`, issue #35 |
| functional currency | what unit is this journal denominated in | `FOR SHARE`, ADR 0025 |
| **tenant business date** | has close-of-business started (#125) | plain read, pre-claim |
| **break-glass authority** | may this actor post into a prior period (#123) | cached, then a plain read |

Both open cases were found by review on #120 — the pull request that raised the path to
`SERIALIZABLE` — and both were verified against the code before being filed. Neither is a
regression in the strict sense; both were already wrong at `READ COMMITTED` and the raise made the
window wider. That distinction is recorded because it decides priority, not because it excuses
anything.

### #125 — the close-of-business gate

`PostingEngine.post` pins its snapshot at its **second** statement, the isolation guard's
`current_setting` read; under `PostingTransactions.execute { … }` it is pinned earlier still, by
the product module's own first statement. `PostingPeriodResolver.resolveDates` then read
`business_date` with a plain `SELECT`, and nothing locked the row or re-read it before the journal
committed. Every link was checked rather than assumed:

| link | state | evidence |
| --- | --- | --- |
| the posting's business-date read | not locking | plain `SELECT` in `JooqBusinessDateStore.current` |
| `startCob`'s write | takes no contended lock | plain `UPDATE … WHERE row_version = ?` |
| any later lock on `business_date` | none | the row was read once, never touched again |
| SSI | inert | one rw-dependency edge, no cycle, so no `40001` |
| the #124 retry | cannot help | there is no serialization failure to retry |

So a `startCob` committing after the pin was invisible, the gate saw `OPEN`, and a current-dated
journal committed into a day whose close-of-business had started. At `READ COMMITTED` the read was
at least fresh at read time, so the window was read-to-commit rather than pin-to-commit. It was
never correct.

**Bounded today because nothing consumes `CLOSING`.** Close-of-business is an FSM status plus an
externalized `CobStarted` event; the gate's only effect is denying current-dated postings. The
failure is *"a journal lands on a day whose close-of-business had started"*, which matters for
integrity now and will matter far more once close-of-business computes anything — that is when a
late journal starts invalidating figures a batch has already produced.

### #123 — break-glass authority

`PostingPeriodResolver.lockAndValidate` gates a backdated posting on
`AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD`, which resolved through
`AuthorizationService.requireBreakGlassPermission` → `EffectivePermissionResolver`. That resolver
is **cache-first**, and the interleaving is worse than a plain stale read because the cache and the
snapshot compound:

1. A revocation commits and evicts the membership's cache entries — `PermissionCacheInvalidator`,
   which is what `RoleManagementService.revokeRoleFromUser` calls.
2. The in-flight posting reaches its gate and now **misses** the cache.
3. `resolve` queries the database — from the posting's pinned, pre-revocation snapshot.
4. The stale grant comes back and the posting is admitted.

The eviction is what creates the miss. The very act that should deny the posting is what routes the
check onto the path where the revocation is invisible. On a cache *hit* no query is issued at all
and the answer is stale for a different reason, so both cache outcomes are wrong and only the
mechanism differs.

What bounds it, stated so the priority is judged honestly: the actor must already hold
`journal.post_prior_period`, which ADR 0022 keeps out of every default bundle and grants to a named
actor, so the population is small and privileged by construction; and the revocation must commit
inside the posting's window. The blast radius is one backdated posting committing under an
authority revoked moments earlier — narrow, and precisely the kind of thing an audit asks about
afterwards, which is the reason break-glass is audited at all.

## Decision

**Both gates become locking reads, which is the mechanism this repository already has for exactly
this shape.** The other three candidate remedies were costed first and are rejected below with
reasons, because ADR 0025's own history shows that the cheapest remedy to reach for — hoisting an
advisory lock — was measured not to work, and a record that lists only the winner invites the next
author to retry a loser.

### #125: a locking read of `business_date`

`AccountingBusinessDateLookup` gains `currentBusinessDateForPosting`, implemented by
`LifecycleAccountingBusinessDateAdapter` over a new `BusinessDateStore.lockCurrentForPosting` — the
existing select with `.forShare()`, asserting an active transaction first as every locking adapter
in that package does. `PostingPeriodResolver.lockAndValidate` calls it and re-applies the
close-of-business gate to the row it locked.

**Only a current-dated posting takes it, and that is a correctness requirement rather than an
optimisation.** A backdated posting is admissible *while the day is closing* — close-of-business
must not deadlock corrections — so its admissibility does not rest on this row. A first revision of
this change took the lock unconditionally and thereby inverted the very rule it was added to serve:
a long-running correction would have made `startCob` wait behind it, and, because PostgreSQL queues
an incoming request behind a pending exclusive one, parked every new posting in the tenant behind
that wait. `PostingPeriodResolverTests` now asserts that a backdated posting issues no locking read
at all, so the rule fails a test rather than a support ticket.

**The comparison against the resolved date is a `check`, not a published code.** Both reads come
from one snapshot — the engine refuses any isolation below `SERIALIZABLE` — so they can only
disagree if that guard has stopped working, which is not a condition a caller can act on. An
earlier revision raised a public `accounting.business_date_changed` here and put it in the API error
table; no production interleaving could reach it, which is precisely the "published code nothing
can raise" defect this record criticises `FiscalPeriodLifecycleService` for two sections down. It
was removed rather than shipped.

**The lock is per tuple, not per column, so an ordinary `advance` aborts a current-dated posting
too.** ADR 0023 states that trade for the `organisation` row — *"any committed update to the
tenant's `organisation` row … conflicts with the posting's `FOR SHARE`"* — and it is the same trade
here, accepted for the same reason: rare, operator-driven writes on the tenant's own control row,
and a retry that re-runs against the state that won. The user-visible change is real and is written
into `docs/architecture/accounting-dates-and-periods.md`: a posting that said *"today"* and raced
the day moving lands on the new day rather than committing against a day its snapshot had already
lost. That is the honest reading of the request, and the previous behaviour — committing the old
day from a stale snapshot — was the same blindness that let it commit into a closing one.

`currentBusinessDate` is unchanged and stays non-locking for its two other callers —
`ControlAccountReconciliationService`, and `PostingPeriodResolver.resolveDates`' own pre-claim read
— with a KDoc warning that it must never decide whether a posting may commit, the same shape as the
warning `AccountingTenantLookup.functionalCurrencyOf` carries.

**The pre-claim read stays non-locking, deliberately, and for the reason ADR 0025 gives for the
currency.** The resolved dates are fingerprinted; if the row changed after this transaction's
snapshot, the post-claim `FOR SHARE` raises `40001`, the transaction aborts, and the retry resolves
against the day that won. Making the pre-claim read locking would hold `business_date` for the whole
of every **replay**, and a replay deliberately takes no locks at all — that is the design of
`PostingEngine.post` and the reason ADR 0023 moved the claim ahead of validation.

**`PostingDatePolicy.rejectClosedBusinessDate` becomes the public `requireOpenForPosting`, called
from both reads rather than copied.** The pre-claim call is kept because it refuses an obviously
closed day before the engine writes a claim it would only roll back, and because a replay never
reaches the second call and still has to be answered. The post-claim call is the one that decides.

### #123: a locking, cache-free read of the one grant

`AuthorizationService.requireBreakGlassPermission` no longer resolves through
`EffectivePermissionResolver` at all. It answers from a new
`PermissionResolutionQueries.lockedBreakGlassGrant(membershipId, permissionCode)`, which applies
the same rule the resolver applies — an ACTIVE membership, granted through an active tenant-scoped
role assignment, an active role and an active catalogue entry, or directly allowed, and not
directly denied — with two differences that carry the whole point:

- **It locks.** Every row the answer rests on is read `FOR SHARE` and held to the caller's commit.
- **It never consults the cache.** A cached answer is by construction a pre-revocation answer, and
  a break-glass control a cache can satisfy is not a control.

Each statement names its `OF` list, and that is not cosmetic: a bare `FOR SHARE` locks the selected
rows of **every** table in the `FROM`, which here would include the `permission` catalogue —
platform-global reference data seeded by `V2`/`V5`. Every backdated posting in every tenant would
then contend on one row per code, and a single catalogue edit would abort backdated postings
platform-wide. Deactivating a code is a platform-wide reference-data change rather than a tenant's
revocation, so it is read without being held; what is held is exactly what a *tenant* can revoke.

Three statements rather than one, and the honest reason is legibility and short-circuiting rather
than a database restriction. One restriction is real and was measured: `FOR SHARE` is rejected
outright with `SELECT DISTINCT`, so `rolePermissionCodes`' existing shape cannot simply gain
`.forShare()`. The other restriction a first draft of this record asserted — that `FOR SHARE` does
not reach into a sub-`SELECT`, so an `EXISTS`-shaped single statement would lock nothing — is
**false**, and it is written down here because it is the kind of plausible claim that survives
review. Measured, `select exists (select 1 from … for share)` locks the sub-select's rows and
raises `40001` exactly as a top-level lock does; the transcript is below. What three statements buy
is that they mirror `EffectivePermissionResolver.resolve`'s three reads one for one, so the rule is
visibly the same rule, and that a code settled by a direct row leaves the role rows unlocked
instead of locking every row in the predicate unconditionally.

It is scoped to one code, not to the membership's whole effective set, because locking the rows
behind every code would hold far more of `iam` than the decision needs.

Short-circuiting matches `EffectivePermissionResolver.resolve`, where the effective set is
`allowed - denied`: a direct `DENY` settles it, then a direct `ALLOW`, and only an undecided code is
looked for among the role grants. A code settled by a direct row leaves the role rows unlocked,
which is correct — those rows are not what the answer rests on, and locking them would abort
postings for revocations that could not have changed the outcome.

The ordinary `requirePermission` paths are untouched and keep the cache. That asymmetry is the
decision, not an oversight: ordinary authorization is a request-scoped read whose staleness window
is one request, while break-glass is a ledger control evaluated inside a financial transaction.

### What a locking read does not close, and why it is still the right remedy

A lock is taken on rows that **exist**. An *inserted* `DENY` row is a phantom: there is nothing to
lock, and above `READ COMMITTED` the new row is not in the caller's snapshot, so a `DENY` inserted
mid-posting is not seen. Stated plainly rather than left implicit, because it is the one hole the
chosen remedy leaves.

It is unreachable today, and checked rather than assumed: **no production code writes
`membership_permission` at all** — the table is read by `directPermissionEffects` and by the
membership read model, and written by nothing. Every revocation this repository can perform is an
`UPDATE` of `user_role_assignment`, `role`, `permission` or the membership row, or a `DELETE` from
`role_permission`, and a `FOR SHARE` read catches all five. Measured on `postgres:18.4`, both the
update and the delete arrive as `could not serialize access due to concurrent update` — the delete
does *not* report the `concurrent delete` wording a reader might expect, which matters only if
someone ever matches on the message rather than on `40001`. **A writer of `membership_permission`
is what would make the phantom reachable, and adding one requires revisiting this record.**

### Rejected: re-check under the covering-period lock

Both issues name this as probably the cheapest option and ask for it to be costed first. It does
not apply to either gate, for the same structural reason in each case: the fiscal-period lock is one
statement against `accounting_fiscal_period`, and folding another table into it means accounting's
persistence adapter reading `business_date` and `user_role_assignment` directly. Those are
lifecycle's and identity's tables. Accounting reaches them through `AccountingBusinessDateLookup`
and `AccountingPermissionGuard` precisely so a module cannot acquire a dependency on another's
schema, and `docs/architecture/accounting-module-boundary.md` is explicit that it must not. Buying
one fewer lock class with a schema-level module violation is a bad trade, and the violation would
not even be caught by the build — the generated jOOQ tables live in one package that every module
may use.

### Rejected: a non-snapshot read on a separate connection

Both issues list it; both note the cost. A `REQUIRES_NEW` transaction, or a second connection, reads
current state regardless of the caller's snapshot and would have closed the phantom-`DENY` hole
above, which is a genuine advantage and the reason it was costed rather than dismissed.

It is rejected because **it is not linearizable, and the difference is exactly what these issues are
about.** A fresh read is fresh *at read time*; nothing then stops a revocation or a `startCob`
committing between that read and the journal. It would restore the `READ COMMITTED` behaviour —
which was already the defect — while reading as though it had fixed it. `BreakGlassRevocationRace`'s
second test is the one that says so: it fails for a fresh-but-unlocked read, because the revocation
does not wait. The connection cost is secondary and was not decisive: HikariCP is at its
unconfigured default of ten, but the break-glass gate only fires for a backdated posting, so the
second connection would never have been on the ordinary path.

### Rejected: accept and document

Issue #123 asks for this to be rejected explicitly rather than by omission, and it is. The argument
for it is that snapshot-time authority is the correct semantics for a transaction that began before
the revocation. That is defensible for ordinary permissions — and is in fact what this change leaves
in place for them — and is not defensible for break-glass, whose entire purpose is that a tenant
administrator can grant it to a named actor and take it back. An authority that cannot be withdrawn
promptly is not break-glass; it is a permanent grant with a scary name. The same argument applies to
close-of-business: an operator who has started the day's close and is told postings are now refused
has been told something false if a posting commits afterwards.

### The lock chain gains two classes, and the acyclicity is re-derived rather than asserted

ADR 0023 carries the full chain; the ordering is restated there with both new classes in it. A
posting now holds, in this order and never any other:

1. the reversal advisory lock **or** the `manual_journal` row lock, taken before the engine;
2. the `posting_request` row of the idempotency claim;
3. the tenant functional-currency advisory lock, shared;
4. the `organisation` row, `FOR SHARE`;
5. **the `business_date` row, `FOR SHARE`** — new, **current-dated** postings only;
6. **the `iam` grant rows, `FOR SHARE`** — new, **backdated** postings only:
   `user_organisation_membership`, then either `membership_permission` + `permission`, or
   `user_role_assignment` + `role` + `role_permission` + `permission`;
7. the covering `accounting_fiscal_period` row, `FOR SHARE`;
8. every `gl_account` row the legs name, `FOR SHARE`, ascending id;
9. the tenant's `reference_sequence` row, updated by the gapless allocator.

Classes 5 and 6 are mutually exclusive: a posting is current-dated or backdated, never both, so it
takes at most one of them.

**No cycle is representable, and the reason is a property of the writers rather than of the
ordering.** Every lock a posting takes in classes 4–8 is **shared**, so postings never conflict with
each other there. A deadlock would therefore need a writer of one of the new classes to also want a
class a posting holds *before* it, and no such writer exists:

- **`business_date` writers.** `BusinessDateService` alone: `initialize`, `advance`, `startCob`,
  `changeStatus`. Each touches `business_date`, `business_date_history`, `audit_event` and the
  outbox. It reads `organisation` and the actor's permissions with plain, non-locking selects, and
  it takes no advisory lock, no accounting row lock and no `iam` row lock. It can wait for a
  posting;
  a posting can never wait for it.
- **`iam` grant-row writers.** `RoleManagementService` alone: `assignRoleToUser` and
  `revokeRoleFromUser` write `user_role_assignment`; `addPermissionToRole` and
  `removePermissionFromRole` write `role_permission`; role activation writes `role`. Membership
  status is written by `UserProvisioningService`. None of them locks `organisation`,
  `business_date`,
  any accounting row, or the tenant-currency advisory key; their only overlap with a posting is the
  `audit_event` insert, and inserts do not conflict on rows.

So the posting is the only transaction that ever holds locks from both the `iam`/lifecycle classes
and the accounting ones. A cycle needs two transactions each holding what the other wants, and the
writers above hold nothing a posting waits for. This is the property a future change must re-check
rather than assume: **a writer of `business_date` or of an `iam` grant row that acquires an
accounting lock, or the tenant-currency advisory lock, reopens this question.**

Both acquisition orders are tested, as issue #123 requires: `BusinessDateCloseRaceIntegrationTests`
and `BreakGlassRevocationRaceIntegrationTests` each assert the abort-when-the-writer-committed-first
direction *and* prove, out of `pg_locks` and `pg_stat_activity` rather than from thread timing, that
a writer arriving second genuinely waits behind the posting's shared lock.

### Every business-date mutation is now bounded, because every one of them is now a waiter

This is the operational consequence of class 5 and it is not optional. `startCob`, `advance`,
`completeCob` and `reopen` all `UPDATE business_date`, so each now queues behind the postings in
flight for the tenant. That is the semantics wanted — a close that sails past in-flight postings is
the defect being closed — but unbounded it turns a fast status flip into a request that may never
return.

It is worse than a slow administrator, for the reason `AccountingProperties`' currency bound is
sized by: PostgreSQL conflicts an incoming lock request against the **pending** queue as well as
against what is granted, so while the exclusive request is queued behind an in-flight posting, every
**new** posting for the tenant queues behind it in turn. An unbounded wait here is an unbounded
stall
of the tenant's whole posting path.

`BusinessDateService` therefore applies `SET LOCAL lock_timeout` to each of the four mutations and
translates an expiry into `lifecycle.business_date_lock_timeout`, which is retryable and reaches
`409` through the existing `ApplicationException` path with no edit to `ApiExceptionHandler`.
`initialize` is excluded: it inserts a row that does not exist yet, so it has nothing to wait on.
The bound is `finaxis.lifecycle.business-date-lock-timeout`, ten seconds by default, and validated
at startup to be at least one millisecond — below that `Duration.toMillis()` truncates to zero,
which PostgreSQL reads as *no timeout at all*, the exact opposite of what the operator asked for.

`CannotAcquireLockException`, not `QueryTimeoutException`: PostgreSQL raises `55P03` when
`lock_timeout` expires and Spring lists `55P03` under `cannotAcquireLockCodes`, while
`QueryTimeoutException` is a *sibling* under `TransientDataAccessException` and never a supertype.
`FiscalPeriodLifecycleService` made that mistake once, left a published code nothing could raise,
and records it in a comment; this is the second caller and it does not repeat it.

**`TransactionLockBound` and `TransactionLockTimeout` move from accounting to
`common::persistence`**, beside `AdvisoryLockNamespace`, because a second module now needs them. One
implementation serving both is the alternative to two copies of a `SET LOCAL` and two copies of the
reasoning about sub-millisecond truncation. Nothing about their behaviour changes.

## The authorization audit issue #123 asks for

The requirement is that the answer be written down **even where it is "unchanged"**. The
`SERIALIZABLE` transaction in this repository is the one `SerializablePostingTransaction` opens; an
ArchUnit rule forbids a non-default isolation anywhere else in `com.finaxis.platform.accounting..`,
and nothing outside accounting declares one. So *"reachable from a `SERIALIZABLE` transaction"*
means *"reachable from inside `PostingTransactionBoundary.execute`"*, and the boundary has exactly
three entry points: `PostingTransactions.execute` for a product module,
`DefaultPostingService.reverse`, and `ManualJournalService.approve`.

Three checks are reachable, not one. That is more than the issue assumed and more than a first
reading of the code suggests, because both accounting flows that enter the boundary authorize
*inside* it rather than before it — `JournalReversalService.reverse` is
`@Transactional(MANDATORY)` and `ManualJournalService.approve` calls `approveInTransaction`
directly from inside `boundary.execute`, both deliberately, so that the work commits with the
journal.

| check | where | inside? | verdict |
| --- | --- | --- | --- |
| break-glass `JOURNAL_POST_PRIOR_PERIOD` | `PostingPeriodResolver.lockAndValidate` | **yes** | **changed**: locking, cache-free |
| tenant `JOURNAL_REVERSE` | `JournalReversalService.reverse` | **yes** | unchanged — argued below |
| tenant `JOURNAL_APPROVE` | `ManualJournalService.approveInTransaction` | **yes** | unchanged — argued below |
| break-glass `FISCAL_PERIOD_REOPEN` | `FiscalPeriodLifecycleService.reopen` | no — own transaction | locking now, as it shares the one method |
| fiscal-period lifecycle | `FiscalPeriodLifecycleService.open/close` | no — own transaction | unchanged |
| chart of accounts | `ChartOfAccountsService`, `GlAccountLifecycleService` | no — own transactions | unchanged |
| posting rules | `PostingRuleService` | no — the path resolves rules, authorizes nothing | unchanged |
| manual-journal create/submit/amend/reject | `ManualJournalService` | no — own transactions | unchanged |
| reconciliation | `ControlAccountReconciliationService` | no — own transaction | unchanged |
| tenant settings | `TenantSettingsService.authorize()` | no — own transaction | unchanged |
| controller authority gates | Spring Security, `iam` web adapters | no — before any transaction | unchanged |

The three **yes** rows are reachable from `PostingTransactionBoundary.execute`; the rest are
evaluated in their own transactions, whose staleness window is their own and is unrelated to the
posting's snapshot.

**`PostingEngine` itself performs no authorization.** It reconciles the caller's claimed context
against the ambient request context, which is a trust boundary rather than a permission, and the
only permission it reaches is the break-glass gate in the period resolver.

### Why `journal.reverse` and `journal.approve` are left snapshot-bound

They have the same mechanical staleness the break-glass gate had: `requireTenantPermission` resolves
through `RequestPermissionCache` and then `EffectivePermissionResolver`, so a cache hit answers
from before a revocation and a cache miss reads from the transaction's pinned snapshot. The
residual is therefore real and is named rather than waved at: **a `journal.reverse` or
`journal.approve` revoked while a reversal or an approval is in flight can let that one operation
through.** Four reasons that is the right call, and a fifth that would change it.

1. **They are ordinary tenant permissions, and this record already concedes the point for those.**
   The "accept and document" option is rejected above *for break-glass*, on the ground that an
   authority whose whole purpose is that a tenant can take it back is not one if withdrawal is
   deferred. Neither of these is break-glass: both sit in default role bundles, and
   `AccountingSeparationOfDutiesPolicyTests` asserts that break-glass codes do not.
2. **They are not fail-closed controls to begin with, so a lock would not make them one.**
   `requirePermission` short-circuits to *allow* for the system-actor sentinels — that is exactly
   the difference `AccountingPermissionGuard.requireBreakGlassPermission` exists to make. Taking
   `iam` row locks to freshen a check a batch job can bypass entirely would buy precision in the
   wrong place.
3. **The separation-of-duties property does not depend on them.** What makes an approval or a
   reversal safe is actor identity at the transition — the approver must differ from the submitter,
   the reverser from the poster — and both are enforced under the aggregate's own lock, from rows
   the transaction holds. A stale permission cannot make one actor into two.
4. **The cost is on the ordinary path, not the rare one.** The break-glass gate fires only for a
   backdated posting; these fire on every reversal and every manual-journal approval. Locking them
   would put the `role_permission` row for a widely granted code into the lock chain of the
   platform's most common two-actor flows, and would block an administrator editing that role
   behind every in-flight approval in the tenant.
5. **What would change it:** a deployment that treats reversal or manual-journal approval as a
   privileged control rather than routine maker-checker work — moving either code out of the
   default bundles, as `AccountingPermissions.BREAK_GLASS` codes already are. At that point the
   argument in (1) and (2) stops holding and the fix is one line each, because
   `requireBreakGlassPermission` already does the locking, cache-free thing.

A check made **before** the boundary is entered is a different question, not an exemption. Its
staleness window is its own transaction's, which is short and unrelated to the posting's snapshot.
Moving any of them inside the boundary would move it into the first three rows of this table and
would need the same argument made for it.

## Measured, not argued

Against the pinned `postgres:18.4`, in the format ADR 0025 established. Session A is the posting:
`SERIALIZABLE`, snapshot pinned by its first read, as it always is in production. Session B is the
concurrent writer at the default isolation level, as every production writer of these rows is.

**The close-of-business gate (#125).** A is pinned, B commits the close, and the plain read A used
to decide from still reports the day open:

    A  begin transaction isolation level serializable;
    A  select current_business_date from business_date where organisation_id = 7;  ->  2026-08-31
    B  update business_date set status = 'CLOSING', row_version = row_version + 1
         where organisation_id = 7;                                                ->  committed
    A  select status from business_date where organisation_id = 7;                 ->  OPEN
    A  select status from business_date where organisation_id = 7 for share;
       ERROR:  could not serialize access due to concurrent update

The third line is the defect: `OPEN` is what `postingAllowed` was computed from, so the gate passed
and the journal committed into a closing day. The fourth is the repair, and it repairs by failing.

**Break-glass authority (#123).** The same shape one table over:

    A  begin transaction isolation level serializable;
    A  select status from user_role_assignment where id = 1;                       ->  ACTIVE
    B  update user_role_assignment set status = 'REVOKED', row_version = row_version + 1
         where id = 1;                                                             ->  committed
    A  select status from user_role_assignment where id = 1;                       ->  ACTIVE
    A  select status from user_role_assignment where id = 1 for share;
       ERROR:  could not serialize access due to concurrent update

**A deleted grant row aborts too, and not with the message a reader expects.** The
`removePermissionFromRole` shape:

    A  begin transaction isolation level serializable;
    A  select permission_id from role_permission where role_id = 9;                ->  99
    B  delete from role_permission where role_id = 9;                              ->  committed
    A  select permission_id from role_permission where role_id = 9 for share;
       ERROR:  could not serialize access due to concurrent update

**`FOR SHARE` and `DISTINCT`, and `FOR SHARE` in a sub-`SELECT`.** The first is the real constraint
on the implementation; the second corrects a claim this record made in draft:

    select distinct status from user_role_assignment where id = 1 for share;
    ERROR:  FOR SHARE is not allowed with DISTINCT clause

    select exists (select 1 from user_role_assignment where id = 1 and status = 'ACTIVE'
                   for share) as granted;                                          ->  t

and, with the same statement run against a row B had already revoked after A's snapshot:

    ERROR:  could not serialize access due to concurrent update

So a sub-`SELECT` does lock, and a single `EXISTS`-shaped statement would have been a legitimate
shape. Three statements is a choice about legibility and about not over-locking, not a workaround.

**The other acquisition order: the writer waits.** A holds the shared lock and B's update parks on
A's transaction id until A commits:

    A  begin transaction isolation level serializable;
    A  select status from business_date where organisation_id = 7 for share;       ->  OPEN
    B  update business_date set status = 'CLOSING' … where organisation_id = 7;    ->  blocks

       select wait_event_type, wait_event, left(query, 46) from pg_stat_activity
         where state = 'active' and query like 'update business_date%';
       Lock | transactionid | update business_date set status='CLOSING', row

    A  commit;
    B  (proceeds)

That second direction is what a *fresh but unlocked* read — a second connection, or a
`REQUIRES_NEW` transaction — would not have produced, and is why option (2) was rejected above. The
same two directions are asserted permanently by `BusinessDateCloseRaceIntegrationTests` and
`BreakGlassRevocationRaceIntegrationTests`, which read the obstruction out of `pg_locks` and
`pg_stat_activity` rather than inferring it from thread timing.

## Consequences

**ADR 0025's Consequences now name four cases, not two, and this record is where the fourth is
argued.** A reader arriving at 0025's bolded principle should leave with the full list: the covering
period, the functional currency, the business date and break-glass authority. The principle was
right and its application was incomplete, which is a more useful thing to have written down than a
second statement of the principle.

**An ordinary `advance` now also aborts a current-dated posting in flight.** The row lock is per
tuple, and this is ADR 0023's `organisation`-row trade applied to a second control row. Where the
close case ends in a refusal, the advance case ends in a *different date*: the retry resolves
against the day that won and the journal lands there. Both are written into the dates-and-periods
guide, because the previous behaviour — quietly committing the superseded day — is the one an
operator will remember.

**A posting racing a close-of-business now costs a retry and then a named refusal.** The aborted
attempt rolls back, the next opens a new transaction with a fresh snapshot, reads `CLOSING`, and
`PostingDatePolicy` answers `accounting.business_date_not_open` before the claim. So the
user-visible outcome of the race is the code that was always documented for it, which it was not
before: before this change the race had no outcome at all, because the posting committed.

**A posting racing a revocation costs a retry and then a `403`.** Same mechanism, different answer:
the retry's fresh snapshot sees the revoked assignment and the gate refuses. `AccessDeniedException`
is a `ForbiddenOperationException` and therefore an `ApplicationException`, outside
`ConcurrencyFailureException` entirely, so the retry boundary propagates it un-retried rather than
spending the budget on a decision that cannot change.

**A backdated posting now issues three extra statements, and an ordinary posting one.** The three
are
the break-glass reads; the one is the business-date lock. Both are on a path that already takes four
lock classes and updates a per-tenant counter every posting, which is the real throughput ceiling
(ADR 0025 measures the abort rate for overlapping same-tenant postings as approaching one hundred
percent). **Issue #119 owns measuring this**, and it should report per-tenant abort rate rather than
aggregate throughput, as ADR 0025 requires of any measurement on this path. Nothing here was sized
by measurement and this record does not claim otherwise.

**Two shared locks on rows outside accounting mean two new ways for an administrator to be made to
wait.** A business-date mutation waits for in-flight postings, bounded above. A revocation of a role
assignment that carries a break-glass code waits for in-flight *backdated* postings, and is **not**
bounded: it goes through `RoleManagementService`, which has no lock-timeout story, and a bound there
would be a change to `iam`'s ordinary write path to serve a case that arises only for the small,
privileged population holding a break-glass code. The honest statement is that the wait is bounded
by
the posting transaction rather than by a timeout, and that if `iam` ever needs a bound it now has
`common::persistence`'s `TransactionLockBound` to reach for. This is written down rather than fixed
because fixing it speculatively would put a `SET LOCAL` on every role revocation in the platform.

**`FinancialTransactionAtomicityFixture` needs no new probe.** No new durable write is added. Both
changes are reads — locking ones — and the business-date bound is a `SET LOCAL` that reverts with
the transaction. The fixture's probe set is unchanged because the set of durable effects is
unchanged.

**The `AccountingBusinessDateLookup` port gains a method with a precondition its sibling does not
have**, exactly as `AccountingTenantLookup` did for the currency, and for the same reason: a boolean
parameter on one method would let a read-side caller take a transaction-scoped lock by accident. Two
methods make the mistake unwriteable rather than reviewable, which is the same argument ADR 0025
makes for deleting the boolean-returning `PostgresRowLock` primitive.

**`membership_permission` is now load-bearing for a claim this record makes.** The phantom-`DENY`
hole is unreachable *because nothing writes that table*. That is a fact about today's code, asserted
in `JooqPermissionResolutionQueries`' KDoc where a future author will be standing when they add the
first writer, and repeated here because a KDoc is not where a design decision lives.
