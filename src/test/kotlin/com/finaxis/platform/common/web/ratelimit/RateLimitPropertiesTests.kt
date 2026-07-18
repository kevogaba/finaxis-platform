package com.finaxis.platform.common.web.ratelimit

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimitPropertiesTests {
    @Test
    fun `application yaml binds distributed rate limit defaults`() {
        val environment = StandardEnvironment()
        YamlPropertySourceLoader()
            .load("application", FileSystemResource("src/main/resources/application.yaml"))
            .forEach { source -> environment.propertySources.addLast(source) }

        val properties =
            Binder
                .get(environment)
                .bind("finaxis.rate-limit", Bindable.of(RateLimitProperties::class.java))
                .get()

        assertTrue(properties.enabled)
        assertEquals(20, properties.policies.getValue(RateLimitPolicyId.AUTH_SELECTION).capacity)
        assertEquals(600, properties.policies.getValue(RateLimitPolicyId.PLATFORM_READ).capacity)
        assertEquals(120, properties.policies.getValue(RateLimitPolicyId.TENANT_COMMAND).capacity)
        assertTrue(
            properties.paths.rules.any { it.policy == RateLimitPolicyId.AUTH_SELECTION },
        )
        assertTrue(properties.paths.excluded.contains("/actuator/health/**"))
    }

    @Test
    fun `policy configuration requires exactly the external policy set`() {
        assertFailsWith<IllegalArgumentException> {
            RateLimitProperties(
                policies =
                    mapOf(
                        RateLimitPolicyId.PLATFORM_READ to RateLimitPolicy(),
                    ),
            )
        }
    }

    @Test
    fun `invalid rule policy reference fails configuration binding`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "invalid-rule",
                mapOf(
                    "finaxis.rate-limit.paths.rules[0].method" to "GET",
                    "finaxis.rate-limit.paths.rules[0].path" to "/api/v1/**",
                    "finaxis.rate-limit.paths.rules[0].policy" to "typo-policy",
                ),
            ),
        )

        assertFailsWith<BindException> {
            Binder
                .get(environment)
                .bind("finaxis.rate-limit", Bindable.of(RateLimitProperties::class.java))
                .get()
        }
    }

    @Test
    fun `arbitrary policy identifier fails configuration binding`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "invalid-policy",
                mapOf(
                    "finaxis.rate-limit.policies.arbitrary.capacity" to "10",
                    "finaxis.rate-limit.policies.arbitrary.refill-tokens" to "10",
                    "finaxis.rate-limit.policies.arbitrary.refill-period" to "1m",
                ),
            ),
        )

        assertFailsWith<BindException> {
            Binder
                .get(environment)
                .bind("finaxis.rate-limit", Bindable.of(RateLimitProperties::class.java))
                .get()
        }
    }
}
