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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Configures the shared public OpenAPI contract for every versioned REST endpoint. */
@Configuration(proxyBeanMethods = false)
class FoundationOpenApiConfiguration {
    /** Provides API metadata, bearer authentication, and reusable public API components. */
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(apiInfo())
            .components(components())

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
                ActiveOrganisationContextService.HEADER,
                "Signed active organisation context token.",
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
}
