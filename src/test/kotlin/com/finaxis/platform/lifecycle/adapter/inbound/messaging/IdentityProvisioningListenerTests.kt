package com.finaxis.platform.lifecycle.adapter.inbound.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.lifecycle.application.KeycloakInvitationResendJobRequest
import org.jobrunr.scheduling.JobRequestScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import java.time.Instant
import java.util.UUID

/** The invitation-resend branch of the provisioning listener (#274). */
class IdentityProvisioningListenerTests {
    private val objectMapper =
        ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val scheduler = mock<JobRequestScheduler>()
    private val listener = IdentityProvisioningListener(objectMapper, scheduler)
    private val organisationId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    @Test
    fun `a resend event enqueues one job carrying ids and no personal data`() {
        listener.onEvent(resendEvent(attempt = "4"))

        verify(scheduler).enqueue(
            any<UUID>(),
            eq(KeycloakInvitationResendJobRequest(organisationId, userId, "key", 4)),
        )
    }

    @Test
    fun `a resend event with a bad attempt is rejected, never requeued`() {
        listOf("four", "99999999999", "1.5").forEach { attempt ->
            assertThrows<AmqpRejectAndDontRequeueException>(attempt) {
                listener.onEvent(resendEvent(attempt))
            }
        }
        verify(scheduler, never()).enqueue(any<UUID>(), any<KeycloakInvitationResendJobRequest>())
    }

    private fun resendEvent(attempt: String): ByteArray =
        objectMapper.writeValueAsBytes(
            ExternalizedTransitionEvent(
                target = "finaxis.lifecycle.user.invitation-resend-requested",
                aggregateType = "USER_ACCOUNT",
                aggregateId = userId.toString(),
                transition = "INVITATION_RESEND_REQUESTED",
                fromState = "INVITED",
                toState = "INVITED",
                actor = TransitionActor("USER", UUID.randomUUID().toString()),
                occurredAt = Instant.now(),
                metadata =
                    mapOf(
                        "organisationId" to organisationId.toString(),
                        "userId" to userId.toString(),
                        "dispatchKey" to "key",
                        "attempt" to attempt,
                    ),
            ),
        )
}
