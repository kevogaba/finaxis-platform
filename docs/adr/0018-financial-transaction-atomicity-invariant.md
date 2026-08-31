# ADR 0018: Financial Transaction Atomicity Invariant

## Status

Accepted

Date: 2026-08-31

## Context

The accounting foundation (epic #55) rests on one guarantee: a balance-affecting product
transaction commits its source-domain mutation, its subsidiary-ledger effect and its general-ledger
posting together, or commits none of them. Every later decision — immutable journals, derived
balances, control-account reconciliation — assumes it. GitHub issue #29 makes proving it a gate
that must pass before any journal or posting code is accepted.

The guarantee was not previously asserted anywhere. This repository has no custom jOOQ
configuration, no custom `ConnectionProvider` or `TransactionProvider`, and no explicit
`@EnableTransactionManagement`: the property that a jOOQ statement joins the ambient Spring
transaction comes entirely from Spring Boot's `JooqAutoConfiguration`. That is the standard
arrangement, but nothing in the test suite checked it, so an autoconfiguration change could have
removed it silently.

Issue #12 made this urgent. Filed 2026-07-15, it reported that a jOOQ write survived a failing
`@Transactional` method across three "independent" mechanisms, and concluded that rollback might be
broken app-wide. If true, the accounting gate could not proceed.

Investigating it produced three findings. First, two tests already on `main` contradict the report
and nobody had connected them to it: `JooqIdempotencyStoreTests` performs a real jOOQ insert inside
a `TransactionTemplate`, throws, and asserts the table is empty; and
`InitialAdministratorBootstrapFailureRecorderIntegrationTests` asserts `attempts == 0`, proving an
`ATTEMPTS = ATTEMPTS + 1` update inside a failed transaction was reverted. Second, the specific code
paths #12 named no longer exist — `OrganisationSettingsService` was superseded by
`TenantSettingsService`, and `@AuditedAction` with its aspect was removed on 2026-08-02 (ADR 0007,
amended) — so the original scenario cannot be re-run verbatim. Third, and decisively, the reported
*observation* is reproducible, and it is an artifact.

Spring's `TransactionAwareDataSourceProxy` binds a connection per thread. Reading through the same
`DSLContext` on the thread that holds an open transaction returns that transaction's uncommitted
state; reading from a thread with no active transaction borrows a different pooled connection and
cannot see it. An assertion made on the first kind of connection proves nothing about durability.
That single confound explains why all three of #12's mechanisms failed identically: they were not
independent — they shared one ambient transaction and one connection.

## Decision

**One runtime, one PostgreSQL transaction, all effects.** A financial operation's source-domain
mutation, subsidiary-ledger effect, general-ledger posting, transition log, audit row and
externalized-event registration commit together or not at all. Core ledger consistency never
depends on RabbitMQ, JobRunr, or eventual reconstruction.

**The production Spring and jOOQ wiring is the only transaction path.** Tests prove the invariant
through the real `PlatformTransactionManager` and the real `DSLContext`, never through a test-only
transaction path that could pass while production is broken. `JooqSpringTransactionWiringTests`
asserts the arrangement directly: the `ConnectionProvider` is a `DataSourceConnectionProvider` over
a `TransactionAwareDataSourceProxy`, the `TransactionProvider` is Boot's `SpringTransactionProvider`,
and `TenantSettingsService` carries a `TransactionInterceptor` in its advisor chain. Those are
structural. The behavioural assertion is the one that matters: two jOOQ statements inside one
`TransactionTemplate` report the same `pg_current_xact_id()`, and two statements outside any
transaction report different ones — so the first assertion cannot pass vacuously.

**`REQUIRES_NEW` is the sole permitted exception, and every use needs a stated reason.** Two uses
exist today, and both record *failure* so that it survives the rollback of the transaction that
produced it:

- `AuditService.recordIndependently`, for rejection and failure audits.
- `InitialAdministratorBootstrapFailureRecorder.markFailed`, which persists bootstrap failure
  status after the enclosing transaction rolls back.

Both are deliberate and neither is a financial write path, which is the distinction that matters:
this invariant governs the durable effects of a financial operation, not a failure record about
one. `FinancialTransactionAtomicityIntegrationTests` asserts the surviving audit row explicitly
rather than ignoring it, so the exception is visible in the test suite rather than folklore.

**Every future financial write path registers its durable effects as probes.**
`FinancialTransactionAtomicityFixture` takes an `AtomicityProbe` list supplied per test, so the
posting engine of issue #41 adds `posting_request`, `journal_entry`, `journal_line` and
product-owned sub-ledger probes without modifying the harness. A financial write path that ships
without probes has not been proven atomic.

**External publication is a post-commit-only observation.** An outbox row must not be visible to
any other connection before the producing transaction commits.
`assertVisibleOnlyAfterCommit` holds a transaction open, asserts from a second connection that the
history row, the audit row and the outbox row are all absent, then commits and asserts each appears.

**Assertions about durability must be made from a connection with no active transaction.** This is
the rule issue #12 violated. It is recorded here because it is not obvious, it is easy to get wrong
in exactly the way that produces a false alarm, and the resulting false alarm cost this project a
gate.

## Consequences

Issue #12 is settled and closed: the defect does not reproduce, the observation does, and the
observation is explained. `RollbackObservationArtifactIntegrationTests` keeps that explanation
executable — the three tests run the identical failing operation and differ only in where the
assertion's connection sits.

There is no after-commit listener anywhere in `src/main` today: `@TransactionalEventListener` and
`@ApplicationModuleListener` are unused, and Modulith runs in `outbox` externalization mode. Adding
one would change the guarantee this ADR states, so it is an ADR-level decision, not an
implementation detail. The `event_publication` probe exists to make that change loud rather than
silent.

`AuditService.recordIndependently` cannot reference rows created in the still-uncommitted enclosing
transaction, because `audit_event.organisation_id` is `NOT NULL REFERENCES organisation (id)` and
the `REQUIRES_NEW` transaction cannot see them. Tests therefore use a pre-committed organisation.
This is a latent trap for any future independent audit on a freshly created aggregate and is
recorded as a follow-up rather than fixed here.

A PostgreSQL constraint violation aborts the entire transaction: no further statement can run
inside it, so a compensating write after a constraint failure is impossible by construction. Tests
assert only from a fresh connection after rollback.

The cost is that every financial write path now owes a probe registration and an atomicity test.
That is deliberate: the alternative is discovering a partial financial write in production, where
the ledger cannot be trusted and there is no clean way back.
