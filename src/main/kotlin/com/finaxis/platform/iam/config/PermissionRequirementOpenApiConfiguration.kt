package com.finaxis.platform.iam.config

import com.finaxis.platform.iam.application.port.outbound.PermissionViewRequirementQueries
import org.springdoc.core.customizers.GlobalOperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.access.prepost.PreAuthorize

/**
 * Publishes the two permission codes every mutation route needs (ADR 0030, decision 4).
 *
 * A route names its mutation code in `@PreAuthorize`; the view code(s) come from the catalogue
 * (`permission_view_requirement`), the very rows the guard enforces. Reading them here, and not
 * typing them in each route's description, means the document cannot drift from the rule: a
 * migration that changes a pairing changes the published text with it.
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
                handlerMethod.getMethodAnnotation(PreAuthorize::class.java)?.value
            val codes =
                expression
                    ?.let { AUTHORITY.findAll(it).map { match -> match.groupValues[1] }.toList() }
                    .orEmpty()
            val sentences =
                codes.mapNotNull { code ->
                    pairing[code]?.let { views -> sentence(code, views) }
                }
            if (sentences.isNotEmpty()) {
                operation.description =
                    listOfNotNull(operation.description, sentences.joinToString(" "))
                        .joinToString("\n\n")
            }
            operation
        }
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
        val AUTHORITY = Regex("'([a-z_.]+)'")
    }
}
