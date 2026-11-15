package com.finaxis.platform.lifecycle.adapter.inbound.jobrunr

import com.finaxis.platform.lifecycle.application.BusinessDateAutoAdvance
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.springframework.stereotype.Component

/**
 * The recurring JobRunr trigger of the scheduled business-date advance (#277, ADR 0041). JobRunr's
 * master server alone schedules a recurring job, and it never creates a new instance while one is
 * still scheduled, enqueued or processing, so one run is in flight across every replica. The
 * application service does the work; this class only names the schedule.
 */
@Component
class BusinessDateAutoAdvanceJob(
    private val autoAdvance: BusinessDateAutoAdvance,
) {
    /**
     * Runs the advance once. Not transactional: each day advanced is its own transaction, so one
     * organisation's failure rolls back only that day. No retries: the next run is the retry.
     */
    @Job(name = "Advance opted-in business dates on schedule", retries = 0)
    @Recurring(
        id = "business-date-auto-advance",
        cron = $$"""${finaxis.lifecycle.business-date.auto-advance.schedule}""",
    )
    fun run() {
        autoAdvance.run()
    }
}
