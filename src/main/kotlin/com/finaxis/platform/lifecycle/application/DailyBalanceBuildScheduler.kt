package com.finaxis.platform.lifecycle.application

import java.time.LocalDate
import java.util.UUID

/**
 * Schedules the accounting daily-balance build for a business date the tenant has just left.
 *
 * A port rather than a direct `JobRequestScheduler` call, following
 * `notifications.application.port.outbound.WelcomeEmailScheduler`: the application layer says
 * *what* must happen durably after this transaction, and the adapter decides that JobRunr is how.
 * Keeping the concrete scheduler out of [BusinessDateService] is what lets that service be tested
 * without a background-job runtime, and what keeps the one accounting-adjacent thing lifecycle does
 * from looking like lifecycle knowing about accounting's internals.
 *
 * **The implementation must schedule after the caller's transaction commits, not during it.** A
 * rolled-back advance must leave no build behind: the build's central claim is that the set of
 * journals recorded on that business date is closed, and that is only true once the date has
 * actually moved. Running it after the commit also keeps it off the `business_date` row lock that
 * every current-dated posting queues behind, which a rebuild inside the transaction would hold for
 * as long as the rebuild took.
 *
 * The residual failure - a process that dies between the commit and the scheduling - loses one
 * day's build, and the next build's trailing business-date re-scan picks it up. That asymmetry is
 * the reason for the ordering: there is no equivalent safety net for a build that ran for a date
 * the tenant never left.
 */
fun interface DailyBalanceBuildScheduler {
    /**
     * Schedules the build for [businessDateLeft] — the date being left behind, not the new one.
     *
     * Once the tenant's business date has moved past a day, no journal can ever again be recorded
     * against it, which is what makes the set of journals the build enumerates closed rather than
     * still filling.
     */
    fun scheduleBuild(
        organisationId: UUID,
        businessDateLeft: LocalDate,
    )
}
