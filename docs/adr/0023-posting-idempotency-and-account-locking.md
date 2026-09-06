# ADR 0023: Posting Idempotency Claim Ordering And Posting-Time Account Locking

## Status

Accepted

Date: 2026-09-02

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
`JournalReversalService` before it calls the engine at all), then the fiscal-period shared row lock,
then the `gl_account` shared row locks in ascending id order. `ChartOfAccountsService` may hold, in
order: the chart-hierarchy advisory lock, then one `gl_account` exclusive row lock.
`GlAccountLifecycleService` and `PostingRuleService.approve` each hold at most one lock class beyond
their own aggregate's lock (the account lock) at a time. No flow acquires a lock class from another
flow's prefix, so no cycle is representable: the account lock is always the terminal lock in every
flow that takes it, which is what a future addition must preserve. A new lock class introduced
"beside" these without restating this ordering is exactly how a review round becomes a production
deadlock instead of a race.

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
