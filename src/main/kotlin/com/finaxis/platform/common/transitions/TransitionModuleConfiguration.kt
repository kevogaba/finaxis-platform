package com.finaxis.platform.common.transitions

import io.namastack.outbox.rabbit.RabbitOutboxRouting
import io.namastack.outbox.rabbit.rabbitOutboxRouting
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget
import java.time.Clock

/**
 * Spring wiring for reusable FSM infrastructure and Modulith externalization selection.
 */
@Configuration
class TransitionModuleConfiguration {
    /**
     * Routes each transition log to the module that owns its aggregate type.
     *
     * Conditional on a [TransitionLogWriter] rather than unconditional so a slice test that wires
     * no module gets no executor, exactly as the previous condition on the repository did — and so
     * neither condition names a bean declared in this same configuration class.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnBean(TransitionLogWriter::class)
    fun transitionLogRepository(writers: List<TransitionLogWriter>): TransitionLogRepository =
        DispatchingTransitionLogRepository(writers)

    /**
     * Reusable FSM infrastructure excluded from Modulith proxying: in 2.1.0, rendering this
     * executor's F-bounded generic signature recurses indefinitely.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnBean(TransitionLogWriter::class)
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
            }.build()

    /**
     * Routes typed externalized transition events to their declared RabbitMQ exchange. Each
     * message also carries the outbox record key as a header so consumers can deduplicate under
     * Namastack's at-least-once delivery semantics.
     */
    @Bean
    fun rabbitOutboxRouting(): RabbitOutboxRouting =
        rabbitOutboxRouting {
            route(OutboxPayloadSelector.type(ExternalizedTransitionEvent::class.java)) {
                target { payload, _ -> (payload as ExternalizedTransitionEvent).target }
                key { _, _ -> "" }
                header(OUTBOX_RECORD_KEY_HEADER) { _, metadata -> metadata.key }
            }
        }

    private companion object {
        const val OUTBOX_RECORD_KEY_HEADER = "X-Outbox-Record-Key"
    }
}
