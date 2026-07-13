package com.finaxis.platform.common.transitions

import java.time.Clock

/**
 * Coordinates transition validation, state mutation, audit logging, and event publication.
 */
open class TransitionExecutor(
    private val clock: Clock,
    private val transitionLogRepository: TransitionLogRepository,
    private val transitionEventPublisher: TransitionEventPublisher,
) {
    /**
     * Executes a named transition against an aggregate.
     */
    fun <S, T, A> execute(
        execution: TransitionExecution<S, T, A>,
    ): TransitionResult<S, T, A>
        where S : Enum<S>, T : Enum<T>, A : Transitionable<S> {
        val definition =
            execution.graph.requireDefinition(
                execution.aggregate.state,
                execution.transition,
            )
        val context = buildContext(execution, definition)

        validate(context, definition)
        execution.aggregate.transitionTo(definition.to)
        definition.effects.forEach { effect -> effect.apply(context) }
        val savedAggregate = execution.persist(execution.aggregate)
        val log = createLog(savedAggregate, context, definition)
        transitionLogRepository.save(log)
        val events = definition.eventFactories.map { factory -> factory.create(context) }
        events.forEach(transitionEventPublisher::publish)

        return TransitionResult(
            savedAggregate,
            execution.transition,
            context.fromState,
            definition.to,
            log,
            events,
        )
    }

    private fun <S, T, A> buildContext(
        execution: TransitionExecution<S, T, A>,
        definition: TransitionDefinition<S, T, A>,
    ): TransitionContext<S, T, A>
        where S : Enum<S>, T : Enum<T>, A : Transitionable<S> =
        TransitionContext(
            aggregate = execution.aggregate,
            transition = execution.transition,
            fromState = definition.from,
            toState = definition.to,
            actor = execution.actor,
            command = execution.command,
            occurredAt = execution.command.occurredAt ?: clock.instant(),
        )

    private fun <S, T, A> validate(
        context: TransitionContext<S, T, A>,
        definition: TransitionDefinition<S, T, A>,
    ) where S : Enum<S>, T : Enum<T>, A : Transitionable<S> {
        definition.guards.forEach { guard -> guard.check(context) }
        definition.policies.forEach { policy -> policy.validate(context) }
    }

    private fun <S, T, A> createLog(
        aggregate: A,
        context: TransitionContext<S, T, A>,
        definition: TransitionDefinition<S, T, A>,
    ): TransitionLog
        where S : Enum<S>, T : Enum<T>, A : Transitionable<S> =
        TransitionLog(
            aggregateType = aggregate.aggregateType,
            aggregateId = aggregate.aggregateId,
            transition = context.transition.name,
            fromState = context.fromState.name,
            toState = context.toState.name,
            actorType = context.actor.type,
            actorId = context.actor.id,
            reason = context.command.reason,
            comment = context.command.comment,
            metadata = definition.metadata + context.command.metadata,
            occurredAt = context.occurredAt,
            createdAt = clock.instant(),
            correlationId = context.command.correlationId,
            requestId = context.command.requestId,
        )
}
