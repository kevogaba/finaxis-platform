package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserProvisioningRequest
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import org.awaitility.Awaitility.await
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A repeated approval while JobRunr has the failed provisioning job SCHEDULED for its own retry
 * changes nothing: no second job, no second publish, and the one retry JobRunr already scheduled
 * is the run that provisions the user. Retries stay at JobRunr's default, so the job is SCHEDULED
 * after its first failure.
 *
 * JobRunr's first retry is due `seed ^ failureCount` seconds after the failure (`RetryFilter`), and
 * the default seed of 3 gives 3 s. The background server enqueues a scheduled job as soon as it is
 * due within its 5 s poll, so with that default the SCHEDULED state lasts 0 to 5 s, and the test's
 * 500 ms poll misses it. The seed of 30 keeps the job SCHEDULED for 30 s after the failure: the
 * test sees it, repeats the approval inside that window, and the retry then runs well inside the
 * await.
 *
 * The context is single-use: it closes with the class, so its JobRunr server does not stay cached
 * (fix round 2).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
        "jobrunr.dashboard.enabled=false",
        "jobrunr.jobs.retry-back-off-time-seed=30",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class KeycloakProvisioningScheduledRetryIntegrationTests(
    organisationProvisioningService: OrganisationProvisioningService,
    branchProvisioningService: BranchProvisioningService,
    private val userProvisioningService: UserProvisioningService,
    private val storageProvider: StorageProvider,
    dsl: DSLContext,
) {
    @MockitoBean
    private lateinit var identityProvisioningGateway: IdentityProvisioningGateway

    private val fixture =
        KeycloakProvisioningRetryFixture(
            organisationProvisioningService,
            branchProvisioningService,
            userProvisioningService,
            dsl,
        )

    @Test
    fun `a repeated approval while the retry is scheduled changes nothing`() {
        val email = "keycloak-retry-${UUID.randomUUID()}@example.test"
        stubGateway(email)
        val invite = fixture.invitePendingUser(email)
        fixture.approve(invite)
        awaitJobState(invite.jobId, StateName.SCHEDULED)
        assertTrue(storageProvider.getJobById(invite.jobId).jobStates.any { it is FailedState })
        // The await has just seen the job SCHEDULED, so the retry is about 30 s away. The repeat
        // must append no state to the job's history: a requeue or a second run would add one.
        val historyBeforeRepeat = storageProvider.getJobById(invite.jobId).jobStates.size

        val repeated = fixture.approve(invite)

        assertTrue(repeated.keycloakProvisioningRequested)
        assertEquals(historyBeforeRepeat, storageProvider.getJobById(invite.jobId).jobStates.size)
        awaitJobState(invite.jobId, StateName.SUCCEEDED)
        assertEquals("INVITED", fixture.userStatus(invite.userId))
        assertEquals("ACTIVE", fixture.membershipStatus(invite.membershipId))
        // One failed attempt and one successful retry: the repeated approval started no run.
        verify(identityProvisioningGateway, times(2)).findOrCreateUser(requestFor(email))
    }

    /**
     * Every identity the gateway is asked for succeeds, except [email]'s, which fails once and then
     * succeeds. The general stub is registered first, so it is the fallback for other identities.
     */
    private fun stubGateway(email: String) {
        whenever(identityProvisioningGateway.findOrCreateUser(any()))
            .thenReturn(KeycloakUserRef(subject = UUID.randomUUID().toString(), created = true))
        whenever(identityProvisioningGateway.findOrCreateUser(requestFor(email)))
            .thenThrow(IdentityProvisioningException("Keycloak is unreachable"))
            .thenReturn(KeycloakUserRef(subject = UUID.randomUUID().toString(), created = true))
    }

    private fun requestFor(email: String): KeycloakUserProvisioningRequest =
        argThat { this.email == email }

    private fun awaitJobState(
        jobId: UUID,
        state: StateName,
    ) {
        await()
            .ignoreException(JobNotFoundException::class.java)
            .atMost(Duration.ofSeconds(150))
            .pollInterval(Duration.ofMillis(500))
            .until { storageProvider.getJobById(jobId).state == state }
    }
}
