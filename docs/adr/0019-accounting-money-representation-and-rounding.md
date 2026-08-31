# ADR 0019: Accounting Money Representation And Rounding

## Status

Accepted

Date: 2026-08-31

## Context

The accounting foundation (epic #55, issue #30) must fix how money is represented before any
accounting table exists, because these choices cannot be changed later without rewriting immutable
history.

Today the repository has **no** money representation at all. There is not one `NUMERIC` or
`DECIMAL` column in any migration, not one `BigDecimal` in `src/main/kotlin`, no money type, and no
currency reference table. Currency exists only as a code: `organisation.base_currency_code CHAR(3)`
with a `^[A-Z]{3}$` check, and a `base_currency` tenant setting validated against
`java.util.Currency.getAvailableCurrencies()` in `TenantSettingCatalog`. That is a clean slate,
which makes this the cheapest moment to decide and the most expensive moment to defer.

The benchmarks disagree with each other. Apache Fineract uses `DECIMAL(19,6)`. Apache OFBiz uses
`currency-amount` at `DECIMAL(18,2)` and `currency-precise` at `DECIMAL(18,3)`, and carries a
second `origAmount`/`origCurrencyUomId` pair for multi-currency. Martin Fowler's accounting patterns
insist on an explicit `Money` value object and no binary floating point but do not fix a scale.
BIAN is silent on physical representation.

## Decision

**Monetary amounts are `NUMERIC(23, 6)`, mapped to `java.math.BigDecimal`.** Six fractional digits
cover every ISO 4217 currency exponent — the maximum in use is 4 (CLF, UYW) — and leave two digits
of headroom so per-line interest accrual and allocation residue do not accumulate error at the
storage boundary. Seventeen integer digits survive any aggregate a SACCO will produce.

**Exchange rates are `NUMERIC(20, 10)` with `CHECK (exchange_rate > 0)`**, deliberately a different
type from an amount. A rate is a ratio, not a quantity of money, and giving it the money type
invites it to be summed.

**Binary floating point is banned in accounting schema and code.** No `double precision`, no `real`
column in an accounting table; no `Double` or `Float` under `com.finaxis.platform.accounting`. Both
halves are enforced rather than left to review:
`AccountingBoundaryRuleTests.accounting code never uses binary floating point` for the code, and
`FoundationSchemaNumericTypeTests` for the columns. The schema test covers every application
table rather than only accounting's, so that it is meaningful now rather than passing vacuously
until issue #36 creates the first accounting table.

**Rounding is `HALF_EVEN` at the currency's minor unit** for any amount presented or settled;
intermediate computation stays at scale 6. `BigDecimal.divide` without an explicit scale and
`RoundingMode` is banned: its default throws on a non-terminating expansion, which turns a rounding
policy question into a production incident. Enforced by
`MoneyArithmeticRuleTests.no production code divides a BigDecimal without an explicit rounding mode`.

That rule is a source scan rather than Detekt's `ForbiddenMethodCall`, and the reason is worth
recording. `ForbiddenMethodCall` matches fully qualified signatures and therefore needs type
resolution, which requires the Detekt tasks to be given a compile classpath; this build configures
none. Activating the rule was tried first and **silently matched nothing** — a banned call was
added and Detekt stayed green. A textual scan is cruder, and defeatable by aliasing, but it fires
on the overload a developer actually reaches for.

**Allocation residue is assigned, not scattered.** When an amount is split across N legs, the
posting rule marks exactly one leg `is_residual`; that leg receives the difference between the
total and the sum of the other legs. The total is never re-rounded. This makes a split reproducible
and keeps the double-entry invariant exact rather than approximately satisfied.

**Direction carries the sign; amounts are always positive.** Journal lines store
`direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT'))` alongside
`CHECK (amount > 0)`. A signed amount would make "is this a debit or a credit" depend on the
account's normal balance, which is a derived reading and therefore a bug waiting to happen; and it
would make a zero-value line representable, which hides errors. This follows Fineract and OFBiz
over Fowler's signed `Entry`, because it makes the balance invariant directly expressible as
`SUM(debit) = SUM(credit)`.

A **STORED generated column** `signed_functional_amount` is provided for ad-hoc and reconciliation
SQL, computed by the database from `direction` and `functional_amount` so it cannot drift from the
columns it derives from.

**Currency is `CHAR(3)` with `CHECK (currency_code ~ '^[A-Z]{3}$')`** — the same regex the
foundation already applies to `organisation.base_currency_code`. **There is deliberately no
`currency` reference table.** Validation stays where it already lives, in `java.util.Currency` via
`TenantSettingCatalog`. A second source of truth for currency codes is a bug factory, and a table
would have to be maintained against a standard that already ships with the JDK.

**Every money-bearing row carries five columns, always populated:** `currency_code`, `amount`,
`functional_currency_code`, `functional_amount`, `exchange_rate`. In a single-currency tenant the
two currencies are equal, the two amounts are equal, and the rate is `1`. This is OFBiz's
`origAmount`/`origCurrencyUomId` shape, adopted from day one.

**The double-entry balance invariant is enforced on `functional_amount` only.** Transaction-currency
amounts never have to balance across currencies, which is what makes a multi-currency journal
expressible at all.

## Consequences

The multi-currency columns are the one place this ADR deliberately pays a cost before the
requirement lands. That is not speculative generality: retrofitting a functional-currency column
onto an immutable table with 200M rows per year is a backfill of data nobody has — the historical
rate at each posting instant is not recoverable after the fact. Three columns now, versus an
unfixable gap later. No speculative tables and no speculative behaviour accompany them: there is no
rate table, no revaluation engine, and no multi-currency reporting until a requirement asks for it.

Scale 6 with `HALF_EVEN` means a Finaxis amount is not bit-identical to a Fineract amount at the
same nominal value if a migration ever compares them; the comparison must be at the currency's
minor unit, not at raw scale.

Banning `BigDecimal.divide` without an explicit rounding mode is a real constraint on future code
and will feel pedantic at each call site. It is kept because the failure it prevents —
`ArithmeticException` on a non-terminating decimal expansion, in a posting path, in production — is
exactly the kind of defect that is invisible in testing with round numbers.

The generated `signed_functional_amount` column costs storage on the largest table in the schema.
It is accepted because reconciliation and ad-hoc investigation are otherwise written with a `CASE`
expression that every analyst must get right by hand, and getting it wrong silently inverts a
balance.
