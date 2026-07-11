package com.finaxis.platform.common.transitions

/**
 * Result returned after a transition has been accepted, persisted, logged, and published.
 */
data class TransitionResult<S, T, A>(
    val aggregate: A,
    val transition: T,
    val fromState: S,
    val toState: S,
    val log: TransitionLog,
    val events: List<TransitionEvent>,
) where S : Enum<S>, T : Enum<T>, A : Transitionable<S>
