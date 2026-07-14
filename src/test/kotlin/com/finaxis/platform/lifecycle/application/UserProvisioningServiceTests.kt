package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogRepository
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.LifecyclePrerequisites
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleTransition
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserProvisioningServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T11:00:00Z"), ZoneOffset.UTC)
    private val fake = UserProvisioningFake()
    private val events = UserProvisioningEventCapture()
    private val audits = UserProvisioningAuditCapture()
    private val logs = UserProvisioningTransitionLogCapture()
    private val lifecycle =
        FoundationLifecycleService(
            TransitionExecutor(clock, logs, events),
            fake,
            fake,
            fake,
            AuditService(audits, clock),
        )
    private val branches =
        BranchProvisioningService(lifecycle, fake, fake, AuditService(audits, clock), events)
    private val deactivationAssignmentRevoker =
        UserDeactivationAssignmentRevoker(fake, AuditService(audits, clock), events, clock)
    private val service =
        UserProvisioningService(
            lifecycle,
            fake,
            branches,
            deactivationAssignmentRevoker,
            AuditService(audits, clock),
            events,
            clock,
        )

    @Test
    fun `new user invite creates draft user pending membership assignments and audit`() {
        val context = activeInvitationContext()

        val result = service.inviteUser(inviteCommand(context))

        assertEquals(UserLifecycleState.DRAFT, result.userStatus)
        assertEquals(MembershipLifecycleState.PENDING_APPROVAL, result.membershipStatus)
        assertTrue(fake.branchAssignments.contains(BranchAssignmentKey(context.org, result.userId)))
        assertEquals(1, fake.roleAssignments.size)
        assertTrue(audits.events.any { it.action == "user.invite" })
    }

    @Test
    fun `invitation reuses existing user by email`() {
        val context = activeInvitationContext()
        val existingUserId =
            fake.addUser("member@example.test", "member", UserLifecycleState.INVITED)

        val result = service.inviteUser(inviteCommand(context))

        assertEquals(existingUserId, result.userId)
        assertEquals(1, fake.users.size)
    }

    @Test
    fun `invitation rejects inactive organisation`() {
        val context = activeInvitationContext()
        fake.organisationStates[context.org] = OrganisationLifecycleState.SUSPENDED

        assertFailsWith<IllegalArgumentException> {
            service.inviteUser(inviteCommand(context))
        }
    }

    @Test
    fun `invitation rejects missing ordinary branch assignment`() {
        val context = activeInvitationContext()

        assertFailsWith<IllegalArgumentException> {
            service.inviteUser(inviteCommand(context).copy(branchAssignments = emptyList()))
        }
    }

    @Test
    fun `invitation rejects missing role assignment`() {
        val context = activeInvitationContext()

        assertFailsWith<IllegalArgumentException> {
            service.inviteUser(inviteCommand(context).copy(roleAssignments = emptyList()))
        }
    }

    @Test
    fun `invitation rejects duplicate active membership`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.addMembership(context.org, userId, MembershipLifecycleState.ACTIVE)

        assertFailsWith<IllegalArgumentException> {
            service.inviteUser(inviteCommand(context))
        }
    }

    @Test
    fun `approval of new user requests keycloak provisioning and leaves membership pending`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))

        val approval =
            service.approveUser(
                ApproveUserCommand(context.org, invitation.membershipId, context.actor, "req-1"),
            )

        assertTrue(approval.keycloakProvisioningRequested)
        assertEquals(UserLifecycleState.PROVISIONING_IDP, approval.userStatus)
        assertEquals(MembershipLifecycleState.PENDING_APPROVAL, approval.membershipStatus)
        assertTrue(
            events.externalTargets().contains(
                "finaxis.lifecycle.user.keycloak-provisioning-requested",
            ),
        )
        assertTrue(
            fake.dispatches.contains(
                DispatchRecord(
                    invitation.userId,
                    IdentityDispatchType.KEYCLOAK_PROVISIONING,
                    "${invitation.userId}:KEYCLOAK_PROVISIONING",
                ),
            ),
        )
    }

    @Test
    fun `approval of existing active user activates membership immediately`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.identityLinks += userId
        val invitation = service.inviteUser(inviteCommand(context))

        val approval =
            service.approveUser(
                ApproveUserCommand(context.org, invitation.membershipId, context.actor),
            )

        assertFalse(approval.keycloakProvisioningRequested)
        assertEquals(MembershipLifecycleState.ACTIVE, approval.membershipStatus)
        assertEquals(MembershipLifecycleTransition.ACTIVATE.name, logs.logs.last().transition)
    }

    @Test
    fun `application invite event is emitted when enabled`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context, sendApplicationInvite = true))

        val approval =
            service.approveUser(
                ApproveUserCommand(context.org, invitation.membershipId, context.actor),
            )

        assertTrue(approval.applicationInviteRequested)
        assertTrue(
            events.externalTargets().contains(
                "finaxis.lifecycle.user.application-invite-requested",
            ),
        )
        assertTrue(
            fake.dispatches.contains(
                DispatchRecord(
                    invitation.userId,
                    IdentityDispatchType.APPLICATION_INVITE,
                    "${invitation.userId}:${context.org}:APPLICATION_INVITE",
                ),
            ),
        )
    }

    @Test
    fun `suspend reactivate and deactivate drive user lifecycle transitions`() {
        val org = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val userId = fake.addUser("user@example.test", "user", UserLifecycleState.ACTIVE)
        fake.organisationStates[org] = OrganisationLifecycleState.ACTIVE
        fake.identityLinks += userId

        service.suspendUser(SuspendUserCommand(org, userId, actor, "risk"))
        assertEquals(UserLifecycleState.SUSPENDED, fake.users.getValue(userId).state)

        service.reactivateUser(ReactivateUserCommand(org, userId, actor, "cleared"))
        assertEquals(UserLifecycleState.ACTIVE, fake.users.getValue(userId).state)

        service.deactivateUser(DeactivateUserCommand(org, userId, actor, "left"))
        assertEquals(UserLifecycleState.DEACTIVATED, fake.users.getValue(userId).state)
        assertTrue(
            logs.logs.map { it.transition }.contains(UserLifecycleTransition.REACTIVATE.name),
        )
    }

    @Test
    fun `completed deactivation revokes active assignments with audit and externalized events`() {
        val org = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.organisationStates[org] = OrganisationLifecycleState.ACTIVE
        fake.identityLinks += userId
        fake.branchAssignments += BranchAssignmentKey(org, userId)
        val roleId = UUID.randomUUID()
        fake.roleAssignments += RoleAssignmentKey(org, userId, roleId, null)

        service.deactivateUser(DeactivateUserCommand(org, userId, actor, "left"))

        assertTrue(fake.branchAssignments.isEmpty())
        assertTrue(fake.roleAssignments.isEmpty())
        assertTrue(
            audits.events.any { it.action == "user.deactivation_assignment_revoked" },
        )
        assertEquals(
            2,
            audits.events.count { it.action == "user.deactivation_assignment_revoked" },
        )
        assertTrue(
            events.externalTargets().count {
                it == "finaxis.lifecycle.user.deactivation-assignment-revoked"
            } == 2,
        )
    }

    @Test
    fun `membership revocation cascades branch and role revocation`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        val membershipId = fake.addMembership(context.org, userId, MembershipLifecycleState.ACTIVE)
        fake.branchAssignments += BranchAssignmentKey(context.org, userId)
        fake.roleAssignments += RoleAssignmentKey(context.org, userId, context.role, null)

        service.revokeTenantMembership(
            RevokeTenantMembershipCommand(context.org, membershipId, context.actor, "offboard"),
        )

        assertEquals(
            MembershipLifecycleState.REVOKED,
            fake.memberships.getValue(context.org to membershipId).state,
        )
        assertTrue(fake.branchAssignments.isEmpty())
        assertTrue(fake.roleAssignments.isEmpty())
        assertTrue(audits.events.any { it.action == "membership.revoke" })
    }

    private fun activeInvitationContext(): InvitationContext {
        val context =
            InvitationContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
            )
        fake.organisationStates[context.org] = OrganisationLifecycleState.ACTIVE
        fake.branchStates[context.org to context.branch] = BranchLifecycleState.ACTIVE
        fake.roles += context.org to context.role
        return context
    }

    private fun inviteCommand(
        context: InvitationContext,
        sendApplicationInvite: Boolean = false,
    ): InviteUserCommand =
        InviteUserCommand(
            organisationId = context.org,
            email = "member@example.test",
            username = "member",
            displayName = "Member One",
            membershipType = MembershipType.STAFF,
            primaryBranchId = context.branch,
            branchAssignments =
                listOf(BranchAssignmentRequest(context.branch, BranchAssignmentType.HOME)),
            roleAssignments =
                listOf(RoleAssignmentRequest(context.role, RoleAssignmentScopeType.TENANT)),
            invitedBy = context.actor,
            sendKeycloakInvite = true,
            sendApplicationInvite = sendApplicationInvite,
        )
}

private class UserProvisioningFake :
    FoundationLifecycleReader,
    FoundationLifecycleWriter,
    LifecyclePrerequisites,
    UserProvisioningStore,
    BranchLifecycleStore,
    BranchAssignmentStore {
    val organisationStates = mutableMapOf<UUID, OrganisationLifecycleState>()
    val branchStates = mutableMapOf<Pair<UUID, UUID>, BranchLifecycleState>()
    val users = mutableMapOf<UUID, LifecycleAggregate<UserLifecycleState>>()
    val userEmails = mutableMapOf<UUID, String>()
    val usernames = mutableMapOf<UUID, String>()
    val memberships = mutableMapOf<Pair<UUID, UUID>, LifecycleAggregate<MembershipLifecycleState>>()
    val membershipUsers = mutableMapOf<Pair<UUID, UUID>, UUID>()
    val membershipTypes = mutableMapOf<Pair<UUID, UUID>, MembershipType>()
    val preferences = mutableMapOf<Pair<UUID, UUID>, Pair<Boolean, Boolean>>()
    val roles = mutableSetOf<Pair<UUID, UUID>>()
    val identityLinks = mutableSetOf<UUID>()
    val branchAssignments = mutableSetOf<BranchAssignmentKey>()
    val roleAssignments = mutableSetOf<RoleAssignmentKey>()
    val dispatches = mutableSetOf<DispatchRecord>()

    fun addUser(
        email: String,
        username: String,
        status: UserLifecycleState,
    ): UUID {
        val userId = UUID.randomUUID()
        users[userId] = LifecycleAggregate(userId, status, "USER_ACCOUNT")
        userEmails[userId] = email
        usernames[userId] = username
        return userId
    }

    fun addMembership(
        organisationId: UUID,
        userId: UUID,
        status: MembershipLifecycleState,
        type: MembershipType = MembershipType.STAFF,
    ): UUID {
        val membershipId = UUID.randomUUID()
        memberships[organisationId to membershipId] =
            LifecycleAggregate(membershipId, status, "MEMBERSHIP", organisationId)
        membershipUsers[organisationId to membershipId] = userId
        membershipTypes[organisationId to membershipId] = type
        preferences[organisationId to membershipId] = true to true
        return membershipId
    }

    override fun organisationState(organisationId: UUID) = organisationStates[organisationId]

    override fun findUserIdByEmail(email: String): UUID? =
        userEmails.entries.firstOrNull { it.value.equals(email, ignoreCase = true) }?.key

    override fun createUserAccount(
        email: String,
        username: String,
        displayName: String,
        phoneE164: String?,
        actorId: UUID,
    ): UUID = addUser(email, username, UserLifecycleState.DRAFT)

    override fun userStatus(userId: UUID): UserLifecycleState? = users[userId]?.state

    override fun createMembership(
        organisationId: UUID,
        userId: UUID,
        membershipType: MembershipType,
        primaryBranchId: UUID?,
        actorId: UUID,
    ): UUID =
        addMembership(
            organisationId,
            userId,
            MembershipLifecycleState.PENDING_APPROVAL,
            membershipType,
        )

    override fun saveInvitationPreferences(
        organisationId: UUID,
        membershipId: UUID,
        sendKeycloakInvite: Boolean,
        sendApplicationInvite: Boolean,
        actorId: UUID,
    ) {
        preferences[organisationId to membershipId] = sendKeycloakInvite to sendApplicationInvite
    }

    override fun membershipSnapshot(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot? {
        val key = organisationId to membershipId
        val aggregate = memberships[key] ?: return null
        val userId = requireNotNull(membershipUsers[key])
        val preference = preferences[key] ?: (true to true)
        return MembershipProvisioningSnapshot(
            membershipId,
            userId,
            aggregate.state,
            requireNotNull(membershipTypes[key]),
            requireNotNull(userEmails[userId]),
            requireNotNull(usernames[userId]),
            requireNotNull(userStatus(userId)),
            preference.first,
            preference.second,
        )
    }

    override fun activeMembershipExists(
        organisationId: UUID,
        userId: UUID,
    ): Boolean =
        memberships.any { (key, aggregate) ->
            key.first == organisationId &&
                membershipUsers[key] == userId &&
                aggregate.state == MembershipLifecycleState.ACTIVE
        }

    override fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ): BranchLifecycleState? = branchStates[organisationId to branchId]

    override fun createDraft(command: CreateBranchCommand): UUID = UUID.randomUUID()

    override fun branchCodeExists(
        organisationId: UUID,
        branchCode: String,
        excludingBranchId: UUID?,
    ): Boolean = false

    override fun branchCode(
        organisationId: UUID,
        branchId: UUID,
    ): String? = "branch-$branchId"

    override fun parentBelongsToOrganisation(
        organisationId: UUID,
        parentBranchId: UUID,
    ): Boolean = branchStates.containsKey(organisationId to parentBranchId)

    override fun parentBranchId(
        organisationId: UUID,
        branchId: UUID,
    ): UUID? = null

    override fun roleExists(
        organisationId: UUID,
        roleId: UUID,
    ): Boolean = roles.contains(organisationId to roleId)

    override fun hasKeycloakIdentity(userId: UUID): Boolean = identityLinks.contains(userId)

    override fun assignRole(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleAssignmentScopeType,
        branchId: UUID?,
        actorId: UUID,
    ): UUID {
        roleAssignments += RoleAssignmentKey(organisationId, userId, roleId, branchId)
        return UUID.randomUUID()
    }

    override fun hasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = branchAssignments.contains(BranchAssignmentKey(organisationId, userId))

    override fun hasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = roleAssignments.any { it.organisationId == organisationId && it.userId == userId }

    override fun recordDispatch(
        organisationId: UUID,
        userId: UUID,
        dispatchType: IdentityDispatchType,
        dispatchKey: String,
        actorId: UUID,
    ) {
        dispatches += DispatchRecord(userId, dispatchType, dispatchKey)
    }

    override fun linkKeycloakIdentity(
        userId: UUID,
        subject: String,
        realm: String,
        actorId: UUID,
    ) {
        identityLinks += userId
    }

    override fun markDispatchSucceeded(
        dispatchKey: String,
        externalRef: String,
    ) = Unit

    override fun markDispatchFailed(
        dispatchKey: String,
        error: String,
    ) = Unit

    override fun dispatchStatus(dispatchKey: String): String? = null

    override fun revokeBranchAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int {
        val userId = requireNotNull(membershipUsers[organisationId to membershipId])
        val before = branchAssignments.size
        branchAssignments.removeIf { it.organisationId == organisationId && it.userId == userId }
        return before - branchAssignments.size
    }

    override fun revokeRoleAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int {
        val userId = requireNotNull(membershipUsers[organisationId to membershipId])
        val before = roleAssignments.size
        roleAssignments.removeIf { it.organisationId == organisationId && it.userId == userId }
        return before - roleAssignments.size
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
    ): List<DeprovisionedAssignment> {
        val matchingBranch =
            branchAssignments.filter { it.organisationId == organisationId && it.userId == userId }
        val matchingRole =
            roleAssignments.filter { it.organisationId == organisationId && it.userId == userId }
        branchAssignments.removeAll(matchingBranch.toSet())
        roleAssignments.removeAll(matchingRole.toSet())
        return matchingBranch.map {
            DeprovisionedAssignment(UUID.randomUUID(), "USER_BRANCH_ASSIGNMENT")
        } +
            matchingRole.map { DeprovisionedAssignment(UUID.randomUUID(), "USER_ROLE_ASSIGNMENT") }
    }

    override fun branchHasActiveAssignments(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean = false

    override fun branchHasActiveChildren(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean = false

    override fun userHasKeycloakIdentity(userId: UUID): Boolean = identityLinks.contains(userId)

    override fun userState(userId: UUID): UserLifecycleState? = userStatus(userId)

    override fun membershipHasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = hasActiveBranchAssignment(organisationId, userId)

    override fun membershipHasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = hasActiveRoleAssignment(organisationId, userId)

    override fun userExists(userId: UUID): Boolean = users.containsKey(userId)

    override fun membership(
        organisationId: UUID,
        userId: UUID,
    ): MembershipSnapshot? {
        val membership =
            memberships.entries.firstOrNull { (key, _) ->
                key.first == organisationId && membershipUsers[key] == userId
            } ?: return null
        return MembershipSnapshot(
            membership.value.state,
            requireNotNull(membershipTypes[membership.key]),
        )
    }

    override fun assign(command: AssignUserToBranchCommand): Boolean =
        branchAssignments.add(BranchAssignmentKey(command.organisationId, command.userId))

    override fun isActive(command: RevokeUserBranchAssignmentCommand): Boolean =
        branchAssignments.contains(BranchAssignmentKey(command.organisationId, command.userId))

    override fun activeAssignments(
        organisationId: UUID,
        userId: UUID,
    ): Int = if (branchAssignments.contains(BranchAssignmentKey(organisationId, userId))) 1 else 0

    override fun revoke(command: RevokeUserBranchAssignmentCommand): Boolean =
        branchAssignments.remove(BranchAssignmentKey(command.organisationId, command.userId))
}

private class UserProvisioningEventCapture : TransitionEventPublisher {
    val events = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        events += event
    }

    fun externalTargets(): List<String> =
        events.mapNotNull { (it as? ExternalizedTransitionEvent)?.target }
}

private class UserProvisioningAuditCapture : AuditEventRepository {
    val events = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        events += event
    }
}

private class UserProvisioningTransitionLogCapture : TransitionLogRepository {
    val logs = mutableListOf<TransitionLog>()

    override fun save(log: TransitionLog) {
        logs += log
    }
}

private data class InvitationContext(
    val org: UUID,
    val branch: UUID,
    val role: UUID,
    val actor: UUID,
)

private data class BranchAssignmentKey(
    val organisationId: UUID,
    val userId: UUID,
)

private data class RoleAssignmentKey(
    val organisationId: UUID,
    val userId: UUID,
    val roleId: UUID,
    val branchId: UUID?,
)

private data class DispatchRecord(
    val userId: UUID,
    val type: IdentityDispatchType,
    val key: String,
)
