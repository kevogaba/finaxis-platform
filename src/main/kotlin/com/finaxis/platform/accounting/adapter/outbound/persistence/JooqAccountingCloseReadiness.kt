package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.AccountingCloseReadiness
import com.finaxis.platform.accounting.CloseReadinessSnapshot
import com.finaxis.platform.jooq.tables.references.CONTROL_ACCOUNT_RECONCILIATION_RUN
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Answers [AccountingCloseReadiness] from `manual_journal`, `gl_account` and
 * `control_account_reconciliation_run`. Two statements, both tenant-filtered and non-locking, each
 * leading with an index that already exists, so neither scans another tenant's rows.
 */
@Component
class JooqAccountingCloseReadiness(
    private val dsl: DSLContext,
) : AccountingCloseReadiness {
    @Transactional(readOnly = true)
    override fun snapshot(organisationId: UUID): CloseReadinessSnapshot {
        val pending = DSL.count().filterWhere(MANUAL_JOURNAL.STATUS.eq(PENDING_APPROVAL))
        val drafts = DSL.count().filterWhere(MANUAL_JOURNAL.STATUS.eq(DRAFT))
        // idx_manual_journal_organisation_status (organisation_id, status): a range over the
        // tenant's open drafts only, never its posted history.
        val journals =
            dsl
                .select(pending, drafts)
                .from(MANUAL_JOURNAL)
                .where(MANUAL_JOURNAL.ORGANISATION_ID.eq(organisationId))
                .and(MANUAL_JOURNAL.STATUS.`in`(PENDING_APPROVAL, DRAFT))
                .fetchSingle()
        return CloseReadinessSnapshot(
            pendingApprovalManualJournals = journals.value1().toLong(),
            draftManualJournals = journals.value2().toLong(),
            latestBreakRuns = latestBreakRuns(organisationId),
        )
    }

    /**
     * Control accounts whose newest run is a `BREAK`. Newest is `as_of_date` then `id` (a
     * time-ordered UUIDv7), the order of `idx_control_account_reconciliation_run_account_date`,
     * so each account costs one index probe. At most one control account exists per sub-ledger
     * class (`uq_gl_account_control_kind`), which is what bounds the number of probes.
     */
    private fun latestBreakRuns(organisationId: UUID): Long {
        val run = CONTROL_ACCOUNT_RECONCILIATION_RUN
        val latestStatus =
            DSL
                .select(run.STATUS)
                .from(run)
                .where(run.ORGANISATION_ID.eq(GL_ACCOUNT.ORGANISATION_ID))
                .and(run.GL_ACCOUNT_ID.eq(GL_ACCOUNT.ID))
                .orderBy(run.AS_OF_DATE.desc(), run.ID.desc())
                .limit(1)
                .asField<String>()
        return dsl
            .selectCount()
            .from(GL_ACCOUNT)
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT.IS_CONTROL_ACCOUNT.isTrue)
            .and(latestStatus.eq(BREAK))
            .fetchSingle(0, Long::class.java) ?: 0L
    }

    private companion object {
        const val PENDING_APPROVAL = "PENDING_APPROVAL"
        const val DRAFT = "DRAFT"
        const val BREAK = "BREAK"
    }
}
