package com.finaxis.platform.common.transitions

/**
 * Outbound port for publishing transition events without coupling the executor to Spring.
 */
fun interface TransitionEventPublisher {
    /**
     * Publishes a transition event to the application event bus.
     */
    fun publish(event: TransitionEvent)
}
