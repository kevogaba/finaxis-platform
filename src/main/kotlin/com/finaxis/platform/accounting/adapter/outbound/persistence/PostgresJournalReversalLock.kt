package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.ledger.JournalReversalLock
import com.finaxis.platform.common.persistence.AdvisoryLockNamespace
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Per-journal advisory lock over reversal, in the two-`int4` key space
 * [AdvisoryLockNamespace] reserves for accounting.
 *
 * `xact`, not `session`, so a failed reversal releases it with its transaction; blocking rather
 * than `try`, because a second reversal that arrives while the first is in flight should wait and
 * then be told the journal is already reversed, not fail with a spurious lock error. Keyed on the
 * journal, not the tenant: reversals of different journals never contend.
 */
@Component
class PostgresJournalReversalLock(
    private val dsl: DSLContext,
) : JournalReversalLock {
    override fun lockForReversal(
        organisationId: UUID,
        journalEntryId: UUID,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "A journal reversal lock is transaction-scoped and needs an active transaction."
        }
        dsl.execute(
            "select pg_advisory_xact_lock(?, ?)",
            AdvisoryLockNamespace.ACCOUNTING_JOURNAL_REVERSAL,
            AdvisoryLockNamespace.objectId("$organisationId:$journalEntryId"),
        )
    }
}
