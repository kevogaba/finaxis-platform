package com.finaxis.platform.common.jobs

import org.jobrunr.jobs.Job
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.ConcurrentJobModificationException
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * JobRunr adapter of [FailedJobRequeuer]. A FAILED job is enqueued again on the same job, so its
 * stored request, its retry history and its id are unchanged; any other state is only read.
 */
@Component
class JobRunrFailedJobRequeuer(
    private val storageProvider: StorageProvider,
) : FailedJobRequeuer {
    override fun requeueIfFailed(jobId: UUID): JobRequeueOutcome {
        val job = findJob(jobId) ?: return JobRequeueOutcome.NOT_FOUND
        if (job.state != StateName.FAILED) return JobRequeueOutcome.NOT_FAILED
        job.enqueue()
        return save(job)
    }

    private fun findJob(jobId: UUID): Job? =
        try {
            storageProvider.getJobById(jobId)
        } catch (ignored: JobNotFoundException) {
            null
        }

    /**
     * A concurrent approval that requeued the job, or a worker that changed it, after this read
     * makes the save stale: the job is then no longer this caller's FAILED job, so it is left
     * alone.
     */
    private fun save(job: Job): JobRequeueOutcome =
        try {
            storageProvider.save(job)
            JobRequeueOutcome.REQUEUED
        } catch (ignored: ConcurrentJobModificationException) {
            JobRequeueOutcome.NOT_FAILED
        }
}
