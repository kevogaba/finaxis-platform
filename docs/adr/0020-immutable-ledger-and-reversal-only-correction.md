# ADR 0020: Immutable General Ledger, Reversal-Only Correction, And Ledger Architecture

## Status

Accepted

Date: 2026-08-31

## Context

Issue #30 freezes the accounting design before any table is created, so later issues consume a
decision record instead of inventing schema. This ADR covers what the ledger *is*: what is
authoritative, what is derived, who owns which rows, and how a mistake is corrected. Money
representation is ADR 0019; the transaction guarantee is ADR 0018.

The benchmarks diverge most sharply here, and the divergences are consequential rather than
stylistic. Apache Fineract's `acc_gl_journal_entry` is headerless — the entry *is* the line — and it
records a reversal by `UPDATE`-ing `reversed` and `reversal_id` on the original row. Apache OFBiz
separates `AcctgTrans` (a header carrying no account and no amount) from `AcctgTransEntry` (the
debit or credit line), and keeps period balances in a separate `GlAccountHistory` entity. Martin
Fowler's patterns
insist entries are immutable and balances are derived, and give reversal and adjustment as the
correction mechanisms. BIAN's `Position Keeping` has product domains post to a position log whose
"reconciled financial transactions are subsequently used for posting to the accounting systems".

## Decision

**A journal is a header plus lines.** `journal_entry` carries the balanced totals, the entry number,
the reversal link, the branch and the fiscal-period binding; `journal_line` carries one debit or
credit against one GL account. Fineract's headerless model is **rejected**: without a header there
is nowhere to put the balanced totals, the gapless entry number, the reversal link or the period
binding, and every "show me this journal" query becomes a self-join on a correlation column.

**Posted journals are immutable.** `journal_entry` and `journal_line` carry `created_at` and
`created_by` and deliberately **no** `updated_at`, `updated_by` or `row_version`, following the
`business_date_history` precedent already in the foundation schema. The absence is the point: there
is no column for an update to maintain, so the schema itself states the row is append-only.
Immutability is backed physically by `REVOKE UPDATE, DELETE` on a dedicated least-privilege
application role, which is declarative and visible in `\dp`. A `BEFORE UPDATE OR DELETE`
raise-exception trigger is still **rejected**, but on its merits rather than on a trigger count:
`REVOKE` states the same prohibition declaratively, is visible in `\dp` to anyone auditing the
grant, and needs no function body read to understand. What does **not** follow, and what an earlier
revision of this paragraph implied, is that a trigger is never the right instrument over these
tables. `REVOKE UPDATE, DELETE` leaves **append** open — a transaction holding only `INSERT` can
add a line to a journal that committed long ago — and
[ADR 0024](0024-journal-line-append-guard-and-trigger-policy.md) closes that with a statement-level
trigger in `V13`, and states the standard a trigger has to meet to be admitted at all. This ADR is
**amended** by 0024, not superseded: every decision recorded here still stands. Note also that
neither instrument makes the ledger physically immutable — a **new** `journal_entry` is always
insertable, because that is what a reversal is.

**The double-entry invariant is enforced by a denormalised balanced header**, because it is a
cross-row property and cannot be a single `CHECK`. `journal_entry` carries
`total_debit_functional`, `total_credit_functional` and `line_count`, with
`CHECK (total_debit_functional = total_credit_functional)`, `CHECK (total_debit_functional > 0)`
and `CHECK (line_count >= 2)`; a repeatable header-versus-lines proof query ships as both an
integration test and an operational check. A deferred `CONSTRAINT TRIGGER` over this invariant is
rejected on **cost**, not on the categorical ground the paragraph above once offered. It fires at
`COMMIT` rather than at the offending statement, so it names the transaction and not the write; and
every fixture that builds a journal row by row writes the header and each of its lines as separate
auto-commit statements, so the first line would meet a commit-time equality check against a header
declaring two and fail. `V13`'s `trg_journal_line_append_guard` is per-statement and deliberately
carries the weaker "no more than declared" predicate for exactly that reason — see
[ADR 0024](0024-journal-line-append-guard-and-trigger-policy.md).

**Correction is reversal, never mutation.** A reversal is a **new** `journal_entry` with
`entry_type = 'REVERSAL'` and `reverses_journal_entry_id` pointing at the original. Its lines mirror
the original's with `direction` flipped and the **same positive amounts** — never negative amounts.
Negative-amount storno is rejected because it makes turnover reporting wrong: a reversed 1,000
debit must appear as 1,000 of debit turnover and 1,000 of credit turnover, not as zero. The
original row is never updated, so "has this been reversed" is derived, and a partial unique index
on `(organisation_id, reverses_journal_entry_id)` enforces at most one reversal per journal while
serving the lookup. Fineract's in-place `reversed`/`reversal_id` update is **rejected** — it is an
`UPDATE` of posted financial history.

**There is no `CORRECTION` entry type and no edit path.** A correction is a reversal followed by a
fresh posting. Correction lineage lives on the *mutable* `posting_request`, in its
`corrects_posting_request_id` column, which keeps the immutable journal minimal and keeps the audit
question ("what was this fixing?") answerable without touching posted rows.

**Lineage runs business transaction → `posting_request` → `journal_entry` → `journal_line` →
product-owned subsidiary position.** Accounting owns everything up to the journal line and holds
**no** foreign key into a product-owned table, because those modules do not exist yet and a
speculative foreign key would either block their design or rot.

**There is exactly one idempotency mechanism** at the domain layer:
`posting_request.source_reference`, unique per `(organisation_id, source_module,
source_reference)`, reusing the proven `identity_dispatch_log.dispatch_key` pattern.
`source_entity_type` and `source_entity_id` are descriptive drill-down columns — not the key, and
not foreign keys. This is distinct from `api_idempotency_record`, which handles the HTTP
`Idempotency-Key` at the web boundary; conflating the two layers is how duplicate postings get
written.

**Product modules own their subsidiary ledgers; accounting owns the general ledger.** A savings
position belongs to `savings`, a loan position to `loans`. Product modules never write accounting
persistence and never choose GL accounts — they express posting intent and accounting resolves it.
BIAN's `Position Keeping` sequencing is **rejected**: the GL journal is written in the same
PostgreSQL transaction as the source mutation and the subsidiary-ledger effect, so there is no
window in which a position exists that the ledger does not know about, and no reconciliation step
that promotes positions into the ledger.

**Control accounts are a classification on `gl_account`**, via `is_control_account` and
`control_subledger_kind` — not a separate table, because a control account *is* a GL account with
no independent lifecycle. Reconciliation *runs* are separate evidence rows (issue #46).

**Balances are derived, with exactly one projection.** `gl_account_daily_balance` (issue #47) is
written only for account-branch-currency-day combinations that had movement, built by the existing
close-of-business pipeline, and **rebuildable from `journal_line` by a documented deterministic
query that ships alongside it**. No projection is ever a statutory source of truth.

What is **rejected** is any *authoritative* stored balance, and in particular a running balance
carried on the account row: that is a per-account write hotspot on every posting and a silent
divergence risk. It is worth being precise about the benchmark here, because it is easy to
misremember. OFBiz trunk does **not** carry a running balance on `GlAccount`; it keeps
`GlAccountHistory` with `openingBalance`, `postedDebits`, `postedCredits` and `endingBalance`, keyed
by `(glAccountId, organizationPartyId, customTimePeriodId)` — structurally a period rollup, close in
spirit to the projection adopted here. Finaxis differs in two respects that matter: the projection
is keyed by **day** rather than by fiscal period, so an as-of balance needs one row rather than a
period scan; and it is required to be **rebuildable by a documented query**, which makes drift
detectable rather than permanent.

**Posting rules are versioned and effective-dated** — a Finaxis addition neither benchmark has. It
is required, not decorative: a prior-period correction must re-post using the rule that was
effective on that transaction's business date, not today's rule. Fineract's overlapping mechanisms
are **rejected**: it ships `acc_accounting_rule`, `acc_product_mapping` *and*
`acc_gl_financial_activity_account`, three ways to decide which account a posting lands in, and a
reviewer cannot tell from the schema which one governs a given posting. Finaxis has one resolution
path with a single indirection point.

## Consequences

Reversal-only correction means the ledger grows on every mistake and never shrinks. That is the
intended trade: an auditor can reconstruct what was believed at every point in time, which a
mutable ledger cannot offer at any price.

The `REVOKE UPDATE, DELETE` guarantee depends on a dedicated least-privilege database role. The
application currently connects as `postgres` locally, so that role is an operations prerequisite
tracked by issue #54. Until it exists, immutability is enforced at the application layer plus
tests — which is weaker, and is stated here rather than glossed.

Denormalising `branch_id`, `business_date`, `fiscal_period_id` and the currency codes onto
`journal_line` is safe **only because** the table is append-only with a hard no-update rule: the
copies cannot drift. It removes a join from every aggregate query. OFBiz does the opposite, and its
`AcctgTransEntrySums` view exists precisely to paper over the join that decision creates.

Gapless journal numbering via the existing `reference_sequence` row serialises journal creation per
tenant, because the `UPDATE ... RETURNING` holds a row lock until commit. At the design envelope
(~50 postings/s peak against a ~200/s ceiling) this is not the bottleneck, and auditors require
gaplessness that a PostgreSQL sequence cannot provide across rollbacks. The threshold to revisit is
measured, not vibes: posting p99 above 20ms *and* `reference_sequence` lock-wait above 10% of it.

Holding no foreign key into product-owned subsidiary tables means GL-to-subledger consistency is
proven by reconciliation runs rather than enforced by the database. That is a deliberate cost of
letting product modules ship independently, and it is why the reconciliation proof contract is a
first-class deliverable rather than an afterthought.
