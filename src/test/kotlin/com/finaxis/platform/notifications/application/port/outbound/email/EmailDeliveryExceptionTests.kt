package com.finaxis.platform.notifications.application.port.outbound.email

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class EmailDeliveryExceptionTests {
    @Test
    fun `retryable and permanent exceptions are both EmailDeliveryException`() {
        val retryable = RetryableEmailDeliveryException("transient smtp failure")
        val permanent = PermanentEmailDeliveryException("bad recipient")

        assertIs<EmailDeliveryException>(retryable)
        assertIs<EmailDeliveryException>(permanent)
        assertNull(retryable.cause)
    }
}
