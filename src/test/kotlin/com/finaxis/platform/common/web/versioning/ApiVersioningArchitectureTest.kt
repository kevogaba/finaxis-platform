package com.finaxis.platform.common.web.versioning

import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.reflect.full.findAnnotation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.finaxis.platform.common.web.idempotency.IdempotentMutation as DurableIdempotentMutation

private const val API_PREFIX = "/api/v"

class ApiVersioningArchitectureTest {
    @Test
    fun `all public rest controllers are mapped under an explicit api version`() {
        val violations =
            restControllerTypes().mapNotNull(::unversionedControllerMapping)

        assertTrue(
            violations.isEmpty(),
            "Public controllers must use /api/vN paths: ${violations.joinToString()}",
        )
    }

    @Test
    fun `all mutation endpoints declare durable idempotency`() {
        val violations = mutationViolations(restControllerTypes())

        assertTrue(
            violations.isEmpty(),
            "Mutation endpoints must use @IdempotentMutation: ${violations.joinToString()}",
        )
    }

    @Test
    fun `mutation guard recognizes inherited and composed endpoint contracts`() {
        assertTrue(mutationViolations(listOf(InheritedController::class.java)).isEmpty())
        assertTrue(mutationViolations(listOf(ComposedController::class.java)).isEmpty())
    }

    @Test
    fun `mutation guard rejects an unrelated annotation with the same simple name`() {
        val violations = mutationViolations(listOf(FakeAnnotationController::class.java))

        assertEquals(
            listOf("${FakeAnnotationController::class.java.name}#mutate"),
            violations,
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
                    isUnversionedApiExample(line.second)
                }.toList()

        assertTrue(
            offenders.isEmpty(),
            "Docs must use versioned API examples: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `only endpoint examples are considered by the documentation scanner`() {
        assertFalse(isUnversionedApiExample("Source: src/main/kotlin/web/api/ApiProblem.kt"))
        assertTrue(isUnversionedApiExample("Call /api/auth/me after login."))
        assertFalse(isUnversionedApiExample("Call /api/v1/auth/me after login."))
    }

    private fun unversionedControllerMapping(javaType: Class<*>): String? {
        val type = javaType.kotlin
        if (type.findAnnotation<RestController>() == null) {
            return null
        }
        val mapping =
            type.findAnnotation<RequestMapping>() ?: return "${javaType.name} has no mapping"
        val paths = mapping.value.toList() + mapping.path.toList()
        return paths
            .takeIf { it.isEmpty() || it.any { path -> !path.startsWith(API_PREFIX) } }
            ?.let { "${javaType.name} -> ${it.ifEmpty { listOf("<empty>") }}" }
    }

    private fun restControllerTypes(): List<Class<*>> =
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(RestController::class.java)) }
            .findCandidateComponents("com.finaxis.platform")
            .map { candidate -> Class.forName(requireNotNull(candidate.beanClassName)) }
            .filterNot { type ->
                type.protectionDomain.codeSource.location.path
                    .contains("/test/")
            }

    private fun mutationViolations(types: List<Class<*>>): List<String> =
        types.flatMap { type ->
            type.methods.mapNotNull { method ->
                val mapping =
                    AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping::class.java)
                val mutates = mapping?.method?.any(MUTATION_METHODS::contains) == true
                method
                    .takeIf {
                        mutates &&
                            !AnnotatedElementUtils.hasAnnotation(
                                it,
                                DurableIdempotentMutation::class.java,
                            )
                    }?.let { "${type.name}#${it.name}" }
            }
        }

    private fun isUnversionedApiExample(line: String): Boolean =
        ENDPOINT_EXAMPLE.findAll(line).any { match -> !match.value.startsWith(API_PREFIX) }

    private companion object {
        val MUTATION_METHODS =
            setOf(
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.PATCH,
                RequestMethod.DELETE,
            )
        val ENDPOINT_EXAMPLE = Regex("""(?<![A-Za-z0-9_.-])/api/(?:[A-Za-z0-9._~-]+/?)*""")
    }
}

private interface InheritedMutationContract {
    @RequestMapping(method = [RequestMethod.POST])
    @DurableIdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    fun mutate()
}

private class InheritedController : InheritedMutationContract {
    override fun mutate() = Unit
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@RequestMapping(method = [RequestMethod.POST])
private annotation class ComposedPost

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@DurableIdempotentMutation(scope = IdempotencyScopeKind.TENANT)
private annotation class ComposedIdempotentMutation

private class ComposedController {
    @ComposedPost
    @ComposedIdempotentMutation
    fun mutate() = Unit
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class IdempotentMutation

private class FakeAnnotationController {
    @RequestMapping(method = [RequestMethod.POST])
    @IdempotentMutation
    fun mutate() = Unit
}
