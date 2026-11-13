package com.finaxis.platform.lifecycle.adapter.inbound.jobrunr

import com.finaxis.platform.lifecycle.application.AuditRetention
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.springframework.stereotype.Component

/**
 * The recurring JobRunr trigger of the audit retention job (#278, ADR 0042), daily and off-peak by
 * default. One run is in flight across every replica (JobRunr's master schedules it and never
 * overlaps it), and each batch holds the organisation's advisory lock besides. The application
 * service does the work; this class only names the schedule.
 */
@Component
class AuditRetentionJob(
    private val retention: AuditRetention,
) {
    /** Runs the retention once. Not transactional: each batch is its own transaction. */
    @Job(name = "Delete audit events past their tenant's retention", retries = 0)
    @Recurring(
        id = "audit-retention",
        cron = $$"""${finaxis.audit.retention.schedule}""",
    )
    fun run() {
        retention.run()
    }
}
