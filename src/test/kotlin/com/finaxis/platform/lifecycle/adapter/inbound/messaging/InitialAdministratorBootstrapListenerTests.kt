package com.finaxis.platform.lifecycle.adapter.inbound.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapJobRequest
import org.jobrunr.scheduling.JobRequestScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import java.time.Instant
import java.util.UUID

class InitialAdministratorBootstrapListenerTests {
    private val objectMapper =
        ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val jobRequestScheduler = mock<JobRequestScheduler>()
    private val listener = InitialAdministratorBootstrapListener(objectMapper, jobRequestScheduler)

    @Test
    fun `enqueues initial administrator bootstrap job on organisation activated event`() {
        val organisationId = UUID.randomUUID()
        val event =
            ExternalizedTransitionEvent(
                target = "finaxis.lifecycle.organisation.activated",
                aggregateType = "ORGANISATION",
                aggregateId = organisationId.toString(),
                transition = "ACTIVATE",
                fromState = "PROVISIONING",
                toState = "ACTIVE",
                actor = TransitionActor("USER", UUID.randomUUID().toString()),
                occurredAt = Instant.now(),
                metadata = emptyMap(),
            )
        val body = objectMapper.writeValueAsBytes(event)

        listener.onEvent(body)

        val dispatchKey = "$organisationId:BOOTSTRAP"
        val expectedJobId = UUID.nameUUIDFromBytes(dispatchKey.toByteArray())
        verify(jobRequestScheduler).enqueue(
            eq(expectedJobId),
            eq(InitialAdministratorBootstrapJobRequest(organisationId)),
        )
    }

    @Test
    fun `rejects malformed messages`() {
        val body = "invalid-json".toByteArray()
        assertThrows<AmqpRejectAndDontRequeueException> {
            listener.onEvent(body)
        }
    }

    @Test
    fun `rejects unexpected event targets`() {
        val event =
            ExternalizedTransitionEvent(
                target = "unexpected-target",
                aggregateType = "ORGANISATION",
                aggregateId = UUID.randomUUID().toString(),
                transition = "ACTIVATE",
                fromState = "PROVISIONING",
                toState = "ACTIVE",
                actor = TransitionActor("USER", UUID.randomUUID().toString()),
                occurredAt = Instant.now(),
                metadata = emptyMap(),
            )
        val body = objectMapper.writeValueAsBytes(event)
        assertThrows<AmqpRejectAndDontRequeueException> {
            listener.onEvent(body)
        }
    }
}
