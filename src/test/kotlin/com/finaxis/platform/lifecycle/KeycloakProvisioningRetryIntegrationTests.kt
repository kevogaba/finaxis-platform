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
import org.jobrunr.jobs.states.SucceededState
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
 * A failed Keycloak provisioning job, run by real JobRunr, is run again under its own id when the
 * approval is repeated. Retries are off, so the first failure is terminal (FAILED) and the second
 * approval is the only thing that makes the job run again.
 *
 * It closes its context with the class, so its JobRunr server does not outlive the test (fix
 * round 2).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
        "jobrunr.dashboard.enabled=false",
        "jobrunr.jobs.default-number-of-retries=0",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class KeycloakProvisioningRetryIntegrationTests(
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
    fun `a repeated approval runs the same failed job again and provisions the user`() {
        val email = "keycloak-retry-${UUID.randomUUID()}@example.test"
        stubGateway(email)
        val invite = fixture.invitePendingUser(email)
        assertTrue(fixture.approve(invite).keycloakProvisioningRequested)
        awaitJobState(invite.jobId, StateName.FAILED)
        assertEquals("PROVISIONING_IDP", fixture.userStatus(invite.userId))
        assertEquals("PENDING_APPROVAL", fixture.membershipStatus(invite.membershipId))

        val repeated = fixture.approve(invite)

        assertTrue(repeated.keycloakProvisioningRequested)
        awaitJobState(invite.jobId, StateName.SUCCEEDED)
        assertEquals("INVITED", fixture.userStatus(invite.userId))
        assertEquals("ACTIVE", fixture.membershipStatus(invite.membershipId))
        val job = storageProvider.getJobById(invite.jobId)
        assertEquals(1, job.jobStates.count { it is FailedState }, "the first attempt failed")
        assertEquals(1, job.jobStates.count { it is SucceededState }, "the repeat succeeded")
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
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofMillis(500))
            .until { storageProvider.getJobById(jobId).state == state }
    }
}
