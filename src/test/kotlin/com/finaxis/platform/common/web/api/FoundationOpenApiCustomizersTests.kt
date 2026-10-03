package com.finaxis.platform.common.web.api

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.method.HandlerMethod

/**
 * Unit tests for the schema and parameter customizers in [FoundationOpenApiConfiguration]. They
 * run on hand-built swagger models, without a Spring context.
 */
class FoundationOpenApiCustomizersTests {
    private val configuration = FoundationOpenApiConfiguration()
    private val wireNaming = configuration.wireNamingOpenApiCustomizer(ApiJsonCodec())
    private val sortDirection = configuration.sortDirectionOperationCustomizer()

    @Test
    fun `camelCase properties become snake_case and required follows them`() {
        val schema =
            objectSchema("firstName" to StringSchema(), "createdAt" to StringSchema())
                .apply { required = listOf("firstName", "createdAt") }

        customise(schema)

        assertThat(schema.properties.keys).containsExactly("first_name", "created_at")
        // swagger keeps `required` sorted, so only membership is asserted
        assertThat(schema.required).containsExactlyInAnyOrder("first_name", "created_at")
    }

    @Test
    fun `renaming keeps property order and the property schemas themselves`() {
        val first = StringSchema().description("first")
        val second = StringSchema().description("second")
        val schema = objectSchema("zebraCode" to first, "alphaCode" to second)

        customise(schema)

        assertThat(schema.properties.entries.map { it.key to it.value })
            .containsExactly("zebra_code" to first, "alpha_code" to second)
    }

    @Test
    fun `a schema without required keeps required unset`() {
        val schema = objectSchema("branchCode" to StringSchema())

        customise(schema)

        assertThat(schema.properties.keys).containsExactly("branch_code")
        assertThat(schema.required).isNull()
    }

    @Test
    fun `already snake_case names are unchanged and the customizer is idempotent`() {
        val schema =
            objectSchema("branch_code" to StringSchema(), "createdAt" to StringSchema())
                .apply { required = listOf("branch_code", "createdAt") }
        val openApi = openApiWith("Branch" to schema)

        wireNaming.customise(openApi)
        val afterFirst = schema.properties.keys.toList() to schema.required.toList()
        wireNaming.customise(openApi)

        assertThat(afterFirst.first).containsExactly("branch_code", "created_at")
        assertThat(afterFirst.second).containsExactlyInAnyOrder("branch_code", "created_at")
        assertThat(schema.properties.keys).containsExactlyElementsOf(afterFirst.first)
        assertThat(schema.required).containsExactlyInAnyOrderElementsOf(afterFirst.second)
    }

    @Test
    fun `nested object properties are renamed recursively`() {
        val inner =
            objectSchema("streetLine" to StringSchema())
                .apply { required = listOf("streetLine") }
        val schema = objectSchema("homeAddress" to inner)

        customise(schema)

        assertThat(schema.properties.keys).containsExactly("home_address")
        assertThat(inner.properties.keys).containsExactly("street_line")
        assertThat(inner.required).containsExactly("street_line")
    }

    @Test
    fun `array items are renamed recursively`() {
        val item =
            objectSchema("roleCode" to StringSchema()).apply { required = listOf("roleCode") }
        val schema = objectSchema("roleItems" to ArraySchema().items(item))

        customise(schema)

        assertThat(schema.properties.keys).containsExactly("role_items")
        assertThat(item.properties.keys).containsExactly("role_code")
        assertThat(item.required).containsExactly("role_code")
    }

    @Test
    fun `a top level array schema renames its items`() {
        val item = objectSchema("roleCode" to StringSchema())
        val array = ArraySchema().items(item)

        customise(array)

        assertThat(item.properties.keys).containsExactly("role_code")
    }

    @Test
    fun `additionalProperties schemas are renamed recursively`() {
        val value =
            objectSchema("limitAmount" to StringSchema()).apply { required = listOf("limitAmount") }
        val schema = objectSchema("limitsByCurrency" to ObjectSchema().additionalProperties(value))

        customise(schema)

        assertThat(schema.properties.keys).containsExactly("limits_by_currency")
        assertThat(value.properties.keys).containsExactly("limit_amount")
        assertThat(value.required).containsExactly("limit_amount")
    }

    @Test
    fun `a boolean additionalProperties value is left alone`() {
        val schema =
            objectSchema(
                "someFlag" to StringSchema(),
            ).apply { additionalProperties = true }

        customise(schema)

        assertThat(schema.properties.keys).containsExactly("some_flag")
        assertThat(schema.additionalProperties).isEqualTo(true)
    }

    @Test
    fun `allOf anyOf and oneOf members are renamed recursively`() {
        val all = objectSchema("allField" to StringSchema()).apply { required = listOf("allField") }
        val any = objectSchema("anyField" to StringSchema())
        val one = objectSchema("oneField" to StringSchema())
        val schema =
            Schema<Any>().apply {
                allOf = listOf(all)
                anyOf = listOf(any)
                oneOf = listOf(one)
            }

        customise(schema)

        assertThat(all.properties.keys).containsExactly("all_field")
        assertThat(all.required).containsExactly("all_field")
        assertThat(any.properties.keys).containsExactly("any_field")
        assertThat(one.properties.keys).containsExactly("one_field")
    }

    @Test
    fun `enum values are not renamed`() {
        val status = StringSchema().apply { enum = listOf("pendingApproval", "inactiveSince") }
        val schema = objectSchema("accountStatus" to status)

        customise(schema)

        assertThat(schema.properties.keys).containsExactly("account_status")
        assertThat(status.enum).containsExactly("pendingApproval", "inactiveSince")
    }

    @Test
    fun `request body and response schemas of operations are renamed`() {
        val request = objectSchema("requestField" to StringSchema())
        val response = objectSchema("responseField" to StringSchema())
        val operation =
            Operation()
                .requestBody(RequestBody().content(jsonContent(request)))
                .responses(ApiResponses().addApiResponse("200", responseWith(response)))
        val openApi =
            OpenAPI().paths(Paths().addPathItem("/api/v1/things", PathItem().post(operation)))

        wireNaming.customise(openApi)

        assertThat(request.properties.keys).containsExactly("request_field")
        assertThat(response.properties.keys).containsExactly("response_field")
    }

    @Test
    fun `operation parameter names are not renamed`() {
        val parameter = Parameter().name("sortBy").`in`("query").schema(StringSchema())
        val operation = Operation().parameters(mutableListOf(parameter))
        val openApi =
            OpenAPI()
                .components(Components())
                .paths(Paths().addPathItem("/api/v1/things", PathItem().get(operation)))

        wireNaming.customise(openApi)

        assertThat(parameter.name).isEqualTo("sortBy")
    }

    @Test
    fun `a document without components or paths is accepted`() {
        wireNaming.customise(OpenAPI())
    }

    @Test
    fun `sort_dir gains the ASC and DESC enum and a description`() {
        val parameter = Parameter().name("sort_dir").`in`("query").schema(StringSchema())
        val operation = Operation().parameters(mutableListOf(parameter))

        val result = sortDirection.customize(operation, handlerMethod())

        assertThat(result).isSameAs(operation)
        assertThat(parameter.description).isEqualTo("Sort direction. Case-insensitive.")
        assertThat(parameter.schema.enum).containsExactly("ASC", "DESC")
    }

    @Test
    fun `sort_dir enum replaces whatever schema the parameter had`() {
        val parameter =
            Parameter().name("sort_dir").`in`("query").schema(StringSchema().example("down"))
        val operation = Operation().parameters(mutableListOf(parameter))

        sortDirection.customize(operation, handlerMethod())

        assertThat(parameter.schema.example).isNull()
        assertThat(parameter.schema.enum).containsExactly("ASC", "DESC")
    }

    @Test
    fun `an operation without sort_dir gets none`() {
        val limit = Parameter().name("limit").`in`("query")
        val operation = Operation().parameters(mutableListOf(limit))

        sortDirection.customize(operation, handlerMethod())

        assertThat(operation.parameters.map { it.name }).containsExactly("limit")
    }

    @Test
    fun `an operation without parameters is returned untouched`() {
        val operation = Operation()

        val result = sortDirection.customize(operation, handlerMethod())

        assertThat(result).isSameAs(operation)
        assertThat(operation.parameters).isNull()
    }

    @Test
    fun `a ref parameter is left untouched`() {
        val reference = Parameter().`$ref`("#/components/parameters/SortDir")
        val operation = Operation().parameters(mutableListOf(reference))

        sortDirection.customize(operation, handlerMethod())

        assertThat(reference.description).isNull()
        assertThat(reference.schema).isNull()
    }

    @Test
    fun `a ref parameter named sort_dir is left untouched`() {
        val reference =
            Parameter().name("sort_dir").`$ref`("#/components/parameters/SortDir")
        val operation = Operation().parameters(mutableListOf(reference))

        sortDirection.customize(operation, handlerMethod())

        assertThat(reference.description).isNull()
        assertThat(reference.schema).isNull()
    }

    @Test
    fun `other parameters next to sort_dir are not touched`() {
        val sortBy =
            Parameter()
                .name(
                    "sort_by",
                ).`in`("query")
                .description("Sort field.")
                .schema(StringSchema())
        val sortDir = Parameter().name("sort_dir").`in`("query")
        val operation = Operation().parameters(mutableListOf(sortBy, sortDir))

        sortDirection.customize(operation, handlerMethod())

        assertThat(sortBy.description).isEqualTo("Sort field.")
        assertThat(sortBy.schema.enum).isNull()
        assertThat(sortDir.schema.enum).containsExactly("ASC", "DESC")
    }

    private fun customise(schema: Schema<*>) {
        wireNaming.customise(openApiWith("Subject" to schema))
    }

    private fun openApiWith(vararg schemas: Pair<String, Schema<*>>): OpenAPI =
        OpenAPI().components(
            Components().apply { schemas.forEach { addSchemas(it.first, it.second) } },
        )

    private fun objectSchema(vararg properties: Pair<String, Schema<*>>): Schema<Any> =
        Schema<Any>().apply {
            type = "object"
            properties.forEach { addProperty(it.first, it.second) }
        }

    private fun jsonContent(schema: Schema<*>): Content =
        Content().addMediaType("application/json", MediaType().schema(schema))

    private fun responseWith(schema: Schema<*>): ApiResponse =
        ApiResponse().content(jsonContent(schema))

    private fun handlerMethod(): HandlerMethod =
        HandlerMethod(this, FoundationOpenApiCustomizersTests::class.java.getMethod("toString"))
}
