package com.finaxis.platform.common.transitions

/**
 * Creates a transition event from an accepted transition context.
 */
fun interface TransitionEventFactory<S, T, A>
    where S : Enum<S>, T : Enum<T>, A : Transitionable<S> {
    /**
     * Creates the event for publication.
     */
    fun create(context: TransitionContext<S, T, A>): TransitionEvent
}

/**
 * Deterministic definition of one named transition and its target state.
 */
data class TransitionDefinition<S, T, A>(
    val transition: T,
    val from: S,
    val to: S,
    val guards: List<TransitionGuard<S, T, A>> = emptyList(),
    val policies: List<TransitionPolicy<S, T, A>> = emptyList(),
    val effects: List<TransitionEffect<S, T, A>> = emptyList(),
    val eventFactories: List<TransitionEventFactory<S, T, A>> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
) where S : Enum<S>, T : Enum<T>, A : Transitionable<S>
