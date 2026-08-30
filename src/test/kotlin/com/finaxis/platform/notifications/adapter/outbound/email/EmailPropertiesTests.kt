package com.finaxis.platform.notifications.adapter.outbound.email

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmailPropertiesTests {
    @Test
    fun `defaults construct without error`() {
        val properties = EmailProperties()

        assertEquals(false, properties.enabled)
        assertEquals("no-reply@finaxis.local", properties.fromAddress)
        assertEquals("Finaxis", properties.fromDisplayName)
        assertEquals("http://localhost:5173", properties.appBaseUrl)
    }

    @Test
    fun `blank from address is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EmailProperties(fromAddress = "  ")
        }
    }

    @Test
    fun `blank from display name is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EmailProperties(fromDisplayName = "")
        }
    }

    @Test
    fun `non-http app base url is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EmailProperties(appBaseUrl = "ftp://example.test")
        }
    }
}
