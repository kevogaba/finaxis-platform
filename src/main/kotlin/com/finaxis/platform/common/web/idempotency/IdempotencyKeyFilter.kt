package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.common.web.api.ApiProblemWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import java.util.UUID

/** Establishes one bounded, UUID idempotency key context for every HTTP mutation. */
class IdempotencyKeyFilter(
    private val properties: IdempotencyProperties,
    private val problemWriter: ApiProblemWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method !in MUTATION_METHODS) {
            filterChain.doFilter(request, response)
            return
        }
        val supplied = request.getHeader(IDEMPOTENCY_KEY_HEADER)
        val parsed = supplied?.let(::parseKey)
        val effectiveKey = parsed ?: UUID.randomUUID()
        response.setHeader(IDEMPOTENCY_KEY_HEADER, effectiveKey.toString())
        request.setAttribute(IDEMPOTENCY_KEY_ATTRIBUTE, effectiveKey)
        if (supplied != null && parsed == null) {
            problemWriter.write(
                request,
                response,
                HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key must be a valid UUID.",
            )
            return
        }
        if (request.contentLengthLong > properties.maxRequestBodyBytes) {
            problemWriter.write(
                request,
                response,
                HttpStatus.CONTENT_TOO_LARGE,
                "REQUEST_BODY_TOO_LARGE",
                "The request body exceeds the allowed size.",
            )
            return
        }
        val wrapped =
            ContentCachingRequestWrapper(
                request,
                properties.maxRequestBodyBytes + 1,
            )
        wrapped.setAttribute(IDEMPOTENCY_REQUEST_ATTRIBUTE, wrapped)
        filterChain.doFilter(wrapped, response)
    }

    private fun parseKey(raw: String): UUID? =
        runCatching { UUID.fromString(raw) }
            .getOrNull()
            ?.takeIf { parsed -> parsed.toString().equals(raw, ignoreCase = true) }

    /** Shared servlet metadata names and public response headers. */
    companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
        const val IDEMPOTENCY_KEY_ATTRIBUTE =
            "com.finaxis.platform.common.web.idempotency.key"
        const val IDEMPOTENCY_REQUEST_ATTRIBUTE =
            "com.finaxis.platform.common.web.idempotency.request"
        val MUTATION_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}

/** Registers the unproxied idempotency filter before security and MVC processing. */
@Configuration(proxyBeanMethods = false)
class IdempotencyFilterConfiguration {
    /** Creates the highest-precedence mutation request wrapper. */
    @Bean
    fun idempotencyKeyFilterRegistration(
        properties: IdempotencyProperties,
        problemWriter: ApiProblemWriter,
    ): FilterRegistrationBean<IdempotencyKeyFilter> =
        FilterRegistrationBean(IdempotencyKeyFilter(properties, problemWriter)).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }
}
