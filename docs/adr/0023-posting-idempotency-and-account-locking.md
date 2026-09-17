# ADR 0023: Posting Idempotency Claim Ordering And Posting-Time Account Locking

## Status

Accepted

Date: 2026-09-02

Amended by [ADR 0025](0025-serializable-posting-and-the-covering-period-lock.md) and then by
[ADR 0026](0026-real-time-gates-on-the-serializable-posting-path.md). Every decision below stands;
the lock chain in the consequences gains three classes across the two amendments.

0025 added the `organisation` row: raising the posting path to `SERIALIZABLE` made the engine's
post-lock functional-currency re-read snapshot-bound, so that re-read is now a locking read of that
row, taken immediately after the tenant-currency advisory lock and before the fiscal period.

0026 added the `business_date` row, taken `FOR SHARE` by a **current-dated** posting, and the `iam`
grant rows a **backdated** posting's break-glass gate rests on — the same defect as the currency
one, found in two more gates. The two are mutually exclusive: a posting takes one or the other,
never both. The ordering paragraph below is restated with all three classes in it, and its
acyclicity re-derived from the code rather than assumed.

## Context

Issues #89 and #90 are Phase C/D review follow-ups against epic #55, raised across #78, #80, #82
and #84 by both reviewers on every one of those pull requests. Both issues are the same shape:
several findings against the same few lines of `PostingEngine`, which want settling together rather
than as separate patches to the same digest or the same lock protocol.

**Claim ordering (#89).** `PostingEngine.post` validated the fiscal period, the tenant's
postability, the accounts and the balance before it claimed
`(organisation_id, source_module, source_reference)`. A faithful retry of an already-committed
posting - recovering from a lost response, the one case the claim exists for - therefore failed on
whatever had changed since the original posting succeeded: the period closed, an account was
deactivated, the tenant or branch was suspended, or the posting rule that resolved the legs was
superseded or retired. The retry never reached the point where it would have been recognised as a
duplicate and answered with the original receipt.

**Fingerprint composition (#89).** The digest that tells a faithful retry from a conflicting reuse
of the same source reference joined fields with `"\n"` and legs with `"|"`, into which the data
itself - `subledgerReference` in particular - can inject arbitrary bytes, so the encoding was not
injective. It also omitted `branch_id`, `corrects_posting_request_id` and
`reverses_journal_entry_id`, all of which are persisted and all of which are material: the same
source reference retried under a different branch, or against a different correction or reversal
target, was classified as a retry and returned the original journal.

**Correction lineage (#89).** `corrects_posting_request_id` reached persistence unvalidated.
`uq_posting_request_corrects` (added in #79/V7) already makes a request replaced at most once;
nothing checked that the named target had actually been reversed first.

**Account locking (#90).** `PostingEngine` read each leg's account with a plain, unlocked query and
checked `ACTIVE`/`POSTABLE`. A concurrent deactivation could commit between that read and the line
insert, leaving a posting against an account that was inactive at write time - the journal-line
foreign key checks identity only. Symmetrically, `ChartOfAccountsService`'s structural-change path
read `hasJournalLines` unlocked before permitting a code, class or usage change; because posting
never writes to `gl_account`, the account's `row_version` never moves to signal that history
appeared, so the two transactions do not conflict and both can commit. And a posting-rule version
could be approved to reference an account, after which nothing stopped that account being
deactivated - the resolver kept selecting the version, and every posting through it then failed at
the engine, far from the change that caused it.

## Decision

**Claim first, validate second, and skip validation entirely on a replay.** `PostingEngine.post`
now does, before anything else: reconcile the caller's context (the one check that is never
skipped, because the context is a trust boundary rather than a business rule that can legitimately
drift); validate a correction's target, if any, exists and has been reversed (also never skipped -
see below for why that is safe); resolve the accounting dates via `PostingPeriodResolver.resolveDates`,
which reads the tenant business date but takes no lock and consults no period; compute the
fingerprint from those dates and the caller's own inputs; and claim the source reference. A claim
that finds an existing, committed row goes straight to `replay` - comparing the fingerprint and
returning the stored receipt - and never calls the `LegProvider`, never touches the period, the
tenant's postability, or any account. Only a claim that is genuinely new proceeds to `postNew`,
which runs tenant/branch postability, locks and validates the period
(`PostingPeriodResolver.lockAndValidate`), resolves the legs, locks and validates the accounts, and
checks the balance, in that order.

**The fingerprint is computed from the caller's inputs, never from resolved legs - with one
deliberate exception for amounts.** Legs are resolved by a `LegProvider`, and for a product-module
posting that means rule resolution, which can throw or answer differently as configuration changes
over time (retroactive supersession, backdated retirement - see below). A digest that depended on
resolved legs would have to resolve them before the claim, reintroducing exactly the staleness the
reorder exists to remove. But legs are not the only place an amount lives: `PostFinancialFactsCommand`
already carries the caller's asserted amounts as `PostingIntent.Facts.facts`, entirely independent
of any rule, and `LedgerPostingRequest.financialFacts` carries that same list into the engine. A
first draft of this change fingerprinted nothing amount-related at all, on the theory that dropping
legs meant dropping every amount with them; `PostingIdempotencyIntegrationTests`' existing
`"the same reference with a different request is a deterministic conflict"` test caught that this
made a same-reference retry with a **different amount** silently replay the original instead of
conflicting, which the issue's own "existing conflict semantics preserved for a genuinely different
request" requirement rules out. `financialFacts` closes that gap without touching legs or the
resolver: `PostingFingerprint.of` therefore covers the source triple, the event code, the entry
type, the branch, the correction and reversal lineage, the resolved transaction/value/posting
dates, the functional currency, and the sorted `financialFacts`. Reversal and manual journals leave
`financialFacts` empty and need nothing there: both derive their source reference from an already
immutable record (the journal being reversed, the approved manual journal's own id), so the same
reference can never legitimately name a second, different amount regardless of what the fingerprint
covers.

**Each fact's position reference is hashed with its amount** (issue #91, which added
`FinancialFact.positionReference`). It is an *identifier* of the subsidiary-ledger position the
money moved, not a description of the posting, so it belongs with the amount rather than with the
narratives this digest deliberately excludes. The failure it prevents is concrete: a module retries
a deposit under the same source reference after correcting the member's account number, the
fingerprints match, the request replays, and the ledger keeps the deposit against the wrong position
with no error raised anywhere — and the `Q6` drill-down the reference exists for then points at an
account that never received the money. It satisfies this ADR's binding constraint, that the digest
cover only inputs known before the claim: the reference arrives on `PostingIntent.Facts` and is
never resolved. One consequence is worth stating because it is easy to get wrong when sorting: an
absent reference and an empty one are distinct to the length-prefixed encoding below, so they must
be distinct to the sort comparator too, or the same facts in two orders hash differently.

**This changed the digest for any request carrying financial facts**, including when every reference
is absent, because the field is written unconditionally and an absent one still contributes its
marker byte. The field schema stays fixed on purpose — omitting the field when null would make the
stream variable-arity and reduce the injectivity argument below from "by construction" to an
argument about what values happen to be possible. The change is safe to make without versioning the
fingerprint *only because nothing populates `financialFacts` yet*: no product module exists, so
`PostingIntent.Facts` has no production caller, and reversal and manual journals leave the list
empty and hash exactly as before. Once a product module posts, this reasoning expires: a further
change to the composition then needs the fingerprint versioned and existing rows compared with the
algorithm that wrote them, because a mismatch turns a faithful retry into a
`POSTING_REQUEST_CONFLICT` rather than a replayed receipt.

**The encoding is length-prefixed and binary, not delimiter-joined.** Each field is fed to the
digest as a one-byte null/present marker, then - when present - a fixed 4-byte big-endian length,
then the UTF-8 bytes. For a fixed field schema this is injective by construction: no value can be
mistaken for a delimiter because there is no delimiter, only counted bytes.

**`posting_request.posting_rule_version_id` moves from the claim to `markPosted`.** It cannot be
known at claim time any more - the legs, and therefore the rule version, are resolved only in
`postNew`, after the claim. `NewPostingRequest` no longer carries the field; `JournalStore.markPosted`
gains it and writes it in the same statement that flips the request to `POSTED`. This is also what
retires the review threads about retroactive rule supersession and backdated retirement breaking
retries (#84): a replay never calls the resolver at all, so it cannot be affected by a rule change
that happened after the original posting. The column continues to serve its original, narrower
purpose - lineage and audit, answering "which version produced this journal" - which nothing was
ever reading back to *re-decide* anything; the retry hazard was in re-invoking the resolver, not in
the column.

**A correction's target is validated in `post`, before the claim - not in `postNew`, and not by the
reversal service or at the API boundary.** `requireValidCorrectionTarget` reads the named request
and its journal through `JournalReadStore`, which the engine now depends on alongside the
write-only `JournalStore`, and requires that a reversal of that journal exists. Unlike everything
`postNew` runs, this check is **not** skipped on a replay, and that is deliberate rather than an
inconsistency: both facts it tests are monotonic - a target either exists or it never will, and once
reversed it stays reversed forever - so checking them can never turn a faithful replay into a
spurious failure the way re-checking the period or an account's status would. Placement ahead of
the claim also has a mechanical reason: `claimPostingRequest`'s insert writes
`corrects_posting_request_id`, and `fk_posting_request_corrects` would otherwise reject a
nonexistent target as a raw `DataIntegrityViolationException` rather than this check's named
`ResourceNotFoundException` - a gap `PostingIdempotencyIntegrationTests`' new
`"a correction naming a target this tenant does not hold is refused"` test caught when the check
was still placed in `postNew`, after the claim.

**Posting takes a shared, ascending-id-order lock on every distinct account a posting's legs
reference, after the period lock and before the legs are validated.** `GlAccountStore.lockForPosting`
is the posting-time counterpart to the existing `lockForStateChange`: `SELECT … FOR SHARE` against
the same tenant-scoped predicate. Many postings hold it on the same account concurrently - it does
not conflict with itself - the way `PostingPeriodResolver` already holds a shared lock on an open
period; it does conflict with `lockForStateChange`'s exclusive `FOR UPDATE`, so a lifecycle
transition and a posting against the same account always serialise, whichever started first.
Ascending id order is the whole deadlock story: every transaction in this codebase that locks more
than one `gl_account` row - only a multi-leg posting does - takes them in that order, and every
other caller that locks a `gl_account` row locks exactly one. A total order over the only shared
resource plus "never hold more than one class of it" is what rules out a cycle; see the
consequences below for the resulting lock class ordering.

**The same exclusive lock now guards `ChartOfAccountsService.update`'s structural-change path.**
It replaces the plain, unlocked read of the account being edited, taken after the chart-hierarchy
advisory lock and before `hasJournalLines`/`hasChildren` are consulted. That closes the identity
freeze race from the chart side: either this transaction waits for an in-flight posting's shared
lock to release and then sees the line it wrote, or the posting waits for this transaction to finish
deciding the freeze.

**`hasActivePostingRuleLegs` closes the third finding.** `GlAccountStore` gains a bounded existence
check - `posting_rule_leg` joined to `posting_rule_version` filtered to every *approved* status
(`ACTIVE`, `SUPERSEDED`, `RETIRED`; the ones `PostingRuleVersionStatus.isApproved` names, since
`governs()` lets any of them resolve a backdated posting inside their effective window - `DRAFT` and
`PENDING_APPROVAL` are the only statuses truly unreachable by the resolver), served by
`idx_posting_rule_leg_account` - and `GlAccountLifecycleService.deactivate` refuses when it
answers true, checked under the account's exclusive lock alongside the existing different-actor
control. `PostingRuleService.approve` takes the account's *shared* posting lock (not the exclusive
one - approval only reads, it does not deactivate) while validating a version's accounts, so
approving a version and deactivating an account it references can never both win: whichever
transaction locks the account first is the one whose outcome the other observes. Draft creation and
amendment do not lock, because a `DRAFT`/`PENDING_APPROVAL` version is not yet reachable by the
resolver and deactivating its account breaks nothing.

## Consequences

**Widening the fingerprint turns some current replays into conflicts.** A caller that retries the
same source reference under a different branch, or a different correction/reversal target, used to
get the original journal back; it now gets `posting_request_conflict`. That is deliberate: branch is
a reporting dimension the ledger is organised by, and correction/reversal lineage decides what the
journal *is*, so a difference in either is a materially different request, not a retry. A product
module whose branch context drifts between attempts will need to hold it steady, which is the
correct discipline for an idempotency key it owns.

**The lock-class ordering, stated once because it is easy to get wrong by accretion.** A posting may
hold, in this order and never any other: the reversal advisory lock (`JournalReversalLock`, taken by
`JournalReversalService` before it calls the engine at all) **or** the `manual_journal` row lock
(taken by `ManualJournalService` before it approves through the engine); then the `posting_request`
row lock the idempotency claim holds to commit; then the tenant functional-currency advisory lock
taken **shared** (`FunctionalCurrencyLock.lockForPosting`, issue #94, the first thing `postNew`
does); then the `organisation` row shared (`FOR SHARE`, taken by
`AccountingTenantLookup.functionalCurrencyForPosting` in the very next statement - issue #121,
closed here rather than deferred); then, **for a current-dated posting only**, the `business_date`
row shared (`FOR SHARE`, taken by `AccountingBusinessDateLookup.currentBusinessDateForPosting` in
`PostingPeriodResolver.lockAndValidate` - issue #125); then, **for a backdated posting only**, the
`iam` grant rows shared - `user_organisation_membership`, then either `membership_permission` plus
`permission`, or `user_role_assignment` plus `role` plus `role_permission` plus `permission`
(`PermissionResolutionQueries.lockedBreakGlassGrant` - issue #123); then the fiscal-period shared
row lock; then the `gl_account` shared row locks in ascending id order; and last the
`reference_sequence` row lock `allocateNumber` takes.
`ChartOfAccountsService` may hold, in order: the chart-hierarchy advisory lock, then one
`gl_account` exclusive row lock.
`GlAccountLifecycleService` and `PostingRuleService.approve` each hold at most one lock class beyond
their own aggregate's lock (the account lock) at a time. No flow acquires a lock class from another
flow's prefix, so no cycle is representable: the `reference_sequence` row is the terminal lock of a
posting, and the `gl_account` locks are terminal in every *other* flow that takes them, which is
what a future addition must preserve. A new lock class introduced
"beside" these without restating this ordering is exactly how a review round becomes a production
deadlock instead of a race.

The functional-currency lock is the worked example of adding one, and of stating the resulting
property carefully. It is **not** the first lock a posting takes, and an earlier revision of this
paragraph wrongly said it was: the reversal advisory lock, the `manual_journal` row lock and the
`posting_request` row lock of the claim all precede it, the last on the plainest posting path there
is. What is true, and what the safety of the placement actually rests on, is narrower:

> The functional-currency lock is the first lock `PostingEngine.postNew` takes, and **none of the
> classes that precede it in a posting is ever acquired by the currency-change flow.** The change
> takes the currency lock and then only the per-setting-key advisory lock, which nothing else in the
> codebase takes at all.

That is why no cycle is representable today, and it is the property a future change must re-check
rather than assume. The distinction is not pedantic: under the discarded "always first, therefore
acyclic" rule, an administrative flow that took the currency lock and then reversed or re-posted
outstanding journals - the redenomination boundary `accounting-foundation.md` names as future work -
would be waved through, while holding class 5 and wanting class 4 against a reversal holding 4 and
wanting 5. That is a hard deadlock the rule would have blessed.

**The `organisation` row lock exists because the isolation raise took the re-read's freshness away,
and nothing weaker replaces it.** ADR 0025 moved this path to `SERIALIZABLE`, where the engine's
pre-claim read of the functional currency and its post-lock re-read come from one snapshot: a
`base_currency` that changed in between is invisible, and the posting commits in the superseded
unit. The advisory lock cannot rescue that - `pg_advisory_xact_lock_shared` does not participate in
MVCC, so it carries neither an `EvalPlanQual` re-read nor a `40001` with it, measured both with the
lock where it stands and hoisted above the first read. Only a *locking* read of the row raises
`40001`, which is the whole reason the class exists. It does **not** replace the advisory lock: the
row lock closes the *posting's* read of the currency, the advisory lock closes the *change's* read
of "has this tenant posted", and neither subsumes the other. The pre-claim read stays unlocked on
purpose, because a replay is answered from the claim and must take no lock at all; ADR 0025 states
that trade in full.

**The new class introduces no cycle, and this was checked against the code rather than assumed.**
Three flows write the `organisation` row and none of them acquires a class from a posting's prefix.
The `base_currency` tenant-setting change (`TenantSettingsService.createOrUpdate`/`deactivate`)
takes the functional-currency advisory lock exclusively, then the per-setting-key advisory lock,
then writes `organisation_setting` - it never touches the `organisation` row at all, so the one flow
a posting can genuinely be queued behind on the advisory lock does not hold the row the posting
wants next. `organisation.base_currency_code` itself is written only by `createDraft` and
`amendDraft` in `JooqOrganisationBranchProvisioningStore`, and `amendDraft` is refused outside
`DRAFT`, where no journal can exist. The row's remaining writer is `FoundationLifecycleService`'s
organisation transition through `saveOrganisation`, which stamps `status` and `row_version` under no
advisory lock and no accounting row lock: the `organisation` row is terminal there, as `gl_account`
is terminal in every non-posting flow that takes it. That is the property a future change must
re-check - not that the two sides "look ordered the same", but that no flow holds the `organisation`
row while wanting the currency advisory lock, the `posting_request` row, a `manual_journal` row or
the reversal advisory lock.

**The `business_date` and `iam` grant-row classes introduce no cycle either, and the argument is a
property of their writers rather than of the ordering.** ADR 0026 derives it in full; the short form
belongs here, with the chain it constrains. Every lock a posting takes from class 4 onwards is
**shared**, so postings never conflict with each other there. A deadlock would therefore need a
writer of one of the new classes to also want a class a posting holds *before* it, and no such
writer exists. `BusinessDateService` is the only writer of `business_date`, and it takes no advisory
lock, no accounting row lock and no `iam` row lock — it reads `organisation` and the actor's
permissions with plain selects. `RoleManagementService` and `UserProvisioningService` are the only
writers of the grant rows, and they take nothing from accounting or lifecycle either; their one
overlap with a posting is the `audit_event` insert, and inserts do not conflict on rows. So the
posting is the only transaction that ever holds locks from both sides, and a cycle needs two. **The
property a future change must re-check is that a writer of `business_date` or of an `iam` grant row
never acquires an accounting lock or the tenant-currency advisory lock.**

**Every `business_date` mutation is now a waiter, and every one of them is now bounded.**
`startCob`, `advance`, `completeCob` and `reopen` all update that row, so each queues behind the
*current-dated* postings in flight for the tenant — which is the point, and which unbounded would
stall the tenant's whole posting path, because a queued exclusive request makes new postings queue
behind it in turn exactly as the currency change does above. Backdated corrections hold nothing
here and never make a close wait, which is the rule that decided the class is conditional.
`BusinessDateService` applies
`finaxis.lifecycle.business-date-lock-timeout` to all four and surfaces an expiry as the retryable
`lifecycle.business_date_lock_timeout`. `initialize` is excluded: it inserts a row that does not
exist yet.

**The row lock is per tuple, not per column, and that widens what aborts a posting.** Any committed
update to the tenant's `organisation` row - an activation, a suspension, a closure, each of which
moves `status` and `row_version` - conflicts with the posting's `FOR SHARE` exactly as a currency
change would, so a posting whose snapshot predates one of them aborts with `40001` rather than
reading through it. That is the accepted trade rather than something to engineer around: these are
rare administrative writes on the tenant's own root row, the retry in ADR 0025's stack re-runs the
posting at a fresh snapshot, and where the transition really does forbid posting the re-run is
refused by `requireTenantPostable` with its own named code instead of by a lock.

A replay takes neither the advisory lock nor the row lock: a retry of an already committed posting
cannot be a tenant's first, so it has nothing to serialise against, and making every retry wait on a
tenant-wide lock - or hold the tenant's root row for the length of the transaction - would undo the
claim-first property this ADR exists to establish.

**The shared mode makes concurrent postings cheap, but not free.** Shared does not conflict with
shared, so postings do not wait on *each other* - but PostgreSQL makes a request wait when it
conflicts with the *pending* queue as well as with what is granted, so once a `base_currency` change
is queued, every new posting for that tenant queues behind it. Verified against `postgres:18.4`:
with one shared lock granted and an exclusive request waiting, a second shared request on the same
key is reported `granted = false`. An unbounded exclusive wait would therefore freeze a tenant's
entire posting path for as long as one slow posting held the lock, so the change's wait is bounded
by `finaxis.accounting.functional-currency-lock-timeout` and surfaces as the retryable
`accounting.functional_currency_lock_timeout` rather than stalling the ledger. The guard also reads
`hasPostedJournals` **unlocked first**: that predicate only ever moves false to true, so an unlocked
`true` is already final and an administrator retrying against a long-trading tenant is refused
without ever queueing.

**A long-running posting now also delays a chart structural edit on any account it references**,
symmetrically with how it already delays a period close. This is the accepted cost stated in ADR
0022 for the period lock, extended to accounts: a close, a deactivation or a chart edit is a rare
administrative operation, and correctness over an account's identity and status is worth a bounded
wait rather than a race resolved by whichever transaction happens to commit last.

**Posting-rule approval reading `GlAccountStore` is not a new module dependency** - `PostingRuleService`
already depended on it for the unlocked check `requirePostableAccounts` replaces at the call site
`approve` uses.

**`FinancialTransactionAtomicityFixture` needed no new probe.** Nothing here adds a new durable
write; `markPosted`'s new column and every lock acquired are inside the same transaction and the
same statements the fixture already exercises for a standard posting.

**The fingerprint algorithm change carries no version marker, deliberately, for now.** `posting_request`
first gained a row the same feature arc that introduces this ADR shipped (`V7`); no version of the
platform has been deployed with `journal`/`posting_request` traffic under the delimiter-joined,
legs-based algorithm this ADR replaces, so there is no existing fingerprint to stay compatible with.
Should the accounting module reach a real deployment before a *future* fingerprint algorithm change,
that change will need either a `fingerprint_algorithm_version` column (`V11+`) with the outgoing
algorithm kept alongside the new one for `replay`'s comparison, or an accepted one-time cost of the
first retry against a pre-existing row becoming a spurious `posting_request_conflict`. Recorded here
so a future author does not have to re-derive why this revision felt free to change the encoding in
place.
