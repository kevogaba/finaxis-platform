package com.finaxis.platform.common.transitions

/**
 * Complete request for one transition execution.
 */
data class TransitionExecution<S, T, A>(
    val aggregate: A,
    val transition: T,
    val graph: TransitionGraph<S, T, A>,
    val command: TransitionCommand,
    val actor: TransitionActor,
    val persist: (A) -> A,
) where S : Enum<S>, T : Enum<T>, A : Transitionable<S>
