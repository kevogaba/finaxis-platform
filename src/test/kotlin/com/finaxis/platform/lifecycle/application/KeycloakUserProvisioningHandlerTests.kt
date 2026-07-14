package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogRepository
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeycloakUserProvisioningHandlerTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC)
    private val store = ProvisioningWorkerStoreFake()
    private val gateway = IdentityProvisioningGatewayFake()
    private val events = WorkerTransitionEventCapture()
    private val audits = WorkerAuditCapture()
    private val logs = WorkerTransitionLogCapture()
    private val lifecycle =
        FoundationLifecycleService(
            TransitionExecutor(clock, logs, events),
            store,
            store,
            store,
            AuditService(audits, clock),
        )
    private val handler = KeycloakUserProvisioningJobRequestHandler(gateway, store, lifecycle)

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
        gateway.failure = IllegalStateException("keycloak unavailable")

        val failure =
            assertFailsWith<IllegalStateException> {
                handler.run(keycloakRequest(context, "member@example.test", "member"))
            }

        assertEquals("keycloak unavailable", failure.message)
        val dispatch = store.dispatches.getValue(context.dispatchKey)
        assertEquals("FAILED", dispatch.status)
        assertEquals(1, dispatch.attempts)
        assertTrue(requireNotNull(dispatch.lastError).contains("keycloak unavailable"))
    }

    private fun keycloakRequest(
        context: ProvisioningWorkerContext,
        email: String,
        username: String,
        sendKeycloakInvite: Boolean = false,
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
        )
}

private class IdentityProvisioningGatewayFake : IdentityProvisioningGateway {
    var failure: RuntimeException? = null
    var findOrCreateCalls = 0
    var requiredActionsCalls = 0
    var subject = "keycloak-subject-1"

    override fun findOrCreateUser(request: KeycloakUserProvisioningRequest): KeycloakUserRef {
        failure?.let { throw it }
        findOrCreateCalls += 1
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

    override fun organisationState(organisationId: UUID): OrganisationLifecycleState? =
        organisationStates[organisationId]

    override fun findUserIdByEmail(email: String): UUID? = null

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
        )
    }

    override fun membershipExists(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = false

    override fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ): BranchLifecycleState? = BranchLifecycleState.ACTIVE

    override fun roleExists(
        organisationId: UUID,
        roleId: UUID,
    ): Boolean = true

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
    override fun save(event: AuditEvent) = Unit
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
