# ADR 0024: The Journal-Line Append Guard, And When A Database Trigger Is Justified

## Status

Accepted

Date: 2026-09-15

Amends [ADR 0020](0020-immutable-ledger-and-reversal-only-correction.md). Not a supersession: every
decision 0020 records still stands, its rejection of a `BEFORE UPDATE OR DELETE` trigger over the
journal tables included. What changes is the *reason* that rejection was given, and the categorical
rule three documents had drawn out of it.

One paragraph below is corrected by
[ADR 0025](0025-serializable-posting-and-the-covering-period-lock.md): the residual-window
consequence reasoned *"under `READ COMMITTED`"* about a posting path that now runs at
`SERIALIZABLE`. Condition five of the standard this record sets — *a guard's limits are written
down wherever the guarantee is claimed* — applies to this record's own limits too, so the paragraph
is amended here rather than left to be read against a protocol that has changed.

## Context

Issue #54 will `REVOKE UPDATE, DELETE ON journal_entry, journal_line` from a least-privilege
application role, and ADR 0020 records that revoke as what backs immutability physically. Issue #95
established that it does not reach as far as that sentence implies.

`REVOKE UPDATE, DELETE` constrains what may happen *to a row that exists*. It says nothing about
new rows. A transaction holding only `INSERT` on `journal_line` can add a third line to a journal
that committed yesterday declaring two. No protected row is written — the header is never touched —
and yet the journal's balance and its line count both change.
`PostingEngine.verifyHeaderAgainstLines`, the enforcement point the accounting foundation names for
`INV-4`, cannot see it: that read runs inside the transaction that creates the journal and is never
run again. The header-versus-lines proof query would find it hours later, which is detection rather
than prevention. `INV-5` as written — *"never updated and never deleted"* — was therefore true and
insufficient. The gap is **append**, and nothing in the schema closed it.

**No declarative constraint can close it.** The predicate is *"the number of `journal_line` rows
for a journal does not exceed the `line_count` on that journal's `journal_entry` row"*, which
compares an aggregate over one table with a column on another table's row. Every declarative
instrument the foundation schema uses was tried, and each fails for its own reason. A `CHECK` sees
exactly one row, and PostgreSQL rejects a `CHECK` containing a subquery outright.
`uq_journal_line_entry_number` on `(organisation_id, journal_entry_id, line_number)` already
prevents a duplicate ordinal, but the appended line simply takes `line_number = 3` and violates
nothing; no index can express a bound whose value lives on a different table's row. A foreign key
relates a row to a row and carries no cardinality bound in either direction, and an `EXCLUDE`
constraint — the mechanism `V6` used for period overlap — compares *pairs* of rows, not counts.
`journal_entry.line_count` cannot be made a generated column computed from the lines, because a
generated column may reference only the row being written. So the choice is a trigger or nothing.
Three documents — the accounting schema, the accounting foundation and ADR 0020 itself — said, in
nearly identical words, that this repository has zero triggers and that invisible PL/pgSQL business
logic is the opposite of the declarative-`CHECK` culture `V1` established. Taken at face value,
that made the answer nothing.

**A provenance trigger comparing `xmin` with the current transaction id was measured and
rejected.** The idea: a `journal_line` is legitimate only if its header was written by the same
transaction, so compare the header row's `xmin` with `pg_current_xact_id()` and reject when they
differ. Measured against `postgres:18.4`, a row inserted under `BEGIN; SAVEPOINT sp; INSERT`
carries an `xmin` equal to the **subtransaction** id while `pg_current_xact_id()` returns the
**top-level** id, so the predicate is false for a perfectly legitimate posting running under any
nested savepoint — which includes anything Spring's `@Transactional` nests and anything a
JDBC-level retry wraps. Separately and independently fatal:
`JournalSchemaFixture.insertBalancedJournal` writes the header and then each line as its own
statement, and because the schema suites carry no `@Transactional` each of those statements is its
own transaction. A provenance predicate therefore fails every journal built that way, which is
roughly a dozen existing tests across `JournalSchemaIntegrationTests`,
`JooqJournalStoreIntegrationTests`, `ManualJournalSchemaIntegrationTests` and
`ControlAccountReplacementIntegrationTests`. Both failures are in the mechanism rather than in the
tests: a guard that requires every writer to hold one transaction open across header and lines is a
guard that dictates transaction shape to its callers. What is *not* evidence here is
`AccountingQueryPlanTests`' 200,000-line seed. It writes its headers and all four of each journal's
lines in **one** set-based statement, so header and lines share a transaction and a provenance
trigger would have *passed* it. An earlier draft of this record cited that seed as a second witness
against provenance; it is the opposite shape, and citing it would have been wrong.

**A `SECURITY DEFINER` function as the only insert path was rejected too.** The idea: make the
application call a function that owns the write, and revoke direct `INSERT` on the table. It
confers nothing here. The application connects as the initdb bootstrap superuser, which **owns
every table** — `finaxis` locally per `compose.yaml`, `test` under Testcontainers — so a `SECURITY
DEFINER` function's owner and its caller are the same role, and the definer's rights it elevates to
are rights the caller already holds. It would begin to bite only once #54 introduces a role that is
*not* the owner, which means the mechanism meant to hold **until** #54 would only start working
**after** it. It also generates: jOOQ codegen reads `inputSchema = "public"` with `includeRoutines`
defaulting true, so the function would appear in `com.finaxis.platform.jooq` on every build —
exactly the outcome `V6`'s dedicated `extensions` schema exists to prevent for `btree_gist`. A
function that `RETURNS trigger` does not have that problem, because `includeTriggerRoutines`
defaults false and filters it out. That detail is not incidental: it is part of why a trigger is
the codegen-safe shape here and a plain helper function is not.

## Decision

**`V13` adds `trg_journal_line_append_guard`, backed by `fn_journal_line_append_guard`, and it is
this repository's first trigger.** It is `AFTER INSERT ON journal_line REFERENCING NEW TABLE AS
inserted FOR EACH STATEMENT`. The function makes one set-based pass over the statement's own rows,
counts what each journal the statement touched now holds, and raises when any of them holds
**more** `journal_line` rows than that journal's own `line_count` declares. Appending a third line
to a committed two-line journal makes 3 greater than 2 and is refused at the statement that did it.
The verbatim function, trigger and comments are specified in
[the accounting schema](../database/accounting-erd.md#trg_journal_line_append_guard), which is the
design authority `V13` transcribes; where this record and that document disagree about the DDL,
that document is right.

Four properties of the shipped function are decisions rather than defaults, and are recorded here
so a later edit does not drop them by accident. It raises `check_violation`, **SQLSTATE 23514**,
not the plpgsql default `raise_exception` (`P0001`): this is a check a row-level `CHECK` cannot
express, and it belongs in the same class as the `CHECK` constraints beside it, so Spring renders
it as `DataIntegrityViolationException` rather than as an `UncategorizedSQLException`. That
translation is what the guard's tests stand on: a trigger has no `pg_constraint` row to name, so
`JournalSchemaFixture.assertViolates` has nothing to match and the tests assert the SQLSTATE and
the raised message instead. It is `SECURITY INVOKER`, written out: the function reads only two
tables the caller has just written to, so `DEFINER` would confer nothing, and a privilege boundary
that starts working only after #54 is not a privilege boundary. Its `search_path` is pinned to
`pg_catalog, public, pg_temp`, because PostgreSQL searches the temporary schema first for relations
unless `pg_temp` is named explicitly, and without the pin a caller could
`CREATE TEMP TABLE journal_line` and have the guard count an empty decoy while the real append
proceeded. And it
carries a `HINT` naming reversal as the correction path, because the operator who trips this guard
needs to be told what to do instead, not only what was refused.

**The guard is "no more than declared", not "exactly as declared", and that is the load-bearing
choice.** Under an equality predicate a statement inserting the first line of a two-line journal
fails immediately, because one is not two, and every fixture in this repository that builds a
journal row by row does exactly that: `JournalSchemaFixture.insertBalancedJournal` writes the
header and then each line as its own auto-commit statement, and `JournalSchemaIntegrationTests`,
`JooqJournalStoreIntegrationTests`, `ManualJournalSchemaIntegrationTests` and
`ControlAccountReplacementIntegrationTests` all go through it. An under-count is the normal
intermediate state of a journal being built. The weaker predicate permits that and refuses only the
count going over, and nothing is given up by it: for every journal the engine commits the two forms
coincide, because `PostingEngine.writeJournal` inserts the header, inserts the lines, re-reads them
through `verifyHeaderAgainstLines` and only then calls `markPosted`, all in one transaction, so a
committed journal holding *fewer* lines than it declares is unreachable. The extra line still trips
the guard when it arrives. This is deliberately the weakest predicate that still refuses the
append; a stronger one would buy nothing the verification read does not already prove and would
cost the fixtures. (`AccountingQueryPlanTests`' seed is no evidence either way: four lines land in
the same statement against a header declaring four, so it satisfies both forms.)

**The trigger does not enforce `INV-4` and must not be described as doing so.** It bounds the line
count from above. Equality of debits and credits, and equality of the count with `line_count`,
remain the verification read's job. Two mechanisms claiming the same invariant is how one of them
quietly stops being maintained.

**The standard: when a trigger is admitted into this schema.** The old rule counted triggers. A
count is not an argument, and the moment the count had to change it left nothing behind to reason
with. This is the argument it was standing in for. A trigger is admitted only when all five
conditions below hold, and the migration that adds one states which of them it is relying on.

*One: the property is cross-row, and no declarative constraint can express it.* A `CHECK`, a unique
or partial index, an `EXCLUDE` constraint or a foreign key is always preferred, and the change must
say which were tried and why each fails, as the Context above does. `V1`'s declarative-`CHECK`
culture is not repealed by this record; it is the default a trigger has to beat, and beating it
requires showing the default cannot state the property at all.

*Two: it is a physical safety net, not business logic.* The guard restates, at the storage layer,
something the application already enforces and already tests. It decides nothing the application
does not decide, and removing it changes no behaviour any correct caller can observe. A trigger
that computes a value, routes a row, defaults a column or encodes a rule living nowhere else is
business logic in an invisible place and is still rejected. That — not the arithmetic of how many
triggers exist — was the real content of the old rule, and it survives intact.

*Three: it refuses; it never writes.* The function's only effects are raising or not raising. It
inserts nothing, updates nothing, calls nothing, and returns `NULL` from an
`AFTER … FOR EACH STATEMENT` position where the return value is ignored. A trigger that writes is
a second write path, and a second write path is how `FinancialTransactionAtomicityFixture` starts
telling the truth about fewer effects than the system has.

*Four: it fires at the offending statement, and its cost is bounded and stated.* Statement-level
with a transition table wherever a row-level trigger would multiply the work; served by an index
the schema already carries; the cost named rather than asserted. A deferred `CONSTRAINT TRIGGER`
fails this condition on both halves: it fires at `COMMIT`, far from the statement responsible, and
it would break every fixture that builds a journal across auto-commit statements by meeting the
first line with a commit-time equality check.

*Five: its limits are written down wherever the guarantee is claimed.* A guard described as
closing more than it closes is worse than no guard, because the next author stops looking. This one
has four limits. All four appear in the accounting schema and in this record, and the two that
bound what `INV-5` asserts — that the guard is incomplete without #54, and that a new
`journal_entry` stays insertable — appear in `INV-5` itself.

**ADR 0020's own trigger rejection is re-grounded, not reversed.** A `BEFORE UPDATE OR DELETE`
raise-exception trigger over the journal tables is still rejected — it fails condition one, because
`REVOKE UPDATE, DELETE` expresses exactly that property declaratively and reads out of `\dp`
without anyone opening a function body. The rejection was right; the reason given for it was a
count.

## Consequences

**The guard is complete only together with #54, and this record does not claim otherwise.**
`journal_entry.line_count` is a plain mutable column, with deliberately no `updated_at` to betray a
write. A role that can `INSERT` a `journal_line` and also `UPDATE journal_entry` can set
`line_count = 3` first and then append, and the guard sees three against three and permits it. What
`V13` closes is the append an actor holding only `INSERT` can perform — an append every actor can
perform today, because no revoke has landed yet. What #54 closes is everything that needs an
`UPDATE` to get there, `line_count` included. They are complementary and neither is sufficient
alone. Any sentence in this repository asserting that `V13` guards the append independently of #54
is wrong and should be corrected to this one.

**And #54 will need more than a revoke.** PostgreSQL does not consult privileges for a table's
owner, and a superuser bypasses them outright. The application connects as the `initdb` bootstrap
superuser, which owns every table, so `REVOKE UPDATE ON journal_entry` from that role changes
nothing at all: #54 has to move the application onto a role that does not own the journal tables,
or its revoke is decorative. This is not a new problem introduced here — it is the same fact that
makes `SECURITY DEFINER` pointless in this schema today — but recording it under `V13` matters,
because `V13`'s completeness is being staked on #54 landing. Until that role split happens, the
connection that can raise `line_count` can equally `ALTER TABLE journal_line DISABLE TRIGGER`.

**The guard fires on `INSERT` only.** `UPDATE journal_line SET journal_entry_id = …` moves a line
between journals and trips nothing; `DELETE` removes one silently. Both belong to #54's
`REVOKE UPDATE, DELETE` and neither is an append, so this is a boundary rather than a gap — but "a
committed journal cannot grow a line" is a sentence a reader will generalise, and it should not be
allowed to.

**The count is unlocked, so a residual window exists that production cannot reach.** The predicate
is evaluated once per statement with no lock on the header row. Under `READ COMMITTED`, if a
journal were ever committed holding fewer lines than it declares, two concurrent transactions could
each append one, each observe its own count satisfied, and both commit — leaving the journal over
its declared count. This was reproduced deliberately against a hand-made under-count journal, so
the window is real rather than notional. The posting path has since moved off `READ COMMITTED` —
[ADR 0025](0025-serializable-posting-and-the-covering-period-lock.md) raises it to `SERIALIZABLE`,
where the two appenders have a read-write dependency on the count each other writes to and one
aborts with `40001` — but that narrows the window to writers outside the posting path rather than
closing it, and a migration or repair script issuing an `INSERT` at the default level is still
inside it. What makes it unreachable in production is that
`writeJournal`'s verification read and `markPosted` share a transaction and no under-count journal
is ever committed. It is recorded because the fix would be `SELECT … FOR UPDATE` on the header, and
that lock is precisely what #54's revoke makes unavailable — `SELECT … FOR UPDATE` requires the
`UPDATE` privilege, which is the same reason the accounting schema already forbids row locks over
these tables. The guard and the revoke that completes it are, in this one respect, in tension, and
a future author reaching for a header lock here needs to know that before they try.

**A new `journal_entry` is still freely insertable, deliberately.** Nothing here stops a later
transaction inserting a new journal, with or without lines. That is what a reversal is, and
constraining it would break the correction model ADR 0020 adopts. The consequence is that issue #95
item 1 is narrower than *"the ledger is now physically immutable"*, and the documentation wording
change that ships with it is doing real work rather than tidying: the accounting schema and the
accounting foundation now say *"no update, no delete, no late append"* where they used to say
*"immutable"*, because the shorter word was claiming the case this record cannot close.

**The repository now has one trigger, and one is a fact rather than a budget.** The next one is
argued against the five conditions above, not against the number. A reviewer's question changes
from *"does this repository have triggers?"* to *"which condition does this one rely on, and is the
declarative alternative genuinely unable to state the property?"*, which is the question that was
worth asking all along.

**Four passages in three documents carried the categorical rule, and all four are corrected in the
same change.** The accounting schema's immutability section, the accounting foundation's
balance-invariant section, and two separate paragraphs of ADR 0020 — the second of which said only
*"rejected for the same reason as above"* and would otherwise have been left pointing at a reason
that no longer exists. A rule that survives in one of them is the one a future author will quote
back. `V7`'s header comment is the fifth site and is frozen, being a merged forward-only migration,
so `V13`'s own header answers it by name instead, which is the only mechanism a frozen file leaves.

**Codegen is unaffected, and that is a property to re-check rather than assume.** The function is
`RETURNS trigger`, so jOOQ's `includeTriggerRoutines = false` default filters it out of
`com.finaxis.platform.jooq`. A future trigger helper written as a plain `RETURNS boolean` function
in `public` would generate, and would need either the `extensions` schema treatment `V6` gave
`btree_gist` or an explicit codegen exclusion.

**`FinancialTransactionAtomicityFixture` needs no new probe.** The trigger introduces no durable
effect; it only refuses. Condition three above is what makes that statement safe to make in one
line rather than by enumeration.

**The engine pays for the guard on every posting, and the bill is small but not zero.** For an
ordinary two-line posting the recount is one index range scan against
`uq_journal_line_entry_number`, which the schema already carries for the journal drill-down. A bulk
statement does not pay that per journal: it pays one grouped pass plus the transition table the
executor materialises, measured at roughly five percent on the 200,000-line query-plan seed. At the
design envelope this is one extra index probe per posting on a path already taking a
`reference_sequence` row lock, so it is not the thing to measure first; the threshold to revisit is
the same one ADR 0020 states for gapless numbering — posting p99 above 20ms with the guard
attributable in the plan.
