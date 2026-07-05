package com.finaxis.platform.common.transitions

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * Spring adapter that publishes FSM events through the application event bus.
 */
@Component
class SpringTransitionEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) : TransitionEventPublisher {
    override fun publish(event: TransitionEvent) {
        applicationEventPublisher.publishEvent(event)
    }
}
