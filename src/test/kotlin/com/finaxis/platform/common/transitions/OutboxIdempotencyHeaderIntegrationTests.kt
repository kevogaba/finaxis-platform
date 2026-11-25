package com.finaxis.platform.common.transitions

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxRecordRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbitmq.client.RabbitAmqpAdmin
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a consumer can deduplicate on: every message the outbox publishes carries Namastack's
 * `Idempotency-Key` header (`namastack.outbox.rabbit.idempotency-header-enabled`), holding the
 * outbox record id, and no longer the custom `X-Outbox-Record-Key` header the routing code set.
 * Proved on the broker, from a queue bound to an event's exchange beside the application's own.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OutboxIdempotencyHeaderIntegrationTests(
    private val outbox: Outbox,
    private val records: OutboxRecordRepository,
    private val transactions: TransactionTemplate,
    private val admin: RabbitAmqpAdmin,
    private val rabbitTemplate: RabbitTemplate,
) {
    private val queue = Queue("finaxis.test.outbox-idempotency-header", true, false, false)

    @AfterEach
    fun deleteQueue() {
        admin.deleteQueue(queue.name)
    }

    @Test
    fun `a published message carries the outbox record id as its Idempotency-Key`() {
        admin.declareQueue(queue)
        admin.declareBinding(
            Binding(queue.name, Binding.DestinationType.QUEUE, EXCHANGE, "", emptyMap()),
        )
        val key = "idempotency-header-" + uuidV7()

        transactions.executeWithoutResult { outbox.schedule(event(), key) }

        val message = assertNotNull(rabbitTemplate.receive(queue.name, RECEIVE_TIMEOUT))
        val record =
            await().atMost(Duration.ofSeconds(10)).until(
                { records.findCompletedRecords().firstOrNull { it.key == key } },
                { it != null },
            )
        val headers = message.messageProperties.headers
        assertEquals(assertNotNull(record).id, headers["Idempotency-Key"]?.toString())
        assertNull(headers["X-Outbox-Record-Key"])
    }

    private fun event() =
        ExternalizedTransitionEvent(
            target = EXCHANGE,
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

    private companion object {
        const val EXCHANGE = "finaxis.lifecycle.membership.activated"
        const val RECEIVE_TIMEOUT = 10_000L
    }
}
