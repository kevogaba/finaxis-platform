package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DisabledEmailGatewayTests {
    @Test
    fun `always throws a permanent delivery exception`() {
        val gateway = DisabledEmailGateway()

        assertFailsWith<PermanentEmailDeliveryException> {
            gateway.send(sampleWelcomeMessage())
        }
    }
}
