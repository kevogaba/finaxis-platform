package com.finaxis.platform.common.transitions

import java.time.Instant

/**
 * Immutable execution context shared with guards, policies, effects, loggers, and event factories.
 */
data class TransitionContext<S, T, A>(
    val aggregate: A,
    val transition: T,
    val fromState: S,
    val toState: S,
    val actor: TransitionActor,
    val command: TransitionCommand,
    val occurredAt: Instant,
) where S : Enum<S>, T : Enum<T>, A : Transitionable<S>
