package com.finaxis.platform.common.adapter.inbound.messaging

import org.springframework.amqp.rabbit.annotation.RabbitListenerAnnotationBeanPostProcessor.DEFAULT_RABBIT_LISTENER_CONTAINER_FACTORY_BEAN_NAME
import org.springframework.amqp.rabbitmq.client.AmqpConnectionFactory
import org.springframework.amqp.rabbitmq.client.RabbitAmqpAdmin
import org.springframework.amqp.rabbitmq.client.config.RabbitAmqpListenerContainerFactory
import org.springframework.amqp.rabbitmq.client.listener.RabbitAmqpListenerContainer
import org.springframework.boot.amqp.rabbitmq.autoconfigure.RabbitAmqpListenerContainerFactoryConfigurer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Every `@RabbitListener` consumes over RabbitMQ's native AMQP 1.0, and the broker topology (the
 * `Exchange`, `Queue`, `Binding` and `Declarables` beans) is declared over the same connection,
 * the one the outbox publisher (`AmqpOutboxHandler`) uses.
 *
 * The listener factory and the admin are declared here rather than left to auto-configuration so
 * they can refuse to start while [AmqpProperties.enabled] is off (see below).
 *
 * Neither the admin nor the listeners start without a reachable broker: RabbitMQ's AMQP 1.0
 * client makes one attempt at its first connection, so an unreachable broker fails the
 * application at startup (an owner decision: fail fast, and let the orchestrator restart it).
 * After that first connection the client's own recovery reconnects, re-declares the topology and
 * resumes the consumers. [AmqpProperties.enabled] keeps both from ever connecting, for a context
 * that runs no broker.
 */
@Configuration(proxyBeanMethods = false)
class AmqpListenerConfiguration {
    /** The factory behind every `@RabbitListener`: AMQP 1.0 containers, Spring Boot's settings. */
    @Bean(DEFAULT_RABBIT_LISTENER_CONTAINER_FACTORY_BEAN_NAME)
    fun rabbitListenerContainerFactory(
        configurer: RabbitAmqpListenerContainerFactoryConfigurer,
        connectionFactory: AmqpConnectionFactory,
        properties: AmqpProperties,
    ): RabbitAmqpListenerContainerFactory =
        object : RabbitAmqpListenerContainerFactory(connectionFactory) {
            override fun createContainerInstance(): RabbitAmqpListenerContainer =
                GuardedListenerContainer(connectionFactory, properties.enabled)
        }.apply {
            configurer.configure(this)
            setContainerCustomizer { container -> container.setAutoStartup(properties.enabled) }
        }

    /** Declares the topology beans over AMQP 1.0 when the context starts. */
    @Bean
    fun rabbitAmqpAdmin(
        connectionFactory: AmqpConnectionFactory,
        properties: AmqpProperties,
    ): RabbitAmqpAdmin = GuardedAdmin(connectionFactory, properties.enabled)

    /**
     * A container that never starts while AMQP is off. Not starting with the context is not
     * enough: Spring's test context cache pauses an idle context and restarts it for the next
     * test, and Spring AMQP's endpoint registry, whose containers never ran, then starts every
     * container whatever its auto-startup.
     */
    private class GuardedListenerContainer(
        connectionFactory: AmqpConnectionFactory,
        private val enabled: Boolean,
    ) : RabbitAmqpListenerContainer(connectionFactory) {
        override fun start() {
            if (enabled) {
                super.start()
            }
        }
    }

    /** An admin that never declares while AMQP is off, for the same reason. */
    private class GuardedAdmin(
        connectionFactory: AmqpConnectionFactory,
        private val enabled: Boolean,
    ) : RabbitAmqpAdmin(connectionFactory) {
        init {
            isAutoStartup = enabled
        }

        override fun start() {
            if (enabled) {
                super.start()
            }
        }
    }
}

/**
 * Whether the AMQP 1.0 admin, listener containers and outbox publisher connect at all.
 *
 * @property enabled `true` in every deployment, so a broker that cannot be reached fails startup;
 *   `false` only for a context that runs no broker (most test contexts), where the admin and the
 *   containers never start and the outbox publisher fails each record without connecting.
 */
@ConfigurationProperties(prefix = "finaxis.amqp")
data class AmqpProperties(
    val enabled: Boolean = true,
)
