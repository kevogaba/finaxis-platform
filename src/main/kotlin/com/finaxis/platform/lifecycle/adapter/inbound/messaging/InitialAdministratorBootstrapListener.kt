package com.finaxis.platform.lifecycle.adapter.inbound.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapJobRequest
import org.jobrunr.scheduling.JobRequestScheduler
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes organisation activation integration events and delegates to bootstrap job scheduling.
 */
@Component
class InitialAdministratorBootstrapListener(
    private val objectMapper: ObjectMapper,
    private val jobRequestScheduler: JobRequestScheduler,
) {
    /** Deserializes organisation activated event and enqueues a bootstrap job. */
    @RabbitListener(queues = [ORGANISATION_ACTIVATED_BOOTSTRAP_QUEUE])
    fun onEvent(body: ByteArray) {
        val event = parseEvent(body)
        if (event.target == EXPECTED_TARGET && event.transition == EXPECTED_TRANSITION) {
            val organisationId = UUID.fromString(event.aggregateId)
            val dispatchKey = "$organisationId:BOOTSTRAP"
            jobRequestScheduler.enqueue(
                UUID.nameUUIDFromBytes(dispatchKey.toByteArray()),
                InitialAdministratorBootstrapJobRequest(organisationId),
            )
        } else {
            throw AmqpRejectAndDontRequeueException(
                "Unexpected bootstrap target: ${event.target} or transition: ${event.transition}",
            )
        }
    }

    private fun parseEvent(body: ByteArray): ExternalizedTransitionEvent =
        runCatching { objectMapper.readExternalizedTransitionEvent(body) }
            .getOrElse { cause ->
                throw AmqpRejectAndDontRequeueException(
                    "Malformed organisation activated bootstrap message.",
                    cause,
                )
            }

    private companion object {
        const val ORGANISATION_ACTIVATED_BOOTSTRAP_QUEUE =
            "finaxis.lifecycle.organisation-activated.bootstrap"
        const val EXPECTED_TARGET = "finaxis.lifecycle.organisation.activated"
        const val EXPECTED_TRANSITION = "ACTIVATE"
    }
}

private fun ObjectMapper.readExternalizedTransitionEvent(
    body: ByteArray,
): ExternalizedTransitionEvent {
    val event = readTree(body)
    val actor = event.required("actor")
    return ExternalizedTransitionEvent(
        target = event.required("target").asText(),
        aggregateType = event.required("aggregateType").asText(),
        aggregateId = event.required("aggregateId").asText(),
        transition = event.required("transition").asText(),
        fromState = event.required("fromState").asText(),
        toState = event.required("toState").asText(),
        actor =
            TransitionActor(
                type = actor.required("type").asText(),
                id = actor.required("id").asText(),
                displayName = actor.get("displayName")?.takeUnless { it.isNull }?.asText(),
            ),
        occurredAt = Instant.parse(event.required("occurredAt").asText()),
        metadata = event.metadata(),
    )
}

private fun JsonNode.metadata(): Map<String, Any?> {
    val values = linkedMapOf<String, Any?>()
    get("metadata")?.properties()?.forEach { (key, value) ->
        values[key] =
            when {
                value.isNull -> null
                value.isTextual -> value.asText()
                value.isNumber -> value.numberValue()
                value.isBoolean -> value.asBoolean()
                else -> value.toString()
            }
    }
    return values
}

private fun JsonNode.required(field: String): JsonNode =
    requireNotNull(get(field)) { "Missing required event field: $field" }
