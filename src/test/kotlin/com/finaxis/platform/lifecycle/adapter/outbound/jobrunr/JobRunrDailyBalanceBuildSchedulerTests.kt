package com.finaxis.platform.lifecycle.adapter.outbound.jobrunr

import org.jobrunr.scheduling.JobRequestScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Proves the one thing [JobRunrDailyBalanceBuildScheduler] exists to get right: the enqueue must
 * never happen before the caller's transaction commits.
 *
 * A plain unit test rather than a Spring one, following the house style
 * `InitialAdministratorBootstrapListenerTests` already uses for a JobRunr collaborator: a mocked
 * [JobRequestScheduler] is all the class needs, and [TransactionSynchronizationManager] is driven
 * directly rather than through a real Spring transaction, since the class under test only ever
 * calls it as a static utility.
 */
class JobRunrDailyBalanceBuildSchedulerTests {
    private val jobRequestScheduler = mock<JobRequestScheduler>()
    private val scheduler = JobRunrDailyBalanceBuildScheduler(jobRequestScheduler)

    @AfterEach
    fun clearSynchronization() {
        // Always cleared, even when a test fails before reaching its own cleanup, so one test's
        // synchronization can never leak into the next and make it look like the caller had an
        // active transaction it did not.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `a build scheduled inside a transaction enqueues only after that transaction commits`() {
        TransactionSynchronizationManager.initSynchronization()
        val organisationId = UUID.randomUUID()
        val businessDateLeft = LocalDate.of(2026, 8, 10)

        scheduler.scheduleBuild(organisationId, businessDateLeft)

        // The build's central claim is that the day's journals are closed, which is only true once
        // the advance has actually committed - an enqueue registered before that would schedule a
        // build for a business date the tenant may not have left.
        verifyNoInteractions(jobRequestScheduler)

        TransactionSynchronizationUtils.triggerAfterCommit()

        val expectedJobId =
            UUID.nameUUIDFromBytes(
                "daily-balance:$organisationId:$businessDateLeft".toByteArray(),
            )
        verify(jobRequestScheduler).enqueue(
            eq(expectedJobId),
            eq(BuildDailyBalanceJobRequest(organisationId, businessDateLeft)),
        )
    }

    @Test
    fun `a build scheduled outside a transaction enqueues immediately`() {
        assertEquals(false, TransactionSynchronizationManager.isSynchronizationActive())
        val organisationId = UUID.randomUUID()
        val businessDateLeft = LocalDate.of(2026, 8, 10)

        scheduler.scheduleBuild(organisationId, businessDateLeft)

        // No transaction means no commit to wait for - a test, or a future operator path with no
        // transaction of its own, still has its build scheduled rather than silently dropped.
        val expectedJobId =
            UUID.nameUUIDFromBytes(
                "daily-balance:$organisationId:$businessDateLeft".toByteArray(),
            )
        verify(jobRequestScheduler).enqueue(
            eq(expectedJobId),
            eq(BuildDailyBalanceJobRequest(organisationId, businessDateLeft)),
        )
    }
}
