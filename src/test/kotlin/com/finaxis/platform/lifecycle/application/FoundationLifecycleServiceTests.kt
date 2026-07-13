package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.InternalTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogRepository
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleTransition
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoundationLifecycleServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-13T10:00:00Z"), ZoneOffset.UTC)
    private val persistence = FakeLifecyclePersistence()
    private val logs = CapturingTransitionLogs()
    private val audits = CapturingAudits()
    private val events = CapturingTransitionPublisher()
    private val service =
        FoundationLifecycleService(
            TransitionExecutor(clock, logs, events),
            persistence,
            persistence,
            persistence,
            AuditService(audits, clock),
        )

    @Test
    fun `organisation provisioning publishes an internal transition event`() {
        val organisationId = UUID.randomUUID()
        persistence.organisations[organisationId] =
            LifecycleAggregate(
                organisationId,
                OrganisationLifecycleState.PENDING_APPROVAL,
                ORGANISATION,
            )

        val result =
            service.transition(
                OrganisationTransitionCommand(
                    organisationId,
                    OrganisationLifecycleTransition.START_PROVISIONING,
                ),
            )

        assertEquals(OrganisationLifecycleState.PROVISIONING, result.aggregate.state)
        assertEquals("START_PROVISIONING", logs.items.single().transition)
        assertEquals("organisation.start_provisioning", audits.items.single().action)
        val event = events.published.single() as InternalTransitionEvent
        assertEquals(ORGANISATION, event.aggregateType)
        assertEquals(organisationId.toString(), event.aggregateId)
        assertEquals(OrganisationLifecycleTransition.START_PROVISIONING.name, event.transition)
    }

    @Test
    fun `branch activation is rejected until its organisation is active or provisioning`() {
        val organisationId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
        persistence.organisationStates[organisationId] = OrganisationLifecycleState.DRAFT
        persistence.branches[organisationId to branchId] =
            LifecycleAggregate(branchId, BranchLifecycleState.PENDING_APPROVAL, BRANCH)

        val exception =
            assertThrows<com.finaxis.platform.common.transitions.TransitionGuardException> {
                service.transition(
                    BranchTransitionCommand(
                        organisationId,
                        branchId,
                        BranchLifecycleTransition.ACTIVATE,
                    ),
                )
            }

        assertEquals(
            "A branch can be activated only for an active or provisioning organisation.",
            exception.message,
        )
        assertEquals(
            BranchLifecycleState.PENDING_APPROVAL,
            persistence.branches.getValue(organisationId to branchId).state,
        )
        assertTrue(logs.items.isEmpty())
    }

    @Test
    fun `branch reactivation is rejected when its organisation is suspended`() {
        val organisationId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
        persistence.organisationStates[organisationId] = OrganisationLifecycleState.SUSPENDED
        persistence.branches[organisationId to branchId] =
            LifecycleAggregate(branchId, BranchLifecycleState.SUSPENDED, BRANCH)

        val exception =
            assertThrows<com.finaxis.platform.common.transitions.TransitionGuardException> {
                service.transition(
                    BranchTransitionCommand(
                        organisationId,
                        branchId,
                        BranchLifecycleTransition.REACTIVATE,
                    ),
                )
            }

        assertEquals(
            "A branch can be reactivated only for an active or provisioning organisation.",
            exception.message,
        )
        assertEquals(
            BranchLifecycleState.SUSPENDED,
            persistence.branches.getValue(organisationId to branchId).state,
        )
        assertTrue(logs.items.isEmpty())
    }

    @Test
    fun `membership activation requires active organisation user branch and role`() {
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        persistence.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        persistence.membershipUsers[organisationId to membershipId] = userId
        persistence.memberships[organisationId to membershipId] =
            LifecycleAggregate(membershipId, MembershipLifecycleState.PENDING_APPROVAL, MEMBERSHIP)
        persistence.userStates[userId] = UserLifecycleState.ACTIVE

        assertThrows<com.finaxis.platform.common.transitions.TransitionGuardException> {
            service.transition(
                MembershipTransitionCommand(
                    organisationId,
                    membershipId,
                    transition = MembershipLifecycleTransition.ACTIVATE,
                ),
            )
        }

        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            persistence.memberships.getValue(organisationId to membershipId).state,
        )
        assertTrue(audits.items.isEmpty())
    }

    @Test
    fun `membership activation publishes its externalized event with resolved user metadata`() {
        val organisationId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
        persistence.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        persistence.membershipUsers[organisationId to membershipId] = userId
        persistence.memberships[organisationId to membershipId] =
            LifecycleAggregate(membershipId, MembershipLifecycleState.PENDING_APPROVAL, MEMBERSHIP)
        persistence.userStates[userId] = UserLifecycleState.ACTIVE
        persistence.branchAssignments = true
        persistence.roleAssignments = true

        service.transition(
            MembershipTransitionCommand(
                organisationId,
                membershipId,
                branchId = branchId,
                transition = MembershipLifecycleTransition.ACTIVATE,
            ),
        )

        val event = events.published.single() as ExternalizedTransitionEvent
        assertEquals("finaxis.lifecycle.membership.activated", event.target)
        assertEquals(MEMBERSHIP, event.aggregateType)
        assertEquals(membershipId.toString(), event.aggregateId)
        assertEquals(MembershipLifecycleTransition.ACTIVATE.name, event.transition)
        assertEquals(
            mapOf(
                "membershipId" to membershipId.toString(),
                "userId" to userId.toString(),
                "organisationId" to organisationId.toString(),
                "branchId" to branchId.toString(),
                "occurredAt" to clock.instant().toString(),
            ),
            event.metadata,
        )
    }

    @Test
    fun `completed user deactivation revokes active assignments`() {
        val organisationId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        persistence.users[userId] =
            LifecycleAggregate(
                userId,
                UserLifecycleState.DEACTIVATING,
                USER_ACCOUNT,
            )

        service.transition(
            UserTransitionCommand(
                organisationId,
                userId,
                transition = UserLifecycleTransition.COMPLETE_DEACTIVATION,
            ),
        )

        assertTrue(persistence.revokedUsers.contains(organisationId to userId))
        assertFalse(audits.items.isEmpty())
        assertEquals(
            "DEACTIVATED",
            persistence.users
                .getValue(userId)
                .state.name,
        )
    }
}

private class FakeLifecyclePersistence :
    FoundationLifecycleReader,
    FoundationLifecycleWriter,
    com.finaxis.platform.lifecycle.domain.LifecyclePrerequisites {
    val organisations = mutableMapOf<UUID, LifecycleAggregate<OrganisationLifecycleState>>()
    val branches = mutableMapOf<Pair<UUID, UUID>, LifecycleAggregate<BranchLifecycleState>>()
    val users = mutableMapOf<UUID, LifecycleAggregate<UserLifecycleState>>()
    val memberships = mutableMapOf<Pair<UUID, UUID>, LifecycleAggregate<MembershipLifecycleState>>()
    val organisationStates = mutableMapOf<UUID, OrganisationLifecycleState>()
    val userStates = mutableMapOf<UUID, UserLifecycleState>()
    val membershipUsers = mutableMapOf<Pair<UUID, UUID>, UUID>()
    val revokedUsers = mutableSetOf<Pair<UUID, UUID>>()
    var branchAssignments = false
    var roleAssignments = false

    override fun findOrganisation(id: UUID) = organisations[id]

    override fun findBranch(
        organisationId: UUID,
        branchId: UUID,
    ) = branches[organisationId to branchId]

    override fun findUser(userId: UUID) = users[userId]

    override fun findMembership(
        organisationId: UUID,
        membershipId: UUID,
    ) = memberships[organisationId to membershipId]

    override fun membershipUserId(
        organisationId: UUID,
        membershipId: UUID,
    ) = membershipUsers[organisationId to membershipId]

    override fun saveOrganisation(aggregate: LifecycleAggregate<OrganisationLifecycleState>) =
        aggregate

    override fun saveBranch(aggregate: LifecycleAggregate<BranchLifecycleState>) = aggregate

    override fun saveUser(aggregate: LifecycleAggregate<UserLifecycleState>) = aggregate

    override fun saveMembership(aggregate: LifecycleAggregate<MembershipLifecycleState>) = aggregate

    override fun revokeActiveAssignments(
        organisationId: UUID,
        userId: UUID,
    ) {
        revokedUsers.add(organisationId to userId)
    }

    override fun organisationState(organisationId: UUID) = organisationStates[organisationId]

    override fun branchHasActiveAssignments(
        organisationId: UUID,
        branchId: UUID,
    ) = false

    override fun userHasKeycloakIdentity(userId: UUID) = true

    override fun userState(userId: UUID) = userStates[userId]

    override fun membershipHasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ) = branchAssignments

    override fun membershipHasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ) = roleAssignments
}

private class CapturingTransitionLogs : TransitionLogRepository {
    val items = mutableListOf<TransitionLog>()

    override fun save(log: TransitionLog) {
        items.add(log)
    }
}

private class CapturingAudits : AuditEventRepository {
    val items = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        items.add(event)
    }
}

private class CapturingTransitionPublisher : TransitionEventPublisher {
    val published = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        published.add(event)
    }
}

private const val ORGANISATION = "ORGANISATION"
private const val BRANCH = "BRANCH"
private const val MEMBERSHIP = "MEMBERSHIP"
private const val USER_ACCOUNT = "USER_ACCOUNT"
