package com.finaxis.platform.common.adapter.inbound.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.annotation.RabbitListenerAnnotationBeanPostProcessor.DEFAULT_RABBIT_LISTENER_CONTAINER_FACTORY_BEAN_NAME
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry
import org.springframework.amqp.rabbitmq.client.RabbitAmqpAdmin
import org.springframework.amqp.rabbitmq.client.config.RabbitAmqpListenerContainerFactory
import org.springframework.boot.amqp.rabbitmq.autoconfigure.AmqpRabbitAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.rabbitmq.autoconfigure.RabbitAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.ServerSocket
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AmqpListenerConfiguration] against a port nothing listens on: an unreachable broker fails
 * startup unless [AmqpProperties.enabled] is off, which keeps the admin and the listener
 * containers from ever connecting, and the `@RabbitListener` factory is the AMQP 1.0 one although
 * Spring Boot's AMQP 0.9.1 support registers one under the same name.
 */
class AmqpListenerConfigurationTests {
    private val closedPort = ServerSocket(0).use { it.localPort }

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AmqpRabbitAutoConfiguration::class.java,
                    RabbitAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(AmqpListenerConfiguration::class.java, Properties::class.java)
            .withPropertyValues(
                "spring.amqp.rabbitmq.host=localhost",
                "spring.amqp.rabbitmq.port=$closedPort",
            )

    @Test
    fun `an unreachable broker fails startup`() {
        runner.withUserConfiguration(Topology::class.java).run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `switched off, nothing connects, however often the context is started`() {
        val offline =
            runner
                .withUserConfiguration(Topology::class.java, Listener::class.java)
                .withPropertyValues("finaxis.amqp.enabled=false")
        offline.run { context ->
            assertThat(context).hasNotFailed()
            // What Spring's test context cache does to an idle context: before the guard, Spring
            // AMQP's endpoint registry started every container here and the connection failed.
            context.getSourceApplicationContext(ConfigurableApplicationContext::class.java).apply {
                stop()
                start()
            }
            val registry = context.getBean(RabbitListenerEndpointRegistry::class.java)
            assertTrue(registry.listenerContainers.isNotEmpty())
            registry.listenerContainers.forEach { assertFalse(it.isRunning) }
            val admin = context.getBean(RabbitAmqpAdmin::class.java)
            assertFalse(admin.isAutoStartup)
            admin.start()
            assertFalse(admin.isRunning)
        }
    }

    @Test
    fun `the listener factory is the AMQP 1 0 one and its containers start with the context`() {
        // No topology and no listener, so nothing connects: only the settings are under test.
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val factory = context.getBean(DEFAULT_RABBIT_LISTENER_CONTAINER_FACTORY_BEAN_NAME)
            assertTrue(factory is RabbitAmqpListenerContainerFactory, factory.javaClass.name)
            assertTrue(factory.createListenerContainer(null).isAutoStartup)
            assertTrue(context.getBean(RabbitAmqpAdmin::class.java).isAutoStartup)
        }
    }

    /** What `@ConfigurationPropertiesScan` registers in the application. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AmqpProperties::class)
    class Properties

    /** One topology bean, so the admin has something to declare and connects to declare it. */
    @Configuration(proxyBeanMethods = false)
    class Topology {
        @Bean
        fun queue(): Queue = Queue(QUEUE, true)
    }

    /** One listener, so the endpoint registry holds a container. */
    @Configuration(proxyBeanMethods = false)
    class Listener {
        @Bean
        fun listener() = QueueListener()
    }

    /** Consumes nothing: it is never started. */
    class QueueListener {
        @RabbitListener(queues = [QUEUE])
        fun onMessage(body: ByteArray) {
            check(body.isNotEmpty())
        }
    }

    private companion object {
        const val QUEUE = "finaxis.test.amqp-startup"
    }
}
