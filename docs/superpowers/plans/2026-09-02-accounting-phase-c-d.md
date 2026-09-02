# Accounting Gate — Phase C and Phase D Implementation Plan

> Issues #40, #41, #42, #43 (Phase C) and #44, #45, #46, #48 (Phase D) of epic #55. Written
> against `main` at `bba43d8`, immediately after Phase B (#36, #37, #38, #39) merged.

## Headline

**Phases A and B are sound and closed; Phase C is implementable, but only after the ERD is
brought to column level for the three journal tables — the same gap Phase B found for its five.**
`docs/database/accounting-erd.md` still specifies `posting_request`, `journal_entry` and
`journal_line` at relationship level. Its own migration table promises them next; its column
definitions section covers only the `V6` tables. Phase C therefore opens exactly as Phase B did: a
docs-only pull request that makes the authority real, then the migration that transcribes it.

Two design statements in the authority cannot be implemented as written and are corrected in that
first pull request rather than frozen into SQL by accident:

1. **`posting_request` has no persistable `REJECTED` state.** The foundation's posting walkthrough
   describes status-aware collision handling with a *rejected* branch. A rejected posting rolls
   back with the transaction that attempted it (`INV-12` forbids a `REQUIRES_NEW` write in the
   posting path), so nothing is left behind to be rejected. The status domain is `PENDING`
   (visible only inside the posting transaction) and `POSTED`. A retry after a rejection finds no
   row and posts afresh, which is precisely the outcome the walkthrough wanted for the rejected
   case — reached without a second transaction.
2. **Row locks on the immutable tables are impossible under the target privilege model.**
   `SELECT … FOR UPDATE` and `FOR SHARE` require `UPDATE` privilege, and issue #54 revokes it on
   `journal_entry` and `journal_line`. Every serialisation the engine needs is therefore taken on a
   *mutable* row or an advisory lock: `reference_sequence` for numbering, `posting_request` for
   idempotency, a two-int advisory lock for reversal. The partial unique index remains the
   authoritative backstop for "at most one reversal".

## Phase A and B verification

| Check | Result |
| --- | --- |
| Issues #28–#39 | All closed. Epic #55 checkboxes for Phases A and B are ticked |
| `main` history | One conventional commit per merged pull request, stacked bottom-up as the repository rule requires |
| Quality Gate on the last Phase B head (#74, `3dcabd3`) | Green: Qodana and `qualityGate` both succeeded |
| Local build against `main` | `jooqCodegen` against embedded PostgreSQL 18, `compileKotlin`, `compileTestKotlin` all succeed on JDK 25 |
| Local test slice that needs no Docker | Architecture rules, accounting domain and application unit tests — see the pull request for the run |
| Design authority versus implementation | The `V6` migration is a transcription of the ERD's column definitions; `FiscalPeriodStatus`, `GlAccountStatus` and both transition graphs match the `CHECK` domains exactly |

Two findings, neither a defect, both folded into Phase C's first pull request because they are
documentation drift that the next reader would trip on:

- `docs/architecture/accounting-module-boundary.md` still lists #34, #35 and #36 under *Not yet
  implemented* and says there is no fiscal-calendar schema. All three shipped.
- `docs/architecture/accounting-foundation.md` names Phase B's `AccountingQueryPlanTests` as
  *introduced by #40* — correct, and now imminent — but the ERD's index section lists
  `idx_journal_line_account_date` as the one #40 index while the foundation's `INV-4` text still
  credits balance enforcement to a verification read that no issue yet owns in code. Both are
  restated where #40 and #41 implement them.

One environmental constraint is stated up front because it shapes verification: the
implementation environment for this plan has **no Docker**, so the Testcontainers suites cannot
run there directly. They are run two ways — through a local, uncommitted harness that binds
`PostgresTestConfiguration` to the same Zonky embedded PostgreSQL 18 the build already uses for
code generation plus a local Redis, and through the repository's GitHub Actions `qualityGate` on
every push. Nothing in a pull request depends on the harness; it is a development convenience and
the CI run is the gate.

## Decisions already taken

1. **Extend the ERD to column level inside #40**, before the migration, exactly as #36 did.
2. **The engine is two layers**: a module-internal `PostingEngine` that accepts fully resolved
   legs and owns every invariant, and the public `PostingService` that turns `PostingIntent.Facts`
   into legs through a `PostingLegResolver` port. Reversal (#43) and manual journals (#48) are
   accounting-owned callers of the *internal* engine, which is what "the same PostingEngine" in
   #48 and "no second posting path" mean mechanically.
3. **#41 ships the resolver port with an unconfigured implementation** that refuses every intent
   with `accounting.posting_rule_not_found`, so the application context starts and the engine is
   fully testable through a test-scoped resolver. #45 replaces the bean with the rule-backed one.
   Until then no product module can post — which is correct, because no rule exists.
4. **Idempotency is claimed with `INSERT … ON CONFLICT DO NOTHING`**, never by catching a unique
   violation: a constraint violation aborts the caller's whole transaction and the product
   module's own writes with it. The claim either inserts, or locks the existing row `FOR UPDATE`
   and compares fingerprints.
5. **Single-currency postings only.** ADR 0019 ships no rate table. The engine requires the
   transaction currency to equal the tenant's functional currency and writes `exchange_rate = 1`.
   A mismatch is `accounting.currency_not_supported`, never a silent rate of one.
6. **Stacked pull requests**, one conventional commit each, gated on their own base, merged
   bottom-up. The plan itself is a side branch off `main`, as the Phase A and B plans were.

## Per-issue plan

### #40 — journal schema (base of the stack)

**PR C1 — authority (docs only).** Column-level definitions for `posting_request`,
`journal_entry` and `journal_line`: every column, named constraint, index with its justifying
query, and the SQL comments. Settle in writing: the `posting_request` status domain; the
`entry_type` domain (`STANDARD`, `MANUAL`, `REVERSAL`, with `REVERSAL ⇔ reverses_journal_entry_id
IS NOT NULL` as a `CHECK`); that a request produces at most one journal through
`UNIQUE (organisation_id, posting_request_id)` on `journal_entry` rather than a circular foreign
key; the request fingerprint column #42 depends on; the `posting_rule_version_id` column created
now and given its foreign key by #44's migration; the `reference_sequence` backfill #36 recorded
as #40's debt; and the foreign keys that deliberately get no index on the two append-heavy
tables, with the reason.

**PR C2 — the migration.** `V7`, transcribing the amended ERD exactly. Three tables, the `V7`
index set, the `JOURNAL`/`MEMBER`/`TRANSACTION` backfill for every existing organisation, the
registry updates (`APPLICATION_TABLES`, `SHIPPED_ACCOUNTING_TABLE_TYPES`, the foundation-schema
and ADR 0015 table counts), and database-level proof of every constraint: zero and negative
amounts, an unbalanced header, a self-reversal, a cross-tenant line, a second reversal of one
journal, a `REVERSAL` with no link. Plus `AccountingQueryPlanTests` with the seeded fixture the
foundation specifies, asserting the Q1 and Q5 plans and budgets.

### #41 — posting engine (off #40)

Internal `PostingEngine` in `accounting.application.ledger`: reconcile the caller's
`AccountingContext` against `AccountingContextLookup`; check the organisation and branch are
postable; resolve dates and lock the period through `PostingPeriodResolver`; validate every leg's
account through `GlAccountPostingPolicy`; enforce balance in memory; claim the
`posting_request`; allocate `entry_number` from `reference_sequence`; write header and lines;
**re-read the lines and compare with the header before returning** — the `INV-4` enforcement
point, covered by a test that fails when the read is removed; mark the request `POSTED`.

`DefaultPostingService` implements the public port over the resolver and the engine. The
functional currency is read through a new method on `AccountingTenantLookup`, and the
"frozen once posted" rule the foundation assigns to #40 is enforced where the currency can
change — lifecycle's organisation update and the `base_currency` tenant setting — through a new
accounting-declared query port, `AccountingLedgerActivity.hasPostedJournals`. `GlAccountStore`
gains `hasJournalLines`, which completes `ChartHierarchyPolicy`'s identity freeze.

Probes for the three tables join `FoundationAtomicityProbes`; the atomicity gate proves a fake
sub-ledger write and the journal roll back together. `AccountingModuleContextTests` inverts its
"no `PostingService` bean" assertion.

### #42 — idempotency and lineage (off #41)

The claim above becomes status- and fingerprint-aware: same key and fingerprint returns the
existing receipt; same key and a different fingerprint is `accounting.posting_request_conflict`;
a concurrent duplicate blocks on the uncommitted insert and then observes one of those two. The
fingerprint is a SHA-256 over a canonical rendering of the source triple, event code, dates,
currency and legs — never the raw payload. Lineage is answered in both directions by a bounded,
permission-gated `PostingLineageService`. The concurrent-duplicate proof runs two real
transactions against PostgreSQL.

### #43 — reversal (off #42)

`reverse` requires `journal.reverse`, a reason, an original that is not itself a reversal, and an
actor other than the one who posted the original. It takes
`AdvisoryLockNamespace.ACCOUNTING_JOURNAL_REVERSAL` on the original's id, checks for an existing
reversal, and posts the mirrored legs through the engine with `entry_type = 'REVERSAL'`. The
`journal.reverse` audit action is discharged from the ratchet. `PostFinancialFactsCommand` gains
an optional `correctsPostingRequestId` so a replacement posting carries its lineage.

### #44 — posting-rule schema (off #43)

**PR D1 + D2 in one pull request**, because the ERD extension and the migration are small and
the design questions are few: `posting_rule` (tenant, `rule_code`, selectors `event_code`,
`product_class`, `currency_code` with `UNIQUE NULLS NOT DISTINCT`), `posting_rule_version`
(`version_number`, five-state status, `effective_from`/`effective_to`, a tenant-and-rule-scoped
`EXCLUDE` over the effective range for `ACTIVE` and `SUPERSEDED` versions),
`posting_rule_leg` (ordered, `direction`, `account_resolution`, `gl_account_id`,
`amount_source`, `amount_percentage`, `is_residual` with at most one residual per fact), the
version transition log, and the deferred foreign key from `posting_request`.

### #45 — rule lifecycle and resolution (off #44)

FSM `DRAFT → PENDING_APPROVAL → ACTIVE → SUPERSEDED | RETIRED`, rejection back to `DRAFT`,
approval by an actor other than the submitter, activation closing the previous version under the
rule's row lock. A deterministic resolver: exact `event_code`, most-specific selector match,
ambiguity fails fast, then the version effective on the posting date. Leg amounts by percentage
with `HALF_EVEN` at the minor unit and the residue on the `is_residual` leg. Dry-run resolves
and validates without a write. Three audit actions discharged.

### #46 — control accounts and reconciliation (off #45)

`V9`: `gl_account.is_control_account` and `control_subledger_kind` with the `CHECK`s that a
control account is postable and never accepts manual entries; `control_account_reconciliation_run`;
`idx_journal_line_subledger`. A `SubledgerProofProvider` port in the accounting root package for
future product modules. `ControlAccountReconciliationService` computes the GL side from
`journal_line`, asks the provider for the sub-ledger side, writes an evidence row, and never
touches a journal. Resolution is a separate, `CRITICAL`, different-actor operation.

### #48 — manual journals (off #46)

A dedicated `manual_journal` aggregate with lines and a transition log (`V10`), because a draft
that an accountant edits over days is not a `posting_request` — that row is created by the
engine at posting time and its mutability is bounded to the posting transaction. Approval posts
through the internal engine with `source_module = 'accounting'` and `entry_type = 'MANUAL'`, so
the journal rows are ordinary. Every line's account must be `manual_posting_allowed` and not a
control account. The last two audit actions are discharged and the ratchet reaches zero.

## Delivery

```
main ──▶ plan (this document, side branch)

main ──▶ #40 C1 docs ──▶ #40 C2 migration ──▶ #41 engine ──▶ #42 idempotency
                                                                    │
                                                                    ▼
                                                              #43 reversal
                                                                    │
                                                                    ▼
                                                              #44 schema
                                                                    │
                                                                    ▼
          #48 manual journals ◀── #46 reconciliation ◀── #45 rules
```

One conventional commit per pull request. Every branch gated with `./gradlew qualityGate` on its
own base through CI. Merged bottom-up, rebased with `--force-with-lease`.

## Open questions

Each carries a recommendation, and the stack is built on the recommendation so that a different
answer changes one pull request rather than stalls the phase.

1. **Reversal separation of duties.** *(a)* the reversing actor must differ from the actor who
   posted the original — **recommended**, the direct analogue of "the closer cannot reopen" and
   the reading of `INV-10` the fiscal-period work settled on; *(b)* single-actor with `CRITICAL`
   audit only, which lets a teller undo their own mis-key without a supervisor.
2. **Reversing a reversal.** *(a)* forbidden; correct by posting afresh — **recommended**, it
   keeps "has this journal been reversed" a one-index answer; *(b)* allowed, re-instating the
   original by implication.
3. **`account_resolution` domain in `V8`.** *(a)* `FIXED_ACCOUNT` only, widened by a later
   forward migration when a product module ships the binding — **recommended**, YAGNI; *(b)* also
   `PRODUCT_PARAMETER` now, with activation refusing such legs as unresolvable.
4. **Manual-journal persistence.** *(a)* dedicated `manual_journal` tables in #48's one
   migration — **recommended**; *(b)* a `posting_request` in a long-lived draft status with legs
   in JSONB, which needs the column decided in `V7` now and blurs the idempotency row's
   lifecycle.
5. **Automated posting and permissions.** *(a)* `PostingService.post` checks context, tenant
   postability and the backdating break-glass code but no `journal.*` code, because the catalogue
   has no automated-posting code and the product module holds the business permission —
   **recommended**; *(b)* require `journal.approve` for every automated posting, which would grant
   tellers a `CRITICAL` code.
