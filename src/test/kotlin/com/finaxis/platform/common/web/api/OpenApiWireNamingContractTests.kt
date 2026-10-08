package com.finaxis.platform.common.web.api

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.web.scanRestControllers
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.CreateRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.InviteUserRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateOrUpdateTenantSettingRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.UpdateBranchRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Proves the published OpenAPI document describes the wire format [ApiJsonCodec] really speaks:
 * snake_case property names, typed list items, and documented sort values.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiWireNamingContractTests
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
                    .andReturn()
                    .response.contentAsString
            apiJsonCodec.mapper.readTree(body)
        }
        private val schemas: JsonNode get() = document.path("components").path("schemas")

        @Test
        fun `every schema property is snake_case`() {
            val offenders =
                schemas.properties().flatMap { (schemaName, schema) ->
                    schema
                        .path("properties")
                        .propertyNames()
                        .filter { it !in ALLOWED_NON_SNAKE_CASE && !SNAKE_CASE.matches(it) }
                        .map { "$schemaName.$it" }
                }
            assertThat(offenders).describedAs("non snake_case schema properties").isEmpty()
            assertThat(schemas.size()).isPositive
        }

        @Test
        fun `required lists name existing snake_case properties`() {
            val offenders =
                schemas.properties().flatMap { (schemaName, schema) ->
                    schema
                        .path("required")
                        .toList()
                        .map { it.asString() }
                        .filter { !schema.path("properties").has(it) }
                        .map { "$schemaName.$it" }
                }
            assertThat(offenders).describedAs("required names without a property").isEmpty()
        }

        @Test
        fun `every list endpoint types its items with a named schema`() {
            val listRoutes = listEndpointRoutes()
            assertThat(listRoutes).isNotEmpty

            val untyped =
                listRoutes.filter { (path, method) ->
                    val schema = responseSchema(path, method)
                    val items = resolve(schema).path("properties").path("items")
                    val reference = items.path("items").path("\$ref").asString()
                    !reference.startsWith(SCHEMA_REF_PREFIX) ||
                        !schemas.has(reference.removePrefix(SCHEMA_REF_PREFIX))
                }
            assertThat(untyped).describedAs("list endpoints with untyped items").isEmpty()
        }

        @Test
        fun `list item response schemas are published`() {
            assertThat(schemas.propertyNames()).containsAll(LIST_ITEM_SCHEMAS)
        }

        @Test
        fun `permission catalogue schemas publish kind, grant scope and required views`() {
            listOf("PermissionSummaryResponse", "PermissionDetailResponse").forEach { name ->
                val properties = schemas.path(name).path("properties")

                assertThat(
                    properties
                        .path("kind")
                        .path("enum")
                        .toList()
                        .map { it.asString() },
                ).describedAs("%s.kind", name)
                    .containsExactly("VIEW", "MUTATION", "CONTEXT")
                assertThat(
                    properties
                        .path("grant_scope")
                        .path("enum")
                        .toList()
                        .map { it.asString() },
                ).describedAs("%s.grant_scope", name)
                    .containsExactly("TENANT", "PLATFORM")
                val required = properties.path("required_view_permissions")
                assertThat(required.path("type").asString())
                    .describedAs("%s.required_view_permissions", name)
                    .isEqualTo("array")
                assertThat(required.path("items").path("type").asString()).isEqualTo("string")
            }
        }

        @Test
        fun `audit schemas mark only the three older names deprecated`() {
            listOf("AuditEventSummaryResponse", "AuditEventDetailResponse").forEach { name ->
                val properties = schemas.path(name).path("properties")

                listOf("resource_type", "resource_id", "actor_id").forEach { alias ->
                    assertThat(properties.path(alias).path("deprecated").asBoolean())
                        .describedAs("%s.%s deprecated", name, alias)
                        .isTrue()
                }
                listOf("entity_type", "entity_id", "actor_user_id", "event_type").forEach {
                    assertThat(properties.path(it).path("deprecated").asBoolean())
                        .describedAs("%s.%s deprecated", name, it)
                        .isFalse()
                }
            }
        }

        @Test
        fun `sort_by documents the accepted camelCase values per list endpoint`() {
            SORT_BY_VALUES.forEach { (path, expected) ->
                val sortBy = queryParameter(path, "sort_by")
                assertThat(
                    sortBy
                        .path("schema")
                        .path("enum")
                        .toList()
                        .map { it.asString() },
                ).describedAs("GET %s sort_by enum", path)
                    .containsExactlyElementsOf(expected)
            }
            IGNORED_SORT_PATHS.forEach { path ->
                assertThat(queryParameterNames(path))
                    .describedAs("GET %s ignores sorting, so it must not advertise it", path)
                    .doesNotContain("sort_by", "sort_dir")
            }
        }

        @Test
        fun `sort_dir documents ASC and DESC on every list endpoint that sorts`() {
            SORT_BY_VALUES.keys.forEach { path ->
                assertThat(
                    queryParameter(
                        path,
                        "sort_dir",
                    ).path("schema").path("enum").toList().map {
                        it.asString()
                    },
                ).describedAs("GET %s sort_dir enum", path)
                    .containsExactly("ASC", "DESC")
            }
        }

        @Test
        fun `request bodies built from the published property names are accepted by the codec`() {
            REQUEST_BODIES.forEach { (schemaName, type) ->
                val example = exampleFor(schemaName)
                assertThat(example.size()).describedAs(schemaName).isPositive
                val parsed = apiJsonCodec.mapper.readValue(example.toString(), type)
                assertThat(parsed).describedAs(schemaName).isNotNull
            }
        }

        private fun listEndpointRoutes(): List<Pair<String, String>> {
            val controllers = scanRestControllers().toSet()
            val patternsToMethods =
                handlerMapping.handlerMethods
                    .filter { (_, handler) -> handler.beanType in controllers }
                    .filter { (_, handler) -> handler.method.returnType == ApiPage::class.java }
                    .flatMap { (mapping, _) ->
                        mapping.pathPatternsCondition?.patternValues.orEmpty().flatMap { path ->
                            mapping.methodsCondition.methods.map { path to it.name.lowercase() }
                        }
                    }
            return patternsToMethods
        }

        private fun responseSchema(
            path: String,
            method: String,
        ): JsonNode =
            document
                .path("paths")
                .path(path)
                .path(method)
                .path("responses")
                .path("200")
                .path("content")
                .toList()
                .first()
                .path("schema")

        private fun resolve(schema: JsonNode): JsonNode {
            val reference = schema.path("\$ref").asString()
            return if (reference.startsWith(SCHEMA_REF_PREFIX)) {
                schemas.path(reference.removePrefix(SCHEMA_REF_PREFIX))
            } else {
                schema
            }
        }

        private fun queryParameter(
            path: String,
            name: String,
        ): JsonNode =
            document
                .path("paths")
                .path(path)
                .path("get")
                .path("parameters")
                .firstOrNull {
                    it.path("name").asString() == name &&
                        it.path("in").asString() == "query"
                }
                ?: error("GET $path has no $name query parameter")

        private fun queryParameterNames(path: String): List<String> =
            document
                .path("paths")
                .path(path)
                .path("get")
                .path("parameters")
                .toList()
                .filter { it.path("in").asString() == "query" }
                .map { it.path("name").asString() }

        private fun exampleFor(schemaName: String): ObjectNode {
            val example = JsonNodeFactory.instance.objectNode()
            schemas.path(schemaName).path("properties").properties().forEach { (name, property) ->
                example.set(name, sampleValue(property))
            }
            return example
        }

        private fun sampleValue(property: JsonNode): JsonNode {
            val factory = JsonNodeFactory.instance
            val type = property.path("type")
            val kind =
                if (type.isArray) {
                    type
                        .toList()
                        .map {
                            it.asString()
                        }.first { it != "null" }
                } else {
                    type
                        .asString()
                }
            return when {
                property.has("enum") -> factory.stringNode(property.path("enum").first().asString())

                property.has("\$ref") -> factory.objectNode()

                kind == "boolean" -> factory.booleanNode(true)

                kind == "array" -> factory.arrayNode()

                kind == "object" -> factory.objectNode()

                kind == "integer" -> factory.numberNode(1)

                property
                    .path(
                        "format",
                    ).asString() == "uuid" -> factory.stringNode(UUID.randomUUID().toString())

                else -> factory.stringNode("SAMPLE")
            }
        }

        private companion object {
            const val SCHEMA_REF_PREFIX = "#/components/schemas/"
            val SNAKE_CASE = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

            // No exceptions today. Add a name here only with a comment saying which external
            // contract forces it; the codec itself has no exceptions.
            val ALLOWED_NON_SNAKE_CASE = emptySet<String>()

            val LIST_ITEM_SCHEMAS =
                listOf(
                    "MembershipSummaryResponse",
                    "BranchSummaryResponse",
                    "RoleSummaryResponse",
                    "TenantSummaryResponse",
                    "PermissionSummaryResponse",
                    "AuditEventSummaryResponse",
                    "BusinessDateHistoryEntryResponse",
                    "AvailableOrganisationResponse",
                    "AvailableBranchResponse",
                )

            val BRANCH_SORTS =
                listOf("branchCode", "branchName", "branchType", "status", "createdAt")
            val SORT_BY_VALUES =
                mapOf(
                    "/api/v1/tenant/roles" to listOf("roleCode", "roleName", "status", "createdAt"),
                    "/api/v1/tenant/permissions" to
                        listOf(
                            "permissionCode",
                            "permissionName",
                            "riskLevel",
                            "status",
                            "createdAt",
                        ),
                    "/api/v1/platform/tenants" to
                        listOf("tenantCode", "displayName", "countryCode", "createdAt"),
                    "/api/v1/branches" to BRANCH_SORTS,
                    "/api/v1/platform/tenants/{tenant_id}/branches" to BRANCH_SORTS,
                )

            // Accepted by the controller but never applied, so hidden from the spec.
            val IGNORED_SORT_PATHS =
                listOf("/api/v1/tenant/memberships", "/api/v1/tenant/branch-assignments")

            val REQUEST_BODIES: Map<String, Class<*>> =
                mapOf(
                    "CreateRoleRequest" to CreateRoleRequest::class.java,
                    "AssignRoleRequest" to AssignRoleRequest::class.java,
                    "InviteUserRequest" to InviteUserRequest::class.java,
                    "CreateBranchRequest" to CreateBranchRequest::class.java,
                    // Update bodies too (#151): a PATCH or PUT is where a generated client
                    // drifts unseen, since nothing is created for a reader to notice.
                    "UpdateBranchRequest" to UpdateBranchRequest::class.java,
                    "CreateOrUpdateTenantSettingRequest" to
                        CreateOrUpdateTenantSettingRequest::class.java,
                )
        }
    }
