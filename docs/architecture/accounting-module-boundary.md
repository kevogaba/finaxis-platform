# Accounting module boundary

> The `accounting` module owns the general ledger. Product modules reach it only through the
> posting API, and accounting never reaches back into identity or lifecycle. See
> [ADR 0020](../adr/0020-immutable-ledger-and-reversal-only-correction.md) and
> [the accounting foundation](accounting-foundation.md).

At this stage the module is **contracts, the fiscal calendar, the chart of accounts with their
lifecycles and maker-checker controls, and their adapters**. There is no journal schema yet (issue
#40) and no posting engine (issue #41). The boundary existed before any table, deliberately, so
later work cannot accidentally couple a product module to ledger persistence.

## What this module owns

- The general ledger: chart of accounts, fiscal calendar, posting requests, journals and journal
  lines — all designed in [the accounting schema](../database/accounting-erd.md). The chart of
  accounts and the fiscal calendar exist; the journal tables do not yet.
- The public posting API product modules consume.
- The narrow ports foundation modules implement on accounting's behalf.

It does **not** own product subsidiary ledgers. A savings position belongs to `savings`, a loan
position to `loans`. Accounting holds no foreign key into a product-owned table.

## What crosses the boundary

Two named interfaces, and nothing else:

| Named interface | Contains |
| --- | --- |
| `accounting::posting` | `PostingService`, its commands, `PostingReceipt`, `PostingErrorCodes` |
| `accounting::domain` | `MonetaryAmount`, `PostingSide`, `AccountingContext`, `AccountingSourceReference` |

A consuming module declares both:

```java
allowedDependencies = { "accounting::posting", "accounting::domain", ... }
```

`accounting.application.port.outbound`, `accounting.adapter..` and `accounting.config` carry **no**
`@NamedInterface` and are therefore module-internal. `AccountingBoundaryRuleTests` enforces that.

## Ports accounting declares and other modules implement

This is the repository's established inversion: the **consumer** declares the port, the **provider**
ships the adapter and lists the consumer in its own `allowedDependencies`. It is why `iam` depends
on `lifecycle` and no module depends on `iam`.

| Port (declared in `accounting`) | Implemented by | Adapter |
| --- | --- | --- |
| `AccountingPermissionGuard` | `iam` | `iam.adapter.outbound.authorization.AccountingPermissionGuardAdapter` |
| `AccountingBusinessDateLookup` | `lifecycle` | `lifecycle.adapter.outbound.accounting.LifecycleAccountingBusinessDateAdapter` |
| `AccountingTenantLookup` | `lifecycle` | `lifecycle.adapter.outbound.accounting.LifecycleAccountingTenantAdapter` |

Both provider modules gained `"accounting"` in their `allowedDependencies`. The resulting module
graph stays acyclic: `iam → lifecycle`, `iam → accounting`, `lifecycle → accounting`,
`lifecycle → notifications`, everything → `common`, persistence → `jooq`.

The ports are deliberately narrow. `AccountingTenantLookup` returns booleans rather than lifecycle
state enums, and `AccountingBusinessDateLookup` returns a `postingAllowed` flag rather than a status
string, so a new lifecycle state cannot silently change accounting behaviour — it is denied until
someone decides otherwise in the adapter.

## Business date access

Accounting needs the tenant business date, and before this module there was **no cross-module way
to read it**: `BusinessDateStore` lives in `lifecycle.application` and was called only by
`BusinessDateService`. Three options were considered.

**Chosen — accounting declares the port, lifecycle ships the adapter.** Matches the repository's
inversion convention, and points the arrow the way the future needs it: close-of-business (issue
#39) will want lifecycle to ask accounting whether every period is closed. With
`accounting → lifecycle` that later need would be a hard cycle.

**Rejected — a new `lifecycle::businessdate` named interface with accounting depending on
lifecycle.** Reverses the convention and creates exactly the cycle above.

**Rejected — moving business date into `common`.** It would drag the store, its jOOQ adapter, its
history store, its permission checks and its close-of-business state machine into a package with no
`@ApplicationModule` and no authorization. Business date is an organisation lifecycle concern with
its own state machine.

One wrinkle worth knowing: `BusinessDateService` keeps its `OPEN` constant private, so
`LifecycleAccountingBusinessDateAdapter` duplicates the literal.
`LifecycleAccountingAdapterIntegrationTests` drives a real business date through close-of-business
and asserts the adapter follows, so the duplication cannot drift undetected.

## Clock and identifier generation

Accounting takes **no port** for either, and this was decided rather than overlooked.

`java.time.Clock` *is* the platform abstraction: a JDK type, injected as the single bean from
`config.ApplicationConfiguration`, substitutable in tests with `Clock.fixed(...)`. `uuidV7()` from
`common.id` is already the only permitted generator, guarded by `IdentifierGenerationRuleTests`.
Wrapping either in an accounting-owned port would add indirection with no boundary benefit.

## Architecture tests guarding this boundary

`AccountingBoundaryRuleTests`:

1. Nothing outside accounting depends on its adapters, config or outbound ports.
2. Only accounting persistence adapters may touch generated accounting jOOQ tables — written now,
   against a reserved type-name set, so it starts guarding the moment issue #36 creates the first
   table.
3. Accounting does not depend on `iam`, `lifecycle` or `notifications`.
4. Accounting does not depend on AMQP, the outbox or JobRunr — no broker and no background job may
   sit in the posting critical path.
5. Accounting `domain` stays free of framework and persistence types.

`ModuleDependencyRuleTests` also gained two repository-wide rules this issue asked for: no
production code may depend on `RabbitTemplate`, and only messaging adapters may depend on
`org.springframework.amqp` at all. Both passed on first run, which is the point — they lock in an
invariant the codebase already held rather than forcing a change.

## Adding a product module that posts

1. Declare `"accounting::posting"` and `"accounting::domain"` in the module's `allowedDependencies`.
2. Call `PostingService.post(...)` from the application service that owns the business transaction,
   inside the same `@Transactional` boundary as the source mutation.
3. Express **posting intent** — `PostingIntent.Facts` where possible — never GL account ids.
4. Register the new durable effects as probes in `FinancialTransactionAtomicityFixture` and prove
   they commit or roll back together. See
   [financial transaction atomicity](financial-transaction-atomicity.md).
5. Add the module's BIAN mapping line and landscape section, per
   [the BIAN service landscape](bian-service-landscape.md).

## Not yet implemented

| Missing | Issue |
| --- | --- |
| Journal schema | #40 |
| `PostingService` implementation | #41 |
| Posting rules and their resolver | #44, #45 |
| Control accounts and reconciliation | #46 |
| Manual journals | #48 |
| Accounting REST adapters | #52 |

Phase A and B items that this table used to list — the fiscal-calendar and chart-of-accounts
schema (#36), the accounting permission catalogue (#34) and the fiscal-period concurrency
semantics (#35) — have shipped, together with the GL-account domain (#37), the chart-of-accounts
FSM (#38) and the fiscal-period lifecycle (#39).

Two consequences of that state are worth stating plainly. `PostingService` has **no bean**;
`AccountingModuleContextTests` asserts its absence so a later partial implementation is a visible
change rather than something that quietly starts satisfying injection points. And
`HexagonalArchitectureTest`'s web-adapter allow-list will need extending when the first accounting
controller lands in #52 — there is no accounting web adapter today, so no edit was needed here.

## Related documents

- [Accounting foundation](accounting-foundation.md)
- [Accounting schema](../database/accounting-erd.md)
- [Financial transaction atomicity](financial-transaction-atomicity.md)
- [BIAN service landscape](bian-service-landscape.md)
