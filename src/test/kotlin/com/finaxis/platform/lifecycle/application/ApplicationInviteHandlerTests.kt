package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.lifecycle.application.port.outbound.ExistingMembership
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import org.jobrunr.JobRunrException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationInviteHandlerTests {
    private val store = ApplicationInviteStoreFake()
    private val audits = ApplicationInviteAuditCapture()
    private val clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC)
    private val auditService = AuditService(audits, clock)
    private val handler =
        ApplicationInviteJobRequestHandler(
            store,
            EmailGateway { EmailDeliveryReceipt("msg-1") },
            InMemoryJobStepGuard(),
            DispatchOutcomeAuditor(store, auditService),
        )

    @Test
    fun `application invite marks dispatch succeeded`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val userId = uuidV7()

        handler.run(applicationInviteRequest(dispatchKey, userId))

        val dispatch = store.dispatches.getValue(dispatchKey)
        assertEquals("SUCCEEDED", dispatch.status)
        assertEquals(1, dispatch.attempts)
        assertEquals("APPLICATION_INVITE", dispatch.externalRef)
        val dispatchAudit = audits.items.single { it.action == "user.application_invite" }
        assertEquals(AuditOutcome.SUCCESS, dispatchAudit.outcome)
        assertEquals(SystemActor.ID.toString(), dispatchAudit.actorId)
        assertEquals(userId.toString(), dispatchAudit.subjectUserId)
    }

    @Test
    fun `succeeded application invite skips duplicate work`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("SUCCEEDED")

        handler.run(applicationInviteRequest(dispatchKey))

        assertEquals(0, store.succeededCalls)
    }

    @Test
    fun `dispatch failure marks the dispatch failed and audits the failure`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        store.failure = IllegalStateException("provider unavailable")
        val userId = uuidV7()

        val appender =
            ch.qos.logback.core.read
                .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        val sanitisedLogger =
            org.slf4j.LoggerFactory.getLogger(SanitisedJobFailureException::class.java)
                as ch.qos.logback.classic.Logger
        sanitisedLogger.addAppender(appender)
        val failure =
            try {
                assertFailsWith<SanitisedJobFailureException> {
                    handler.run(applicationInviteRequest(dispatchKey, userId))
                }
            } finally {
                sanitisedLogger.detachAppender(appender)
            }

        assertEquals("IllegalStateException", failure.message)
        assertNull(failure.cause)
        val logged = appender.list.single().formattedMessage
        assertTrue(logged.contains(dispatchKey))
        assertTrue(logged.contains("exceptionClass=java.lang.IllegalStateException"))
        assertTrue(logged.contains("\tat "))
        assertFalse(logged.contains("provider unavailable"))
        val dispatch = store.dispatches.getValue(dispatchKey)
        assertEquals("FAILED", dispatch.status)
        assertEquals("INVALID_STATE:IllegalStateException", dispatch.lastError)
        val dispatchAudit = audits.items.single { it.action == "user.application_invite" }
        assertEquals(AuditOutcome.FAILURE, dispatchAudit.outcome)
        assertEquals("IllegalStateException", dispatchAudit.reason)
        assertEquals(SystemActor.ID.toString(), dispatchAudit.actorId)
        // The failed row is about the invitee too, on the failure path as on success.
        assertEquals(userId.toString(), dispatchAudit.subjectUserId)
    }

    @Test
    fun `dispatch failure detail keeps the closed code and class name but never the message`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        store.failure = IllegalStateException("provider rejected jane.doe@acme.test")

        assertFailsWith<SanitisedJobFailureException> {
            handler.run(applicationInviteRequest(dispatchKey))
        }

        val lastError = requireNotNull(store.dispatches.getValue(dispatchKey).lastError)
        assertEquals("INVALID_STATE:IllegalStateException", lastError)
        assertFalse(lastError.contains("jane.doe@acme.test"))
    }

    @Test
    fun `application invite sends the email before marking dispatch succeeded`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val sentMessages = mutableListOf<EmailMessage>()
        val gateway =
            EmailGateway { message ->
                sentMessages.add(message)
                EmailDeliveryReceipt("msg-1")
            }
        val handler =
            ApplicationInviteJobRequestHandler(
                store,
                gateway,
                InMemoryJobStepGuard(),
                DispatchOutcomeAuditor(store, auditService),
            )

        handler.run(applicationInviteRequest(dispatchKey))

        assertEquals(1, sentMessages.size)
        val sent = sentMessages.single()
        assertEquals(EmailCategory.ORGANISATION_INVITE, sent.category)
        assertEquals("Ada Lovelace", sent.recipientDisplayName)
        assertEquals("Acme Bank", sent.organisationDisplayName)
        assertEquals("SUCCEEDED", store.dispatches.getValue(dispatchKey).status)
    }

    @Test
    fun `permanent email failure marks dispatch failed and throws a do-not-retry exception`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val gateway = EmailGateway { throw PermanentEmailDeliveryException("bad address") }
        val handler =
            ApplicationInviteJobRequestHandler(
                store,
                gateway,
                InMemoryJobStepGuard(),
                DispatchOutcomeAuditor(store, auditService),
            )

        val exception =
            assertFailsWith<JobRunrException> { handler.run(applicationInviteRequest(dispatchKey)) }

        assertEquals(true, exception.isProblematicAndDoNotRetry())
        assertEquals("PermanentEmailDeliveryException", exception.message)
        assertNull(exception.cause)
        assertEquals("FAILED", store.dispatches.getValue(dispatchKey).status)
        assertEquals(
            "UNEXPECTED:PermanentEmailDeliveryException",
            store.dispatches.getValue(dispatchKey).lastError,
        )
    }

    @Test
    fun `retryable email failure marks dispatch failed and rethrows`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val gateway = EmailGateway { throw RetryableEmailDeliveryException("timeout") }
        val handler =
            ApplicationInviteJobRequestHandler(
                store,
                gateway,
                InMemoryJobStepGuard(),
                DispatchOutcomeAuditor(store, auditService),
            )

        val failure =
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(applicationInviteRequest(dispatchKey))
            }

        assertEquals("RetryableEmailDeliveryException", failure.message)
        assertNull(failure.cause)

        assertEquals("FAILED", store.dispatches.getValue(dispatchKey).status)
        assertEquals(
            "UNEXPECTED:RetryableEmailDeliveryException",
            store.dispatches.getValue(dispatchKey).lastError,
        )
    }

    @Test
    fun `retrying an already-completed step does not send a second email`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val sentMessages = mutableListOf<EmailMessage>()
        val gateway =
            EmailGateway { message ->
                sentMessages.add(message)
                EmailDeliveryReceipt("msg-1")
            }
        val stepGuard = InMemoryJobStepGuard()
        // Simulate a retry after a later failure by re-invoking run() against the same guard,
        // without resetting dispatch status to PENDING in between.
        val handler =
            ApplicationInviteJobRequestHandler(
                store,
                gateway,
                stepGuard,
                DispatchOutcomeAuditor(store, auditService),
            )
        store.failure = IllegalStateException("boom")
        assertFailsWith<SanitisedJobFailureException> {
            handler.run(
                applicationInviteRequest(dispatchKey),
            )
        }
        store.failure = null

        handler.run(applicationInviteRequest(dispatchKey))

        assertEquals(1, sentMessages.size)
    }

    @Test
    fun `a job of an earlier generation sends no email and is cancelled`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        store.snapshot = store.snapshot?.copy(generation = 1)
        val sent = mutableListOf<EmailMessage>()
        val handler = handlerSending(sent)

        // Returns normally, so JobRunr marks the job done and never retries it.
        handler.run(applicationInviteRequest(dispatchKey))

        assertTrue(sent.isEmpty())
        assertEquals(0, store.succeededCalls)
        val dispatch = store.dispatches.getValue(dispatchKey)
        assertEquals("FAILED", dispatch.status)
        assertEquals("CANCELLED:MembershipReinvited", dispatch.lastError)
        val cancelled = audits.items.single { it.action == "user.application_invite" }
        assertEquals(AuditOutcome.FAILURE, cancelled.outcome)
        assertEquals("MembershipReinvited", cancelled.reason)
    }

    @Test
    fun `a stale job leaves a dispatch that already settled alone`() {
        val sent = mutableListOf<EmailMessage>()
        val handler = handlerSending(sent)
        store.snapshot = store.snapshot?.copy(generation = 2)
        val succeeded = "user-1:org-1:APPLICATION_INVITE"
        val failed = "user-2:org-1:APPLICATION_INVITE"
        store.dispatches[succeeded] = InviteDispatchState("SUCCEEDED", attempts = 1)
        store.dispatches[failed] = InviteDispatchState("FAILED", attempts = 1, lastError = "X")

        handler.run(applicationInviteRequest(succeeded))
        handler.run(applicationInviteRequest(failed))

        assertTrue(sent.isEmpty())
        assertEquals(InviteDispatchState("SUCCEEDED", attempts = 1), store.dispatches[succeeded])
        assertEquals(
            InviteDispatchState("FAILED", attempts = 1, lastError = "X"),
            store.dispatches[failed],
        )
        assertTrue(audits.items.isEmpty())
    }

    @Test
    fun `a job of the membership's current generation is processed`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE:G1"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        store.snapshot = store.snapshot?.copy(generation = 1)
        val sent = mutableListOf<EmailMessage>()

        handlerSending(sent).run(applicationInviteRequest(dispatchKey, generation = 1))

        assertEquals(1, sent.size)
        assertEquals("SUCCEEDED", store.dispatches.getValue(dispatchKey).status)
    }

    @Test
    fun `a stored job without a generation is generation zero`() {
        // Jobs queued before the field existed deserialise with the default, 0.
        assertEquals(0, ApplicationInviteJobRequest().generation)
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val sent = mutableListOf<EmailMessage>()

        handlerSending(sent).run(applicationInviteRequest(dispatchKey))

        assertEquals(1, sent.size)
    }

    private fun handlerSending(sent: MutableList<EmailMessage>) =
        ApplicationInviteJobRequestHandler(
            store,
            EmailGateway { message ->
                sent.add(message)
                EmailDeliveryReceipt("msg-1")
            },
            InMemoryJobStepGuard(),
            DispatchOutcomeAuditor(store, auditService),
        )

    private fun applicationInviteRequest(
        dispatchKey: String,
        userId: UUID = uuidV7(),
        generation: Int = 0,
    ): ApplicationInviteJobRequest =
        ApplicationInviteJobRequest(
            organisationId = uuidV7(),
            membershipId = uuidV7(),
            userId = userId,
            email = "member@example.test",
            dispatchKey = dispatchKey,
            generation = generation,
        )
}

private class ApplicationInviteStoreFake : UserProvisioningStore {
    val dispatches = mutableMapOf<String, InviteDispatchState>()
    var succeededCalls = 0
    var failure: RuntimeException? = null
    var snapshot: MembershipProvisioningSnapshot? =
        MembershipProvisioningSnapshot(
            id = uuidV7(),
            userId = uuidV7(),
            status = MembershipLifecycleState.PENDING_APPROVAL,
            type = MembershipType.STAFF,
            email = "member@example.test",
            username = "member",
            displayName = "Ada Lovelace",
            userStatus = UserLifecycleState.INVITED,
            sendKeycloakInvite = true,
            sendApplicationInvite = true,
        )
    var organisationName: String? = "Acme Bank"

    override fun dispatchStatus(dispatchKey: String): String? = dispatches[dispatchKey]?.status

    override fun markDispatchPending(dispatchKey: String) {
        dispatches[dispatchKey]?.let {
            if (it.status == "FAILED") dispatches[dispatchKey] = it.copy(status = "PENDING")
        }
    }

    override fun markDispatchSucceeded(
        dispatchKey: String,
        externalRef: String,
    ) {
        failure?.let { throw it }
        succeededCalls += 1
        val current = dispatches.getValue(dispatchKey)
        dispatches[dispatchKey] =
            current.copy(
                status = "SUCCEEDED",
                attempts = current.attempts + 1,
                externalRef = externalRef,
            )
    }

    override fun markDispatchFailed(
        dispatchKey: String,
        error: String,
    ) {
        val current = dispatches.getValue(dispatchKey)
        dispatches[dispatchKey] =
            current.copy(status = "FAILED", attempts = current.attempts + 1, lastError = error)
    }

    override fun linkKeycloakIdentity(
        userId: UUID,
        subject: String,
        realm: String,
        actorId: UUID,
    ) = Unit

    override fun organisationState(organisationId: UUID): OrganisationLifecycleState? = null

    override fun lockOrganisation(organisationId: UUID) = Unit

    override fun findUserIdByEmail(email: String): UUID? = null

    override fun usernameInUse(username: String): Boolean = false

    override fun createUserAccount(
        email: String,
        username: String,
        displayName: String,
        phoneE164: String?,
        actorId: UUID,
    ): UUID = uuidV7()

    override fun userStatus(userId: UUID): UserLifecycleState? = null

    override fun hasKeycloakIdentity(userId: UUID): Boolean = false

    override fun createMembership(
        organisationId: UUID,
        userId: UUID,
        membershipType: MembershipType,
        primaryBranchId: UUID?,
        actorId: UUID,
    ): UUID = uuidV7()

    override fun saveInvitationPreferences(
        organisationId: UUID,
        membershipId: UUID,
        sendKeycloakInvite: Boolean,
        sendApplicationInvite: Boolean,
        actorId: UUID,
    ) = Unit

    override fun membershipSnapshot(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot? = snapshot

    override fun organisationDisplayName(organisationId: UUID): String? = organisationName

    override fun existingMembership(
        organisationId: UUID,
        userId: UUID,
    ): ExistingMembership? = null

    override fun resetMembershipForReinvite(
        organisationId: UUID,
        membershipId: UUID,
        membershipType: MembershipType,
        primaryBranchId: UUID?,
        actorId: UUID,
    ) = Unit

    override fun hasActiveMembershipBeyondBootstrap(organisationId: UUID): Boolean = false

    override fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ): BranchLifecycleState? = null

    override fun roleExists(
        organisationId: UUID,
        roleId: UUID,
    ): Boolean = false

    override fun findRoleIdByCode(
        organisationId: UUID,
        roleCode: String,
    ): UUID? = null

    override fun assignRole(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleAssignmentScopeType,
        branchId: UUID?,
        actorId: UUID,
    ): UUID = uuidV7()

    override fun hasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = false

    override fun hasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = false

    override fun revokeBranchAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int = 0

    override fun revokeRoleAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int = 0

    override fun recordDispatch(
        organisationId: UUID,
        userId: UUID,
        dispatchType: IdentityDispatchType,
        dispatchKey: String,
        actorId: UUID,
    ) = Unit
}

private data class InviteDispatchState(
    val status: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val externalRef: String? = null,
)

private class ApplicationInviteAuditCapture : AuditEventRepository {
    val items = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        items.add(event)
    }
}

private class InMemoryJobStepGuard : com.finaxis.platform.common.jobs.JobStepGuard {
    private val completedSteps = mutableSetOf<String>()

    override fun runOnce(
        step: String,
        action: () -> Unit,
    ) {
        if (completedSteps.add(step)) action()
    }
}
