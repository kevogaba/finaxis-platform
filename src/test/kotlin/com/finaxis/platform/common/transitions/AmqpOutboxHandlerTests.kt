package com.finaxis.platform.common.transitions

import com.finaxis.platform.common.adapter.inbound.messaging.AmqpProperties
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.AmqpNackReceivedException
import org.springframework.amqp.rabbitmq.client.RabbitAmqpTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [AmqpOutboxHandler] without a broker: the message it builds and how it reads the outcome. */
class AmqpOutboxHandlerTests {
    private val template = mock<RabbitAmqpTemplate>()
    private val jsonMapper = JsonMapper.builder().findAndAddModules().build()
    private val metadata =
        OutboxRecordMetadata(
            key = "record-key",
            handlerId = "handler",
            createdAt = Instant.EPOCH,
            context = emptyMap(),
        )
    private val event =
        ExternalizedTransitionEvent(
            target = "finaxis.lifecycle.membership.activated",
            aggregateType = "MEMBERSHIP",
            aggregateId = "aggregate",
            transition = "ACTIVATE",
            fromState = "PENDING_ACTIVATION",
            toState = "ACTIVE",
            actor = TransitionActor("USER", "actor"),
            occurredAt = Instant.parse("2026-11-24T12:00:00Z"),
            metadata = mapOf("membershipId" to "membership"),
        )

    @Test
    fun `publishes the event to its exchange as JSON with the record key, once accepted`() {
        whenever(template.send(any<String>(), isNull(), any<Message>()))
            .thenReturn(CompletableFuture.completedFuture(true))

        handler().handle(event, metadata)

        val sent = argumentCaptor<Message>()
        verify(template).send(eq(event.target), isNull(), sent.capture())
        val properties = sent.firstValue.messageProperties
        assertEquals(MessageProperties.CONTENT_TYPE_JSON, properties.contentType)
        assertEquals("record-key", properties.getHeader(AmqpOutboxHandler.OUTBOX_RECORD_KEY_HEADER))
        val body = jsonMapper.readTree(sent.firstValue.body)
        assertEquals(event.target, body.get("target").asString())
        assertEquals("2026-11-24T12:00:00Z", body.get("occurredAt").asString())
        assertEquals("membership", body.get("metadata").get("membershipId").asString())
    }

    @Test
    fun `a message the broker does not accept fails the record`() {
        // What the template completes with when RabbitMQ rejects or releases the message.
        val refusal = AmqpNackReceivedException("The message was rejected", Message(byteArrayOf()))
        whenever(template.send(any<String>(), isNull(), any<Message>()))
            .thenReturn(CompletableFuture.failedFuture(refusal))

        val failure =
            assertFailsWith<AmqpOutboxPublishException> { handler().handle(event, metadata) }

        assertSame(refusal, failure.cause)
    }

    @Test
    fun `no outcome within the publish timeout fails the record`() {
        whenever(template.send(any<String>(), isNull(), any<Message>()))
            .thenReturn(CompletableFuture())

        assertFailsWith<AmqpOutboxPublishException> {
            handler(publishTimeout = Duration.ofMillis(50)).handle(event, metadata)
        }
    }

    @Test
    fun `switched off, it fails the record without connecting`() {
        assertFailsWith<AmqpOutboxPublishException> {
            handler(enabled = false).handle(event, metadata)
        }

        verifyNoInteractions(template)
    }

    @Test
    fun `it handles externalized transition events and the 0 9 1 handler's records`() {
        val handler = handler()

        assertTrue(handler.supports(event, metadata))
        assertFalse(handler.supports("an event of another kind", metadata))
        assertEquals(
            setOf(AmqpOutboxHandler.LEGACY_RABBIT_HANDLER_ID),
            handler.getGenericHandlerIdentity().aliases,
        )
    }

    private fun handler(
        enabled: Boolean = true,
        publishTimeout: Duration = Duration.ofSeconds(1),
    ) = AmqpOutboxHandler(
        template,
        jsonMapper,
        AmqpOutboxProperties(publishTimeout),
        AmqpProperties(enabled),
    )
}
