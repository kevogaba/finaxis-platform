package com.finaxis.platform.lifecycle

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.ApproveUserCommand
import com.finaxis.platform.lifecycle.application.BranchAssignmentRequest
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningDispatchStore
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import jakarta.mail.Folder
import jakarta.mail.Session
import org.awaitility.Awaitility.await
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestConstructor
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the full organisation-invite pipeline (`UserProvisioningService.approveUser` →
 * `ExternalizedTransitionEvent` → Namastack outbox → RabbitMQ → [IdentityProvisioningListener] →
 * `ApplicationInviteJobRequestHandler` → the real [com.finaxis.platform.notifications
 * .adapter.outbound.email.SpringMailEmailGateway]) against a hermetic GreenMail SMTP/IMAP server,
 * including redelivery and job-retry idempotency.
 */
@Import(
    TestcontainersConfiguration::class,
    ApplicationInvitePipelineIntegrationTests.FlakyDispatchStoreTestConfiguration::class,
)
@SpringBootTest(
    properties = [
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
        "jobrunr.dashboard.enabled=false",
        "finaxis.email.enabled=true",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ApplicationInvitePipelineIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val branchProvisioningService: BranchProvisioningService,
    private val userProvisioningService: UserProvisioningService,
    private val storageProvider: StorageProvider,
    private val dsl: DSLContext,
    private val flakyDispatchStore: FlakyDispatchStore,
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    init {
        insertUserIfMissing(ACTOR_ID, "invite-pipeline-actor")
        insertUserIfMissing(CHECKER_ID, "invite-pipeline-checker")
    }

    @Test
    fun `inviting a user delivers exactly one organisation-invite email`() {
        val invitedEmail = "invitee-${uuidV7()}@example.test"
        triggerInvite(invitedEmail)

        await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted {
                assertEquals(1, fetchInboxMessageCount(invitedEmail))
            }
        assertTrue(fetchFirstSubject(invitedEmail).contains("invited to join"))
    }

    @Test
    fun `redelivering the same invite event sends exactly one email`() {
        val invitedEmail = "invitee-redeliver-${uuidV7()}@example.test"
        val invite = triggerInvite(invitedEmail)

        await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted {
                assertEquals(1, fetchInboxMessageCount(invitedEmail))
            }

        // JobRunr's own enqueue-by-deterministic-id is the idempotency seam here: re-scheduling
        // an ApplicationInviteJobRequest under a job id that already exists is a silent no-op
        // (StorageProvider.save throws ConcurrentJobModificationException, which the scheduler
        // swallows) - the job never runs a second time. Redeliver an identical event payload to
        // the queue IdentityProvisioningListener already consumed once, and confirm the inbox
        // count stays at exactly one rather than becoming two.
        redeliverApplicationInvite(invite)

        await()
            .pollDelay(5, TimeUnit.SECONDS)
            .atMost(6, TimeUnit.SECONDS)
            .untilAsserted {
                assertEquals(1, fetchInboxMessageCount(invitedEmail))
            }
    }

    @Test
    fun `a job retry after a later failure does not send a second email`() {
        val invitedEmail = "invitee-retry-${uuidV7()}@example.test"
        val invite = triggerInvite(invitedEmail, failDispatchSuccessOnce = true)

        await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted {
                assertEquals(1, fetchInboxMessageCount(invitedEmail))
            }

        // The email above was sent on the job's first attempt, before the simulated
        // DispatchOutcomeAuditor.recordSuccess failure forced JobRunr to retry the same job.
        // JobStepGuard (backed by JobRunr's own per-job step metadata) must skip re-sending on
        // that retry, and the job must still reach SUCCEEDED once recordSuccess stops failing.
        await()
            .ignoreException(JobNotFoundException::class.java)
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted {
                assertEquals(StateName.SUCCEEDED, storageProvider.getJobById(invite.jobId).state)
            }

        assertEquals(1, fetchInboxMessageCount(invitedEmail))
    }

    /** Provisions an org/branch/role, invites and approves [email], returning the invite's keys. */
    private fun triggerInvite(
        email: String,
        failDispatchSuccessOnce: Boolean = false,
    ): TriggeredInvite {
        val organisationId = fixture.createActiveOrganisation("invite-pipeline", ACTOR_ID)
        fixture.grantTenantAdmin(organisationId, CHECKER_ID)
        val branchId = createActiveBranch(organisationId)
        val roleId = tenantAdminRoleId(organisationId)

        val invitation =
            userProvisioningService.inviteUser(
                InviteUserCommand(
                    organisationId = organisationId,
                    email = email,
                    username = "invitee-${uuidV7()}",
                    displayName = "Pipeline Invitee",
                    membershipType = MembershipType.STAFF,
                    primaryBranchId = branchId,
                    branchAssignments =
                        listOf(BranchAssignmentRequest(branchId, BranchAssignmentType.HOME)),
                    roleAssignments =
                        listOf(RoleAssignmentRequest(roleId, RoleAssignmentScopeType.TENANT)),
                    invitedBy = ACTOR_ID,
                    sendKeycloakInvite = false,
                    sendApplicationInvite = true,
                ),
            )
        val dispatchKey = "${invitation.userId}:$organisationId:APPLICATION_INVITE"
        if (failDispatchSuccessOnce) {
            flakyDispatchStore.dispatchKeyToFailOnce.set(dispatchKey)
        }

        userProvisioningService.approveUser(
            ApproveUserCommand(organisationId, invitation.membershipId, SystemActor.ID),
        )

        return TriggeredInvite(
            organisationId = organisationId,
            membershipId = invitation.membershipId,
            userId = invitation.userId,
            email = email,
            dispatchKey = dispatchKey,
            jobId = UUID.nameUUIDFromBytes(dispatchKey.toByteArray()),
        )
    }

    private fun createActiveBranch(organisationId: UUID): UUID =
        withRequestContext {
            val branchId =
                branchProvisioningService
                    .createDraft(
                        CreateBranchCommand(
                            organisationId = organisationId,
                            branchCode = "invite-${uuidV7()}",
                            branchName = "Invite Pipeline Branch",
                            branchType = "SERVICE",
                            timezone = "Africa/Nairobi",
                            requestedBy = ACTOR_ID,
                        ),
                    ).branchId
            branchProvisioningService.submitForApproval(
                SubmitBranchForApprovalCommand(
                    organisationId = organisationId,
                    branchId = branchId,
                    actorId = ACTOR_ID,
                    requestId = uuidV7(),
                ),
            )
            branchProvisioningService.activate(
                ActivateBranchCommand(
                    organisationId = organisationId,
                    branchId = branchId,
                    actorId = CHECKER_ID,
                    requestId = uuidV7(),
                ),
            )
            branchId
        }

    private fun insertUserIfMissing(
        userId: UUID,
        username: String,
    ) {
        val now = java.time.OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, userId)
            .set(USER_ACCOUNT.USERNAME, username)
            .set(USER_ACCOUNT.EMAIL, "$username@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, username)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .onConflict(USER_ACCOUNT.ID)
            .doNothing()
            .execute()
    }

    private fun tenantAdminRoleId(organisationId: UUID): UUID =
        requireNotNull(
            dsl
                .select(ROLE.ID)
                .from(ROLE)
                .where(ROLE.ORGANISATION_ID.eq(organisationId))
                .and(ROLE.ROLE_CODE.eq("TENANT_ADMIN"))
                .fetchOne(ROLE.ID),
        ) { "TENANT_ADMIN role was not provisioned for the organisation." }

    /**
     * Publishes a second, content-identical `APPLICATION_INVITE_TARGET` event straight to
     * [USER_PROVISIONING_QUEUE], mirroring a genuine RabbitMQ redelivery of the message
     * [IdentityProvisioningListener] already consumed once for this invite.
     */
    private fun redeliverApplicationInvite(invite: TriggeredInvite) {
        val event =
            ExternalizedTransitionEvent(
                target = APPLICATION_INVITE_TARGET,
                aggregateType = "USER_ACCOUNT",
                aggregateId = invite.userId.toString(),
                transition = "APPLICATION_INVITE_REQUESTED",
                fromState = "PENDING_APPROVAL",
                toState = "PENDING_APPROVAL",
                actor = TransitionActor("USER", SystemActor.ID.toString()),
                occurredAt = Instant.now(),
                metadata =
                    mapOf(
                        "organisationId" to invite.organisationId.toString(),
                        "membershipId" to invite.membershipId.toString(),
                        "userId" to invite.userId.toString(),
                        "email" to invite.email,
                        "dispatchKey" to invite.dispatchKey,
                    ),
            )
        rabbitTemplate.convertAndSend(
            USER_PROVISIONING_QUEUE,
            objectMapper.writeValueAsBytes(event),
        )
    }

    private fun fetchInboxMessageCount(recipientEmail: String): Int {
        val container = TestcontainersConfiguration.GREENMAIL_CONTAINER
        val session = Session.getInstance(Properties())
        val store = session.getStore("imap")
        store.connect(
            container.host,
            container.getMappedPort(GREENMAIL_IMAP_PORT),
            recipientEmail,
            "test",
        )
        return try {
            val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            inbox.messageCount.also { inbox.close(false) }
        } finally {
            store.close()
        }
    }

    private fun fetchFirstSubject(recipientEmail: String): String {
        val container = TestcontainersConfiguration.GREENMAIL_CONTAINER
        val session = Session.getInstance(Properties())
        val store = session.getStore("imap")
        store.connect(
            container.host,
            container.getMappedPort(GREENMAIL_IMAP_PORT),
            recipientEmail,
            "test",
        )
        return try {
            val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            inbox.getMessage(1).subject.also { inbox.close(false) }
        } finally {
            store.close()
        }
    }

    private data class TriggeredInvite(
        val organisationId: UUID,
        val membershipId: UUID,
        val userId: UUID,
        val email: String,
        val dispatchKey: String,
        val jobId: UUID,
    )

    /**
     * Decorates the real [UserProvisioningDispatchStore] so a single test-selected dispatch key's
     * next [markDispatchSucceeded] call fails with a retryable [TransientDataAccessResourceException],
     * simulating a crash between sending the invite email and recording dispatch success.
     */
    class FlakyDispatchStore(
        private val delegate: UserProvisioningStore,
    ) : UserProvisioningDispatchStore by delegate {
        val dispatchKeyToFailOnce = AtomicReference<String?>(null)

        override fun markDispatchSucceeded(
            dispatchKey: String,
            externalRef: String,
        ) {
            if (dispatchKeyToFailOnce.compareAndSet(dispatchKey, null)) {
                throw TransientDataAccessResourceException("Simulated transient dispatch failure")
            }
            delegate.markDispatchSucceeded(dispatchKey, externalRef)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FlakyDispatchStoreTestConfiguration {
        @Bean
        @Primary
        fun flakyDispatchStore(delegate: UserProvisioningStore): FlakyDispatchStore =
            FlakyDispatchStore(delegate)
    }

    private companion object {
        val ACTOR_ID: UUID = uuidV7()
        val CHECKER_ID: UUID = uuidV7()
        const val GREENMAIL_IMAP_PORT = 3143
        const val GREENMAIL_SMTP_PORT = 3025
        const val USER_PROVISIONING_QUEUE = "finaxis.lifecycle.user-provisioning-events"
        const val APPLICATION_INVITE_TARGET = "finaxis.lifecycle.user.application-invite-requested"

        @DynamicPropertySource
        @JvmStatic
        fun greenMailProperties(registry: DynamicPropertyRegistry) {
            val container = TestcontainersConfiguration.GREENMAIL_CONTAINER
            registry.add("spring.mail.host") { container.host }
            registry.add("spring.mail.port") { container.getMappedPort(GREENMAIL_SMTP_PORT) }
        }
    }
}
