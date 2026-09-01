# Accounting Gate — Phase B Implementation Plan

> Issues #36, #37, #38, #39 of epic #55. Written against `main` at `cb0abd1`, immediately after
> Phase A (#28, #29, #30, #31, #34, #35) merged.

## Headline

**Phase B is not implementable as written.** The epic makes `docs/database/accounting-erd.md` the
design authority and forbids implementers from inventing accounting columns. That document is
relationship-level only: 222 lines, a Mermaid `erDiagram`, and cross-cutting conventions. It
contains **zero column definitions** for the three tables issue #36 must create.

Its own opening claims otherwise — *"the frozen design record that issues #36, #40, #44, #46 and #47
implement verbatim, so an implementing agent never has to invent a table, a column or a
constraint"* — and for #36 that sentence is false. I wrote that document in #30, so the gap is mine.

So Phase B opens by making the authority real, not by writing SQL.

A verification pass (61 agents, five prerequisite checks, four per-issue breakdowns, four
adversarial hazard lenses, then refutation of every flagged item) confirmed **19 blocking and 26
should-fix hazards**. The ones that change the plan are listed below; the rest are in the register.

## Decisions already taken

1. **Extend the ERD to column level inside #36**, before the migration, and keep it canonical
   afterwards — the document must describe the schema at a level someone could implement from
   without reading the SQL.
2. **#36 creates five tables**, per the ERD's own classification: `accounting_fiscal_year`,
   `accounting_fiscal_period`, `gl_account`, `gl_account_transition_log`,
   `fiscal_period_transition_log`. The issue text lists three; the ERD assigns five, and #38/#39
   are budgeted zero migrations, so the logs have nowhere else to live.
3. **Stacked PRs**, one conventional commit each, gated on their own base, merged bottom-up.
4. **Inherited debt is taken only where the issue's own work requires it.**

## Prerequisite verification

| Dependency | State | What is actually true |
| --- | --- | --- |
| #30 canonical ERD | **NOT READY** | No columns for any #36 table. Conventions only. |
| #31 module boundary | **PARTIAL** | `allowedDependencies` is exactly minimal — lacks `common::transitions` and `jooq`. |
| #34 permissions | **PARTIAL** | 26 codes seeded; COA and period codes exist. Gaps below. |
| #35 concurrency | **PARTIAL** | Ports and lock shipped; the store adapter, FSM and wiring are #36/#39's. |
| Schema conventions | **READY** | V1 gives copyable precedent; two hand-maintained registries must be updated in lockstep. |

### What the ERD *does* specify, and is therefore binding

- `id UUID PRIMARY KEY DEFAULT uuidv7()` plus a client-suppliable `guid` on every accounting table.
- `organisation_id UUID NOT NULL`, composite FKs on `(organisation_id, <parent_id>)`, and a
  `uq_<table>_organisation_id UNIQUE (organisation_id, id)` target on every table that is a parent.
- The standard audit set on mutable tables, with `chk_<table>_version CHECK (row_version >= 0)`.
- `gl_account` is tenant-scoped and carries **no** `branch_id`.
- Both transition logs belong to #36.

### What is entirely absent and must be decided

`account_code` and its uniqueness rule; `account_class` and its five-value domain;
header-vs-postable
usage; `normal_balance`; the `gl_account` lifecycle status set; the manual-posting policy; the
no-destructive-delete mechanism; fiscal year and period date bounds; period number and name; the
overlap-constraint mechanism; every column of both transition logs; and every index.

## Blocking contradictions — settle these before V6 exists

A `CHECK` constraint in a migration is frozen the moment it merges. Each of these would otherwise be
decided by accident, in SQL, by whoever writes V6 first.

### B1 — Fiscal-period status is contradicted three ways

Issue #36 says `FUTURE/OPEN/CLOSED`. Shipped Phase A code declares **four** values —
`FiscalPeriodPorts.kt:21-39` has `FUTURE, OPEN, CLOSED, LOCKED`. `LOCKED` is load-bearing, not
decorative: `accounting-foundation.md:550-551` distinguishes a period that may be reopened from one
that may not, `:507-508` rejects the Fineract model *precisely* for lacking that distinction, and
`FiscalPeriodStateChangeGuard.kt:57-62` already refuses transitions out of `LOCKED`.

A `CHECK` written to the issue's three-value list would break shipped code.

**Recommendation:** adopt the four-value set and record it in the ERD.

### B2 — The rule that excluded SOFT_CLOSED would also exclude FUTURE

`SOFT_CLOSED` is deliberately absent, on the stated ground that #30 does not adopt it and adding a
state the schema will not carry is inventing design ahead of the authority
(`FiscalPeriodPorts.kt:15-17`, ADR 0022:112-114). But `FUTURE` appears in **neither** document — it
exists only in the Kotlin enum. The same standard, applied consistently, deletes it.

**Recommendation:** keep `FUTURE`, and fix the inconsistency by having the ERD adopt all four
values explicitly. The rule is sound; it was applied to one state and not the other.

### B3 — `gl_account` lifecycle states exist only in issue prose, and the two issues disagree

#36 says `DRAFT → PENDING_APPROVAL → ACTIVE → INACTIVE/REJECTED`. #38 says
`DRAFT → PENDING_APPROVAL → ACTIVE`, rejection *"back to a safe editable state or REJECTED"*, and
`ACTIVE → INACTIVE`. Neither authority document contains any of these strings.

#36 freezes the `CHECK` first; #38 designs the FSM afterwards with a zero-migration budget.

**Recommendation:** #36's ERD extension settles the state set and the transition graph, with #38
implementing it. Decide explicitly whether `REJECTED` is terminal.

### B4 — Fiscal-period maker-checker is contradicted between documents

`accounting-foundation.md` requires maker-checker for period close/reopen; ADR 0022 and the shipped
guard implement neither. No code path enforces it either way.

**Recommendation:** settle in the design authority as #39's first task, doc-only, before any code.

### B5 — `btree_gist` would flood jOOQ codegen and gate the build

Verified empirically against `postgres:18.4`: the extension is available, and a tenant-scoped
`EXCLUDE USING gist (organisation_id WITH =, daterange(start, end, '[]') WITH &&)` behaves
correctly — cross-tenant overlap accepted, same-tenant overlap rejected by PostgreSQL.

But `build.gradle.kts:223` sets `inputSchema = "public"` with **no codegen excludes**, and
`compileKotlin dependsOn jooqCodegen`. Installing the extension into `public` generates its
functions into `com.finaxis.platform.jooq` and gates every build.

*Measured after this plan was written, while implementing #36: **213 routine classes** in a new
`com.finaxis.platform.jooq.routines` package, and no UDT package at all. An earlier revision of
this paragraph estimated "~160 extension functions and 5 UDTs" from reading the extension's
catalogue rather than from running codegen. `docs/database/accounting-erd.md` carries the measured
figure.*

**Recommendation:** install into a dedicated schema with `search_path` adjusted, or add codegen
excludes. **Prove the chosen route in both harnesses** — the Zonky embedded PostgreSQL used by
codegen *and* Testcontainers — before the ERD commits to the mechanism.

### B6 — Two hand-maintained registries break the build the moment V6 lands

`FoundationApplicationTables.APPLICATION_TABLES` pins 23 tables and is scoped by its KDoc to V1;
`FoundationSchemaGuidTests` asserts every application table has a `uuidv7` `guid` against that exact
list. `FoundationJdbcEntitySchemaTests.MUTABLE_APPLICATION_TABLES` is a second, independent list.

Both must be updated in the same commit as V6, and `ADR 0015`'s "all 23 application tables" becomes
false.

## Per-issue plan

### #36 — schema (base of the stack)

Recommended as **three stacked PRs**, because the design must be reviewable before the SQL exists:

**PR1 — authority (docs only, zero code).** Extend `accounting-erd.md` to column level for all five
tables: names, types, nullability, named constraints, indexes with their justifying query, and SQL
comments for non-obvious accounting semantics. Settle B1–B4 in writing. Prove the overlap mechanism
(B5) in both PostgreSQL harnesses before committing the ERD to it. Fix the ERD's stale
self-contradicting lines, including the one telling a reader that
`accounting-dates-and-periods.md` — which holds the entire *"What issue #36 must not change"*
checklist — does not exist.

**PR2 — the migration.** `V6`, transcribing the amended ERD exactly. Five tables, the
`reference_sequence` JOURNAL backfill for existing tenants, both registry updates, and
database-level proof of every constraint: cross-tenant parent rejection, same-tenant overlap
rejection, cross-tenant overlap acceptance, code uniqueness, self-parent rejection.

**PR3 — the adapter.** Widen `allowedDependencies` before the first jOOQ import. Implement
`JooqFiscalPeriodStateStore` against the real table. Wire `PostingPeriodResolver`,
`FiscalPeriodStateChangeGuard` and the store as beans **in one change**, and invert the
bean-absence assertions that Phase A left. Delete `FiscalPeriodStandIn` and repoint S1–S8 at
`accounting_fiscal_period`, preserving the isolation assertion and the cross-tenant test.

**Prove `findCovering` against real dates.** The stand-in ignores its `postingDate` parameter and
returns hardcoded bounds, so the date predicate the entire period protocol rests on has never
executed against a database.

### #37 — GL account domain (off #36)

Value objects for code, class, normal balance, usage and status. A pure `ChartHierarchyPolicy` for
self-parent, cycle, depth and parent/child compatibility. A bounded, tenant-filtered recursive-CTE
adapter — one statement, no N+1. Structural immutability and no-destructive-delete enforced only
where "used" is knowable. Give `PostingErrorCodes.ACCOUNT_NOT_POSTABLE` its first thrower.

**Scope warning:** #37's acceptance criterion *"only ACTIVE, postable accounts may receive journal
lines"* depends on `journal_line`, which #40 creates in Phase C. #37 can enforce *eligibility*; it
cannot enforce *receipt*. Say so rather than writing a test that cannot fail.

### #38 — COA FSM and maker-checker (off #37)

Declare the GL-account FSM using the common transition infrastructure. Enforce approver ≠ submitter
as a transition guard. Bind the Kotlin state enum to the shipped `CHECK` constraint so the two
cannot drift.

**Two structural decisions #38 inherits, both currently unowned:**
- `TransitionLogRepository` is a single unqualified bean whose only implementation is lifecycle's.
  Accounting needs its own path — a qualified bean, a registry, or a module-local executor.
- `HighRiskOperationAuditCoverageTests.lifecycleDerivedAuditActions()` hard-codes five foundation
  prefixes, so an accounting FSM-derived action is invisible to the ratchet and its entries become
  undischargeable.

### #39 — fiscal-period lifecycle (off #36, parallel to #37)

Settle B4 first, doc-only. Define the period FSM. Implement open, close, reopen and lock. Publish
the error codes currently private to the guard into the public contract. Bound the close path with a
transaction-scoped `lock_timeout` — ADR 0022 assigns this to #39 and it appears in no test and no
configuration today.

## Delivery

```
main ──▶ #36 (PR1 docs → PR2 migration → PR3 adapter)
             ├──▶ #37 ──▶ #38
             └──▶ #39
```

One conventional commit per PR. Every branch gated with `./gradlew qualityGate` **on its own base**.
Merged bottom-up. Rebased, never merged, with `--force-with-lease`.

## Testing rules carried from Phase A

- **Any guard asserting the absence of something must be mutation-tested.** Five such guards shipped
  in Phase A green against exactly the code they forbade.
- **Deleting `FiscalPeriodStandIn` has zero enforcement.** #36 can ship a real table and adapter
with
  a fully green build while the strongest concurrency proof still runs against
  `organisation_setting`.
  Make that mechanical, not a checklist item.
- **`ACCOUNTING_TABLE_TYPES` is kept in sync with the ERD by a comment.** #36 activates that
boundary
  rule for the first time.
- Testcontainers PostgreSQL for anything schema or concurrency related. Never mocks alone.

## Open questions

1. `normal_balance` — stored on `gl_account`, or derived from `account_class`?
2. Does Finaxis adopt a stored header-vs-postable distinction at all? It is currently cited only as
   Fineract's model in a benchmark table, never as a Finaxis adoption.
3. Period bounds — `start_date` + `end_date` stored, or `start_date` only with the end derived?
4. Does `accounting_fiscal_year` carry its own status and FSM? V5 seeds `fiscal_year.open`/`close`
   permissions, but no year state model or year transition log exists in any design record.
5. Is `REJECTED` terminal, or does rejection return a GL account to `DRAFT`?
6. Is fiscal-period reopen a real submit/approve permission pair, or an actor-identity check?
