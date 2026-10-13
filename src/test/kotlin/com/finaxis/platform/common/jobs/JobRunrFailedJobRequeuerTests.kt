package com.finaxis.platform.common.jobs

import com.finaxis.platform.common.id.uuidV7
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.jobrunr.jobs.mappers.JobMapper
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.scheduling.JobRequestScheduler
import org.jobrunr.storage.ConcurrentJobModificationException
import org.jobrunr.storage.InMemoryStorageProvider
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives [JobRunrFailedJobRequeuer] against JobRunr's own in-memory storage, so every state is a
 * real JobRunr state change rather than a hand-built record.
 */
class JobRunrFailedJobRequeuerTests {
    private val storage =
        InMemoryStorageProvider().apply { setJobMapper(JobMapper(JacksonJsonMapper())) }
    private val scheduler = JobRequestScheduler(storage)
    private val requeuer = JobRunrFailedJobRequeuer(storage)

    @Test
    fun `a failed job is enqueued again under the same id and keeps its history`() {
        val jobId = enqueue()
        fail(jobId)

        assertEquals(JobRequeueOutcome.REQUEUED, requeuer.requeueIfFailed(jobId))

        val requeued = storage.getJobById(jobId)
        assertEquals(StateName.ENQUEUED, requeued.state)
        assertTrue(requeued.jobStates.any { it is FailedState }, "the failure stays in history")
    }

    @Test
    fun `a second requeue of an already requeued job is left alone`() {
        val jobId = enqueue()
        fail(jobId)
        requeuer.requeueIfFailed(jobId)

        assertEquals(JobRequeueOutcome.NOT_FAILED, requeuer.requeueIfFailed(jobId))
        assertEquals(StateName.ENQUEUED, storage.getJobById(jobId).state)
    }

    @Test
    fun `an enqueued job is left alone`() {
        val jobId = enqueue()

        assertEquals(JobRequeueOutcome.NOT_FAILED, requeuer.requeueIfFailed(jobId))
        assertEquals(StateName.ENQUEUED, storage.getJobById(jobId).state)
    }

    @Test
    fun `a scheduled job awaiting its JobRunr retry is left alone`() {
        val jobId = enqueue()
        fail(jobId)
        val job = storage.getJobById(jobId)
        job.scheduleAt(Instant.now().plusSeconds(3600), "retry")
        storage.save(job)

        assertEquals(JobRequeueOutcome.NOT_FAILED, requeuer.requeueIfFailed(jobId))
        assertEquals(StateName.SCHEDULED, storage.getJobById(jobId).state)
    }

    @Test
    fun `a succeeded job is left alone`() {
        val jobId = enqueue()
        val job = storage.getJobById(jobId)
        job.succeeded()
        storage.save(job)

        assertEquals(JobRequeueOutcome.NOT_FAILED, requeuer.requeueIfFailed(jobId))
        assertEquals(StateName.SUCCEEDED, storage.getJobById(jobId).state)
    }

    @Test
    fun `an unknown job is reported as not found and nothing is created`() {
        val jobId = uuidV7()

        assertEquals(JobRequeueOutcome.NOT_FOUND, requeuer.requeueIfFailed(jobId))
        assertFailsWith<JobNotFoundException> { storage.getJobById(jobId) }
    }

    @Test
    fun `a save that loses a race to another writer is left alone and reported as not failed`() {
        val jobId = enqueue()
        fail(jobId)
        val raced = JobRunrFailedJobRequeuer(RaceLosingStorage(storage))

        assertEquals(JobRequeueOutcome.NOT_FAILED, raced.requeueIfFailed(jobId))
        assertEquals(StateName.FAILED, storage.getJobById(jobId).state)
    }

    @Test
    fun `the dispatch key maps to the id the listener's first enqueue used`() {
        val key = "${uuidV7()}:${uuidV7()}:KEYCLOAK_PROVISIONING"

        assertEquals(UUID.nameUUIDFromBytes(key.toByteArray()), deterministicJobId(key))
        assertEquals(deterministicJobId(key), deterministicJobId(key))
        assertTrue(deterministicJobId(key) != deterministicJobId("$key:other"))
    }

    private fun enqueue(): UUID {
        val jobId = uuidV7()
        scheduler.enqueue(jobId, ProbeRequest)
        return jobId
    }

    private fun fail(jobId: UUID) {
        val job: Job = storage.getJobById(jobId)
        job.failed("probe failure", IllegalStateException("probe"))
        storage.save(job)
    }

    /**
     * Storage that loses a race: when the requeuer saves a job the store still holds as FAILED,
     * another writer has changed it first, so the save throws as JobRunr's storage does.
     */
    private class RaceLosingStorage(
        private val delegate: StorageProvider,
    ) : StorageProvider by delegate {
        override fun save(job: Job): Job {
            if (delegate.getJobById(job.id).state == StateName.FAILED) {
                throw ConcurrentJobModificationException(job)
            }
            return delegate.save(job)
        }
    }

    private object ProbeRequest : JobRequest {
        override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> =
            ProbeHandler::class.java
    }

    private class ProbeHandler : JobRequestHandler<ProbeRequest> {
        override fun run(jobRequest: ProbeRequest) = Unit
    }
}
