package com.finaxis.platform.notifications.adapter.inbound.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.notifications.application.NotificationService
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes membership-activation integration events and delegates notification scheduling.
 */
@Component
class MembershipActivatedNotificationListener(
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService,
) {
    /**
     * Deserializes and validates a membership-activation event before delegating to the service.
     *
     * Malformed payloads, missing metadata, or a message whose target/aggregate/transition/state
     * does not match membership activation are permanent failures: they are rejected without
     * requeue so a poison message cannot loop forever ahead of valid notifications. Failures from
     * [notificationService] are left to propagate so Spring AMQP's default nack/requeue behavior
     * retries genuinely transient downstream failures.
     *
     * @param body the unconverted RabbitMQ JSON message body
     */
    @RabbitListener(queues = [MEMBERSHIP_ACTIVATED_QUEUE])
    fun onMembershipActivated(body: ByteArray) {
        val event = parseMembershipActivation(body)
        notificationService.handleMembershipActivated(event)
    }

    private fun parseMembershipActivation(body: ByteArray): ExternalizedTransitionEvent =
        runCatching {
            val event = objectMapper.readExternalizedTransitionEvent(body)
            event.requireMembershipActivationShape()
            event
        }.getOrElse { cause ->
            throw AmqpRejectAndDontRequeueException(
                "Rejecting unprocessable membership activation message: ${cause.message}",
                cause,
            )
        }

    private companion object {
        const val MEMBERSHIP_ACTIVATED_QUEUE = "finaxis.notifications.membership-activated"
        const val EXPECTED_TARGET = "finaxis.lifecycle.membership.activated"
        const val EXPECTED_AGGREGATE_TYPE = "MEMBERSHIP"
        const val EXPECTED_TRANSITION = "ACTIVATE"
        const val EXPECTED_TO_STATE = "ACTIVE"
        val REQUIRED_METADATA = setOf("membershipId", "userId", "organisationId")
    }

    private fun ExternalizedTransitionEvent.requireMembershipActivationShape() {
        require(target == EXPECTED_TARGET) { "Unexpected event target: $target" }
        require(aggregateType == EXPECTED_AGGREGATE_TYPE) {
            "Unexpected aggregate type: $aggregateType"
        }
        require(transition == EXPECTED_TRANSITION) { "Unexpected transition: $transition" }
        require(toState == EXPECTED_TO_STATE) { "Unexpected target state: $toState" }
        REQUIRED_METADATA.forEach { field ->
            val value = metadata[field]?.toString()
            require(!value.isNullOrBlank()) {
                "Membership activation event is missing required metadata: $field"
            }
            requireNotNull(runCatching { UUID.fromString(value) }.getOrNull()) {
                "Membership activation event has an invalid UUID metadata value for $field: $value"
            }
        }
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
