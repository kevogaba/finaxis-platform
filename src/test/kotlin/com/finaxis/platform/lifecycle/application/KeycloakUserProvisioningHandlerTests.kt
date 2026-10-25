package com.finaxis.platform.lifecycle.application

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.jobs.FailedJobRequeuer
import com.finaxis.platform.common.jobs.JobRequeueOutcome
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogRepository
import com.finaxis.platform.lifecycle.application.port.outbound.ExistingMembership
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserProvisioningRequest
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.LifecyclePrerequisites
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.junit.jupiter.api.AfterEach
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeycloakUserProvisioningHandlerTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC)
    private val store = ProvisioningWorkerStoreFake()
    private val gateway = IdentityProvisioningGatewayFake()
    private val events = WorkerTransitionEventCapture()
    private val audits = WorkerAuditCapture()
    private val logs = WorkerTransitionLogCapture()
    private val auditService = AuditService(audits, clock)
    private val lifecycle =
        FoundationLifecycleService(
            TransitionExecutor(clock, logs, events),
            store,
            store,
            store,
            auditService,
        )
    private val dispatchOutcomeAuditor = DispatchOutcomeAuditor(store, auditService)
    private val failedJobRequeuer = FailedJobRequeuer { JobRequeueOutcome.NOT_FAILED }
    private val deactivationAssignmentRevoker = mock(UserDeactivationAssignmentRevoker::class.java)
    private val branchProvisioningService = mock(BranchProvisioningService::class.java)
    private val userProvisioningService =
        UserProvisioningService(
            lifecycle,
            store,
            branchProvisioningService,
            deactivationAssignmentRevoker,
            auditService,
            events,
            clock,
            mock(com.finaxis.platform.lifecycle.PermissionGuard::class.java),
            failedJobRequeuer,
            allControlsOn(),
        )
    private val fakeBootstrapStore =
        object : InitialAdministratorBootstrapStore {
            val records = mutableMapOf<UUID, InitialAdministratorBootstrapRecord>()
            val failedWrites = mutableListOf<UUID>()

            override fun createDraft(
                organisationId: UUID,
                admin: InitialAdministratorDraft,
                requestedBy: UUID,
            ) = Unit

            override fun amendDraft(
                organisationId: UUID,
                admin: InitialAdministratorDraft,
            ) = Unit

            override fun submit(
                organisationId: UUID,
                actorId: UUID,
            ) = Unit

            override fun approve(
                organisationId: UUID,
                actorId: UUID,
            ) = Unit

            override fun reject(organisationId: UUID) = Unit

            override fun find(organisationId: UUID): InitialAdministratorBootstrapRecord? =
                records[organisationId]

            override fun updateStatus(
                organisationId: UUID,
                status: InitialAdministratorBootstrapStatus,
                lastFailureCode: InitialAdministratorBootstrapFailureCode?,
                incrementAttempts: Boolean,
            ): Int {
                val record = records[organisationId] ?: return 0
                if (status == InitialAdministratorBootstrapStatus.FAILED) {
                    failedWrites += organisationId
                    // The store guard: a FAILED write never overwrites COMPLETED.
                    if (record.status == InitialAdministratorBootstrapStatus.COMPLETED) return 0
                }
                records[organisationId] =
                    record.copy(
                        status = status,
                        lastFailureCode = lastFailureCode,
                        attempts =
                            if (incrementAttempts) {
                                record.attempts + 1
                            } else {
                                record.attempts
                            },
                    )
                return 1
            }

            override fun existingAdministratorUserId(organisationId: UUID): UUID? = null

            override fun linkResolvedEntities(
                organisationId: UUID,
                userId: UUID?,
                membershipId: UUID?,
                headOfficeId: UUID?,
                roleId: UUID?,
            ) = Unit
        }
    private val fakeBootstrapOrgStore =
        object : OrganisationBootstrapStore {
            override fun ensureBusinessDate(
                organisationId: UUID,
                date: java.time.LocalDate,
                overwrite: Boolean,
            ) = Unit

            override fun timezone(organisationId: UUID): String = "UTC"

            override fun baseCurrencyCode(organisationId: UUID): String = "KES"

            override fun lockBaseCurrencyCode(organisationId: UUID): String = "KES"

            override fun ensureHeadOfficeDraft(organisationId: UUID): HeadOfficeDraftResult =
                HeadOfficeDraftResult(uuidV7(), true)

            override fun createDefaultReferenceSequences(organisationId: UUID) = Unit

            override fun createDefaultRoles(organisationId: UUID) = Unit

            override fun headOfficeState(organisationId: UUID): BranchLifecycleState? =
                BranchLifecycleState.ACTIVE
        }
    private val failureStatusWriter =
        InitialAdministratorBootstrapFailureStatusWriter(fakeBootstrapStore)
    private val failureRecorder = InitialAdministratorBootstrapFailureRecorder(failureStatusWriter)
    private val bootstrapService =
        InitialAdministratorBootstrapService(
            fakeBootstrapStore,
            fakeBootstrapOrgStore,
            store,
            userProvisioningService,
            events,
            failureRecorder,
            clock,
            failedJobRequeuer,
        )
    private val activation = ProvisionedMembershipActivation(store, lifecycle)
    private val handler =
        KeycloakUserProvisioningJobRequestHandler(
            gateway,
            store,
            lifecycle,
            dispatchOutcomeAuditor,
            bootstrapService,
            failureRecorder,
            activation,
        )

    @Test
    fun `new user provisioning links identity invites user activates membership and succeeds`() {
        val context = store.activePendingProvisioningContext()

        handler.run(
            keycloakRequest(
                context,
                email = "member@example.test",
                username = "member",
                sendKeycloakInvite = true,
            ),
        )

        assertEquals(1, gateway.findOrCreateCalls)
        assertEquals(1, gateway.requiredActionsCalls)
        assertEquals(context.subject, store.linkedSubjects[context.userId])
        assertEquals(UserLifecycleState.INVITED, store.users.getValue(context.userId).state)
        assertEquals(
            MembershipLifecycleState.ACTIVE,
            store.memberships.getValue(context.organisationId to context.membershipId).state,
        )
        assertEquals("SUCCEEDED", store.dispatches.getValue(context.dispatchKey).status)
        assertEquals(context.subject, store.dispatches.getValue(context.dispatchKey).externalRef)
        val dispatchAudit = audits.items.single { it.action == "user.keycloak_provisioning" }
        assertEquals(com.finaxis.platform.common.audit.AuditOutcome.SUCCESS, dispatchAudit.outcome)
        assertEquals("KEYCLOAK", dispatchAudit.metadata["externalSystemReference"])
        assertEquals(context.userId.toString(), dispatchAudit.subjectUserId)
    }

    @Test
    fun `a membership revoked while the job waited ends it without calling Keycloak`() {
        val context = store.activePendingProvisioningContext()
        store.memberships.getValue(context.organisationId to context.membershipId).state =
            MembershipLifecycleState.REVOKED

        handler.run(keycloakRequest(context, "member@example.test", "member"))

        assertEquals(0, gateway.findOrCreateCalls)
        val dispatch = store.dispatches.getValue(context.dispatchKey)
        assertEquals("FAILED", dispatch.status)
        assertEquals("CANCELLED:MembershipRevoked", dispatch.lastError)
        assertEquals(
            UserLifecycleState.PROVISIONING_IDP,
            store.users.getValue(context.userId).state,
            "nothing was provisioned",
        )
        // The cancellation is audited with the state change, like every dispatch outcome.
        val audit = audits.items.single { it.action == "user.keycloak_provisioning" }
        assertEquals(com.finaxis.platform.common.audit.AuditOutcome.FAILURE, audit.outcome)
        assertEquals("MembershipRevoked", audit.reason)
        assertEquals(context.userId.toString(), audit.subjectUserId)
    }

    @Test
    fun `a redelivered job for a member revoked after success leaves the dispatch succeeded`() {
        val context = store.activePendingProvisioningContext()
        store.dispatches[context.dispatchKey] =
            DispatchState("SUCCEEDED", attempts = 1, externalRef = context.subject)
        store.memberships.getValue(context.organisationId to context.membershipId).state =
            MembershipLifecycleState.REVOKED

        handler.run(keycloakRequest(context, "member@example.test", "member"))

        assertEquals(0, gateway.findOrCreateCalls)
        val dispatch = store.dispatches.getValue(context.dispatchKey)
        assertEquals("SUCCEEDED", dispatch.status)
        assertEquals(null, dispatch.lastError)
    }

    @Test
    fun `a job of an earlier generation is cancelled and neither provisions nor activates`() {
        val context = store.activePendingProvisioningContext()
        // The member was revoked and invited again: the row is PENDING_APPROVAL once more, with a
        // new maker and a new checker to come, and this first-life job is still queued or retried.
        store.generations[context.organisationId to context.membershipId] = 1

        handler.run(keycloakRequest(context, "member@example.test", "member"))

        assertEquals(0, gateway.findOrCreateCalls)
        assertEquals(0, gateway.requiredActionsCalls)
        assertFalse(store.linkedSubjects.containsKey(context.userId))
        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            store.memberships.getValue(context.organisationId to context.membershipId).state,
        )
        assertEquals(
            UserLifecycleState.PROVISIONING_IDP,
            store.users.getValue(context.userId).state,
        )
        val dispatch = store.dispatches.getValue(context.dispatchKey)
        assertEquals("FAILED", dispatch.status)
        assertEquals("CANCELLED:MembershipReinvited", dispatch.lastError)
        val audit = audits.items.single { it.action == "user.keycloak_provisioning" }
        assertEquals(com.finaxis.platform.common.audit.AuditOutcome.FAILURE, audit.outcome)
        assertEquals("MembershipReinvited", audit.reason)
    }

    @Test
    fun `a stale job leaves a dispatch that already succeeded and completes no bootstrap`() {
        val context = store.activePendingProvisioningContext()
        store.generations[context.organisationId to context.membershipId] = 3
        store.dispatches[context.dispatchKey] =
            DispatchState("SUCCEEDED", attempts = 1, externalRef = context.subject)
        val untouchedBootstrap = mock(InitialAdministratorBootstrapService::class.java)
        val sameHandler =
            KeycloakUserProvisioningJobRequestHandler(
                gateway,
                store,
                lifecycle,
                dispatchOutcomeAuditor,
                untouchedBootstrap,
                failureRecorder,
                activation,
            )

        sameHandler.run(keycloakRequest(context, "member@example.test", "member"))

        assertEquals(0, gateway.findOrCreateCalls)
        assertEquals(
            DispatchState("SUCCEEDED", 1, null, context.subject),
            store.dispatches[context.dispatchKey],
        )
        assertTrue(audits.items.isEmpty())
        org.mockito.Mockito.verifyNoInteractions(untouchedBootstrap)
    }

    @Test
    fun `a job that becomes stale while it calls Keycloak does not activate the member`() {
        val context = store.activePendingProvisioningContext()
        val key = context.organisationId to context.membershipId
        // Revoke and re-invite land while this first-life job is inside the Keycloak call: when it
        // gets to the activation the row is PENDING_APPROVAL again, as the new life's.
        gateway.whileCalling = { store.generations[key] = 1 }

        handler.run(keycloakRequest(context, "member@example.test", "member"))

        assertEquals(1, gateway.findOrCreateCalls)
        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            store.memberships.getValue(key).state,
        )
        val dispatch = store.dispatches.getValue(context.dispatchKey)
        assertEquals("FAILED", dispatch.status)
        assertEquals("CANCELLED:MembershipReinvited", dispatch.lastError)
        // The dispatch is not recorded as a success, and no membership transition was made.
        assertTrue(
            audits.items.none {
                it.action == "user.keycloak_provisioning" &&
                    it.outcome == com.finaxis.platform.common.audit.AuditOutcome.SUCCESS
            },
        )
        assertTrue(audits.items.none { it.action == "membership.activate" })
    }

    @Test
    fun `a job of the membership's current generation provisions and activates it`() {
        val context = store.activePendingProvisioningContext()
        val key = context.organisationId to context.membershipId
        store.generations[key] = 1

        handler.run(keycloakRequest(context, "member@example.test", "member", generation = 1))

        assertEquals(1, gateway.findOrCreateCalls)
        assertEquals(MembershipLifecycleState.ACTIVE, store.memberships.getValue(key).state)
        assertEquals("SUCCEEDED", store.dispatches.getValue(context.dispatchKey).status)
    }

    @Test
    fun `a stored job without a generation is generation zero`() {
        assertEquals(0, KeycloakUserProvisioningJobRequest().generation)
    }

    @Test
    fun `succeeded dispatch skips keycloak and lifecycle work`() {
        val context = store.activePendingProvisioningContext()
        store.dispatches[context.dispatchKey] =
            DispatchState("SUCCEEDED", attempts = 1, externalRef = context.subject)

        handler.run(keycloakRequest(context, "member@example.test", "member"))

        assertEquals(0, gateway.findOrCreateCalls)
        assertFalse(store.linkedSubjects.containsKey(context.userId))
        assertEquals(
            UserLifecycleState.PROVISIONING_IDP,
            store.users.getValue(context.userId).state,
        )
        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            store.memberships.getValue(context.organisationId to context.membershipId).state,
        )
    }

    @Test
    fun `gateway failure marks dispatch failed and rethrows for retry`() {
        val context = store.activePendingProvisioningContext()
        fakeBootstrapStore.records[context.organisationId] = bootstrapRecord(context)
        gateway.failure = IllegalStateException("keycloak unavailable")

        val failure =
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(keycloakRequest(context, "member@example.test", "member"))
            }

        assertEquals("INVALID_STATE", failure.message)
        assertNull(failure.cause)
        val dispatch = store.dispatches.getValue(context.dispatchKey)
        assertEquals("FAILED", dispatch.status)
        assertEquals(1, dispatch.attempts)
        assertEquals("INVALID_STATE:IllegalStateException", dispatch.lastError)
        assertEquals(
            InitialAdministratorBootstrapStatus.FAILED,
            fakeBootstrapStore.records.getValue(context.organisationId).status,
        )
        val dispatchAudit = audits.items.single { it.action == "user.keycloak_provisioning" }
        assertEquals(com.finaxis.platform.common.audit.AuditOutcome.FAILURE, dispatchAudit.outcome)
        assertEquals("IllegalStateException", dispatchAudit.reason)
        // The failed row is about the user being provisioned, on the failure path as on success.
        assertEquals(context.userId.toString(), dispatchAudit.subjectUserId)
    }

    @Test
    fun `dispatch failure detail keeps the closed code and class name but never the message`() {
        val context = store.activePendingProvisioningContext()
        fakeBootstrapStore.records[context.organisationId] = bootstrapRecord(context)
        gateway.failure = IdentityProvisioningException("keycloak rejected jane.doe@acme.test")

        assertFailsWith<SanitisedJobFailureException> {
            handler.run(keycloakRequest(context, "member@example.test", "member"))
        }

        val lastError = requireNotNull(store.dispatches.getValue(context.dispatchKey).lastError)
        assertEquals("IDENTITY_PROVIDER_FAILED:IdentityProvisioningException", lastError)
        assertFalse(lastError.contains("jane.doe@acme.test"))
    }

    @Test
    fun `an unrelated invitee's failure leaves a completed bootstrap completed`() {
        val context = store.activePendingProvisioningContext()
        fakeBootstrapStore.records[context.organisationId] =
            bootstrapRecord(context, InitialAdministratorBootstrapStatus.COMPLETED)
                .copy(userId = uuidV7(), lastFailureCode = null)
        gateway.failure = IllegalStateException("keycloak unavailable for jane.doe@acme.test")
        val appender = captureSanitisedLog()

        val failure =
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(keycloakRequest(context, "member@example.test", "member"))
            }

        assertEquals("INVALID_STATE", failure.message)
        assertNull(failure.cause)
        val record = fakeBootstrapStore.records.getValue(context.organisationId)
        assertEquals(InitialAdministratorBootstrapStatus.COMPLETED, record.status)
        assertEquals(null, record.lastFailureCode)
        assertTrue(fakeBootstrapStore.failedWrites.isEmpty(), "the recorder is not called")
        assertEquals("FAILED", store.dispatches.getValue(context.dispatchKey).status)
        val dispatchAudit = audits.items.single { it.action == "user.keycloak_provisioning" }
        assertEquals(com.finaxis.platform.common.audit.AuditOutcome.FAILURE, dispatchAudit.outcome)
        val warn = appender.eventsFor(context.dispatchKey).single()
        assertEquals(ch.qos.logback.classic.Level.WARN, warn.level)
        assertTrue(warn.formattedMessage.contains(context.dispatchKey))
        assertTrue(warn.formattedMessage.contains("exceptionClass=java.lang.IllegalStateException"))
        assertFalse(warn.formattedMessage.contains("jane.doe@acme.test"))
        assertNull(warn.throwableProxy)
    }

    @Test
    fun `an unrelated invitee's failure leaves an unfinished bootstrap unchanged`() {
        listOf(
            InitialAdministratorBootstrapStatus.QUEUED,
            InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY,
        ).forEach { status ->
            val context = store.activePendingProvisioningContext()
            fakeBootstrapStore.records[context.organisationId] =
                bootstrapRecord(context, status).copy(userId = uuidV7())
            gateway.failure = IllegalStateException("keycloak unavailable")

            assertFailsWith<SanitisedJobFailureException> {
                handler.run(keycloakRequest(context, "member@example.test", "member"))
            }

            val record = fakeBootstrapStore.records.getValue(context.organisationId)
            assertEquals(status, record.status)
            assertEquals(null, record.lastFailureCode)
            assertTrue(context.organisationId !in fakeBootstrapStore.failedWrites)
            assertEquals("FAILED", store.dispatches.getValue(context.dispatchKey).status)
        }
    }

    @Test
    fun `the administrator's own failure marks the bootstrap failed without a handler WARN`() {
        val context = store.activePendingProvisioningContext()
        fakeBootstrapStore.records[context.organisationId] =
            bootstrapRecord(context, InitialAdministratorBootstrapStatus.QUEUED)
        gateway.failure = IdentityProvisioningException("keycloak unavailable")
        val appender = captureSanitisedLog()

        val failure =
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(keycloakRequest(context, "admin@example.test", "admin"))
            }

        assertEquals("IDENTITY_PROVIDER_FAILED", failure.message)
        assertNull(failure.cause)
        val record = fakeBootstrapStore.records.getValue(context.organisationId)
        assertEquals(InitialAdministratorBootstrapStatus.FAILED, record.status)
        assertEquals(
            InitialAdministratorBootstrapFailureCode.IDENTITY_PROVIDER_FAILED,
            record.lastFailureCode,
        )
        assertTrue(
            appender.eventsFor(context.dispatchKey).isEmpty(),
            "the recorder's own line is the only log",
        )
    }

    @Test
    fun `the administrator's failure after completion is refused by the store and warned about`() {
        val context = store.activePendingProvisioningContext()
        fakeBootstrapStore.records[context.organisationId] =
            bootstrapRecord(context, InitialAdministratorBootstrapStatus.COMPLETED)
                .copy(lastFailureCode = null)
        gateway.failure = IdentityProvisioningException("keycloak unavailable")
        val recorderLog = captureRecorderLog()

        assertFailsWith<SanitisedJobFailureException> {
            handler.run(keycloakRequest(context, "admin@example.test", "admin"))
        }

        val record = fakeBootstrapStore.records.getValue(context.organisationId)
        assertEquals(InitialAdministratorBootstrapStatus.COMPLETED, record.status)
        assertNull(record.lastFailureCode)
        assertEquals(listOf(context.organisationId), fakeBootstrapStore.failedWrites)
        val warn = recorderLog.eventsFor(context.organisationId.toString()).single()
        assertEquals(ch.qos.logback.classic.Level.WARN, warn.level)
        assertTrue(warn.formattedMessage.contains(context.organisationId.toString()))
        assertTrue(warn.formattedMessage.contains("IDENTITY_PROVIDER_FAILED"))
    }

    @Test
    fun `membership activation guard failure marks dispatch failed and rethrows for retry`() {
        val context = store.activePendingProvisioningContext()
        store.branchAssignments.remove(context.organisationId to context.userId)

        val failure =
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(keycloakRequest(context, "member@example.test", "member"))
            }

        assertEquals("CONFLICT", failure.message)
        assertNull(failure.cause)
        assertEquals(
            UserLifecycleState.INVITED,
            store.users.getValue(context.userId).state,
        )
        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            store.memberships.getValue(context.organisationId to context.membershipId).state,
        )
        val dispatch = store.dispatches.getValue(context.dispatchKey)
        assertEquals("FAILED", dispatch.status)
        assertEquals(1, dispatch.attempts)
        assertEquals("CONFLICT:ConflictException", dispatch.lastError)
        val dispatchAudit = audits.items.single { it.action == "user.keycloak_provisioning" }
        assertEquals(com.finaxis.platform.common.audit.AuditOutcome.FAILURE, dispatchAudit.outcome)
        assertEquals("ConflictException", dispatchAudit.reason)
    }

    @Test
    fun `a missing aggregate marks dispatch failed and rethrows the not found error`() {
        val context = store.activePendingProvisioningContext()
        gateway.failure = ResourceNotFoundException()

        val failure =
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(keycloakRequest(context, "member@example.test", "member"))
            }
        assertEquals("NOT_FOUND", failure.message)

        val dispatch = store.dispatches.getValue(context.dispatchKey)
        assertEquals("FAILED", dispatch.status)
        assertEquals(1, dispatch.attempts)
        val dispatchAudit = audits.items.single { it.action == "user.keycloak_provisioning" }
        assertEquals(com.finaxis.platform.common.audit.AuditOutcome.FAILURE, dispatchAudit.outcome)
        assertEquals("ResourceNotFoundException", dispatchAudit.reason)
    }

    @Test
    fun `an uncaught failure type is logged without its message and sanitised without recording`() {
        val context = store.activePendingProvisioningContext()
        gateway.failure = UnsupportedOperationException("provider said jane.doe@acme.test")
        val appender = captureSanitisedLog()

        val failure =
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(keycloakRequest(context, "member@example.test", "member"))
            }

        assertEquals("UNEXPECTED", failure.message)
        assertNull(failure.cause)
        assertTrue(store.dispatches[context.dispatchKey]?.status != "FAILED")
        val logged = appender.eventsFor(context.dispatchKey).single().formattedMessage
        assertTrue(logged.contains(context.dispatchKey))
        assertTrue(logged.contains("exceptionClass=java.lang.UnsupportedOperationException"))
        assertFalse(logged.contains("jane.doe@acme.test"))
    }

    @Test
    fun `a do-not-retry JobRunrException propagates unchanged`() {
        val context = store.activePendingProvisioningContext()
        val doNotRetry = org.jobrunr.JobRunrException("stop", true)
        gateway.failure = doNotRetry

        val thrown =
            assertFailsWith<org.jobrunr.JobRunrException> {
                handler.run(keycloakRequest(context, "member@example.test", "member"))
            }

        assertSame(doNotRetry, thrown)
        assertTrue(thrown.isProblematicAndDoNotRetry())
    }

    @Test
    fun `a failure on the already succeeded branch is sanitised too`() {
        val context = store.activePendingProvisioningContext()
        store.dispatches[context.dispatchKey] =
            DispatchState("SUCCEEDED", attempts = 1, externalRef = context.subject)
        val throwingBootstrap = mock(InitialAdministratorBootstrapService::class.java)
        org.mockito.Mockito
            .doThrow(
                org.springframework.dao.DataIntegrityViolationException("Key (email)=(a@b.test)"),
            ).`when`(throwingBootstrap)
            .completeBootstrapIfCorrelated(context.organisationId, context.userId)
        val sameHandler =
            KeycloakUserProvisioningJobRequestHandler(
                gateway,
                store,
                lifecycle,
                dispatchOutcomeAuditor,
                throwingBootstrap,
                failureRecorder,
                activation,
            )
        val appender = captureSanitisedLog()

        val failure =
            assertFailsWith<SanitisedJobFailureException> {
                sameHandler.run(keycloakRequest(context, "member@example.test", "member"))
            }

        assertEquals("DATABASE_ERROR", failure.message)
        assertNull(failure.cause)
        assertFalse(
            appender
                .eventsFor(context.dispatchKey)
                .single()
                .formattedMessage
                .contains("a@b.test"),
        )
    }

    /** Every appender this test attached, so [detachLogAppenders] can remove it again. */
    private val attachedAppenders =
        mutableListOf<Pair<ch.qos.logback.classic.Logger, ListAppender<ILoggingEvent>>>()

    /**
     * Detaches the appenders this test attached. The loggers are JVM-global: an appender left
     * behind keeps collecting from other threads, such as a background JobRunr retry in a cached
     * context, and the next test would read those events.
     */
    @AfterEach
    fun detachLogAppenders() {
        attachedAppenders.forEach { (logger, appender) ->
            logger.detachAppender(appender)
            appender.stop()
        }
        attachedAppenders.clear()
    }

    /**
     * This test's events only. Each assertion names the dispatch key or organisation of its own
     * test, so an event another thread logged to the same shared logger cannot break it.
     */
    private fun ListAppender<ILoggingEvent>.eventsFor(key: String): List<ILoggingEvent> =
        list.filter { it.formattedMessage.contains(key) }

    private fun captureSanitisedLog(): ListAppender<ILoggingEvent> =
        attachTo(SanitisedJobFailureException::class.java)

    private fun captureRecorderLog(): ListAppender<ILoggingEvent> =
        attachTo(InitialAdministratorBootstrapFailureRecorder::class.java)

    private fun attachTo(loggerType: Class<*>): ListAppender<ILoggingEvent> {
        val logger = org.slf4j.LoggerFactory.getLogger(loggerType) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        attachedAppenders += logger to appender
        return appender
    }

    private fun keycloakRequest(
        context: ProvisioningWorkerContext,
        email: String,
        username: String,
        sendKeycloakInvite: Boolean = false,
        generation: Int = 0,
    ): KeycloakUserProvisioningJobRequest =
        KeycloakUserProvisioningJobRequest(
            organisationId = context.organisationId,
            membershipId = context.membershipId,
            userId = context.userId,
            email = email,
            username = username,
            displayName = "Member One",
            sendKeycloakInvite = sendKeycloakInvite,
            dispatchKey = context.dispatchKey,
            generation = generation,
        )

    private fun bootstrapRecord(
        context: ProvisioningWorkerContext,
        status: InitialAdministratorBootstrapStatus =
            InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY,
    ) = InitialAdministratorBootstrapRecord(
        organisationId = context.organisationId,
        adminEmail = "admin@example.test",
        adminUsername = "admin",
        adminDisplayName = "Admin",
        adminPhoneE164 = null,
        sendApplicationInvite = false,
        status = status,
        attempts = 1,
        requestedBy = uuidV7(),
        submittedBy = uuidV7(),
        approvedBy = uuidV7(),
        userId = context.userId,
        membershipId = context.membershipId,
        headOfficeId = uuidV7(),
        roleId = uuidV7(),
        lastFailureCode = null,
        createdAt = clock.instant(),
        submittedAt = clock.instant(),
        approvedAt = clock.instant(),
        updatedAt = clock.instant(),
        rowVersion = 1,
    )
}

private class IdentityProvisioningGatewayFake : IdentityProvisioningGateway {
    var failure: RuntimeException? = null
    var findOrCreateCalls = 0
    var requiredActionsCalls = 0
    var subject = "keycloak-subject-1"

    /** Runs while Keycloak is being called, to change the world under a job that is mid-flight. */
    var whileCalling: (() -> Unit)? = null

    override fun findOrCreateUser(request: KeycloakUserProvisioningRequest): KeycloakUserRef {
        failure?.let { throw it }
        findOrCreateCalls += 1
        whileCalling?.invoke()
        return KeycloakUserRef(subject, created = true)
    }

    override fun sendRequiredActionsEmail(subject: String) {
        requiredActionsCalls += 1
    }
}

private class ProvisioningWorkerStoreFake :
    FoundationLifecycleReader,
    FoundationLifecycleWriter,
    LifecyclePrerequisites,
    UserProvisioningStore {
    val organisationStates = mutableMapOf<UUID, OrganisationLifecycleState>()
    val users = mutableMapOf<UUID, LifecycleAggregate<UserLifecycleState>>()
    val memberships = mutableMapOf<Pair<UUID, UUID>, LifecycleAggregate<MembershipLifecycleState>>()
    val membershipUsers = mutableMapOf<Pair<UUID, UUID>, UUID>()
    val membershipTypes = mutableMapOf<Pair<UUID, UUID>, MembershipType>()
    val branchAssignments = mutableSetOf<Pair<UUID, UUID>>()
    val roleAssignments = mutableSetOf<Pair<UUID, UUID>>()
    val linkedSubjects = mutableMapOf<UUID, String>()
    val dispatches = mutableMapOf<String, DispatchState>()
    val generations = mutableMapOf<Pair<UUID, UUID>, Int>()

    fun activePendingProvisioningContext(): ProvisioningWorkerContext {
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val userId = uuidV7()
        val dispatchKey = "$userId:KEYCLOAK_PROVISIONING"
        organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        users[userId] = LifecycleAggregate(userId, UserLifecycleState.PROVISIONING_IDP, "USER")
        memberships[organisationId to membershipId] =
            LifecycleAggregate(
                membershipId,
                MembershipLifecycleState.PENDING_APPROVAL,
                "MEMBERSHIP",
            )
        membershipUsers[organisationId to membershipId] = userId
        membershipTypes[organisationId to membershipId] = MembershipType.STAFF
        branchAssignments += organisationId to userId
        roleAssignments += organisationId to userId
        dispatches[dispatchKey] = DispatchState("PENDING")
        return ProvisioningWorkerContext(organisationId, membershipId, userId, dispatchKey)
    }

    override fun linkKeycloakIdentity(
        userId: UUID,
        subject: String,
        realm: String,
        actorId: UUID,
    ) {
        linkedSubjects.putIfAbsent(userId, subject)
    }

    override fun markDispatchSucceeded(
        dispatchKey: String,
        externalRef: String,
    ) {
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

    override fun dispatchStatus(dispatchKey: String): String? = dispatches[dispatchKey]?.status

    override fun markDispatchPending(dispatchKey: String) {
        dispatches[dispatchKey]?.let {
            if (it.status == "FAILED") dispatches[dispatchKey] = it.copy(status = "PENDING")
        }
    }

    override fun organisationState(organisationId: UUID): OrganisationLifecycleState? =
        organisationStates[organisationId]

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

    override fun userStatus(userId: UUID): UserLifecycleState? = users[userId]?.state

    override fun hasKeycloakIdentity(userId: UUID): Boolean = linkedSubjects.containsKey(userId)

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
    ): MembershipProvisioningSnapshot? {
        val key = organisationId to membershipId
        val membership = memberships[key] ?: return null
        val userId = requireNotNull(membershipUsers[key])
        return MembershipProvisioningSnapshot(
            id = membershipId,
            userId = userId,
            status = membership.state,
            type = requireNotNull(membershipTypes[key]),
            email = "member@example.test",
            username = "member",
            displayName = "Member",
            userStatus = requireNotNull(userStatus(userId)),
            sendKeycloakInvite = true,
            sendApplicationInvite = true,
            generation = generations[key] ?: 0,
        )
    }

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
    ): BranchLifecycleState? = BranchLifecycleState.ACTIVE

    override fun roleExists(
        organisationId: UUID,
        roleId: UUID,
    ): Boolean = true

    override fun findRoleIdByCode(
        organisationId: UUID,
        roleCode: String,
    ): UUID? = uuidV7()

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
    ): Boolean = branchAssignments.contains(organisationId to userId)

    override fun hasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = roleAssignments.contains(organisationId to userId)

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
    ) {
        dispatches.putIfAbsent(dispatchKey, DispatchState("PENDING"))
    }

    override fun findOrganisation(id: UUID): LifecycleAggregate<OrganisationLifecycleState>? = null

    override fun findBranch(
        organisationId: UUID,
        branchId: UUID,
    ): LifecycleAggregate<BranchLifecycleState>? = null

    override fun findUser(userId: UUID): LifecycleAggregate<UserLifecycleState>? = users[userId]

    override fun findMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): LifecycleAggregate<MembershipLifecycleState>? = memberships[organisationId to membershipId]

    override fun membershipUserId(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID? = membershipUsers[organisationId to membershipId]

    override fun findOrganisationIdsForActiveUserAccess(userId: UUID): Set<UUID> = emptySet()

    override fun saveOrganisation(
        aggregate: LifecycleAggregate<OrganisationLifecycleState>,
    ): LifecycleAggregate<OrganisationLifecycleState> = aggregate

    override fun saveBranch(
        aggregate: LifecycleAggregate<BranchLifecycleState>,
    ): LifecycleAggregate<BranchLifecycleState> = aggregate

    override fun saveUser(
        aggregate: LifecycleAggregate<UserLifecycleState>,
    ): LifecycleAggregate<UserLifecycleState> = aggregate

    override fun saveMembership(
        aggregate: LifecycleAggregate<MembershipLifecycleState>,
    ): LifecycleAggregate<MembershipLifecycleState> = aggregate

    override fun revokeActiveAssignments(
        organisationId: UUID,
        userId: UUID,
    ): List<DeprovisionedAssignment> = emptyList()

    override fun branchHasActiveAssignments(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean = false

    override fun branchHasActiveChildren(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean = false

    override fun userHasKeycloakIdentity(userId: UUID): Boolean = linkedSubjects.containsKey(userId)

    override fun userState(userId: UUID): UserLifecycleState? = userStatus(userId)

    override fun membershipIsBranchExempt(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = false

    override fun membershipHasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = hasActiveBranchAssignment(organisationId, userId)

    override fun membershipHasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = hasActiveRoleAssignment(organisationId, userId)
}

private class WorkerTransitionEventCapture : TransitionEventPublisher {
    override fun publish(event: TransitionEvent) = Unit
}

private class WorkerAuditCapture : AuditEventRepository {
    val items = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        items.add(event)
    }
}

private class WorkerTransitionLogCapture : TransitionLogRepository {
    override fun save(log: TransitionLog) = Unit
}

private data class ProvisioningWorkerContext(
    val organisationId: UUID,
    val membershipId: UUID,
    val userId: UUID,
    val dispatchKey: String,
) {
    val subject: String = "keycloak-subject-1"
}

private data class DispatchState(
    val status: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val externalRef: String? = null,
)
