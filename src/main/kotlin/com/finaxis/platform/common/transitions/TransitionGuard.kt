package com.finaxis.platform.common.transitions

/**
 * Domain-specific predicate that can reject a transition before state mutation.
 */
fun interface TransitionGuard<S, T, A>
    where S : Enum<S>, T : Enum<T>, A : Transitionable<S> {
    /**
     * Throws [TransitionGuardException] or another domain exception when the transition is blocked.
     */
    fun check(context: TransitionContext<S, T, A>)
}
