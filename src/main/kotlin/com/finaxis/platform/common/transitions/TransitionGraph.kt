package com.finaxis.platform.common.transitions

/**
 * Introspectable finite state machine graph built from deterministic transition definitions.
 */
class TransitionGraph<S, T, A>(
    definitions: Iterable<TransitionDefinition<S, T, A>>,
) where S : Enum<S>, T : Enum<T>, A : Transitionable<S> {
    private val definitionsByTransition: Map<T, TransitionDefinition<S, T, A>> =
        definitions.associateBy { definition -> definition.transition }

    private val definitionsByState: Map<S, List<TransitionDefinition<S, T, A>>> =
        definitions.groupBy { definition -> definition.from }

    /**
     * Returns every transition definition in deterministic transition-name order.
     */
    fun definitions(): List<TransitionDefinition<S, T, A>> =
        definitionsByTransition.values.sortedBy { definition -> definition.transition.name }

    /**
     * Returns legal transition definitions from a state.
     */
    fun allowedTransitionsFrom(state: S): List<TransitionDefinition<S, T, A>> =
        definitionsByState[state].orEmpty().sortedBy { definition -> definition.transition.name }

    /**
     * Returns whether a named transition is legal from the given state.
     */
    fun isAllowed(
        state: S,
        transition: T,
    ): Boolean = definitionsByTransition[transition]?.from == state

    /**
     * Resolves a transition definition or raises a deterministic exception.
     */
    fun requireDefinition(
        state: S,
        transition: T,
    ): TransitionDefinition<S, T, A> {
        val definition =
            definitionsByTransition[transition]
                ?: throw InvalidTransitionException("Transition $transition is not defined.")

        if (definition.from != state) {
            throw TransitionNotAllowedException(
                "Transition $transition is not allowed from $state; expected ${definition.from}.",
            )
        }
        return definition
    }

    /**
     * Exports the graph as Mermaid flowchart text for docs and tests.
     */
    fun toMermaid(): String {
        val edges =
            definitions()
                .joinToString(separator = "\n") { definition ->
                    "    ${definition.from} -->|${definition.transition}| ${definition.to}"
                }
        return "flowchart LR\n$edges"
    }
}
