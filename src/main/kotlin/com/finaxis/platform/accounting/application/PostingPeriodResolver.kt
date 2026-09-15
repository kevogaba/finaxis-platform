package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingDates
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
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
 * Registered as a bean in
 * [com.finaxis.platform.accounting.config.AccountingModuleConfiguration]. It was deliberately not
 * one until `accounting_fiscal_period` existed: with no [FiscalPeriodStateStore] adapter behind it,
 * a `@Service` here failed application context startup for the whole platform rather than merely
 * for accounting. The store adapter and this wiring therefore landed together.
 *
 * One statement is the whole design. [FiscalPeriodStateStore.lockCoveringForPosting] carries the
 * tenant predicate, the date-range predicate and `FOR SHARE` together, so **the decision is taken
 * from the columns of the very tuple this transaction now holds**. There is no earlier, unlocked
 * answer to be tempted by: an edit that commits before the lock is either re-read by `EvalPlanQual`
 * at `READ COMMITTED`, or refused with `40001` above it, and the posting path runs above it.
 *
 * Note what that does and does not buy. The isolation level supplies none of this — a serializable
 * posting that read the period *without* `FOR SHARE` would happily commit a journal into a period a
 * concurrent close had already closed, because there is only one rw-dependency edge and no cycle
 * for SSI to break. "No journal lands in a period closed before it committed" is a linearizability
 * requirement, and the shared row lock is the entire guarantee. See
 * `docs/adr/0025-serializable-posting-and-the-covering-period-lock.md`.
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
     * A convenience over [resolveDates] followed by [lockAndValidate], kept for callers - and
     * [PostingPeriodResolverTests] - that want the dates and the lock in one call. The posting
     * engine calls the two halves separately: it needs the dates before it claims the source
     * reference (`INV-7`), and must not lock or validate the period at all until it knows the claim
     * is a new request rather than a replay of one already committed.
     *
     * Requires an active transaction: the shared lock it takes must outlive this call and be held
     * until the posting commits.
     */
    fun resolveForPosting(command: ResolvePostingPeriodCommand): ResolvedPostingPeriod {
        val dates = resolveDates(command.organisationId, command.dates)
        return lockAndValidate(command.organisationId, command.actorId, dates)
    }

    /**
     * Resolves the accounting dates only: no lock, no period lookup, no permission gate.
     *
     * Pure with respect to everything but the business-date read: given the same business date and
     * the same caller-supplied dates, it always answers the same [AccountingDates]. That is what
     * lets the posting engine compute the idempotency fingerprint, and attempt the claim, before
     * anything about the period's current state is consulted - a faithful retry must not fail here
     * merely because the period has since closed.
     */
    fun resolveDates(
        organisationId: UUID,
        dates: PostingDateRequest,
    ): AccountingDates {
        val businessDate = requireBusinessDate(organisationId)
        return PostingDatePolicy.resolve(dates, businessDate, clock.instant())
    }

    /**
     * Gates a backdated posting on [AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD], locks the
     * period covering [dates], and re-validates the freshly read status.
     *
     * Takes [dates] already resolved by [resolveDates] rather than re-deriving them, so the two
     * calls a new posting makes - one before its claim, one after - never disagree about what the
     * dates are. Requires an active transaction: the shared lock it takes must outlive this call
     * and be held until the posting commits.
     */
    fun lockAndValidate(
        organisationId: UUID,
        actorId: UUID,
        dates: AccountingDates,
    ): ResolvedPostingPeriod {
        requireActiveTransaction()

        val classification = PostingDatePolicy.classify(dates.postingDate, dates.businessDate)
        if (classification == PostingDateClassification.BACKDATED) {
            permissions.requireBreakGlassPermission(
                actorId,
                organisationId,
                AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD,
            )
        }

        val period = periods.lockCoveringForPosting(organisationId, dates.postingDate)
        requireCoveringPeriod(period, dates)

        // The date predicate is inside the locking statement, so a row that comes back is a row
        // this transaction holds FOR SHARE and whose bounds satisfied the predicate at lock time.
        // The bounds check below is now an assertion against the adapter, not a guard against a
        // race: at READ COMMITTED a bounds change makes the statement return no row at all
        // (EvalPlanQual re-evaluates the quals), and above it the statement raises 40001.
        val locked = checkNotNull(period)
        requireCovers(locked, dates)
        requireOpen(locked)

        if (classification == PostingDateClassification.BACKDATED) {
            recordPriorPeriodAuthority(organisationId, actorId, dates, locked)
        }

        return ResolvedPostingPeriod(dates, locked, classification)
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
     *
     * A serialization failure re-runs the whole posting, and with it this write, so one logical
     * backdated posting can leave one row per attempt — bounded by the retry budget. Each row is
     * true on its own terms: every attempt independently located, locked and validated the period
     * before reaching here. Group by `(actorId, tenantId, resourceId, metadata.postingDate)` to
     * count authorities exercised rather than rows written.
     */
    private fun recordPriorPeriodAuthority(
        organisationId: UUID,
        actorId: UUID,
        dates: AccountingDates,
        period: FiscalPeriodSnapshot,
    ) {
        auditService.recordIndependently(
            AuditCommand(
                actorType = ACTOR_TYPE_USER,
                actorId = actorId.toString(),
                tenantId = organisationId.toString(),
                action = AccountingAuditActions.JOURNAL_POST_PRIOR_PERIOD,
                resourceType = RESOURCE_TYPE_FISCAL_PERIOD,
                resourceId = period.key.fiscalPeriodId.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.HIGH,
                reason =
                    "Prior-period posting authority exercised on an admissible posting; " +
                        "recorded once per attempt for a posting that is retried.",
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
