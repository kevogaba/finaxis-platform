package com.finaxis.platform.common.adapter.inbound.messaging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.notifications.application.NotificationService
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailScheduler
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.listener.ConditionalRejectingErrorHandler
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry
import org.springframework.amqp.rabbitmq.client.RabbitAmqpAdmin
import org.springframework.amqp.rabbitmq.client.RabbitAmqpTemplate
import org.springframework.amqp.rabbitmq.client.listener.RabbitAmqpListenerContainer
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Role
import org.springframework.test.context.TestConstructor
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the broker does with a message a `@RabbitListener` fails on, now that the listeners consume
 * over AMQP 1.0: the settlement the AMQP 0.9.1 containers gave, kept. A listener's
 * `AmqpRejectAndDontRequeueException` (an unprocessable message) settles it as rejected, so it is
 * dropped and never delivered again; any other failure releases it to the queue, so it is
 * delivered again. Proved on the membership-activation notification queue, counting the
 * container's failure reports and the service's calls, and reading the queue over AMQP 1.0.
 */
@Import(TestcontainersConfiguration::class, AmqpListenerSettlementIntegrationTests.Flaky::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AmqpListenerSettlementIntegrationTests(
    private val template: RabbitAmqpTemplate,
    private val admin: RabbitAmqpAdmin,
    private val jsonMapper: JsonMapper,
    private val registry: RabbitListenerEndpointRegistry,
    private val notifications: FlakyNotificationService,
) {
    private val failures = ListAppender<ILoggingEvent>()
    private val errorHandlerLogger =
        LoggerFactory.getLogger(ConditionalRejectingErrorHandler::class.java) as Logger

    @BeforeEach
    fun captureListenerFailures() {
        failures.start()
        errorHandlerLogger.addAppender(failures)
        notifications.reset()
    }

    @AfterEach
    fun releaseListenerFailures() {
        errorHandlerLogger.detachAppender(failures)
        failures.stop()
    }

    @Test
    fun `every listener runs in an AMQP 1 0 container`() {
        val containers = registry.listenerContainers

        assertTrue(containers.isNotEmpty())
        containers.forEach { container ->
            assertTrue(container is RabbitAmqpListenerContainer, container.javaClass.name)
            assertTrue(container.isRunning, container.listenerId)
        }
    }

    @Test
    fun `a message the listener rejects is dropped and never delivered again`() {
        publish("not an event".toByteArray())

        await().atMost(SETTLE).until { listenerFailures() == 1 }
        // Released instead, the message would come straight back and fail again and again.
        await().during(QUIET).atMost(QUIET.plus(SETTLE)).until { listenerFailures() == 1 }
        assertEquals(0, notifications.calls.get())
        assertEquals(0L, queuedMessages())
    }

    @Test
    fun `a message the listener fails on is released and delivered again`() {
        notifications.failNext.set(true)

        publish(jsonMapper.writeValueAsBytes(activation()))

        await().atMost(SETTLE).until { notifications.calls.get() == 2 }
        await().during(QUIET).atMost(QUIET.plus(SETTLE)).until { notifications.calls.get() == 2 }
        assertEquals(1, listenerFailures())
        assertEquals(0L, queuedMessages())
    }

    /**
     * Sends [body] straight to the queue and waits for the broker to accept it. An event is
     * written by Spring Boot's Jackson 3 mapper, as the outbox writes it.
     */
    private fun publish(body: ByteArray) {
        template
            .send(
                QUEUE,
                Message(
                    body,
                    MessageProperties().apply { contentType = MessageProperties.CONTENT_TYPE_JSON },
                ),
            ).get(SETTLE.toSeconds(), TimeUnit.SECONDS)
    }

    private fun listenerFailures() = failures.list.count { it.level == Level.WARN }

    private fun queuedMessages(): Long = requireNotNull(admin.getQueueInfo(QUEUE)).messageCount

    private fun activation() =
        ExternalizedTransitionEvent(
            target = "finaxis.lifecycle.membership.activated",
            aggregateType = "MEMBERSHIP",
            aggregateId = uuidV7().toString(),
            transition = "ACTIVATE",
            fromState = "PENDING_ACTIVATION",
            toState = "ACTIVE",
            actor = TransitionActor("USER", uuidV7().toString()),
            occurredAt = Instant.now(),
            metadata =
                mapOf(
                    "membershipId" to uuidV7().toString(),
                    "userId" to uuidV7().toString(),
                    "organisationId" to uuidV7().toString(),
                ),
        )

    /** Replaces the notification service for this context only. */
    @TestConfiguration(proxyBeanMethods = false)
    class Flaky {
        /**
         * Infrastructure, so Spring Modulith's module observability leaves it unproxied and the
         * test reads the counters the listener's calls changed.
         */
        @Bean
        @Primary
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun flakyNotificationService(scheduler: WelcomeEmailScheduler) =
            FlakyNotificationService(scheduler)
    }

    /** Counts its calls and fails the next one when asked, instead of scheduling an email. */
    class FlakyNotificationService(
        scheduler: WelcomeEmailScheduler,
    ) : NotificationService(scheduler) {
        val calls = AtomicInteger()
        val failNext = AtomicBoolean()

        fun reset() {
            calls.set(0)
            failNext.set(false)
        }

        override fun handleMembershipActivated(event: ExternalizedTransitionEvent) {
            calls.incrementAndGet()
            check(!failNext.getAndSet(false)) { "transient failure" }
        }
    }

    private companion object {
        const val QUEUE = "finaxis.notifications.membership-activated"
        val SETTLE: Duration = Duration.ofSeconds(10)

        // How long a settled message must stay settled: a released one is redelivered at once.
        val QUIET: Duration = Duration.ofSeconds(2)
    }
}
