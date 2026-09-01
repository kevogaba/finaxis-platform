package com.finaxis.platform.common.transitions

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Routing, and the two failure modes that matter more than the routing.
 *
 * A transition that mutates state and leaves no history is the audit gap the FSM exists to close,
 * so an **unowned** aggregate type has to fail the transaction rather than drop the row: a
 * dispatcher that silently ignored an unknown type would let a period close with nothing recording
 * who closed it.
 *
 * A **contested** type has to fail for the mirror-image reason. Ownership is exclusive by design,
 * so two claimants is a wiring defect, and any rule that quietly picks one of them decides where
 * history lives by bean-registration order.
 */
class DispatchingTransitionLogRepositoryTests {
    @Test
    fun `a log goes to the module that owns its aggregate type`() {
        val foundation = RecordingWriter(setOf("ORGANISATION", "BRANCH"))
        val accounting = RecordingWriter(setOf("FISCAL_PERIOD"))
        val dispatcher = DispatchingTransitionLogRepository(listOf(foundation, accounting))

        dispatcher.save(log("ORGANISATION"))
        dispatcher.save(log("FISCAL_PERIOD"))

        assertEquals(listOf("ORGANISATION"), foundation.saved.map { it.aggregateType })
        assertEquals(listOf("FISCAL_PERIOD"), accounting.saved.map { it.aggregateType })
    }

    @Test
    fun `an unowned aggregate type fails loudly rather than dropping the row`() {
        val dispatcher =
            DispatchingTransitionLogRepository(listOf(RecordingWriter(setOf("ORGANISATION"))))

        val failure =
            assertFailsWith<IllegalStateException> { dispatcher.save(log("FISCAL_PERIOD")) }

        assertTrue(
            failure.message.orEmpty().contains("FISCAL_PERIOD"),
            "the failure must name the type nobody owns, not merely report that one exists",
        )
    }

    @Test
    fun `no writers at all is still a failure, not a no-op`() {
        // Guards the rule above against being written as "the first writer that supports it, or
        // nothing" - a configuration mistake that left the list empty would then discard every
        // transition log in the platform while every transition still committed.
        val dispatcher = DispatchingTransitionLogRepository(emptyList())

        assertFailsWith<IllegalStateException> { dispatcher.save(log("ORGANISATION")) }
    }

    @Test
    fun `two writers claiming one type is a failure, not a first-past-the-post race`() {
        // An earlier revision of this test asserted the opposite - that the first claimant wins and
        // the second is not called - which reads as a tie-break rule but is really a silent
        // resolution by bean-registration order. Which module a period's history lands in would
        // then depend on construction order, and could differ between the application and a test
        // slice. Exclusive ownership is the invariant, so an overlap must not start.
        val first = RecordingWriter(setOf("ORGANISATION"))
        val second = RecordingWriter(setOf("ORGANISATION"))

        val failure =
            assertFailsWith<IllegalStateException> {
                DispatchingTransitionLogRepository(listOf(first, second)).save(log("ORGANISATION"))
            }

        assertTrue(
            failure.message.orEmpty().contains("ORGANISATION"),
            "the failure must name the contested type: $failure",
        )
        assertEquals(0, first.saved.size, "no writer may persist a contested log")
        assertEquals(0, second.saved.size, "no writer may persist a contested log")
    }

    private fun log(aggregateType: String) =
        TransitionLog(
            aggregateType = aggregateType,
            aggregateId = "11111111-1111-1111-1111-111111111111",
            transition = "close",
            fromState = "OPEN",
            toState = "CLOSED",
            actorType = "USER",
            actorId = "22222222-2222-2222-2222-222222222222",
            reason = null,
            comment = null,
            metadata = emptyMap(),
            occurredAt = Instant.EPOCH,
            createdAt = Instant.EPOCH,
            correlationId = null,
            requestId = null,
        )

    private class RecordingWriter(
        private val owned: Set<String>,
    ) : TransitionLogWriter {
        val saved = mutableListOf<TransitionLog>()

        override fun supports(aggregateType: String) = aggregateType in owned

        override fun save(log: TransitionLog) {
            saved += log
        }
    }
}
