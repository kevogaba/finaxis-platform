# Financial transaction atomicity

> A financial operation commits every durable effect it produces, or none of them. Core ledger
> consistency never depends on RabbitMQ, JobRunr, or eventual reconstruction. See
> [ADR 0018](../adr/0018-financial-transaction-atomicity-invariant.md).

This is the implementer's guide to the accounting atomicity gate (GitHub issue #29): what is
guaranteed, how it is proven, and what a new financial write path must add.

## The invariant

For one balance-affecting operation, all of the following commit together or not at all:

- the source-domain mutation (the product module's own state);
- the subsidiary-ledger effect (the product-owned position);
- the general-ledger effect (posting request, journal entry, journal lines);
- the transition log;
- the audit row;
- the externalized-event registration in the outbox.

There is exactly one documented exception: `AuditService.recordIndependently`, which uses
`REQUIRES_NEW` so a rejection or failure audit survives the rollback of the transaction that
produced it.

## How the wiring actually works

This repository has **no** custom jOOQ configuration, **no** custom `ConnectionProvider` or
`TransactionProvider`, and **no** explicit `@EnableTransactionManagement`. Everything comes from
Spring Boot's `JooqAutoConfiguration`:

```
@Transactional method
  └─ TransactionInterceptor          opens/commits via PlatformTransactionManager
       └─ DSLContext
            └─ DataSourceConnectionProvider
                 └─ TransactionAwareDataSourceProxy   ← binds the connection per thread
                      └─ HikariDataSource
```

The load-bearing property is the proxy: it hands a jOOQ statement the connection already bound to
the current thread's transaction, so jOOQ work joins that transaction instead of running on a
separate autocommit connection.

`JooqSpringTransactionWiringTests` asserts each layer, and — because type assertions alone cannot
prove behaviour — asserts that two jOOQ statements inside one `TransactionTemplate` report the same
`pg_current_xact_id()` while two statements outside any transaction report different ones.

## Connection affinity: the trap

**Assertions about durability must be made from a connection with no active transaction.**

`TransactionAwareDataSourceProxy` binds per thread. So:

| Where the assertion runs | What it sees |
| --- | --- |
| The thread holding the open transaction | That transaction's **uncommitted** state |
| A thread with no active transaction | Only **committed** state |

Reading through the same `DSLContext` on the thread that holds an open transaction therefore
returns the write you just made, whether or not it will ever commit. That reading proves nothing
about durability.

This is exactly what GitHub issue #12 hit. It reported a jOOQ write surviving a failing
`@Transactional` method across three "independent" mechanisms; the mechanisms were not independent,
they shared one ambient transaction and one connection.
`RollbackObservationArtifactIntegrationTests` keeps the explanation executable: three tests run the
identical failing operation and differ only in where the assertion's connection sits.

## The reusable fixture

`FinancialTransactionAtomicityFixture` is the harness every financial write path proves itself
against. Probes are supplied **per test**, so new accounting tables are covered by adding probes,
never by editing the harness.

```kotlin
data class AtomicityProbe(
    val name: String,
    val countRows: (DSLContext) -> Long,
)

class FinancialTransactionAtomicityFixture(
    dsl: DSLContext,
    transactionManager: PlatformTransactionManager,
    probes: List<AtomicityProbe>,
) {
    fun snapshot(): Map<String, Long>

    fun <E : Throwable> assertRollsBackAtomically(
        expected: KClass<E>,
        operation: (TransactionStatus) -> Unit,
    ): E

    fun assertVisibleOnlyAfterCommit(
        expectedDeltas: Map<String, Long>,
        operation: () -> Unit,
    )
}
```

`countRows` must run a single bounded query and must never open its own transaction: the fixture
evaluates it both inside the transaction under test and from a second, independent connection.

## Probes available today

`FoundationAtomicityProbes` covers the tables that exist now:

| Probe | Covers |
| --- | --- |
| `organisationSettingRows(org, key)` | All rows for a setting key, effective or closed |
| `openOrganisationSettingRows(org, key)` | Only the currently effective row |
| `businessDateHistoryRows(org)` | Append-only business-date history |
| `auditEventRows(org, action, outcome)` | Audit rows for one action and outcome |
| `organisationTransitionLogRows(entityId)` | Append-only lifecycle transition log |
| `outboxRecordRows(aggregateId)` | Namastack outbox rows mentioning an aggregate |
| `eventPublicationRows()` | Spring Modulith event publications |

`openOrganisationSettingRows` exists for a specific reason: a settings update **closes** the
previous time-effective row and inserts a replacement. Counting all rows for the key sees the
INSERT rolling back but cannot see the UPDATE rolling back. Both halves need a probe.

`outboxRecordRows` and `eventPublicationRows` use raw SQL deliberately — `outbox_record` and
`event_publication` are created by their starters outside Flyway and have no generated jOOQ
metadata.

## What the gate proves

`FinancialTransactionAtomicityIntegrationTests` covers the four failure modes issue #29 requires,
plus post-commit visibility:

| Case | Failure injected | Key assertion |
| --- | --- | --- |
| Application exception | Throw after a successful settings write | Both the INSERT and the closing UPDATE roll back; no audit row; no outbox row |
| Constraint violation | Duplicate `(organisation_id, setting_key, effective_from)` against `uq_organisation_setting_effective` | Earlier writes in the same transaction roll back |
| Failure after event registration | Throw after `BusinessDateService.advance` has written history, audit and the event | All three roll back; the business date is unchanged |
| Nested transactional services | Submit an already-submitted organisation, so the FSM rejects across two nested `@Transactional` proxies | Transition log and outbox unchanged; the `REQUIRES_NEW` rejection audit **survives** |
| Post-commit visibility | None — hold the transaction open | History, audit and outbox rows are invisible to a second connection until commit |

## Adding a probe for a new accounting table

1. Add a factory to `FoundationAtomicityProbes` returning an `AtomicityProbe` with a bounded,
   transaction-free `countRows`.
2. Include it in the probe list of the test that exercises the write path.
3. If the table is written by a closing-plus-inserting pattern, add **two** probes — one counting
   all rows, one counting only live rows — or the update half goes unproven.
4. For a table created outside Flyway, use raw SQL: there is no generated jOOQ metadata for it.

## Rules for new financial code

- Do not add an after-commit listener without an ADR. There is no `@TransactionalEventListener` or
  `@ApplicationModuleListener` in `src/main` today, and adding one changes this guarantee.
- Do not use `REQUIRES_NEW` in a posting path. The one legitimate use is an independent audit of a
  rejection, and it needs a stated reason.
- `recordIndependently` cannot reference rows created in the still-uncommitted enclosing
  transaction — `audit_event.organisation_id` is a `NOT NULL` foreign key, and the new transaction
  cannot see them. Use a pre-committed aggregate.
- After a constraint violation PostgreSQL aborts the whole transaction, so no compensating
  statement can run inside it. Assert from a fresh connection after rollback.

## Related documents

- [ADR 0018: Financial transaction atomicity invariant](../adr/0018-financial-transaction-atomicity-invariant.md)
- [ADR 0008: Namastack-only transactional outbox](../adr/0008-transactional-outbox-over-direct-amqp.md)
- [ADR 0007: Append-only audit log](../adr/0007-append-only-audit-log-and-redaction-policy.md)
- [Transactional outbox and AMQP](transactional-outbox-amqp.md)
