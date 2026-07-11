package com.finaxis.platform.common.web.pagination

import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.ParameterizedType
import java.util.Optional
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod
import kotlin.test.assertTrue

class PaginationArchitectureTest {
    @Test
    fun `get listing endpoints do not return unbounded collections`() {
        val violations =
            listOf(
                "com.finaxis.platform.iam.adapter.inbound.web.AuthController",
                "com.finaxis.platform.iam.adapter.inbound.web.UserProfileController",
            ).flatMap(::collectionReturningGetEndpoints)

        assertTrue(
            violations.isEmpty(),
            "Listing endpoints must be paginated and must not return raw collections: " +
                violations.joinToString(),
        )
    }

    private fun collectionReturningGetEndpoints(className: String): List<String> {
        val type = Class.forName(className).kotlin
        if (type.findAnnotation<RestController>() == null) {
            return emptyList()
        }
        return type.declaredFunctions
            .filter { function -> function.findAnnotation<GetMapping>() != null }
            .filter { function -> returnsCollection(function.javaMethod?.genericReturnType) }
            .map { function -> "$className.${function.name}" }
    }

    private fun returnsCollection(returnType: java.lang.reflect.Type?): Boolean =
        when (returnType) {
            is Class<*> -> {
                Collection::class.java.isAssignableFrom(returnType)
            }

            is ParameterizedType -> {
                (returnType.rawType as? Class<*>)
                    ?.let {
                        Collection::class.java.isAssignableFrom(it) ||
                            Optional::class.java == it
                    }
                    ?: false
            }

            else -> {
                false
            }
        }
}
