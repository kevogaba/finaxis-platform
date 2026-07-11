package com.finaxis.platform.common.transitions

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget
import java.time.Clock

/**
 * Spring wiring for reusable FSM infrastructure and Modulith externalization selection.
 */
@Configuration
class TransitionModuleConfiguration {
    /**
     * Reusable transition executor for application services.
     */
    @Bean
    @ConditionalOnBean(TransitionLogRepository::class)
    fun transitionExecutor(
        clock: Clock,
        transitionLogRepository: TransitionLogRepository,
        transitionEventPublisher: TransitionEventPublisher,
    ): TransitionExecutor =
        TransitionExecutor(
            clock = clock,
            transitionLogRepository = transitionLogRepository,
            transitionEventPublisher = transitionEventPublisher,
        )

    /**
     * Externalizes only events deliberately annotated or typed as external integration events.
     */
    @Bean
    fun eventExternalizationConfiguration(): EventExternalizationConfiguration =
        EventExternalizationConfiguration
            .externalizing()
            .select { event ->
                EventExternalizationConfiguration.annotatedAsExternalized().test(event)
            }.route(ExternalizedTransitionEvent::class.java) { event ->
                RoutingTarget.forTarget(event.target).withoutKey()
            }.serializeExternalization(true)
            .build()
}
