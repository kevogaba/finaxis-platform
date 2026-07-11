package com.finaxis.platform.common.transitions

/**
 * Synchronous effect that must be atomic with state mutation, such as creating a dependent record.
 */
fun interface TransitionEffect<S, T, A>
    where S : Enum<S>, T : Enum<T>, A : Transitionable<S> {
    /**
     * Runs inside the transition executor before event publication.
     */
    fun apply(context: TransitionContext<S, T, A>)
}
