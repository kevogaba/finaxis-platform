package com.finaxis.platform.common.transitions

import com.finaxis.platform.common.adapter.inbound.messaging.AmqpProperties
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxHandlerIdentity
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbitmq.client.RabbitAmqpTemplate
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Publishes each externalized transition event the Namastack outbox hands it to RabbitMQ over
 * native AMQP 1.0, and returns only once the broker has accepted it (ADR 0046).
 *
 * The event goes to the exchange named by its `target`, with no routing key (the exchanges are
 * fanout), as JSON written by Spring Boot's Jackson 3 mapper, the payload Namastack's AMQP 0.9.1
 * module sent. The outbox record key travels as the `X-Outbox-Record-Key` application property,
 * so a consumer can deduplicate redeliveries.
 *
 * Every failure throws, so the outbox keeps the record and retries it on its backoff: a broker
 * that rejects the message, one that releases it (RabbitMQ releases a message no queue is bound
 * to receive), no outcome within [AmqpOutboxProperties.publishTimeout], a connection failure, or
 * AMQP switched off. A broker outcome that arrives after the timeout leaves a duplicate on retry,
 * which consumers already absorb (at-least-once delivery).
 *
 * Records Namastack's own AMQP 0.9.1 handler scheduled before this release carry its handler id;
 * the identity alias routes them here.
 */
class AmqpOutboxHandler(
    private val template: RabbitAmqpTemplate,
    private val jsonMapper: JsonMapper,
    private val properties: AmqpOutboxProperties,
    private val amqp: AmqpProperties,
) : OutboxHandler {
    /** Keeps records scheduled for Namastack's AMQP 0.9.1 handler routed to this one. */
    override fun getGenericHandlerIdentity(): OutboxHandlerIdentity =
        OutboxHandlerIdentity(aliases = setOf(LEGACY_RABBIT_HANDLER_ID))

    /** Externalized transition events only, the one payload Modulith externalizes. */
    override fun supports(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = payload is ExternalizedTransitionEvent

    /** Publishes the event and waits for the broker to accept it; throws on anything else. */
    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        val event =
            payload as? ExternalizedTransitionEvent
                ?: fail("Not an externalized transition event: ${payload.javaClass.name}")
        if (!amqp.enabled) {
            fail("AMQP is switched off (finaxis.amqp.enabled=false)")
        }
        awaitAcceptance(template.send(event.target, null, message(event, metadata)), event.target)
    }

    private fun awaitAcceptance(
        outcome: CompletableFuture<Boolean>,
        exchange: String,
    ) {
        try {
            outcome.get(properties.publishTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            fail(
                "No outcome from RabbitMQ for exchange $exchange within " +
                    properties.publishTimeout,
                timeout,
            )
        } catch (failure: ExecutionException) {
            fail(
                "RabbitMQ did not accept the message for exchange $exchange",
                failure.cause ?: failure,
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            fail("Interrupted waiting for RabbitMQ's outcome for exchange $exchange", interrupted)
        }
    }

    private fun fail(
        reason: String,
        cause: Throwable? = null,
    ): Nothing = throw AmqpOutboxPublishException(reason, cause)

    private fun message(
        event: ExternalizedTransitionEvent,
        metadata: OutboxRecordMetadata,
    ): Message =
        Message(
            jsonMapper.writeValueAsBytes(event),
            MessageProperties().apply {
                contentType = MessageProperties.CONTENT_TYPE_JSON
                contentEncoding = Charsets.UTF_8.name()
                setHeader(OUTBOX_RECORD_KEY_HEADER, metadata.key)
            },
        )

    /** The handler id and header the AMQP 0.9.1 publishing used. */
    companion object {
        /** The id Namastack generated for its own AMQP 0.9.1 handler, stored on its records. */
        const val LEGACY_RABBIT_HANDLER_ID =
            "io.namastack.outbox.rabbit.RabbitOutboxHandler#handle(" +
                "java.lang.Object,io.namastack.outbox.handler.OutboxRecordMetadata)"

        /** The outbox record key, for a consumer that deduplicates. */
        const val OUTBOX_RECORD_KEY_HEADER = "X-Outbox-Record-Key"
    }
}

/** RabbitMQ did not accept an outbox message; the outbox retries the record. */
class AmqpOutboxPublishException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The outbox publisher's settings.
 *
 * @property publishTimeout how long to wait for RabbitMQ's outcome before the outbox retries
 */
@ConfigurationProperties(prefix = "finaxis.outbox.amqp")
data class AmqpOutboxProperties(
    @DefaultValue("10s")
    val publishTimeout: Duration,
)

/** Registers [AmqpOutboxHandler] as the outbox's one handler. */
@Configuration(proxyBeanMethods = false)
class AmqpOutboxConfiguration {
    /**
     * The outbox handler that publishes over AMQP 1.0. Infrastructure, so Spring Modulith's module
     * observability does not wrap it in a class proxy (Namastack invokes it, not a module).
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun amqpOutboxHandler(
        template: RabbitAmqpTemplate,
        jsonMapper: JsonMapper,
        properties: AmqpOutboxProperties,
        amqp: AmqpProperties,
    ): AmqpOutboxHandler = AmqpOutboxHandler(template, jsonMapper, properties, amqp)
}
