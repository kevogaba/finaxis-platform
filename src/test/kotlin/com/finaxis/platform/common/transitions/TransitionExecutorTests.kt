package com.finaxis.platform.common.transitions

import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransitionExecutorTests {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-06T08:00:00Z"), ZoneOffset.UTC)
    private val logs = CapturingTransitionLogRepository()
    private val publisher = CapturingTransitionEventPublisher()
    private val executor = TransitionExecutor(clock, logs, publisher)

    @Test
    fun `valid transition mutates persists logs and publishes event`() {
        val aggregate = SampleAggregate(SampleState.DRAFT)
        val result =
            executor.execute(
                TransitionExecution(
                    aggregate = aggregate,
                    transition = SampleTransition.CONFIRM,
                    graph = sampleGraph(),
                    command = command(),
                    actor = actor(),
                    persist = { saved -> saved.also { it.persisted = true } },
                ),
            )

        assertSame(aggregate, result.aggregate)
        assertEquals(SampleState.CONFIRMED, aggregate.state)
        assertTrue(aggregate.persisted)
        assertEquals("CONFIRM", logs.saved.single().transition)
        assertEquals("DRAFT", logs.saved.single().fromState)
        assertEquals("CONFIRMED", logs.saved.single().toState)
        assertEquals("operator", logs.saved.single().actorType)
        assertEquals("request-1", logs.saved.single().requestId)
        assertEquals(1, publisher.published.size)
    }

    @Test
    fun `invalid transition fails before mutation logging or publication`() {
        val aggregate = SampleAggregate(SampleState.CONFIRMED)

        assertThrows<TransitionNotAllowedException> {
            executor.execute(
                TransitionExecution(
                    aggregate = aggregate,
                    transition = SampleTransition.CONFIRM,
                    graph = sampleGraph(),
                    command = command(),
                    actor = actor(),
                    persist = { it },
                ),
            )
        }

        assertEquals(SampleState.CONFIRMED, aggregate.state)
        assertTrue(logs.saved.isEmpty())
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `guard failure prevents transition`() {
        val aggregate = SampleAggregate(SampleState.CONFIRMED)

        assertThrows<TransitionGuardException> {
            executor.execute(
                TransitionExecution(
                    aggregate = aggregate,
                    transition = SampleTransition.APPROVE,
                    graph = guardedGraph(allowed = false),
                    command = command(),
                    actor = actor(),
                    persist = { it },
                ),
            )
        }

        assertEquals(SampleState.CONFIRMED, aggregate.state)
        assertTrue(logs.saved.isEmpty())
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `transition can publish no events`() {
        executor.execute(
            TransitionExecution(
                aggregate = SampleAggregate(SampleState.APPROVED),
                transition = SampleTransition.COMPLETE,
                graph = sampleGraph(),
                command = command(),
                actor = actor(),
                persist = { it },
            ),
        )

        assertTrue(publisher.published.isEmpty())
        assertEquals(1, logs.saved.size)
    }

    @Test
    fun `transition can publish multiple events`() {
        executor.execute(
            TransitionExecution(
                aggregate = SampleAggregate(SampleState.CONFIRMED),
                transition = SampleTransition.APPROVE,
                graph = guardedGraph(allowed = true),
                command = command(),
                actor = actor(),
                persist = { it },
            ),
        )

        assertEquals(2, publisher.published.size)
        assertTrue(publisher.published.any { event -> event is InternalTransitionEvent })
        assertTrue(publisher.published.any { event -> event is ExternalizedTransitionEvent })
    }

    @Test
    fun `graph exposes legal transitions and mermaid output`() {
        val graph = sampleGraph()

        assertTrue(graph.isAllowed(SampleState.DRAFT, SampleTransition.CONFIRM))
        assertFalse(graph.isAllowed(SampleState.CONFIRMED, SampleTransition.CONFIRM))
        assertEquals(
            listOf(
                SampleTransition.APPROVE,
            ),
            graph.allowedTransitionsFrom(SampleState.CONFIRMED).map {
                it.transition
            },
        )
        assertTrue(graph.toMermaid().contains("DRAFT -->|CONFIRM| CONFIRMED"))
    }

    private fun command(): TransitionCommand =
        TransitionCommand(
            reason = "valid business reason",
            comment = "reviewed",
            metadata = mapOf("source" to "test"),
            requestId = "request-1",
            correlationId = "correlation-1",
        )

    private fun actor(): TransitionActor = TransitionActor(type = "operator", id = "user-1")

    private fun sampleGraph(): TransitionGraph<SampleState, SampleTransition, SampleAggregate> =
        TransitionGraph(
            listOf(
                TransitionDefinition(
                    transition = SampleTransition.CONFIRM,
                    from = SampleState.DRAFT,
                    to = SampleState.CONFIRMED,
                    eventFactories = listOf(TransitionEventFactory(::internalEvent)),
                ),
                TransitionDefinition(
                    transition = SampleTransition.APPROVE,
                    from = SampleState.CONFIRMED,
                    to = SampleState.APPROVED,
                ),
                TransitionDefinition(
                    transition = SampleTransition.COMPLETE,
                    from = SampleState.APPROVED,
                    to = SampleState.COMPLETED,
                ),
            ),
        )

    private fun guardedGraph(
        allowed: Boolean,
    ): TransitionGraph<SampleState, SampleTransition, SampleAggregate> =
        TransitionGraph(
            listOf(
                TransitionDefinition(
                    transition = SampleTransition.APPROVE,
                    from = SampleState.CONFIRMED,
                    to = SampleState.APPROVED,
                    guards =
                        listOf(
                            TransitionGuard { _ ->
                                if (!allowed) {
                                    throw TransitionGuardException("approval is blocked")
                                }
                            },
                        ),
                    eventFactories =
                        listOf(
                            TransitionEventFactory(::internalEvent),
                            TransitionEventFactory(::externalizedEvent),
                        ),
                ),
            ),
        )

    private fun internalEvent(
        context: TransitionContext<SampleState, SampleTransition, SampleAggregate>,
    ): InternalTransitionEvent =
        InternalTransitionEvent(
            aggregateType = context.aggregate.aggregateType,
            aggregateId = context.aggregate.aggregateId,
            transition = context.transition.name,
            fromState = context.fromState.name,
            toState = context.toState.name,
            actor = context.actor,
            occurredAt = context.occurredAt,
            metadata = context.command.metadata,
        )

    private fun externalizedEvent(
        context: TransitionContext<SampleState, SampleTransition, SampleAggregate>,
    ): ExternalizedTransitionEvent =
        ExternalizedTransitionEvent(
            target = "tradestack.sample.changed",
            aggregateType = context.aggregate.aggregateType,
            aggregateId = context.aggregate.aggregateId,
            transition = context.transition.name,
            fromState = context.fromState.name,
            toState = context.toState.name,
            actor = context.actor,
            occurredAt = context.occurredAt,
            metadata = context.command.metadata,
        )
}

private enum class SampleState {
    DRAFT,
    CONFIRMED,
    APPROVED,
    COMPLETED,
}

private enum class SampleTransition {
    CONFIRM,
    APPROVE,
    COMPLETE,
}

private class SampleAggregate(
    initialState: SampleState,
) : Transitionable<SampleState> {
    override val aggregateId: String = "sample-1"
    override val aggregateType: String = "SAMPLE"
    override var state: SampleState = initialState
        private set
    var persisted: Boolean = false

    override fun transitionTo(state: SampleState) {
        this.state = state
    }
}

private class CapturingTransitionLogRepository : TransitionLogRepository {
    val saved = mutableListOf<TransitionLog>()

    override fun save(log: TransitionLog) {
        saved.add(log)
    }
}

private class CapturingTransitionEventPublisher : TransitionEventPublisher {
    val published = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        published.add(event)
    }
}
