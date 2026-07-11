package com.finaxis.platform.common.web.versioning

import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.reflect.full.findAnnotation
import kotlin.test.assertTrue

private const val API_PREFIX = "/api/v"

class ApiVersioningArchitectureTest {
    @Test
    fun `all public rest controllers are mapped under an explicit api version`() {
        val violations =
            listOf(
                "com.finaxis.platform.iam.adapter.inbound.web.AuthController",
                "com.finaxis.platform.iam.adapter.inbound.web.UserProfileController",
            ).mapNotNull(::unversionedControllerMapping)

        assertTrue(
            violations.isEmpty(),
            "Public controllers must use /api/vN paths: ${violations.joinToString()}",
        )
    }

    @Test
    fun `local smoke script uses versioned api paths`() {
        val script = Path.of("scripts/local-smoke.sh").readText()

        assertTrue(script.contains("/api/v1/auth/select-organisation"))
        assertTrue(script.contains("/api/v1/auth/select-branch"))
        assertTrue(script.contains("/api/v1/auth/me"))
    }

    @Test
    fun `documentation does not introduce unversioned api examples`() {
        val offenders =
            Files
                .walk(Path.of("docs"))
                .filter { path -> path.toString().endsWith(".md") }
                .flatMap { path ->
                    path
                        .readText()
                        .lines()
                        .mapIndexed { index, line -> path to (index + 1 to line) }
                        .stream()
                }.filter { (_, line) ->
                    line.second.contains("/api/") && !line.second.contains(API_PREFIX)
                }.toList()

        assertTrue(
            offenders.isEmpty(),
            "Docs must use versioned API examples: ${offenders.joinToString()}",
        )
    }

    private fun unversionedControllerMapping(className: String): String? {
        val type = Class.forName(className).kotlin
        if (type.findAnnotation<RestController>() == null) {
            return null
        }
        val mapping = type.findAnnotation<RequestMapping>() ?: return "$className has no mapping"
        val paths = mapping.value.toList() + mapping.path.toList()
        return paths
            .takeIf { it.isEmpty() || it.any { path -> !path.startsWith(API_PREFIX) } }
            ?.let { "$className -> ${it.ifEmpty { listOf("<empty>") }}" }
    }
}
