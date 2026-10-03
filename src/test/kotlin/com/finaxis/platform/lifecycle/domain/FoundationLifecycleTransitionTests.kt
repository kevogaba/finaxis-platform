package com.finaxis.platform.lifecycle.domain

import com.finaxis.platform.common.transitions.TransitionException
import com.finaxis.platform.common.transitions.TransitionGraph
import com.finaxis.platform.common.transitions.Transitionable
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
