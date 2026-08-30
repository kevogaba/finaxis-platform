package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MeteredEmailGatewayTests {
    private val registry = SimpleMeterRegistry()

    @Test
    fun `success records an attempt with outcome success and no pii tag`() {
        val gateway = MeteredEmailGateway({ EmailDeliveryReceipt("id-1") }, registry)

        gateway.send(sampleWelcomeMessage())

        val counter =
            registry
                .find("finaxis.email.delivery.attempts.total")
                .tags("category", "WELCOME", "outcome", "success")
                .counter()
        assertEquals(1.0, counter?.count())
        assertNull(counter?.id?.getTag("recipientEmail"))
    }

    @Test
    fun `permanent failure records outcome permanent_failure and rethrows`() {
        val delegate =
            EmailGateway { throw PermanentEmailDeliveryException("bad address") }
        val gateway = MeteredEmailGateway(delegate, registry)

        assertFailsWith<PermanentEmailDeliveryException> { gateway.send(sampleWelcomeMessage()) }

        val counter =
            registry
                .find("finaxis.email.delivery.attempts.total")
                .tags("category", "WELCOME", "outcome", "permanent_failure")
                .counter()
        assertEquals(1.0, counter?.count())
    }

    @Test
    fun `retryable failure records outcome retryable_failure and rethrows`() {
        val delegate =
            EmailGateway { throw RetryableEmailDeliveryException("transient") }
        val gateway = MeteredEmailGateway(delegate, registry)

        assertFailsWith<RetryableEmailDeliveryException> { gateway.send(sampleWelcomeMessage()) }

        val counter =
            registry
                .find("finaxis.email.delivery.attempts.total")
                .tags("category", "WELCOME", "outcome", "retryable_failure")
                .counter()
        assertEquals(1.0, counter?.count())
    }
}

internal fun sampleWelcomeMessage(): EmailMessage =
    EmailMessage(
        category = EmailCategory.WELCOME,
        recipientEmail = "member@example.test",
        recipientDisplayName = "Ada Lovelace",
        organisationDisplayName = "Acme Bank",
    )
