package com.finaxis.platform.notifications.adapter.inbound.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.notifications.application.NotificationService
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

class MembershipActivatedNotificationListenerTests {
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val notificationService = mock(NotificationService::class.java)
    private val listener =
        MembershipActivatedNotificationListener(objectMapper, notificationService)

    @Test
    fun `listener deserializes a membership activation and delegates to the service`() {
        listener.onMembershipActivated(validPayload().toByteArray(StandardCharsets.UTF_8))

        val invocation =
            org.mockito.Mockito
                .mockingDetails(
                    notificationService,
                ).invocations
                .single()
        assertEquals("handleMembershipActivated", invocation.method.name)
        assertEquals(
            "1adf7850-5aea-4f7a-b88b-f3d1261ce4b6",
            invocation.arguments
                .single()
                .let {
                    it as com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
                }.metadata["membershipId"],
        )
    }

    @Test
    fun `listener rejects malformed payloads`() {
        assertThrows<Exception> {
            listener.onMembershipActivated("not-json".toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `listener rejects payloads without required membership metadata`() {
        assertThrows<IllegalArgumentException> {
            listener.onMembershipActivated(
                missingMembershipIdPayload().toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    private fun validPayload(): String =
        """
        {
          "target":"finaxis.lifecycle.membership.activated",
          "aggregateType":"MEMBERSHIP",
          "aggregateId":"1adf7850-5aea-4f7a-b88b-f3d1261ce4b6",
          "transition":"ACTIVATE",
          "fromState":"INVITED",
          "toState":"ACTIVE",
          "actor":{"type":"USER","id":"aa93153b-4806-4d42-ba5a-e791632c9ef7","displayName":"Example User"},
          "occurredAt":"2026-07-13T10:15:30Z",
          "metadata":{
            "membershipId":"1adf7850-5aea-4f7a-b88b-f3d1261ce4b6",
            "userId":"aa93153b-4806-4d42-ba5a-e791632c9ef7",
            "organisationId":"f0bb4770-04e0-4a32-8c15-c5c6f9b0573d"
          }
        }
        """.trimIndent()

    private fun missingMembershipIdPayload(): String =
        validPayload().replace("\"membershipId\":\"1adf7850-5aea-4f7a-b88b-f3d1261ce4b6\",", "")
}
