package com.finaxis.platform.iam.config

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.web.api.ApiJsonCodec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode

/**
 * Pins the **generated** description of every `hasAnyAuthority` route to the any-of form (#223).
 * Such a route accepts any one of its mutation codes, each with its own views, so
 * [PermissionRequirementOpenApiConfiguration] must publish "Requires any one of ..." and never the
 * single-code "Requires `x` and its view ..." sentence, which read as if every listed code were
 * needed at once. The routes are found from the live handler mapping and the text is read from the
 * live `/v3/api-docs`, so a new either-or route is covered without editing this test.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PermissionRequirementOpenApiDocumentTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        @Qualifier("requestMappingHandlerMapping")
        private val handlerMapping: RequestMappingHandlerMapping,
    ) {
        private val document: JsonNode by lazy {
            val body =
                mockMvc
                    .get("/v3/api-docs")
                    .andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
            apiJsonCodec.mapper.readTree(body)
        }

        @Test
        fun `every hasAnyAuthority route publishes the any-of requirement`() {
            val routes = anyOfRoutes()
            // Two today (tenant and platform branch return); never vacuous.
            assertThat(routes).describedAs("hasAnyAuthority routes").hasSizeGreaterThanOrEqualTo(2)

            routes.forEach { route ->
                val operation =
                    document.path("paths").path(route.path).path(route.method)
                assertThat(operation.isMissingNode)
                    .describedAs("%s %s is in the document", route.method, route.path)
                    .isFalse
                listOf(
                    "description" to operation.path("description").asString(),
                    "403" to
                        operation
                            .path("responses")
                            .path("403")
                            .path("description")
                            .asString(),
                ).forEach { (where, text) ->
                    assertAnyOf(text, route.codes, "${route.method} ${route.path} $where")
                }
            }
        }

        private fun assertAnyOf(
            text: String,
            codes: List<String>,
            where: String,
        ) {
            val any = codes.joinToString(" or ") { "`$it`" }
            assertThat(text)
                .describedAs(where)
                .contains("Requires any one of $any, together with its view permissions")
            codes.forEach { code ->
                assertThat(text)
                    .describedAs(where)
                    .contains("`$code` needs ")
                    // The single-code sentence states that this one code is required.
                    .doesNotContain("Requires `$code` and its view")
            }
        }

        /** Every mapped route whose effective `@PreAuthorize` is a `hasAnyAuthority` gate. */
        private fun anyOfRoutes(): List<Route> =
            handlerMapping.handlerMethods.flatMap { (mapping, handler) ->
                val expression = gate(handler) ?: return@flatMap emptyList()
                if (!expression.contains("hasAnyAuthority")) return@flatMap emptyList()
                val codes = AUTHORITY.findAll(expression).map { it.groupValues[1] }.toList()
                mapping.pathPatternsCondition?.patternValues.orEmpty().flatMap { path ->
                    mapping.methodsCondition.methods.map { method ->
                        Route(path, method.name.lowercase(), codes)
                    }
                }
            }

        private fun gate(handler: HandlerMethod): String? =
            (
                AnnotatedElementUtils.findMergedAnnotation(
                    handler.method,
                    PreAuthorize::class.java,
                ) ?: AnnotatedElementUtils.findMergedAnnotation(
                    handler.beanType,
                    PreAuthorize::class.java,
                )
            )?.value

        private data class Route(
            val path: String,
            val method: String,
            val codes: List<String>,
        )

        private companion object {
            val AUTHORITY = Regex("'([a-z_.]+)'")
        }
    }
