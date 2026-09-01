package com.finaxis.platform.accounting.adapter.outbound.persistence

import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * PostgreSQL row-lock primitive for tenant-scoped state rows.
 *
 * A shared lock lets many postings proceed concurrently while blocking any close; the exclusive
 * lock makes a close wait for every in-flight posting. `FOR SHARE` is deliberate rather than
 * `FOR KEY SHARE`: the weaker mode does not conflict with `FOR NO KEY UPDATE`, which a plain
 * `UPDATE` takes, so a future close path that forgot its explicit lock would slip straight past a
 * key-share lock.
 *
 * Parameterised over the table because this contract ships before issue #36 creates
 * `accounting_fiscal_period`; the fiscal-period adapter binds it to that table with no change here.
 *
 * Both methods require an active Spring transaction. A row lock taken on an autocommit connection
 * is released before the caller can use it, which is the same hazard
 * [com.finaxis.platform.common.web.idempotency.JooqIdempotencyStore] guards against.
 */
@Component
class PostgresRowLock(
    private val dsl: DSLContext,
) {
    /**
     * Takes a shared row lock on a tenant-scoped row, returning false when no such row exists
     * **for that tenant**.
     *
     * The tenant predicate is not optional. Locking on the primary key alone would let a caller
     * that supplies another tenant's row id lock, read and then write that row, and the mismatch
     * would be invisible to any check performed on the caller's own organisation id.
     */
    fun lockForShare(
        table: Table<*>,
        idField: TableField<*, UUID?>,
        organisationField: TableField<*, UUID?>,
        id: UUID,
        organisationId: UUID,
    ): Boolean {
        requireActiveTransaction("A row lock")
        return dsl
            .select(DSL.one())
            .from(table)
            .where(idField.eq(id).and(organisationField.eq(organisationId)))
            .forShare()
            .fetch()
            .isNotEmpty()
    }

    /**
     * Takes an exclusive row lock on a tenant-scoped row, returning false when no such row exists
     * **for that tenant**. See [lockForShare] on why the tenant predicate is mandatory.
     */
    fun lockForUpdate(
        table: Table<*>,
        idField: TableField<*, UUID?>,
        organisationField: TableField<*, UUID?>,
        id: UUID,
        organisationId: UUID,
    ): Boolean {
        requireActiveTransaction("A row lock")
        return dsl
            .select(DSL.one())
            .from(table)
            .where(idField.eq(id).and(organisationField.eq(organisationId)))
            .forUpdate()
            .fetch()
            .isNotEmpty()
    }
}

/**
 * Fails fast when no Spring transaction is active.
 *
 * A row lock on an autocommit connection is released the moment the statement returns, so a caller
 * that believes it holds the row until commit holds nothing. The same applies to a write that only
 * makes sense under such a lock: [JooqFiscalPeriodStateStore.updateStatus] documents that the
 * caller is holding `FOR UPDATE`, and outside a transaction that claim is false. Shared rather than
 * duplicated, because the linearizability guarantee is only as strong as its weakest participant.
 *
 * @param operation named in the failure message so the caller can tell which contract it broke.
 */
internal fun requireActiveTransaction(operation: String) {
    check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "$operation requires an active transaction; on an autocommit connection the row lock it " +
            "relies on is released before the caller can act on it."
    }
}
