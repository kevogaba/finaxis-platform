package com.finaxis.platform.common.transitions

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.FanoutExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbitmq.client.RabbitAmqpAdmin
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * [AmqpOutboxHandler] against the broker: RabbitMQ accepts a message only for an exchange that
 * routes it somewhere, and anything it does not accept fails the record, as AMQP 0.9.1 publishing
 * did with `fail-on-unroutable`. Records stored under Namastack's own AMQP 0.9.1 handler id reach
 * this handler.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AmqpOutboxHandlerIntegrationTests(
    private val handler: AmqpOutboxHandler,
    private val admin: RabbitAmqpAdmin,
    private val registry: OutboxHandlerRegistry,
) {
    @Test
    fun `an event for an exchange no queue is bound to fails the record`() {
        val exchange = "finaxis.test.unbound-${uuidV7()}"
        // Durable, as RabbitMQ 4 deprecates transient entities; the context's virtual host goes
        // with the context.
        admin.declareExchange(FanoutExchange(exchange, true, false))

        assertFailsWith<AmqpOutboxPublishException> { handler.handle(event(exchange), metadata()) }
    }

    @Test
    fun `an event for an exchange that does not exist fails the record`() {
        assertFailsWith<AmqpOutboxPublishException> {
            handler.handle(event("finaxis.test.missing-${uuidV7()}"), metadata())
        }
    }

    @Test
    fun `an event for an exchange bound to a queue is accepted and delivered there`() {
        val name = "finaxis.test.bound-${uuidV7()}"
        val exchange = FanoutExchange(name, true, false)
        val queue = Queue(name, true)
        admin.declareExchange(exchange)
        admin.declareQueue(queue)
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange))

        handler.handle(event(name), metadata())

        assertEquals(1L, requireNotNull(admin.getQueueInfo(name)).messageCount)
    }

    @Test
    fun `records of Namastack's AMQP 0 9 1 handler are routed to this handler`() {
        val legacyId = AmqpOutboxHandler.LEGACY_RABBIT_HANDLER_ID
        val descriptor = assertNotNull(registry.findHandlerDescriptorById(legacyId))

        assertEquals(AmqpOutboxHandler::class.java.name, descriptor.beanClass)
    }

    private fun event(target: String) =
        ExternalizedTransitionEvent(
            target = target,
            aggregateType = "ORGANISATION",
            aggregateId = uuidV7().toString(),
            transition = "TEST",
            fromState = "A",
            toState = "B",
            actor = TransitionActor("SYSTEM", uuidV7().toString()),
            occurredAt = Instant.now(),
            metadata = emptyMap(),
        )

    private fun metadata() =
        OutboxRecordMetadata(
            key = uuidV7().toString(),
            handlerId = AmqpOutboxHandler.LEGACY_RABBIT_HANDLER_ID,
            createdAt = Instant.now(),
            context = emptyMap(),
        )
}
