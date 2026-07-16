package com.finaxis.platform.common.transitions

import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import org.springframework.modulith.events.EventExternalizationConfiguration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitionModuleConfigurationTests {
    @Test
    fun `externalization configuration routes only externalized transition events`() {
        ApplicationContextRunner()
            .withUserConfiguration(TransitionModuleConfiguration::class.java)
            .run { context ->
                val config = context.getBean(EventExternalizationConfiguration::class.java)
                val event = externalizedEvent()

                assertTrue(config.supports(event))
                assertFalse(config.supports(internalEvent()))
                assertEquals("tradestack.sample.changed", config.determineTarget(event).target)
                assertFalse(config.serializeExternalization())
            }
    }

    @Test
    fun `application yaml configures Modulith Namastack outbox and RabbitMQ publishing`() {
        val source =
            YamlPropertySourceLoader()
                .load("application", FileSystemResource("src/main/resources/application.yaml"))
                .first()
        // Config values use "${FINAXIS_...:default}" placeholders; resolve them through a real
        // Environment (with no matching OS env vars set) so assertions see the resolved defaults.
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(source)

        assertEquals(
            "outbox",
            environment.getProperty("spring.modulith.events.externalization.mode"),
        )
        assertEquals(
            "false",
            environment.getProperty(
                "spring.modulith.events.externalization.serialize-externalization",
            ),
        )
        assertEquals(
            "correlated",
            environment.getProperty("spring.rabbitmq.publisher-confirm-type"),
        )
        assertEquals("true", environment.getProperty("spring.rabbitmq.publisher-returns"))
        assertEquals("true", environment.getProperty("spring.rabbitmq.template.mandatory"))
        assertEquals(
            "true",
            environment.getProperty("namastack.outbox.rabbit.fail-on-unroutable"),
        )
        assertEquals("true", environment.getProperty("namastack.outbox.enabled"))
        assertEquals("fixed", environment.getProperty("namastack.outbox.polling.trigger"))
        assertEquals("2s", environment.getProperty("namastack.outbox.polling.fixed.interval"))
        assertEquals("10", environment.getProperty("namastack.outbox.polling.batch-size"))
        assertEquals("5", environment.getProperty("namastack.outbox.retry.max-retries"))
        assertEquals("exponential", environment.getProperty("namastack.outbox.retry.policy"))
        assertEquals(
            "2s",
            environment.getProperty("namastack.outbox.retry.exponential.initial-delay"),
        )
        assertEquals(
            "5m",
            environment.getProperty("namastack.outbox.retry.exponential.max-delay"),
        )
        assertEquals(
            "2.0",
            environment.getProperty("namastack.outbox.retry.exponential.multiplier"),
        )
        assertEquals("500ms", environment.getProperty("namastack.outbox.retry.jitter"))
        assertEquals(
            "true",
            environment.getProperty("namastack.outbox.processing.stop-on-first-failure"),
        )
        assertEquals(
            "false",
            environment.getProperty("namastack.outbox.processing.delete-completed-records"),
        )
    }

    private fun externalizedEvent(): ExternalizedTransitionEvent =
        ExternalizedTransitionEvent(
            target = "tradestack.sample.changed",
            aggregateType = "SAMPLE",
            aggregateId = "sample-1",
            transition = "APPROVE",
            fromState = "CONFIRMED",
            toState = "APPROVED",
            actor = TransitionActor("operator", "user-1"),
            occurredAt = Instant.parse("2026-07-06T08:00:00Z"),
        )

    private fun internalEvent(): InternalTransitionEvent =
        InternalTransitionEvent(
            aggregateType = "SAMPLE",
            aggregateId = "sample-1",
            transition = "APPROVE",
            fromState = "CONFIRMED",
            toState = "APPROVED",
            actor = TransitionActor("operator", "user-1"),
            occurredAt = Instant.parse("2026-07-06T08:00:00Z"),
        )
}
