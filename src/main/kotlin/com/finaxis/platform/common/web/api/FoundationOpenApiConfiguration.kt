package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.GlobalOperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod

/** Configures the shared public OpenAPI contract for every versioned REST endpoint. */
@Configuration(proxyBeanMethods = false)
class FoundationOpenApiConfiguration {
    /** Provides API metadata, bearer authentication, and reusable public API components. */
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(apiInfo())
            .components(components())

    /**
     * Documents the active organisation transport on every operation except the two that
     * establish it: organisation discovery and organisation selection. Every other operation,
     * including platform-administration routes and branch selection, resolves its caller from
     * the active organisation context and therefore needs it documented.
     */
    @Bean
    fun activeOrganisationContextOperationCustomizer(): GlobalOperationCustomizer =
        GlobalOperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.requiresActiveOrganisationContext()) {
                val parameters = operation.parameters ?: mutableListOf()
                if (parameters.none { it.`$ref` == ACTIVE_ORGANISATION_CONTEXT_PARAMETER_REF }) {
                    parameters.add(
                        Parameter().`$ref`(ACTIVE_ORGANISATION_CONTEXT_PARAMETER_REF),
                    )
                    operation.parameters = parameters
                }
            }
            operation
        }

    private fun apiInfo(): Info =
        Info()
            .title("Finaxis Platform API")
            .version("v1")
            .description("Business dates use the `dd-MM-yyyy` format, for example `25-07-2026`.")

    private fun components(): Components =
        Components()
            .addSecuritySchemes(
                "bearer-key",
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT"),
            ).addPublicSchema<ApiProblem>()
            .addPublicSchema<ApiViolation>()
            .addPublicSchema<ApiPage<*>>()
            .addPublicSchema<ApiPageMetadata>()
            .addSchemas(
                "BusinessDate",
                StringSchema()
                    .description("Business date formatted as dd-MM-yyyy.")
                    .example("25-07-2026"),
            ).addHeaderParameters()

    private fun Components.addHeaderParameters(): Components =
        addParameters(
            "RequestId",
            headerParameter(ApiProblemFactory.REQUEST_ID_HEADER, "Request correlation identifier."),
        ).addParameters(
            "IdempotencyKey",
            headerParameter(
                IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                "Optional UUID; the server generates one when omitted.",
                format = "uuid",
            ),
        ).addParameters(
            "IdempotencyReplayed",
            headerParameter(
                IdempotencyKeyFilter.IDEMPOTENCY_REPLAYED_HEADER,
                "True when the response was replayed from a completed mutation.",
                type = "boolean",
            ),
        ).addParameters(
            "ActiveOrganisationContext",
            headerParameter(
                name = ActiveOrganisationContextService.HEADER,
                description =
                    "Signed active organisation context token. Required unless the caller " +
                        "already has an established active-organisation session, for " +
                        "example a browser client that completed selection earlier in the " +
                        "same session.",
            ),
        ).addParameters(
            "RateLimitLimit",
            headerParameter("RateLimit-Limit", "Request limit for the current window.", "integer"),
        ).addParameters(
            "RateLimitRemaining",
            headerParameter(
                "RateLimit-Remaining",
                "Requests remaining in the current window.",
                "integer",
            ),
        ).addParameters(
            "RateLimitReset",
            headerParameter(
                "RateLimit-Reset",
                "UTC timestamp at which the current limit resets.",
                format = "date-time",
            ),
        ).addParameters(
            "RetryAfter",
            headerParameter("Retry-After", "Seconds to wait before retrying.", "integer"),
        )

    private inline fun <reified T> Components.addPublicSchema(): Components {
        val resolved =
            ModelConverters
                .getInstance()
                .resolveAsResolvedSchema(AnnotatedType(T::class.java).resolveAsRef(false))
        requireNotNull(resolved.referencedSchemas)[T::class.simpleName]
            ?: error("Swagger could not resolve the ${T::class.simpleName} OpenAPI schema.")
        resolved.referencedSchemas.forEach(::addSchemas)
        return this
    }

    private fun headerParameter(
        name: String,
        description: String,
        type: String = "string",
        format: String? = null,
    ): Parameter =
        Parameter()
            .name(name)
            .description(description)
            .`in`("header")
            .schema(Schema<Any>().type(type).format(format))

    private fun HandlerMethod.requiresActiveOrganisationContext(): Boolean =
        !(beanType.simpleName == "AuthController" && method.name in CONTEXT_FREE_AUTH_OPERATIONS)

    private companion object {
        const val ACTIVE_ORGANISATION_CONTEXT_PARAMETER_REF =
            "#/components/parameters/ActiveOrganisationContext"

        // AuthController.availableOrganisations() and .selectOrganisation() are the only
        // operations that run before an active organisation context exists. Every other
        // operation, including AuthController.selectBranch()/.availableBranches() and every
        // platform-administration controller, resolves its caller from that context.
        val CONTEXT_FREE_AUTH_OPERATIONS = setOf("availableOrganisations", "selectOrganisation")
    }
}
