package com.finaxis.platform.common.web.ratelimit

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import kotlin.test.assertEquals
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
        assertEquals(100, properties.anonymous.capacity)
        assertEquals(1000, properties.authenticated.capacity)
        assertTrue(properties.paths.excluded.contains("/actuator/health/**"))
    }
}
