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
        val properties = bindApplicationYaml()

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
    fun `application yaml classifies auth and platform routes before tenant catch all`() {
        val rules = bindApplicationYaml().paths.rules

        assertEquals(
            setOf("POST", "PUT", "PATCH", "DELETE"),
            rules
                .filter {
                    it.path == "/api/v1/auth/**" &&
                        it.policy == RateLimitPolicyId.PLATFORM_COMMAND
                }.map { it.method }
                .toSet(),
        )
        assertEquals(
            setOf("POST", "PUT", "PATCH", "DELETE"),
            rules
                .filter {
                    it.path == "/api/v1/platform/**" &&
                        it.policy == RateLimitPolicyId.PLATFORM_COMMAND
                }.map { it.method }
                .toSet(),
        )
        assertRuleBeforeTenantCatchAll(rules, "GET", RateLimitPolicyId.PLATFORM_READ)
        assertRuleBeforeTenantCatchAll(rules, "POST", RateLimitPolicyId.PLATFORM_COMMAND)
        assertRuleBeforeTenantCatchAll(rules, "PUT", RateLimitPolicyId.PLATFORM_COMMAND)
        assertRuleBeforeTenantCatchAll(rules, "PATCH", RateLimitPolicyId.PLATFORM_COMMAND)
        assertRuleBeforeTenantCatchAll(rules, "DELETE", RateLimitPolicyId.PLATFORM_COMMAND)
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

    private fun bindApplicationYaml(): RateLimitProperties {
        val environment = StandardEnvironment()
        YamlPropertySourceLoader()
            .load("application", FileSystemResource("src/main/resources/application.yaml"))
            .forEach { source -> environment.propertySources.addLast(source) }

        return Binder
            .get(environment)
            .bind("finaxis.rate-limit", Bindable.of(RateLimitProperties::class.java))
            .get()
    }

    private fun assertRuleBeforeTenantCatchAll(
        rules: List<RateLimitPathRule>,
        method: String,
        expectedPolicy: RateLimitPolicyId,
    ) {
        val platformRuleIndex =
            rules.indexOfFirst { rule ->
                rule.method == method &&
                    rule.path == "/api/v1/platform/**" &&
                    rule.policy == expectedPolicy
            }
        val tenantRuleIndex =
            rules.indexOfFirst { rule ->
                rule.method == method && rule.path == "/api/v1/**"
            }

        assertTrue(platformRuleIndex >= 0)
        assertTrue(tenantRuleIndex >= 0)
        assertTrue(platformRuleIndex < tenantRuleIndex)
    }
}
