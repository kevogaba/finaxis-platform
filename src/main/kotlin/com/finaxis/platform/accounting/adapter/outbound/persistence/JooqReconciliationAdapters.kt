package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.application.RequiredSnapshotIsolation
import com.finaxis.platform.accounting.application.SnapshotIsolationGuard
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.reconciliation.NewReconciliationRun
import com.finaxis.platform.accounting.application.reconciliation.ProofSnapshot
import com.finaxis.platform.accounting.application.reconciliation.ReconciliationRun
import com.finaxis.platform.accounting.application.reconciliation.ReconciliationRunStore
import com.finaxis.platform.accounting.application.reconciliation.ReconciliationStatus
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.jooq.tables.records.ControlAccountReconciliationRunRecord
import com.finaxis.platform.jooq.tables.references.CONTROL_ACCOUNT_RECONCILIATION_RUN
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * PostgreSQL's answer to *"which snapshot is this transaction reading from"*, and the guard that
 * the transaction is one whose answer stays true.
 *
 * Two statements, both load-bearing:
 *
 * `current_setting('transaction_isolation')` is the only honest way to learn the isolation actually
 * in force. `@Transactional(isolation = REPEATABLE_READ)` is a request, not a guarantee: Spring's
 * transaction managers ship with `validateExistingTransaction = false`, so a method that *joins* a
 * transaction already open at `READ COMMITTED` runs at `READ COMMITTED` with its declared isolation
 * silently dropped. The proof would then be exactly as torn as it was before, and nothing would
 * say so. Asking the database closes that.
 *
 * `pg_export_snapshot()` then returns a token another session can adopt with
 * `SET TRANSACTION SNAPSHOT`, valid while this transaction is open. A sub-ledger provider reading
 * on accounting's own connection already sees this snapshot; one reading on a connection of its own
 * has something it can actually honour, rather than an opaque identifier it can only log.
 */
@Component
class PostgresProofSnapshot(
    private val dsl: DSLContext,
) : ProofSnapshot,
    SnapshotIsolationGuard {
    override fun requireStableSnapshot(
        minimum: RequiredSnapshotIsolation,
        operation: String,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "$operation from one snapshot needs an active transaction."
        }
        val isolation =
            dsl.fetchValue(
                DSL.field("current_setting('transaction_isolation')", String::class.java),
            )
        // The comparison is by rank, not by set membership, so a level stronger than the one
        // demanded is accepted while a weaker one is refused. A read pair asking for repeatable
        // read is satisfied by serializable - refusing it would reject a caller whose guarantee is
        // better than the one it asked for - but a posting asking for serializable is NOT
        // satisfied by repeatable read, even though that level does hold one snapshot for the
        // transaction's whole life. Anything absent from the table - READ COMMITTED, READ
        // UNCOMMITTED - ranks 0 and is refused by every caller.
        val rank = ISOLATION_RANKS[isolation?.lowercase()] ?: 0
        if (rank < minimum.rank) {
            throw ConflictException(
                code = PostingErrorCodes.SNAPSHOT_ISOLATION_UNAVAILABLE,
                safeDetail =
                    "$operation requires at least " +
                        "${minimum.name.lowercase().replace('_', ' ')}; " +
                        "this transaction is $isolation.",
            )
        }
    }

    override fun currentSnapshotId(): String {
        requireStableSnapshot(RequiredSnapshotIsolation.REPEATABLE_READ, "A reconciliation")
        return requireNotNull(
            dsl.fetchValue(DSL.field("pg_export_snapshot()", String::class.java)),
        ) {
            "pg_export_snapshot() returned no snapshot identity"
        }
    }

    private companion object {
        /**
         * PostgreSQL's own `transaction_isolation` spellings, ranked against
         * [RequiredSnapshotIsolation.rank].
         *
         * Weaker levels are deliberately absent rather than mapped to a low number: an unknown
         * answer and `read committed` deserve the same treatment, and a lookup miss ranking 0
         * gives it to both. These strings are the only place PostgreSQL's vocabulary appears; the
         * port speaks in [RequiredSnapshotIsolation] so the application layer never has to know
         * which database is underneath it.
         */
        val ISOLATION_RANKS = mapOf("repeatable read" to 1, "serializable" to 2)
    }
}

/** Evidence rows for control-account proofs. Every statement carries the tenant predicate. */
@Component
class JooqReconciliationRunStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : ReconciliationRunStore {
    override fun create(run: NewReconciliationRun): ReconciliationRun {
        val now = OffsetDateTime.now(clock)
        return dsl
            .insertInto(CONTROL_ACCOUNT_RECONCILIATION_RUN)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.ORGANISATION_ID, run.organisationId)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.GL_ACCOUNT_ID, run.accountId)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.BRANCH_ID, run.branchId)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CONTROL_SUBLEDGER_KIND, run.kind.name)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.AS_OF_DATE, run.asOfDate)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CURRENCY_CODE, run.currencyCode)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.GL_BALANCE, run.glBalance)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.SUBLEDGER_BALANCE, run.subledgerBalance)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.TOLERANCE, run.tolerance)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.STATUS, run.status.name)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.PROVIDER, run.provider)
            .set(
                CONTROL_ACCOUNT_RECONCILIATION_RUN.SUBLEDGER_DETAIL_JSONB,
                JSONB.jsonb(objectMapper.writeValueAsString(run.detail)),
            ).set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CREATED_AT, now)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CREATED_BY, run.actorId)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.UPDATED_AT, now)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.UPDATED_BY, run.actorId)
            .returning()
            .fetchOne()!!
            .let(::toRun)
    }

    override fun find(
        organisationId: UUID,
        runId: UUID,
    ): ReconciliationRun? =
        dsl
            .selectFrom(CONTROL_ACCOUNT_RECONCILIATION_RUN)
            .where(CONTROL_ACCOUNT_RECONCILIATION_RUN.ORGANISATION_ID.eq(organisationId))
            .and(CONTROL_ACCOUNT_RECONCILIATION_RUN.ID.eq(runId))
            .fetchOne()
            ?.let(::toRun)

    override fun lock(
        organisationId: UUID,
        runId: UUID,
    ): ReconciliationRun? {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Locking a reconciliation run needs an active transaction."
        }
        return dsl
            .selectFrom(CONTROL_ACCOUNT_RECONCILIATION_RUN)
            .where(CONTROL_ACCOUNT_RECONCILIATION_RUN.ORGANISATION_ID.eq(organisationId))
            .and(CONTROL_ACCOUNT_RECONCILIATION_RUN.ID.eq(runId))
            .forUpdate()
            .fetchOne()
            ?.let(::toRun)
    }

    override fun resolve(
        organisationId: UUID,
        runId: UUID,
        reason: String,
        actorId: UUID,
        resolvedAt: Instant,
    ): Boolean =
        dsl
            .update(CONTROL_ACCOUNT_RECONCILIATION_RUN)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.STATUS, ReconciliationStatus.RESOLVED.name)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.RESOLUTION_REASON, reason)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.RESOLVED_BY, actorId)
            .set(
                CONTROL_ACCOUNT_RECONCILIATION_RUN.RESOLVED_AT,
                resolvedAt.atOffset(ZoneOffset.UTC),
            ).set(CONTROL_ACCOUNT_RECONCILIATION_RUN.UPDATED_AT, OffsetDateTime.now(clock))
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.UPDATED_BY, actorId)
            .set(
                CONTROL_ACCOUNT_RECONCILIATION_RUN.ROW_VERSION,
                CONTROL_ACCOUNT_RECONCILIATION_RUN.ROW_VERSION.plus(1),
            ).where(CONTROL_ACCOUNT_RECONCILIATION_RUN.ORGANISATION_ID.eq(organisationId))
            .and(CONTROL_ACCOUNT_RECONCILIATION_RUN.ID.eq(runId))
            .and(CONTROL_ACCOUNT_RECONCILIATION_RUN.STATUS.eq(ReconciliationStatus.BREAK.name))
            .execute() == 1

    override fun listForAccount(
        organisationId: UUID,
        accountId: UUID,
        beforeId: UUID?,
        pageSize: Int,
    ): List<ReconciliationRun> =
        dsl
            .selectFrom(CONTROL_ACCOUNT_RECONCILIATION_RUN)
            .where(CONTROL_ACCOUNT_RECONCILIATION_RUN.ORGANISATION_ID.eq(organisationId))
            .and(CONTROL_ACCOUNT_RECONCILIATION_RUN.GL_ACCOUNT_ID.eq(accountId))
            .and(
                beforeId?.let { CONTROL_ACCOUNT_RECONCILIATION_RUN.ID.lt(it) } ?: DSL.noCondition(),
            ).orderBy(CONTROL_ACCOUNT_RECONCILIATION_RUN.ID.desc())
            .limit(pageSize)
            .fetch(::toRun)

    private fun toRun(record: ControlAccountReconciliationRunRecord) =
        ReconciliationRun(
            id = record.id!!,
            organisationId = record.organisationId!!,
            accountId = record.glAccountId!!,
            branchId = record.branchId,
            kind = ControlSubledgerKind.valueOf(record.controlSubledgerKind!!),
            asOfDate = record.asOfDate!!,
            currencyCode = record.currencyCode!!,
            glBalance = record.glBalance!!,
            subledgerBalance = record.subledgerBalance!!,
            difference = record.difference!!,
            tolerance = record.tolerance!!,
            status = ReconciliationStatus.valueOf(record.status!!),
            provider = record.provider!!,
            detail =
                objectMapper.readValue(
                    record.subledgerDetailJsonb!!.data(),
                    objectMapper.typeFactory.constructMapType(
                        Map::class.java,
                        String::class.java,
                        Any::class.java,
                    ),
                ),
            resolutionReason = record.resolutionReason,
            resolvedBy = record.resolvedBy,
            resolvedAt = record.resolvedAt?.toInstant(),
            runBy = record.createdBy,
            runAt = record.createdAt!!.toInstant(),
        )
}
