package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.common.application.RequestTooLargeException
import com.finaxis.platform.common.web.api.ApiProblemWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.DefaultCorsProcessor
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.Collections
import java.util.UUID

/** Establishes one bounded, UUID idempotency key context for every HTTP mutation. */
class IdempotencyKeyFilter(
    private val properties: IdempotencyProperties,
    private val problemWriter: ApiProblemWriter,
    private val corsConfigurationSource: CorsConfigurationSource? = null,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method in MUTATION_METHODS) {
            filterMutation(request, response, filterChain)
        } else {
            filterChain.doFilter(request, response)
        }
    }

    private fun filterMutation(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val suppliedValues = Collections.list(request.getHeaders(IDEMPOTENCY_KEY_HEADER))
        val supplied = suppliedValues.singleOrNull()
        val parsed = supplied?.let(::parseKey)
        val effectiveKey = parsed ?: UUID.randomUUID()
        response.setHeader(IDEMPOTENCY_KEY_HEADER, effectiveKey.toString())
        request.setAttribute(IDEMPOTENCY_KEY_ATTRIBUTE, effectiveKey)
        if (suppliedValues.size > 1 || (supplied != null && parsed == null)) {
            applyCors(request, response)
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
            applyCors(request, response)
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
            try {
                BoundedContentCachingRequestWrapper(request, properties.maxRequestBodyBytes)
            } catch (_: RequestTooLargeException) {
                applyCors(request, response)
                problemWriter.write(
                    request,
                    response,
                    HttpStatus.CONTENT_TOO_LARGE,
                    "REQUEST_BODY_TOO_LARGE",
                    "The request body exceeds the allowed size.",
                )
                return
            }
        wrapped.setAttribute(IDEMPOTENCY_REQUEST_ATTRIBUTE, wrapped)
        filterChain.doFilter(wrapped, response)
    }

    private fun applyCors(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val configuration = corsConfigurationSource?.getCorsConfiguration(request) ?: return
        DefaultCorsProcessor().processRequest(configuration, request, response)
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
        corsConfigurationSource: ObjectProvider<CorsConfigurationSource>,
    ): FilterRegistrationBean<IdempotencyKeyFilter> =
        FilterRegistrationBean(
            IdempotencyKeyFilter(
                properties,
                problemWriter,
                corsConfigurationSource.getIfAvailable(),
            ),
        ).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }
}

/** Eager, repeatable request wrapper that never buffers more than the configured limit. */
class BoundedContentCachingRequestWrapper(
    request: HttpServletRequest,
    maximumBytes: Int,
) : HttpServletRequestWrapper(request) {
    /** Complete request bytes proven to fit within the public limit. */
    val contentAsByteArray: ByteArray = readBounded(request, maximumBytes)

    override fun getInputStream(): ServletInputStream =
        ByteArrayServletInputStream(contentAsByteArray)

    override fun getReader(): BufferedReader =
        BufferedReader(
            InputStreamReader(
                inputStream,
                characterEncoding?.let(Charset::forName) ?: Charsets.UTF_8,
            ),
        )

    override fun getContentLength(): Int = contentAsByteArray.size

    override fun getContentLengthLong(): Long = contentAsByteArray.size.toLong()

    private companion object {
        fun readBounded(
            request: HttpServletRequest,
            maximumBytes: Int,
        ): ByteArray {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
            var total = 0
            request.inputStream.use { input ->
                while (true) {
                    val remainingBeforeRejection = maximumBytes - total + 1
                    val read = input.read(buffer, 0, minOf(buffer.size, remainingBeforeRejection))
                    if (read < 0) break
                    if (read > maximumBytes - total) throw RequestTooLargeException()
                    output.write(buffer, 0, read)
                    total += read
                }
            }
            return output.toByteArray()
        }
    }
}

private class ByteArrayServletInputStream(
    bytes: ByteArray,
) : ServletInputStream() {
    private val delegate = ByteArrayInputStream(bytes)

    override fun read(): Int = delegate.read()

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int = delegate.read(bytes, offset, length)

    override fun isFinished(): Boolean = delegate.available() == 0

    override fun isReady(): Boolean = true

    override fun setReadListener(readListener: ReadListener) {
        if (isFinished) readListener.onAllDataRead() else readListener.onDataAvailable()
    }
}
