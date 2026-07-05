package com.finaxis.platform.common.transitions

/**
 * Coarser validation hook for reusable business policies spanning multiple transitions.
 */
fun interface TransitionPolicy<S, T, A>
    where S : Enum<S>, T : Enum<T>, A : Transitionable<S> {
    /**
     * Throws when the transition violates a reusable policy.
     */
    fun validate(context: TransitionContext<S, T, A>)
}
