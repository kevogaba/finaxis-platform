package com.finaxis.platform.iam.config

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.scanRestControllers
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.port.outbound.PermissionViewRequirementQueries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.http.HttpMethod
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode

/**
 * ADR 0030: every mutation route of the generated OpenAPI document says, in its `403` response,
 * which permission it needs and which view permissions the catalogue pairs with it.
 *
 * The expectation is computed, not typed: the codes come from each route's real `@PreAuthorize`
 * (on the method or its controller) and the views from `permission_view_requirement`, the rows the
 * guard enforces. A route added tomorrow is therefore held to the rule with no edit here, and a
 * mutation route that names no catalogue mutation code must be one of the four listed below, whose
 * authority is checked per key or per context in the service.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class MutationRouteForbiddenContractTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val requirements: PermissionViewRequirementQueries,
        @Qualifier("requestMappingHandlerMapping")
        private val handlerMapping: RequestMappingHandlerMapping,
    ) {
        @Test
        fun `every mutation route lists its code and its views in its 403 response`() {
            val pairing = requirements.requiredViewCodesByPermission()
            val paths = document().path("paths")
            val checked = mutableListOf<String>()
            val withoutCatalogueGate = mutableSetOf<Route>()

            mutationHandlers().forEach { (route, handler) ->
                val operation = paths.path(route.path).path(route.method.name().lowercase())
                assertThat(
                    operation.isMissingNode,
                ).withFailMessage("$route is not documented").isFalse
                val codes = gateCodes(handler).filter { it in pairing }
                if (codes.isEmpty()) {
                    withoutCatalogueGate += route
                    return@forEach
                }
                val forbidden =
                    operation
                        .path(
                            "responses",
                        ).path("403")
                        .path("description")
                        .asString()
                codes.forEach { code ->
                    assertThat(forbidden)
                        .withFailMessage("$route 403 does not name `$code`: $forbidden")
                        .contains("`$code`")
                    pairing.getValue(code).forEach { view ->
                        assertThat(forbidden)
                            .withFailMessage(
                                "$route 403 does not name its view `$view`: $forbidden",
                            ).contains("`$view`")
                    }
                }
                assertThat(operation.path("description").asString())
                    .withFailMessage("$route description does not name `${codes.first()}`")
                    .contains("`${codes.first()}`")
                checked += route.toString()
            }

            assertThat(
                checked.size,
            ).withFailMessage("only $checked").isGreaterThanOrEqualTo(MIN_ROUTES)
            assertThat(withoutCatalogueGate).containsExactlyInAnyOrderElementsOf(UNGATED_ROUTES)
        }

        @Test
        fun `the platform tenant routes name tenant view in their 403 response`() {
            // The review of the platform tenant decisions found their hand-written 403 text never
            // mentioned the view; pinned on its own so the family cannot regress unnoticed.
            val paths = document().path("paths")
            val decisions =
                listOf("approve", "reject", "return", "suspend", "reactivate", "deprovision")

            decisions.forEach { decision ->
                val forbidden =
                    paths
                        .path("${ApiPaths.PLATFORM_TENANTS}/{tenant_id}/$decision")
                        .path("post")
                        .path("responses")
                        .path("403")
                        .path("description")
                        .asString()
                assertThat(
                    forbidden,
                ).withFailMessage("$decision: $forbidden").contains("`tenant.view`")
            }
        }

        private fun document(): JsonNode =
            apiJsonCodec.mapper.readTree(
                mockMvc
                    .get("/v3/api-docs")
                    .andReturn()
                    .response.contentAsString,
            )

        private fun mutationHandlers(): Map<Route, HandlerMethod> {
            val controllers = scanRestControllers().toSet()
            return handlerMapping.handlerMethods
                .filterValues { it.beanType in controllers }
                .flatMap { (mapping, handler) ->
                    mapping.pathPatternsCondition?.patternValues.orEmpty().flatMap { path ->
                        mapping.methodsCondition.methods
                            .map { HttpMethod.valueOf(it.name) }
                            .filter { it in MUTATION_METHODS }
                            .map { Route(path, it) to handler }
                    }
                }.toMap()
        }

        private fun gateCodes(handler: HandlerMethod): List<String> =
            (
                AnnotatedElementUtils.findMergedAnnotation(handler.method, PreAuthorize::class.java)
                    ?: AnnotatedElementUtils.findMergedAnnotation(
                        handler.beanType,
                        PreAuthorize::class.java,
                    )
            )?.value
                ?.let { AUTHORITY.findAll(it).map { match -> match.groupValues[1] }.toList() }
                .orEmpty()

        private data class Route(
            val path: String,
            val method: HttpMethod,
        ) {
            override fun toString(): String = "${method.name()} $path"
        }

        private companion object {
            const val MIN_ROUTES = 45
            val AUTHORITY = Regex("'([a-z_.]+)'")
            val MUTATION_METHODS =
                setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)

            /** Context selection and per-key tenant settings: authorised in the service. */
            val UNGATED_ROUTES =
                setOf(
                    Route("${ApiPaths.AUTH}/select-organisation", HttpMethod.POST),
                    Route("${ApiPaths.AUTH}/select-branch", HttpMethod.POST),
                    Route("${ApiPaths.TENANT_SETTINGS}/{key}", HttpMethod.PUT),
                    Route("${ApiPaths.TENANT_SETTINGS}/{key}", HttpMethod.DELETE),
                )
        }
    }
