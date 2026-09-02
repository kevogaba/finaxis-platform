package com.finaxis.platform.common.persistence

/**
 * Registry of PostgreSQL advisory-lock key spaces.
 *
 * PostgreSQL keeps two structurally separate advisory key spaces: the single-`bigint` form and the
 * two-`int4` form. They appear in `pg_locks` with `objsubid` 1 and 2 respectively, so a two-int
 * lock cannot collide with a single-key lock even when the numbers coincide — verified empirically
 * by `AdvisoryLockNamespaceIntegrationTests` rather than taken on trust.
 *
 * That matters here because this repository already has two single-key call sites that hash
 * arbitrary text into one shared space: tenant settings hash `"$organisationId:$settingKey"`, and
 * idempotency hashes `"$organisationId:$idempotencyKey"`. Nothing separates those two domains
 * today, so a setting key that happened to be a UUID string could in principle collide with an
 * idempotency key. Migrating them is out of scope; **new** domains use the two-int form instead of
 * adding a third occupant to that space.
 *
 * Within one class, a 32-bit [objectId] collision causes false sharing — two unrelated keys
 * serialize against each other — which costs throughput but never correctness.
 *
 * **Calling this from accounting requires a module-declaration change.** The constants are
 * `const val` and inline, leaving no bytecode trace, but [objectId] is a real method: the first
 * production call from `com.finaxis.platform.accounting` creates an
 * `accounting -> common::persistence` edge, and accounting's `@ApplicationModule` does not declare
 * it. Add `common::persistence` to `allowedDependencies` in the same change, or
 * `ApplicationModules.verify()` fails. The failure is loud, but it is easier to read here than in
 * a Modulith stack trace.
 *
 * See `docs/adr/0022-accounting-date-and-fiscal-period-concurrency.md`.
 */
object AdvisoryLockNamespace {
    /** Materializing a missing fiscal period for a tenant and date. */
    const val ACCOUNTING_FISCAL_PERIOD: Int = 1

    /** Materializing a missing fiscal year for a tenant. */
    const val ACCOUNTING_FISCAL_YEAR: Int = 2

    /**
     * Changing the structure of a tenant's chart of accounts.
     *
     * Keyed on the organisation alone, not on an account: a cycle is a property of a *pair* of
     * moves, so locking the two accounts a caller happens to name would not exclude the move that
     * completes the cycle from the other end.
     */
    const val ACCOUNTING_CHART_HIERARCHY: Int = 3

    /**
     * Reversing one journal.
     *
     * Keyed on the journal, because "at most one reversal" is a property of one journal and a row
     * lock on the immutable `journal_entry` is unavailable under the least-privilege role issue #54
     * introduces - `FOR UPDATE` needs the `UPDATE` privilege that role revokes.
     */
    const val ACCOUNTING_JOURNAL_REVERSAL: Int = 4

    /**
     * The `objid` for a two-int advisory lock, derived in the JVM so a caller never has to
     * round-trip to the database for a lock key.
     *
     * This is Java's `String.hashCode`, **not** PostgreSQL's `hashtextextended`. The two are
     * unrelated functions and do not agree on any key, so a lock key must be derived on one side
     * only: deriving it here for one caller and in SQL for another yields two different locks and
     * silently loses mutual exclusion. Every caller in a given lock domain must use this function.
     * (The repository's two pre-existing single-`bigint` sites derive their keys in SQL with
     * `hashtextextended`; they are a separate key space and are not interchangeable with this one.)
     *
     * Within one class, a 32-bit collision causes false sharing - two unrelated keys serialize
     * against each other - which costs throughput but never correctness.
     */
    fun objectId(key: String): Int = key.hashCode()
}
