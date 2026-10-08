package com.finaxis.platform.iam.config

import com.finaxis.platform.iam.application.port.outbound.PermissionViewRequirementQueries
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.GlobalOperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.access.prepost.PreAuthorize

/**
 * Publishes the two permission codes every mutation route needs (ADR 0030, decision 4).
 *
 * A route names its mutation code in `@PreAuthorize`; the view code(s) come from the catalogue
 * (`permission_view_requirement`), the very rows the guard enforces. Reading them here, and not
 * typing them in each route's description, means the document cannot drift from the rule: a
 * migration that changes a pairing changes the published text with it.
 *
 * The text goes in two places: the operation description, and the `403` response, because the
 * refusal is what a client reads when it lacks a code and a hand-written `403` such as "Forbidden"
 * says nothing about the views. The gate is found on the method, or on the controller class when
 * the method carries none, so a class-level `@PreAuthorize` is covered the same way.
 */
@Configuration(proxyBeanMethods = false)
class PermissionRequirementOpenApiConfiguration {
    /** Appends the mutation code and its view codes to the description of every mutation route. */
    @Bean
    fun permissionRequirementOperationCustomizer(
        requirements: PermissionViewRequirementQueries,
    ): GlobalOperationCustomizer {
        val pairing by lazy { requirements.requiredViewCodesByPermission() }
        return GlobalOperationCustomizer { operation, handlerMethod ->
            val expression =
                (
                    AnnotatedElementUtils.findMergedAnnotation(
                        handlerMethod.method,
                        PreAuthorize::class.java,
                    ) ?: AnnotatedElementUtils.findMergedAnnotation(
                        handlerMethod.beanType,
                        PreAuthorize::class.java,
                    )
                )?.value
            val codes =
                expression
                    ?.let { AUTHORITY.findAll(it).map { match -> match.groupValues[1] }.toList() }
                    .orEmpty()
            val required = codes.filter { it in pairing }
            if (required.isNotEmpty()) {
                val text =
                    if (required.size == 1) {
                        sentence(required.single(), pairing.getValue(required.single()))
                    } else {
                        anyOfSentence(required.associateWith { pairing.getValue(it) })
                    }
                operation.description =
                    listOfNotNull(operation.description, text).joinToString("\n\n")
                appendToForbidden(operation, text)
            }
            operation
        }
    }

    private fun appendToForbidden(
        operation: Operation,
        text: String,
    ) {
        val responses = operation.responses ?: ApiResponses().also { operation.responses = it }
        val forbidden =
            responses[FORBIDDEN] ?: newForbidden().also { responses.addApiResponse(FORBIDDEN, it) }
        forbidden.description =
            listOfNotNull(
                forbidden.description?.trimEnd()?.takeIf { it.isNotEmpty() }?.let {
                    if (it.endsWith(".")) it else "$it."
                },
                text,
            ).joinToString(" ")
    }

    private fun newForbidden(): ApiResponse =
        ApiResponse().content(
            Content().addMediaType(
                PROBLEM_JSON,
                MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ApiProblem")),
            ),
        )

    /** A gate that accepts any one of several mutation codes, each with its own views. */
    private fun anyOfSentence(pairs: Map<String, List<String>>): String {
        val codes = pairs.keys.joinToString(" or ") { "`$it`" }
        val detail =
            pairs.entries.joinToString("; ") { (code, views) ->
                "`$code` needs ${views.joinToString(" and ") { "`$it`" }}"
            }
        return "Requires any one of $codes, together with its view permissions at the same " +
            "scope ($detail). A caller holding one of them without its views gets 403 " +
            "`forbidden` naming the first missing code, and nothing is changed."
    }

    private fun sentence(
        code: String,
        views: List<String>,
    ): String {
        val viewList = views.joinToString(" and ") { "`$it`" }
        val noun = if (views.size == 1) "view permission" else "view permissions"
        return "Requires `$code` and its $noun $viewList, at the same scope. A caller holding " +
            "`$code` without $viewList gets 403 `forbidden` naming the first missing code, " +
            "and nothing is changed."
    }

    private companion object {
        const val FORBIDDEN = "403"
        const val PROBLEM_JSON = "application/problem+json"
        val AUTHORITY = Regex("'([a-z_.]+)'")
    }
}
