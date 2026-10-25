package com.finaxis.platform.lifecycle.domain

import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.InternalTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.transitions.TransitionContext
import com.finaxis.platform.common.transitions.TransitionException
import com.finaxis.platform.common.transitions.TransitionGraph
import com.finaxis.platform.common.transitions.TransitionGuardException
import com.finaxis.platform.common.transitions.Transitionable
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exhaustive matrix tests for every foundation lifecycle graph so no undeclared state/transition
 * pair can become legal without a deliberate graph definition change.
 */
class FoundationLifecycleTransitionTests {
    @Test
    fun `organisation lifecycle accepts only graph-declared transitions`() {
        assertMatrix(
            graph = FoundationLifecycleDefinitions.organisationGraph(),
            states = OrganisationLifecycleState.entries,
            transitions = OrganisationLifecycleTransition.entries,
        )
    }

    @Test
    fun `a pending organisation returns to draft through exactly one declared edge`() {
        val graph = FoundationLifecycleDefinitions.organisationGraph()

        val returns =
            graph.definitions().filter {
                it.transition == OrganisationLifecycleTransition.RETURN_FOR_CHANGES
            }

        assertEquals(
            listOf(
                OrganisationLifecycleState.PENDING_APPROVAL to OrganisationLifecycleState.DRAFT,
            ),
            returns.map { it.from to it.to },
        )
        // Nothing else leaves a pending organisation for DRAFT, and REJECT stays terminal.
        assertEquals(
            listOf(OrganisationLifecycleTransition.RETURN_FOR_CHANGES),
            graph
                .definitions()
                .filter { it.to == OrganisationLifecycleState.DRAFT }
                .map { it.transition },
        )
        assertEquals(
            emptyList(),
            graph
                .definitions()
                .filter { it.from == OrganisationLifecycleState.REJECTED },
        )
        OrganisationLifecycleState.entries
            .filter { it != OrganisationLifecycleState.PENDING_APPROVAL }
            .forEach { state ->
                assertFailsWith<TransitionException> {
                    graph.requireDefinition(
                        state,
                        OrganisationLifecycleTransition.RETURN_FOR_CHANGES,
                    )
                }
            }
        // The returned draft resubmits through the unchanged SUBMIT edge.
        assertEquals(
            OrganisationLifecycleState.PENDING_APPROVAL,
            graph
                .requireDefinition(
                    OrganisationLifecycleState.DRAFT,
                    OrganisationLifecycleTransition.SUBMIT,
                ).to,
        )
    }

    @Test
    fun `branch lifecycle accepts only graph-declared transitions`() {
        assertMatrix(
            graph =
                FoundationLifecycleDefinitions.branchGraph(
                    prerequisites = allowingPrerequisites,
                    organisationId = ORGANISATION_ID,
                    branchId = BRANCH_ID,
                ),
            states = BranchLifecycleState.entries,
            transitions = BranchLifecycleTransition.entries,
        )
    }

    @Test
    fun `a pending branch returns to draft through exactly one declared edge`() {
        val graph =
            FoundationLifecycleDefinitions.branchGraph(
                prerequisites = allowingPrerequisites,
                organisationId = ORGANISATION_ID,
                branchId = BRANCH_ID,
            )

        val returns =
            graph.definitions().filter {
                it.transition == BranchLifecycleTransition.RETURN_FOR_CHANGES
            }

        assertEquals(
            listOf(BranchLifecycleState.PENDING_APPROVAL to BranchLifecycleState.DRAFT),
            returns.map { it.from to it.to },
        )
        // Nothing else leaves a pending branch for DRAFT: one transition serves both intents.
        assertEquals(
            listOf(BranchLifecycleTransition.RETURN_FOR_CHANGES),
            graph
                .definitions()
                .filter { it.to == BranchLifecycleState.DRAFT }
                .map { it.transition },
        )
        // Every other state refuses it, and SUBMIT still resubmits the returned draft.
        BranchLifecycleState.entries
            .filter { it != BranchLifecycleState.PENDING_APPROVAL }
            .forEach { state ->
                assertFailsWith<TransitionException> {
                    graph.requireDefinition(state, BranchLifecycleTransition.RETURN_FOR_CHANGES)
                }
            }
        assertEquals(
            BranchLifecycleState.PENDING_APPROVAL,
            graph
                .requireDefinition(
                    BranchLifecycleState.DRAFT,
                    BranchLifecycleTransition.SUBMIT,
                ).to,
        )
    }

    @Test
    fun `a draft branch is discarded to closed through exactly one declared edge`() {
        val graph =
            FoundationLifecycleDefinitions.branchGraph(
                prerequisites = allowingPrerequisites,
                organisationId = ORGANISATION_ID,
                branchId = BRANCH_ID,
            )

        val discards =
            graph.definitions().filter { it.transition == BranchLifecycleTransition.DISCARD }

        assertEquals(
            listOf(BranchLifecycleState.DRAFT to BranchLifecycleState.CLOSED),
            discards.map { it.from to it.to },
        )
        // Every other state refuses it, whatever else can reach CLOSED from there.
        BranchLifecycleState.entries
            .filter { it != BranchLifecycleState.DRAFT }
            .forEach { state ->
                assertFailsWith<TransitionException>("$state") {
                    graph.requireDefinition(state, BranchLifecycleTransition.DISCARD)
                }
            }
        // The ways into CLOSED stay exactly these three: DISCARD has a name of its own, so the
        // transition log tells a branch that never opened from one that was closed.
        assertEquals(
            setOf(
                BranchLifecycleTransition.CLOSE,
                BranchLifecycleTransition.CLOSE_SUSPENDED,
                BranchLifecycleTransition.DISCARD,
            ),
            graph
                .definitions()
                .filter { it.to == BranchLifecycleState.CLOSED }
                .map { it.transition }
                .toSet(),
        )
        // Nothing new leaves DRAFT: SUBMIT and the cascade's SUSPEND_DRAFT keep their edges.
        assertEquals(
            setOf(
                BranchLifecycleTransition.SUBMIT,
                BranchLifecycleTransition.SUSPEND_DRAFT,
                BranchLifecycleTransition.DISCARD,
            ),
            graph
                .definitions()
                .filter { it.from == BranchLifecycleState.DRAFT }
                .map { it.transition }
                .toSet(),
        )
    }

    @Test
    fun `discarding a draft announces nothing outside the process`() {
        val graph = branchGraphWith(allowingPrerequisites)
        val definition =
            graph.requireDefinition(BranchLifecycleState.DRAFT, BranchLifecycleTransition.DISCARD)

        val events = definition.eventFactories.map { it.create(draftDiscardContext()) }

        // A branch that never opened has nothing to announce: no finaxis.lifecycle.branch.closed.
        assertTrue(events.isNotEmpty() && events.all { it is InternalTransitionEvent }, "$events")
        // CLOSE and CLOSE_SUSPENDED still announce, which is what DISCARD must not copy.
        listOf(
            BranchLifecycleState.ACTIVE to BranchLifecycleTransition.CLOSE,
            BranchLifecycleState.SUSPENDED to BranchLifecycleTransition.CLOSE_SUSPENDED,
        ).forEach { (from, transition) ->
            val closing = graph.requireDefinition(from, transition)
            val announced =
                closing.eventFactories.map {
                    it.create(
                        draftDiscardContext(from, transition),
                    )
                }
            assertTrue(announced.single() is ExternalizedTransitionEvent, "$transition")
        }
    }

    @Test
    fun `discarding a draft carries the closure guard for children and assignments`() {
        val withChildren =
            object : LifecyclePrerequisites by allowingPrerequisites {
                override fun branchHasActiveChildren(
                    organisationId: UUID,
                    branchId: UUID,
                ): Boolean = true
            }
        val withAssignments =
            object : LifecyclePrerequisites by allowingPrerequisites {
                override fun branchHasActiveAssignments(
                    organisationId: UUID,
                    branchId: UUID,
                ): Boolean = true
            }

        listOf(withChildren, withAssignments).forEach { prerequisites ->
            val definition =
                branchGraphWith(prerequisites)
                    .requireDefinition(
                        BranchLifecycleState.DRAFT,
                        BranchLifecycleTransition.DISCARD,
                    )
            val error =
                assertFailsWith<TransitionGuardException> {
                    definition.guards.forEach { it.check(draftDiscardContext()) }
                }
            assertTrue(error.message.orEmpty().contains("discarding"), error.message)
        }
        // With neither, every guard passes.
        branchGraphWith(allowingPrerequisites)
            .requireDefinition(BranchLifecycleState.DRAFT, BranchLifecycleTransition.DISCARD)
            .guards
            .forEach { it.check(draftDiscardContext()) }
    }

    private fun branchGraphWith(prerequisites: LifecyclePrerequisites) =
        FoundationLifecycleDefinitions.branchGraph(
            prerequisites = prerequisites,
            organisationId = ORGANISATION_ID,
            branchId = BRANCH_ID,
        )

    private fun draftDiscardContext(
        from: BranchLifecycleState = BranchLifecycleState.DRAFT,
        transition: BranchLifecycleTransition = BranchLifecycleTransition.DISCARD,
    ) = TransitionContext(
        aggregate = LifecycleAggregate(BRANCH_ID, from, "BRANCH", ORGANISATION_ID, 0L),
        transition = transition,
        fromState = from,
        toState = BranchLifecycleState.CLOSED,
        actor = TransitionActor("USER", USER_ID.toString()),
        command = TransitionCommand(reason = "Opened by mistake"),
        occurredAt = Instant.EPOCH,
    )

    @Test
    fun `user lifecycle accepts only graph-declared transitions`() {
        assertMatrix(
            graph =
                FoundationLifecycleDefinitions.userGraph(
                    prerequisites = allowingPrerequisites,
                    userId = USER_ID,
                ),
            states = UserLifecycleState.entries,
            transitions = UserLifecycleTransition.entries,
        )
    }

    @Test
    fun `membership lifecycle accepts only graph-declared transitions`() {
        assertMatrix(
            graph =
                FoundationLifecycleDefinitions.membershipGraph(
                    prerequisites = allowingPrerequisites,
                    organisationId = ORGANISATION_ID,
                    userId = USER_ID,
                ),
            states = MembershipLifecycleState.entries,
            transitions = MembershipLifecycleTransition.entries,
        )
    }

    @Test
    fun `membership REINVITE leaves only REVOKED and re-enters PENDING_APPROVAL`() {
        val graph =
            FoundationLifecycleDefinitions.membershipGraph(
                prerequisites = allowingPrerequisites,
                organisationId = ORGANISATION_ID,
                userId = USER_ID,
            )

        val reinvite =
            graph.requireDefinition(
                MembershipLifecycleState.REVOKED,
                MembershipLifecycleTransition.REINVITE,
            )

        assertEquals(MembershipLifecycleState.PENDING_APPROVAL, reinvite.to)
        // REVOKED has this one way out, and REINVITE has this one way in.
        assertEquals(
            listOf(MembershipLifecycleTransition.REINVITE),
            graph
                .definitions()
                .filter { it.from == MembershipLifecycleState.REVOKED }
                .map { it.transition },
        )
        assertEquals(
            listOf(MembershipLifecycleState.REVOKED),
            graph
                .definitions()
                .filter { it.transition == MembershipLifecycleTransition.REINVITE }
                .map { it.from },
        )
        MembershipLifecycleState.entries
            .filter { it != MembershipLifecycleState.REVOKED }
            .forEach { state ->
                assertFailsWith<TransitionException> {
                    graph.requireDefinition(state, MembershipLifecycleTransition.REINVITE)
                }
            }
    }

    private fun <S, T, A> assertMatrix(
        graph: TransitionGraph<S, T, A>,
        states: Iterable<S>,
        transitions: Iterable<T>,
    ) where S : Enum<S>, T : Enum<T>, A : Transitionable<S> {
        val legalPairs = graph.definitions().map { it.from to it.transition }.toSet()

        states.forEach { state ->
            transitions.forEach { transition ->
                if (state to transition in legalPairs) {
                    val definition = graph.requireDefinition(state, transition)
                    assertEquals(state, definition.from)
                    assertEquals(transition, definition.transition)
                } else {
                    assertFailsWith<TransitionException> {
                        graph.requireDefinition(state, transition)
                    }
                }
            }
        }
    }

    private companion object {
        val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val BRANCH_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val allowingPrerequisites =
            object : LifecyclePrerequisites {
                override fun organisationState(organisationId: UUID): OrganisationLifecycleState =
                    OrganisationLifecycleState.ACTIVE

                override fun branchHasActiveAssignments(
                    organisationId: UUID,
                    branchId: UUID,
                ): Boolean = false

                override fun branchHasActiveChildren(
                    organisationId: UUID,
                    branchId: UUID,
                ): Boolean = false

                override fun userHasKeycloakIdentity(userId: UUID): Boolean = true

                override fun userState(userId: UUID): UserLifecycleState = UserLifecycleState.ACTIVE

                override fun membershipIsBranchExempt(
                    organisationId: UUID,
                    userId: UUID,
                ): Boolean = true

                override fun membershipHasActiveBranchAssignment(
                    organisationId: UUID,
                    userId: UUID,
                ): Boolean = true

                override fun membershipHasActiveRoleAssignment(
                    organisationId: UUID,
                    userId: UUID,
                ): Boolean = true
            }
    }
}
