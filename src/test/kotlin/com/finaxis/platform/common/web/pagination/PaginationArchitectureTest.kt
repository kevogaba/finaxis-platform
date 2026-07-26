package com.finaxis.platform.common.web.pagination

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.scanRestControllers
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.lang.reflect.ParameterizedType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod
import kotlin.test.assertTrue

class PaginationArchitectureTest {
    @Test
    fun `get listing endpoints do not return unbounded collections`() {
        val violations =
            scanRestControllers().flatMap(::collectionReturningGetEndpoints)

        assertTrue(
            violations.isEmpty(),
            "Listing endpoints must be paginated and must not return raw collections: " +
                violations.joinToString(),
        )
    }

    @Test
    fun `paginated get endpoints declare page and size request parameters`() {
        val violations = scanRestControllers().flatMap(::apiPageWithoutPageAndSizeParameters)

        assertTrue(
            violations.isEmpty(),
            "ApiPage endpoints must declare page and size request parameters: " +
                violations.joinToString(),
        )
    }

    private fun collectionReturningGetEndpoints(type: Class<*>): List<String> =
        type.kotlin
            .declaredFunctions
            .filter { function -> function.findAnnotation<GetMapping>() != null }
            .filter { function -> returnsCollection(function.javaMethod?.genericReturnType) }
            .map { function -> "${type.name}.${function.name}" }

    private fun apiPageWithoutPageAndSizeParameters(type: Class<*>): List<String> =
        type.kotlin
            .declaredFunctions
            .filter { function -> function.findAnnotation<GetMapping>() != null }
            .filter { function -> returnsApiPage(function.javaMethod?.genericReturnType) }
            .filterNot(::declaresPageAndSizeRequestParameters)
            .map { function -> "${type.name}.${function.name}" }

    private fun declaresPageAndSizeRequestParameters(
        function: kotlin.reflect.KFunction<*>,
    ): Boolean {
        val requestParameterNames =
            function.parameters
                .filter { parameter -> parameter.findAnnotation<RequestParam>() != null }
                .mapNotNull { parameter -> parameter.name }

        return requestParameterNames.containsAll(PAGINATION_PARAMETER_NAMES)
    }

    private fun returnsApiPage(returnType: java.lang.reflect.Type?): Boolean =
        when (returnType) {
            is Class<*> -> returnType == ApiPage::class.java
            is ParameterizedType -> returnType.rawType == ApiPage::class.java
            else -> false
        }

    private fun returnsCollection(returnType: java.lang.reflect.Type?): Boolean =
        when (returnType) {
            is Class<*> -> {
                Collection::class.java.isAssignableFrom(returnType)
            }

            is ParameterizedType -> {
                (returnType.rawType as? Class<*>)
                    ?.let { Collection::class.java.isAssignableFrom(it) }
                    ?: false
            }

            else -> {
                false
            }
        }

    private companion object {
        val PAGINATION_PARAMETER_NAMES = setOf("page", "size")
    }
}
