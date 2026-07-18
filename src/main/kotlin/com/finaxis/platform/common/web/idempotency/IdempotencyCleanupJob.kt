package com.finaxis.platform.common.web.idempotency

import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Recurring JobRunr worker that removes one bounded batch of expired replay records. */
@Component
class IdempotencyCleanupJob(
    private val store: IdempotencyStore,
    private val properties: IdempotencyProperties,
    private val clock: Clock,
) {
    /** Deletes one configured batch so cleanup cannot monopolize the database. */
    @Job(name = "Cleanup expired API idempotency records")
    @Recurring(
        id = "api-idempotency-cleanup",
        cron = "\${finaxis.api.idempotency.cleanup-schedule}",
    )
    @Transactional
    fun cleanup() {
        store.deleteExpiredCompleted(clock.instant(), properties.cleanupBatchSize)
    }
}
