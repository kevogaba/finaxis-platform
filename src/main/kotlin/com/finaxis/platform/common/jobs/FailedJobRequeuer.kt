package com.finaxis.platform.common.jobs

import java.util.UUID

/**
 * Asks for a failed background job to run again under its own id, so a retry never creates a
 * second job for the same work. Only a FAILED job is requeued; a DELETED one is not (see
 * [JobRequeueOutcome.NOT_FAILED]).
 */
fun interface FailedJobRequeuer {
    /** Requeues the job only when it is FAILED; reports what it found. */
    fun requeueIfFailed(jobId: UUID): JobRequeueOutcome
}

/** What [FailedJobRequeuer.requeueIfFailed] found and did. */
enum class JobRequeueOutcome {
    /** The job was FAILED and is now enqueued again under the same id. */
    REQUEUED,

    /**
     * The job is in another state (scheduled, enqueued, processing, succeeded or deleted), or a
     * save lost a race to another writer: left alone.
     *
     * A DELETED job was removed on purpose by an operator, so it is not requeued. JobRunr keeps it
     * for 72 hours by default (`jobrunr.jobs.permanently-delete-deleted-jobs-after`) before
     * permanent deletion. Until then a re-approval answers `202` and does nothing, and the
     * listener's enqueue under the same id is skipped too, since JobRunr skips an id that already
     * exists.
     */
    NOT_FAILED,

    /** No job with that id exists. */
    NOT_FOUND,
}
