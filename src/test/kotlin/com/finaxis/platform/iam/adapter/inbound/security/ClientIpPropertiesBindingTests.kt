package com.finaxis.platform.iam.adapter.inbound.security

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `finaxis.security.client-ip.trusted-proxies` binds, defaults to empty, and fails startup. */
class ClientIpPropertiesBindingTests {
    private val contextRunner =
        ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun `the trusted proxy list is empty by default`() {
        contextRunner.run { context ->
            assertNull(context.startupFailure)
            assertEquals(emptyList(), context.getBean(ClientIpProperties::class.java).trustedRanges)
        }
    }

    @Test
    fun `an empty value, as the unset environment variable gives, binds to no proxy`() {
        contextRunner.withPropertyValues("$PROPERTY=").run { context ->
            assertNull(context.startupFailure)
            assertEquals(emptyList(), context.getBean(ClientIpProperties::class.java).trustedRanges)
        }
    }

    @Test
    fun `a comma-separated list of addresses and ranges binds`() {
        contextRunner
            .withPropertyValues("$PROPERTY=10.0.0.0/8, 192.0.2.10,2001:db8::/32")
            .run { context ->
                assertNull(context.startupFailure)
                val properties = context.getBean(ClientIpProperties::class.java)
                assertEquals(
                    listOf("10.0.0.0/8", "192.0.2.10", "2001:db8::/32"),
                    properties.trustedProxies.map(String::trim),
                )
                assertEquals(3, properties.trustedRanges.size)
            }
    }

    @Test
    fun `an invalid entry fails startup naming the property and the entry`() {
        contextRunner
            .withPropertyValues("$PROPERTY=10.0.0.0/8,10.0.0.0/33")
            .run { context ->
                val failure = assertNotNull(context.startupFailure)
                val messages =
                    generateSequence(failure as Throwable) { it.cause }
                        .mapNotNull { it.message }
                        .joinToString("\n")
                assertTrue(messages.contains("10.0.0.0/33"), messages)
                assertTrue(messages.contains("finaxis.security.client-ip"), messages)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClientIpProperties::class)
    class PropertiesConfiguration

    private companion object {
        const val PROPERTY = "finaxis.security.client-ip.trusted-proxies"
    }
}
