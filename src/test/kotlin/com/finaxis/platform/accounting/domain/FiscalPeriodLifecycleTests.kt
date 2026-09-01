package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.transitions.TransitionNotAllowedException
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fiscal-period graph, asserted exhaustively.
 *
 * Exhaustively matters here more than usual: `FiscalPeriodStatus` has four states and four
 * transitions, so there are sixteen state-transition pairs and only four are legal. A test that
 * checked the four legal ones would pass for a graph that allowed all sixteen.
 */
class FiscalPeriodLifecycleTests {
    @Test
    fun `exactly four state and transition pairs are legal`() {
        val legal =
            FiscalPeriodStatus.entries
                .flatMap { state ->
                    FiscalPeriodTransition.entries
                        .filter { FiscalPeriodLifecycle.GRAPH.isAllowed(state, it) }
                        .map { state to it }
                }.toSet()

        assertEquals(
            setOf(
                FiscalPeriodStatus.FUTURE to FiscalPeriodTransition.OPEN,
                FiscalPeriodStatus.OPEN to FiscalPeriodTransition.CLOSE,
                FiscalPeriodStatus.CLOSED to FiscalPeriodTransition.REOPEN,
                FiscalPeriodStatus.CLOSED to FiscalPeriodTransition.LOCK,
            ),
            legal,
            "the other twelve pairs must be rejected",
        )
    }

    @Test
    fun `LOCKED is terminal`() {
        // The state FiscalPeriodStateChangeGuard has refused since issue #35, when nothing could
        // produce it. This graph is what makes it reachable; that only means something if it is
        // also a dead end.
        assertTrue(
            FiscalPeriodLifecycle.GRAPH.allowedTransitionsFrom(FiscalPeriodStatus.LOCKED).isEmpty(),
            "a locked period is permanently final, so nothing may transition out of it",
        )
    }

    @Test
    fun `LOCK is reachable only from CLOSED, never straight from OPEN`() {
        // A direct OPEN to LOCKED would let a period skip the closing controls entirely and land
        // permanently final in one step.
        assertFailsWith<TransitionNotAllowedException> {
            FiscalPeriodLifecycle.GRAPH.requireDefinition(
                FiscalPeriodStatus.OPEN,
                FiscalPeriodTransition.LOCK,
            )
        }
    }

    @Test
    fun `reaching OPEN from FUTURE and from CLOSED are different transitions`() {
        // The reusable graph keys one definition per transition name, so a single "open" covering
        // both would silently lose one edge. It also matters for the reopen control: the actor
        // check reads CLOSE rows, which only exist because reopening is its own named transition.
        assertEquals(
            FiscalPeriodStatus.OPEN,
            FiscalPeriodLifecycle.GRAPH
                .requireDefinition(FiscalPeriodStatus.FUTURE, FiscalPeriodTransition.OPEN)
                .to,
        )
        assertEquals(
            FiscalPeriodStatus.OPEN,
            FiscalPeriodLifecycle.GRAPH
                .requireDefinition(FiscalPeriodStatus.CLOSED, FiscalPeriodTransition.REOPEN)
                .to,
        )
    }

    @Test
    fun `no transition publishes a broker event`() {
        // Deliberate, and asserted so a later change has to be a decision rather than a habit:
        // nothing outside accounting consumes a period state change yet, and publishing merely
        // because a transition occurred is what ADR 0004 warns against.
        assertTrue(
            FiscalPeriodLifecycle.GRAPH.definitions().all { it.eventFactories.isEmpty() },
            "add an event factory only when a real downstream consumer exists",
        )
    }

    @Test
    fun `the aggregate carries the tenant and reports the accounting aggregate type`() {
        // The transition log has no organisation column of its own in TransitionLog, so the tenant
        // travels in metadata and the writer reads it from there. The aggregate type is what the
        // dispatching repository routes on.
        val key = FiscalPeriodKey(UUID.randomUUID(), UUID.randomUUID())
        val aggregate = FiscalPeriodAggregate(key, FiscalPeriodStatus.OPEN)

        assertEquals("FISCAL_PERIOD", aggregate.aggregateType)
        assertEquals(key.fiscalPeriodId.toString(), aggregate.aggregateId)

        aggregate.transitionTo(FiscalPeriodStatus.CLOSED)
        assertEquals(FiscalPeriodStatus.CLOSED, aggregate.state)
    }
}
