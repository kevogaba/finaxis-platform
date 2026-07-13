package com.finaxis.platform.notifications.adapter.inbound.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.notifications.application.NotificationService
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component
import java.time.Instant

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
     * Exceptions are intentionally allowed to propagate so Spring AMQP nacks and requeues the
     * message according to its configured default behavior.
     *
     * @param body the unconverted RabbitMQ JSON message body
     */
    @RabbitListener(queues = [MEMBERSHIP_ACTIVATED_QUEUE])
    fun onMembershipActivated(body: ByteArray) {
        val event = objectMapper.readExternalizedTransitionEvent(body)
        event.validateRequiredMetadata()
        notificationService.handleMembershipActivated(event)
    }

    private companion object {
        const val MEMBERSHIP_ACTIVATED_QUEUE = "finaxis.notifications.membership-activated"
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

private fun com.fasterxml.jackson.databind.JsonNode.metadata(): Map<String, Any?> {
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

private fun ExternalizedTransitionEvent.validateRequiredMetadata() {
    REQUIRED_METADATA.forEach { field ->
        require(!metadata[field]?.toString().isNullOrBlank()) {
            "Membership activation event is missing required metadata: $field"
        }
    }
}

private val REQUIRED_METADATA = setOf("membershipId", "userId", "organisationId")
