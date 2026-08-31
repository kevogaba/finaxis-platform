package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingDates
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.PostingDateClassification
import com.finaxis.platform.accounting.domain.PostingDatePolicy
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.util.UUID

/**
 * Resolves and locks the fiscal period a posting must commit into.
 *
 * Guarantees the linearizable outcome issue #35 requires: a posting either holds a shared lock on
 * an OPEN period for the rest of its transaction, or it fails. Never both, and never a journal
 * committed into a period the same transaction observed as closed.
 *
 * Deliberately **not** a Spring bean yet. It depends on [FiscalPeriodStateStore], whose adapter
 * arrives with `accounting_fiscal_period` in issue #36; registering it as a `@Service` before then
 * would fail application context startup for the whole platform, not merely for accounting. Issue
 * #36 adds the store adapter and the wiring together.
 *
 * The order matters and is the whole design. An unlocked lookup finds the covering period cheaply;
 * the lock is then taken; and **the decision uses the status returned by the locking read**, never
 * the one from the lookup. Under READ COMMITTED the locking read sees the latest committed value,
 * so a close that committed in between is observed rather than missed.
 */
class PostingPeriodResolver(
    private val periods: FiscalPeriodStateStore,
    private val businessDates: AccountingBusinessDateLookup,
    private val permissions: AccountingPermissionGuard,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    /** Raised when no fiscal period covers the posting date. */
    private companion object {
        // Deliberately aliases rather than re-declares: PostingErrorCodes is the public,
        // documented contract, and a private copy is how the two drift apart. An earlier revision
        // of this file declared "accounting.fiscal_period_closed" here while the public contract
        // said "accounting.period_closed" - a client keying on the published code would never
        // have matched what was actually thrown.
        const val PERIOD_NOT_FOUND = PostingErrorCodes.PERIOD_NOT_FOUND
        const val PERIOD_CLOSED = PostingErrorCodes.PERIOD_CLOSED
        const val BUSINESS_DATE_UNAVAILABLE = PostingErrorCodes.BUSINESS_DATE_UNAVAILABLE

        const val ACTOR_TYPE_USER = "USER"
        const val RESOURCE_TYPE_FISCAL_PERIOD = "FISCAL_PERIOD"
    }

    /**
     * Resolves the accounting dates, gates a backdated posting on
     * [AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD], locks the covering period, and
     * re-validates the freshly read status.
     *
     * Requires an active transaction: the shared lock it takes must outlive this call and be held
     * until the posting commits.
     */
    fun resolveForPosting(command: ResolvePostingPeriodCommand): ResolvedPostingPeriod {
        requireActiveTransaction()

        val businessDate = requireBusinessDate(command.organisationId)
        val dates = PostingDatePolicy.resolve(command.dates, businessDate, clock.instant())
        val classification = PostingDatePolicy.classify(dates.postingDate, dates.businessDate)
        if (classification == PostingDateClassification.BACKDATED) {
            permissions.requireBreakGlassPermission(
                command.actorId,
                command.organisationId,
                AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD,
            )
        }

        val candidate = periods.findCovering(command.organisationId, dates.postingDate)
        val locked = candidate?.key?.let { periods.lockForPosting(it) }
        requireCoveringPeriod(locked, dates)

        // Both decisions use the snapshot read under the lock, never `candidate`: a close - or an
        // edit to the period's bounds - may have committed between the two reads, and that is
        // precisely the race this guards. Coverage is re-checked as well as status, because the
        // pre-lock coverage answer is exactly as stale as the pre-lock status answer was.
        val period = checkNotNull(locked)
        requireCovers(period, dates)
        requireOpen(period)

        if (classification == PostingDateClassification.BACKDATED) {
            recordPriorPeriodAuthority(command, dates, period)
        }

        return ResolvedPostingPeriod(dates, period, classification)
    }

    /**
     * Records that prior-period authority was exercised on an admissible backdated posting.
     *
     * Deliberately after the period is located, locked and validated, not at the permission check.
     * An earlier revision recorded `SUCCESS` at the gate, before the period was resolved — so a
     * backdated request into a closed or missing period committed a permanent row claiming a
     * successful `journal.post_prior_period` with no journal behind it.
     *
     * Recorded with `recordIndependently` so it survives a rollback of the posting that follows.
     * That is intentional and the reason string says so: this row means *authority was exercised
     * on a posting the ledger accepted as admissible*, not *a journal was committed*. The journal's
     * own outcome is issue #41's to audit, which is the only place the journal id exists.
     */
    private fun recordPriorPeriodAuthority(
        command: ResolvePostingPeriodCommand,
        dates: AccountingDates,
        period: FiscalPeriodSnapshot,
    ) {
        auditService.recordIndependently(
            AuditCommand(
                actorType = ACTOR_TYPE_USER,
                actorId = command.actorId.toString(),
                tenantId = command.organisationId.toString(),
                action = AccountingAuditActions.JOURNAL_POST_PRIOR_PERIOD,
                resourceType = RESOURCE_TYPE_FISCAL_PERIOD,
                resourceId = period.key.fiscalPeriodId.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.HIGH,
                reason = "Prior-period posting authority exercised on an admissible posting.",
                metadata =
                    mapOf(
                        "postingDate" to dates.postingDate.toString(),
                        "businessDate" to dates.businessDate.toString(),
                    ),
            ),
        )
    }

    private fun requireActiveTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Resolving a posting period takes a row lock that must be held until commit, so it " +
                "requires an active transaction."
        }
    }

    private fun requireBusinessDate(organisationId: UUID) =
        businessDates.currentBusinessDate(organisationId)
            ?: throw ConflictException(
                code = BUSINESS_DATE_UNAVAILABLE,
                safeDetail = "The organisation has no initialized business date.",
            )

    private fun requireCoveringPeriod(
        locked: FiscalPeriodSnapshot?,
        dates: AccountingDates,
    ) {
        if (locked == null) {
            throw periodNotFound(dates)
        }
    }

    private fun requireCovers(
        period: FiscalPeriodSnapshot,
        dates: AccountingDates,
    ) {
        if (dates.postingDate < period.startDate || dates.postingDate > period.endDate) {
            throw ConflictException(
                code = PERIOD_NOT_FOUND,
                safeDetail = "No open fiscal period covers the posting date.",
            )
        }
    }

    private fun requireOpen(locked: FiscalPeriodSnapshot) {
        if (locked.status != FiscalPeriodStatus.OPEN) {
            throw ConflictException(
                code = PERIOD_CLOSED,
                safeDetail = "The fiscal period covering the posting date is not open.",
            )
        }
    }

    private fun periodNotFound(dates: AccountingDates) =
        ConflictException(
            code = PERIOD_NOT_FOUND,
            safeDetail = "No fiscal period covers the posting date ${dates.postingDate}.",
        )
}

/** Request to resolve and lock the fiscal period for one posting. */
data class ResolvePostingPeriodCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val dates: PostingDateRequest = PostingDateRequest(),
)

/** The resolved dates plus the fiscal period now locked for the caller's transaction. */
data class ResolvedPostingPeriod(
    val dates: AccountingDates,
    val period: FiscalPeriodSnapshot,
    val classification: PostingDateClassification,
)
