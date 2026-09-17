package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.AccountingLedgerActivity
import com.finaxis.platform.accounting.application.FunctionalCurrencyLock
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.config.AccountingProperties
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.persistence.TransactionLockBound
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.CannotAcquireLockException
import org.springframework.stereotype.Component
import java.util.UUID

/** Answers [AccountingLedgerActivity] from `journal_entry`, bounded to one existence probe. */
@Component
class JooqAccountingLedgerActivity(
    private val dsl: DSLContext,
    private val currency: FunctionalCurrencyLock,
    private val lockTimeout: TransactionLockBound,
    private val properties: AccountingProperties,
) : AccountingLedgerActivity {
    override fun hasPostedJournals(organisationId: UUID): Boolean =
        dsl.fetchExists(
            DSL
                .selectOne()
                .from(JOURNAL_ENTRY)
                .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId)),
        )

    /**
     * Bounded, because an unbounded wait here stalls more than the waiter.
     *
     * PostgreSQL makes a request wait when it conflicts with the *pending* queue as well as with
     * the granted locks - verified against `postgres:18.4`: with one shared lock granted and one
     * exclusive request queued, a second shared request is reported `granted = false`. So while
     * this exclusive request waits behind an in-flight posting, every new posting for the tenant
     * queues behind it, and the tenant's whole ledger is frozen for as long as the wait lasts. A
     * change that cannot get in therefore gives up and is retried, exactly as a fiscal-period close
     * does.
     */
    override fun lockFunctionalCurrencyForChange(organisationId: UUID) {
        lockTimeout.applyToCurrentTransaction(properties.functionalCurrencyLockTimeout)
        try {
            currency.lockForCurrencyChange(organisationId)
        } catch (ex: CannotAcquireLockException) {
            // 55P03, which Spring maps to CannotAcquireLockException - not QueryTimeoutException,
            // which is a sibling rather than a supertype (see FiscalPeriodLifecycleService.close).
            throw ConflictException(
                code = PostingErrorCodes.FUNCTIONAL_CURRENCY_LOCK_TIMEOUT,
                safeDetail =
                    "The organisation is busy with in-flight postings; retry the change shortly.",
                cause = ex,
            )
        }
    }
}
