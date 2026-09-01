package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.transitions.TransitionNotAllowedException
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The chart-of-accounts graph, asserted exhaustively.
 *
 * Four states and four transitions is sixteen pairs, of which four are legal. A test that checked
 * only the four legal ones would pass for a graph that allowed all sixteen.
 */
class GlAccountLifecycleTests {
    @Test
    fun `exactly four state and transition pairs are legal`() {
        val legal =
            GlAccountStatus.entries
                .flatMap { state ->
                    GlAccountTransition.entries
                        .filter { GlAccountLifecycle.GRAPH.isAllowed(state, it) }
                        .map { state to it }
                }.toSet()

        assertEquals(
            setOf(
                GlAccountStatus.DRAFT to GlAccountTransition.SUBMIT,
                GlAccountStatus.PENDING_APPROVAL to GlAccountTransition.APPROVE,
                GlAccountStatus.PENDING_APPROVAL to GlAccountTransition.REJECT,
                GlAccountStatus.ACTIVE to GlAccountTransition.DEACTIVATE,
            ),
            legal,
            "the other twelve pairs must be rejected",
        )
    }

    @Test
    fun `rejection returns an account to DRAFT rather than to a terminal state`() {
        // The reason there is no REJECTED status at all: account_code is unique per tenant, so a
        // rejected account that kept its code would hold it forever and the second attempt at the
        // same account could not reuse it.
        assertEquals(
            GlAccountStatus.DRAFT,
            GlAccountLifecycle.GRAPH
                .requireDefinition(GlAccountStatus.PENDING_APPROVAL, GlAccountTransition.REJECT)
                .to,
        )
        assertTrue(
            GlAccountStatus.entries.none { it.name == "REJECTED" },
            "adding REJECTED here would ship an enum chk_gl_account_status rejects",
        )
    }

    @Test
    fun `a draft account cannot be approved without being submitted`() {
        // Skipping the submission is how the maker-checker control gets bypassed: with no SUBMIT
        // row there is no maker to compare the approver against.
        assertFailsWith<TransitionNotAllowedException> {
            GlAccountLifecycle.GRAPH.requireDefinition(
                GlAccountStatus.DRAFT,
                GlAccountTransition.APPROVE,
            )
        }
    }

    @Test
    fun `INACTIVE is terminal, so reactivation is a decision nobody has made yet`() {
        assertTrue(
            GlAccountLifecycle.GRAPH.allowedTransitionsFrom(GlAccountStatus.INACTIVE).isEmpty(),
            "an account is deactivated because it must stop receiving postings; quietly " +
                "reversing that is what the maker-checker controls exist for",
        )
    }

    @Test
    fun `no transition publishes a broker event`() {
        assertTrue(
            GlAccountLifecycle.GRAPH.definitions().all { it.eventFactories.isEmpty() },
            "add an event factory only when a real downstream consumer exists",
        )
    }

    @Test
    fun `the aggregate reports the type the dispatching log repository routes on`() {
        // It deliberately carries no tenant: TransitionExecutor builds a log's metadata from the
        // TransitionCommand, never from the aggregate, so the organisation travels there.
        val aggregate = GlAccountAggregate(UUID.randomUUID(), GlAccountStatus.DRAFT)

        assertEquals("GL_ACCOUNT", aggregate.aggregateType)

        aggregate.transitionTo(GlAccountStatus.PENDING_APPROVAL)
        assertEquals(GlAccountStatus.PENDING_APPROVAL, aggregate.state)
    }
}
