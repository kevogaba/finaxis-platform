package com.finaxis.platform.common.transitions

import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
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
                .load("application", ClassPathResource("application.yaml"))
                .first()

        assertEquals("outbox", source.getProperty("spring.modulith.events.externalization.mode"))
        assertEquals(
            false,
            source.getProperty("spring.modulith.events.externalization.serialize-externalization"),
        )
        assertEquals("correlated", source.getProperty("spring.rabbitmq.publisher-confirm-type"))
        assertEquals(true, source.getProperty("spring.rabbitmq.publisher-returns"))
        assertEquals(true, source.getProperty("spring.rabbitmq.template.mandatory"))
        assertEquals(true, source.getProperty("namastack.outbox.rabbit.fail-on-unroutable"))
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
