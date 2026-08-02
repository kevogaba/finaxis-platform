package com.finaxis.platform.common.web.api

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.scanRestControllers
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class FoundationOpenApiContractTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        @Qualifier("requestMappingHandlerMapping")
        private val handlerMapping: RequestMappingHandlerMapping,
    ) {
        @Test
        fun `generated OpenAPI documents every real controller operation`() {
            val response = mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }.andReturn()
            val document: JsonNode = apiJsonCodec.mapper.readTree(response.response.contentAsString)
            val components = document.requiredObject("components")
            val controllerOperations = controllerOperations()
            val documentedRoutes = mutableSetOf<OperationRoute>()

            assertDocumentedOperations(
                document.requiredObject("paths"),
                components,
                controllerOperations,
                documentedRoutes,
            )
            assertThat(documentedRoutes).containsAll(controllerOperations.keys)
            assertSharedComponents(components)
        }

        private fun assertDocumentedOperations(
            paths: JsonNode,
            components: JsonNode,
            controllerOperations: Map<OperationRoute, HandlerMethod>,
            documentedRoutes: MutableSet<OperationRoute>,
        ) {
            paths.properties().forEach { (path, pathItem) ->
                HTTP_METHODS.forEach { method ->
                    val operation = pathItem.path(method.toString().lowercase())
                    if (operation.isMissingNode) return@forEach

                    val route = OperationRoute(path, method)
                    val handler = controllerOperations[route] ?: return@forEach
                    documentedRoutes += route
                    assertOperationContract(path, method, operation)
                    assertOperationSpecificContract(
                        path,
                        method,
                        pathItem,
                        operation,
                        components,
                        handler,
                    )
                }
            }
        }

        private fun assertOperationContract(
            path: String,
            method: HttpMethod,
            operation: JsonNode,
        ) {
            assertThat(operation.path("operationId").asString())
                .withFailMessage("%s %s operationId", method, path)
                .isNotBlank
            assertThat(operation.path("summary").asString())
                .withFailMessage("%s %s summary", method, path)
                .isNotBlank
            assertThat(
                operation.path("tags").size(),
            ).withFailMessage("%s %s tags", method, path).isPositive
            assertThat(operation.hasBearerKeySecurity())
                .withFailMessage("%s %s bearer-key security", method, path)
                .isTrue
            assertThat(operation.hasSuccessResponse())
                .withFailMessage("%s %s success response", method, path)
                .isTrue
        }

        private fun assertOperationSpecificContract(
            path: String,
            method: HttpMethod,
            pathItem: JsonNode,
            operation: JsonNode,
            components: JsonNode,
            handler: HandlerMethod,
        ) {
            if (method in MUTATION_METHODS) {
                assertThat(handler.hasMethodAnnotation(IdempotentMutation::class.java))
                    .withFailMessage("%s %s IdempotentMutation annotation", method, path)
                    .isTrue
                assertThat(operation.hasHeaderParameter(pathItem, components, "Idempotency-Key"))
                    .withFailMessage("%s %s Idempotency-Key parameter", method, path)
                    .isTrue
            }
            if (requiresActiveOrganisationContext(path, method)) {
                assertThat(
                    operation.hasHeaderParameter(
                        pathItem,
                        components,
                        ActiveOrganisationContextService.HEADER,
                    ),
                ).withFailMessage("%s %s active organisation context parameter", method, path)
                    .isTrue
                assertThat(
                    operation.hasRequiredHeaderParameter(
                        pathItem,
                        components,
                        ActiveOrganisationContextService.HEADER,
                    ),
                ).withFailMessage(
                    "%s %s active organisation context must stay optional because an " +
                        "established session also satisfies it",
                    method,
                    path,
                ).isFalse
            }
            if (method == HttpMethod.GET && handler.method.returnType == ApiPage::class.java) {
                assertThat(operation.hasPaginationResponse(components))
                    .withFailMessage("%s %s ApiPage response", method, path)
                    .isTrue
            }
        }

        private fun assertSharedComponents(components: JsonNode) {
            assertThat(
                components
                    .path("securitySchemes")
                    .path("bearer-key")
                    .path("type")
                    .asString(),
            ).isEqualTo("http")
            assertThat(
                components
                    .path("securitySchemes")
                    .path("bearer-key")
                    .path("scheme")
                    .asString(),
            ).isEqualTo("bearer")
            assertThat(components.path("schemas").has("ApiProblem")).isTrue
        }

        private fun controllerOperations(): Map<OperationRoute, HandlerMethod> {
            val controllerTypes = scanRestControllers().toSet()
            return handlerMapping.handlerMethods
                .flatMap { (mapping, handler) ->
                    if (handler.beanType !in controllerTypes) {
                        emptyList()
                    } else {
                        mapping.pathPatternsCondition?.patternValues.orEmpty().flatMap { path ->
                            mapping.methodsCondition.methods.map { method ->
                                OperationRoute(path, HttpMethod.valueOf(method.name)) to handler
                            }
                        }
                    }
                }.toMap()
        }

        private fun JsonNode.requiredObject(name: String): JsonNode =
            path(name).also { node -> assertThat(node.isObject).withFailMessage(name).isTrue }

        private fun JsonNode.hasBearerKeySecurity(): Boolean =
            path("security").any { requirement -> requirement.has("bearer-key") }

        private fun JsonNode.hasSuccessResponse(): Boolean =
            path("responses").propertyNames().any { status -> status.startsWith('2') }

        private fun JsonNode.hasHeaderParameter(
            pathItem: JsonNode,
            components: JsonNode,
            headerName: String,
        ): Boolean =
            (path("parameters").toList() + pathItem.path("parameters").toList()).any { parameter ->
                parameter.resolveParameter(components).let { resolved ->
                    resolved.path("name").asString() == headerName &&
                        resolved.path("in").asString() == "header"
                }
            }

        private fun JsonNode.hasRequiredHeaderParameter(
            pathItem: JsonNode,
            components: JsonNode,
            headerName: String,
        ): Boolean =
            (path("parameters").toList() + pathItem.path("parameters").toList()).any { parameter ->
                parameter.resolveParameter(components).let { resolved ->
                    resolved.path("name").asString() == headerName &&
                        resolved.path("in").asString() == "header" &&
                        resolved.path("required").asBoolean()
                }
            }

        private fun requiresActiveOrganisationContext(
            path: String,
            method: HttpMethod,
        ): Boolean = OperationRoute(path, method) !in CONTEXT_FREE_OPERATIONS

        private fun JsonNode.resolveParameter(components: JsonNode): JsonNode =
            path("\$ref")
                .asString()
                .takeIf { reference -> reference.startsWith("#/components/parameters/") }
                ?.let { reference ->
                    components.path("parameters").path(reference.substringAfterLast('/'))
                }
                ?: this

        private fun JsonNode.hasPaginationResponse(components: JsonNode): Boolean =
            path("responses")
                .path("200")
                .path("content")
                .toList()
                .map { content -> content.path("schema") }
                .any { schema -> schema.hasPaginationShape(components.path("schemas"), emptySet()) }

        private fun JsonNode.hasPaginationShape(
            schemas: JsonNode,
            visitedReferences: Set<String>,
        ): Boolean {
            if (path("properties").has("items") && path("properties").has("page")) return true
            val reference = path("\$ref").asString()
            if (reference.startsWith("#/components/schemas/") && reference !in visitedReferences) {
                return schemas
                    .path(reference.substringAfterLast('/'))
                    .hasPaginationShape(schemas, visitedReferences + reference)
            }
            return false
        }

        private data class OperationRoute(
            val path: String,
            val method: HttpMethod,
        )

        private companion object {
            val HTTP_METHODS =
                setOf(
                    HttpMethod.GET,
                    HttpMethod.POST,
                    HttpMethod.PUT,
                    HttpMethod.PATCH,
                    HttpMethod.DELETE,
                )
            val MUTATION_METHODS = HTTP_METHODS - HttpMethod.GET

            // The only operations that run before an active organisation context exists.
            // Every other documented operation, including platform-administration routes and
            // branch selection, must document the header (see FoundationOpenApiConfiguration).
            val CONTEXT_FREE_OPERATIONS =
                setOf(
                    OperationRoute("${ApiPaths.AUTH}/organisations", HttpMethod.GET),
                    OperationRoute("${ApiPaths.AUTH}/select-organisation", HttpMethod.POST),
                )
        }
    }
