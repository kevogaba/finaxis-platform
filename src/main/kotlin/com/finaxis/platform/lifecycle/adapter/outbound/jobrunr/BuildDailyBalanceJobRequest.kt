package com.finaxis.platform.lifecycle.adapter.outbound.jobrunr

import com.finaxis.platform.accounting.AccountingDayRollover
import com.finaxis.platform.lifecycle.application.DailyBalanceBuildScheduler
import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.jobrunr.scheduling.JobRequestScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDate
import java.util.UUID

/**
 * Serializable JobRunr request to settle accounting's derived balances for a business date the
 * tenant has just left behind.
 *
 * [businessDate] is the date being left, not the new one. Once the tenant's date has moved past it,
 * no journal can ever again be recorded against it, which is what makes the set of journals the
 * build enumerates closed rather than still filling.
 */
data class BuildDailyBalanceJobRequest
    @JvmOverloads
    constructor(
        val organisationId: UUID = UUID(0, 0),
        val businessDate: LocalDate = LocalDate.EPOCH,
    ) : JobRequest {
        /** Identifies the Spring-managed handler that processes this background job. */
        override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> =
            BuildDailyBalanceJobRequestHandler::class.java
    }

/**
 * JobRunr-backed implementation of the build schedule.
 *
 * **Lifecycle hosts this and accounting does the work**, which is not an arbitrary split.
 * `AccountingBoundaryRuleTests` forbids `org.jobrunr` anywhere under
 * `com.finaxis.platform.accounting..` - no broker and no background job may sit in the posting
 * critical path - and the sibling rule confines `gl_account_daily_balance` to accounting's own
 * persistence adapters, so no third module could host it either. Lifecycle's module descriptor
 * already allows both `accounting` and `common::jobs`. The rule is kept rather than narrowed; see
 * `docs/adr/0027-derived-balance-projection-and-its-build-trigger.md`.
 *
 * ## Enqueued after commit, deliberately
 *
 * JobRunr's storage provider takes its own connection rather than the caller's, so an `enqueue`
 * inside a Spring transaction is **not** rolled back with it. Calling it inline would therefore
 * schedule a build for a business date the tenant may not have left - the advance could still fail
 * on its optimistic-lock predicate, or on anything after it - and the build's central claim, that
 * the set of journals recorded on that date is closed, would be false for exactly that run.
 *
 * So the enqueue is registered as an after-commit synchronisation. That inverts which failure is
 * possible, and the one it leaves is the one already covered: a process that dies between the
 * commit and the callback loses the enqueue, and the **next** build's trailing business-date
 * re-scan picks that day up. There is no such safety net in the other direction.
 *
 * Outside a transaction it enqueues directly, so a caller that has no transaction to wait for -
 * a test, or a future operator path - still works.
 *
 * The job id is derived from the tenant and the date, following [JobRunrWelcomeEmailScheduler], so
 * a duplicate enqueue for one business date is the same job rather than a second build.
 */
@Component
class JobRunrDailyBalanceBuildScheduler(
    private val jobRequestScheduler: JobRequestScheduler,
) : DailyBalanceBuildScheduler {
    override fun scheduleBuild(
        organisationId: UUID,
        businessDateLeft: LocalDate,
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueue(organisationId, businessDateLeft)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = enqueue(organisationId, businessDateLeft)
            },
        )
    }

    private fun enqueue(
        organisationId: UUID,
        businessDateLeft: LocalDate,
    ) {
        jobRequestScheduler.enqueue(
            UUID.nameUUIDFromBytes("daily-balance:$organisationId:$businessDateLeft".toByteArray()),
            BuildDailyBalanceJobRequest(organisationId, businessDateLeft),
        )
    }
}

/**
 * Runs the build by calling accounting's own port.
 *
 * Deliberately thin, and it needs no step guard: `settleDay` is idempotent by construction - it
 * recomputes each affected series from a date forward rather than accumulating - so a JobRunr retry
 * re-does the work and reaches the same rows.
 */
@Component
class BuildDailyBalanceJobRequestHandler(
    private val dayRollover: AccountingDayRollover,
) : JobRequestHandler<BuildDailyBalanceJobRequest> {
    override fun run(jobRequest: BuildDailyBalanceJobRequest) {
        dayRollover.settleDay(jobRequest.organisationId, jobRequest.businessDate)
    }
}
