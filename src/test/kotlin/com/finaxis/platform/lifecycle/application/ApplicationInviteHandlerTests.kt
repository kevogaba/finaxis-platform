package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationInviteHandlerTests {
    private val store = ApplicationInviteStoreFake()
    private val audits = ApplicationInviteAuditCapture()
    private val clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC)
    private val auditService = AuditService(audits, clock)
    private val handler =
        ApplicationInviteJobRequestHandler(store, DispatchOutcomeAuditor(store, auditService))

    @Test
    fun `application invite marks dispatch succeeded`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")

        handler.run(applicationInviteRequest(dispatchKey))

        val dispatch = store.dispatches.getValue(dispatchKey)
        assertEquals("SUCCEEDED", dispatch.status)
        assertEquals(1, dispatch.attempts)
        assertEquals("APPLICATION_INVITE", dispatch.externalRef)
        val dispatchAudit = audits.items.single { it.action == "user.application_invite" }
        assertEquals(AuditOutcome.SUCCESS, dispatchAudit.outcome)
        assertEquals(SystemActor.ID.toString(), dispatchAudit.actorId)
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

        assertFailsWith<IllegalStateException> {
            handler.run(applicationInviteRequest(dispatchKey))
        }

        val dispatch = store.dispatches.getValue(dispatchKey)
        assertEquals("FAILED", dispatch.status)
        val dispatchAudit = audits.items.single { it.action == "user.application_invite" }
        assertEquals(AuditOutcome.FAILURE, dispatchAudit.outcome)
        assertEquals("provider unavailable", dispatchAudit.reason)
        assertEquals(SystemActor.ID.toString(), dispatchAudit.actorId)
    }

    private fun applicationInviteRequest(dispatchKey: String): ApplicationInviteJobRequest =
        ApplicationInviteJobRequest(
            organisationId = uuidV7(),
            membershipId = uuidV7(),
            userId = uuidV7(),
            email = "member@example.test",
            dispatchKey = dispatchKey,
        )
}

private class ApplicationInviteStoreFake : UserProvisioningStore {
    val dispatches = mutableMapOf<String, InviteDispatchState>()
    var succeededCalls = 0
    var failure: RuntimeException? = null

    override fun dispatchStatus(dispatchKey: String): String? = dispatches[dispatchKey]?.status

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

    override fun findUserIdByEmail(email: String): UUID? = null

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
    ): MembershipProvisioningSnapshot? = null

    override fun membershipExists(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = false

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
