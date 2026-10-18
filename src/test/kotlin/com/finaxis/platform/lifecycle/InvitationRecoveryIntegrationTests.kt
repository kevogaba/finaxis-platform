package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.jobs.deterministicJobId
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.InvitationRecoveryService
import com.finaxis.platform.lifecycle.application.LifecycleErrorCodes
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ResendInvitationCommand
import com.finaxis.platform.lifecycle.application.RetryInvitationCommand
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserProvisioningRequest
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import com.finaxis.platform.lifecycle.application.query.LifecycleIamReadService
import org.awaitility.Awaitility.await
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.never
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
import kotlin.test.assertFailsWith

/**
 * Recovery of an invitation (#274) through real JobRunr and the real outbox and listener, with the
 * Keycloak gateway mocked: the derived status, the retry of a failed job, and a resend.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
        "jobrunr.dashboard.enabled=false",
        "jobrunr.jobs.default-number-of-retries=0",
        "finaxis.invitation.resend-cooldown=0s",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InvitationRecoveryIntegrationTests(
    organisationProvisioningService: OrganisationProvisioningService,
    branchProvisioningService: BranchProvisioningService,
    userProvisioningService: UserProvisioningService,
    private val recovery: InvitationRecoveryService,
    private val readService: LifecycleIamReadService,
    private val storageProvider: StorageProvider,
    dsl: DSLContext,
) {
    @MockitoBean
    private lateinit var gateway: IdentityProvisioningGateway

    private val fixture =
        KeycloakProvisioningRetryFixture(
            organisationProvisioningService,
            branchProvisioningService,
            userProvisioningService,
            dsl,
        )
    private val checker = KeycloakProvisioningRetryFixture.CHECKER_ID

    @Test
    fun `a failed provisioning shows its code and a retry runs it again to completion`() {
        val email = "recovery-${UUID.randomUUID()}@example.test"
        stubGateway(email)
        val invite = fixture.invitePendingUser(email)

        assertEquals("AWAITING_APPROVAL", status(invite).status)
        fixture.approve(invite)
        awaitJob(invite.jobId, StateName.FAILED)
        val failed = status(invite)
        assertEquals("FAILED", failed.status)
        assertEquals("IDENTITY_PROVIDER_FAILED", failed.lastErrorCode)
        assertEquals(1, failed.attempts)

        recovery.retry(RetryInvitationCommand(invite.organisationId, invite.membershipId, checker))

        awaitJob(invite.jobId, StateName.SUCCEEDED)
        val done = status(invite)
        assertEquals("COMPLETED", done.status)
        assertEquals(null, done.lastErrorCode)
        assertEquals("INVITED", fixture.userStatus(invite.userId))
    }

    @Test
    fun `a resend emails the invited user again, and nothing is retried when nothing failed`() {
        val email = "recovery-${UUID.randomUUID()}@example.test"
        stubGateway(email)
        val invite = fixture.invitePendingUser(email)
        fixture.approve(invite)
        awaitJob(invite.jobId, StateName.FAILED)
        recovery.retry(RetryInvitationCommand(invite.organisationId, invite.membershipId, checker))
        awaitJob(invite.jobId, StateName.SUCCEEDED)

        // Not failed, not pending: a retry has nothing to run and says so.
        assertFailsWith<ConflictException> {
            recovery.retry(
                RetryInvitationCommand(invite.organisationId, invite.membershipId, checker),
            )
        }

        recovery.resend(
            ResendInvitationCommand(invite.organisationId, invite.membershipId, checker),
        )

        val resendJob =
            deterministicJobId("${dispatchKey(invite)}:resend:${status(invite).attempts}")
        awaitJob(resendJob, StateName.SUCCEEDED)
        // Once at provisioning (the invite asked for the email) and once for the resend.
        verify(gateway, times(2)).sendRequiredActionsEmail(subjectOf(email))
        assertEquals(3, status(invite).attempts, "the failure, the success and the resend")
    }

    @Test
    fun `a membership that is not invited has no invitation to resend`() {
        val email = "recovery-${UUID.randomUUID()}@example.test"
        stubGateway(email)
        val invite = fixture.invitePendingUser(email)

        val refusal =
            assertFailsWith<ConflictException> {
                recovery.resend(
                    ResendInvitationCommand(invite.organisationId, invite.membershipId, checker),
                )
            }

        assertEquals(LifecycleErrorCodes.INVITATION_RESEND_NOT_APPLICABLE, refusal.code)
        verify(gateway, never()).sendRequiredActionsEmail(subjectOf(email))
    }

    private fun status(invite: KeycloakProvisioningRetryFixture.PendingInvite) =
        requireNotNull(
            readService
                .getMembership(
                    invite.organisationId,
                    invite.membershipId,
                    TenantCaller(checker, invite.organisationId),
                ).invitation,
        )

    private fun dispatchKey(invite: KeycloakProvisioningRetryFixture.PendingInvite) =
        "${invite.organisationId}:${invite.userId}:KEYCLOAK_PROVISIONING"

    private fun subjectOf(email: String) = "subject-of-$email"

    /** [email]'s identity fails once and then succeeds; every other identity succeeds. */
    private fun stubGateway(email: String) {
        whenever(gateway.findOrCreateUser(any()))
            .thenReturn(KeycloakUserRef(subject = uuidV7().toString(), created = true))
        whenever(gateway.findOrCreateUser(requestFor(email)))
            .thenThrow(IdentityProvisioningException("Keycloak is unreachable"))
            .thenReturn(KeycloakUserRef(subject = subjectOf(email), created = true))
    }

    private fun requestFor(email: String): KeycloakUserProvisioningRequest =
        argThat { this.email == email }

    private fun awaitJob(
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
