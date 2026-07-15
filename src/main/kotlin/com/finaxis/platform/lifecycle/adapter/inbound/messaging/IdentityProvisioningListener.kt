package com.finaxis.platform.lifecycle.adapter.inbound.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.lifecycle.application.ApplicationInviteJobRequest
import com.finaxis.platform.lifecycle.application.KeycloakUserProvisioningJobRequest
import org.jobrunr.scheduling.JobRequestScheduler
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Consumes lifecycle identity provisioning events and schedules durable JobRunr workers. */
@Component
class IdentityProvisioningListener(
    private val objectMapper: ObjectMapper,
    private val jobRequestScheduler: JobRequestScheduler,
) {
    /** Deserializes a provisioning event, validates target metadata, and enqueues one job. */
    @RabbitListener(queues = [USER_PROVISIONING_QUEUE])
    fun onEvent(body: ByteArray) {
        val event = parseEvent(body)
        when (event.target) {
            KEYCLOAK_TARGET -> scheduleKeycloakProvisioning(event)
            APPLICATION_INVITE_TARGET -> scheduleApplicationInvite(event)
            else -> reject("Unexpected user provisioning target: ${event.target}", null)
        }
    }

    private fun parseEvent(body: ByteArray): ExternalizedTransitionEvent =
        runCatching { objectMapper.readExternalizedTransitionEvent(body) }
            .getOrElse { cause -> reject("Malformed user provisioning message.", cause) }

    private fun scheduleKeycloakProvisioning(event: ExternalizedTransitionEvent) {
        event.requireMetadata(KEYCLOAK_METADATA)
        val dispatchKey = event.requiredMetadata(DISPATCH_KEY)
        jobRequestScheduler.enqueue(
            deterministicJobId(dispatchKey),
            KeycloakUserProvisioningJobRequest(
                organisationId = event.requiredMetadataUuid(ORGANISATION_ID),
                membershipId = event.requiredMetadataUuid(MEMBERSHIP_ID),
                userId = event.requiredMetadataUuid(USER_ID),
                email = event.requiredMetadata(EMAIL),
                username = event.requiredMetadata(USERNAME),
                displayName =
                    event.metadata[DISPLAY_NAME]?.toString() ?: event.requiredMetadata(USERNAME),
                sendKeycloakInvite = event.requiredMetadata(SEND_KEYCLOAK_INVITE).toBooleanStrict(),
                dispatchKey = dispatchKey,
                actorId = UUID.fromString(event.actor.id),
            ),
        )
    }

    private fun scheduleApplicationInvite(event: ExternalizedTransitionEvent) {
        event.requireMetadata(APPLICATION_INVITE_METADATA)
        val dispatchKey = event.requiredMetadata(DISPATCH_KEY)
        jobRequestScheduler.enqueue(
            deterministicJobId(dispatchKey),
            ApplicationInviteJobRequest(
                organisationId = event.requiredMetadataUuid(ORGANISATION_ID),
                membershipId = event.requiredMetadataUuid(MEMBERSHIP_ID),
                userId = event.requiredMetadataUuid(USER_ID),
                email = event.requiredMetadata(EMAIL),
                dispatchKey = dispatchKey,
            ),
        )
    }

    private fun deterministicJobId(dispatchKey: String): UUID =
        UUID.nameUUIDFromBytes(dispatchKey.toByteArray())

    private companion object {
        const val USER_PROVISIONING_QUEUE = "finaxis.lifecycle.user-provisioning-events"
        const val KEYCLOAK_TARGET = "finaxis.lifecycle.user.keycloak-provisioning-requested"
        const val APPLICATION_INVITE_TARGET = "finaxis.lifecycle.user.application-invite-requested"
        const val ORGANISATION_ID = "organisationId"
        const val MEMBERSHIP_ID = "membershipId"
        const val USER_ID = "userId"
        const val EMAIL = "email"
        const val USERNAME = "username"
        const val DISPLAY_NAME = "displayName"
        const val SEND_KEYCLOAK_INVITE = "sendKeycloakInvite"
        const val DISPATCH_KEY = "dispatchKey"
        val KEYCLOAK_METADATA =
            setOf(
                ORGANISATION_ID,
                MEMBERSHIP_ID,
                USER_ID,
                EMAIL,
                USERNAME,
                SEND_KEYCLOAK_INVITE,
                DISPATCH_KEY,
            )
        val APPLICATION_INVITE_METADATA =
            setOf(ORGANISATION_ID, MEMBERSHIP_ID, USER_ID, EMAIL, DISPATCH_KEY)
    }
}

private fun ExternalizedTransitionEvent.requireMetadata(fields: Set<String>) {
    fields.forEach { field -> requiredMetadata(field) }
}

private fun ExternalizedTransitionEvent.requiredMetadata(field: String): String =
    metadata[field]?.toString()?.takeIf(String::isNotBlank)
        ?: reject("User provisioning event is missing required metadata: $field", null)

private fun ExternalizedTransitionEvent.requiredMetadataUuid(field: String): UUID =
    runCatching { UUID.fromString(requiredMetadata(field)) }
        .getOrElse { cause -> reject("Invalid UUID metadata for $field.", cause) }

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

private fun reject(
    message: String,
    cause: Throwable?,
): Nothing = throw AmqpRejectAndDontRequeueException(message, cause)
