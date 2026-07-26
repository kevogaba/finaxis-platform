package com.finaxis.platform.config

import com.finaxis.platform.common.web.api.ApiProblemFactory
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import java.io.PrintWriter
import java.io.Writer
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Emits HTTP access logs through SLF4J so access logs share the same Logback appenders, format,
 * correlation fields, and OpenTelemetry export path as application logs.
 */
class HttpAccessLogFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startedAt = System.nanoTime()
        val requestId = request.requestId()
        response.setHeader(ApiProblemFactory.REQUEST_ID_HEADER, requestId)
        val countingResponse = CountingHttpServletResponse(response)
        var failed = false

        try {
            filterChain.doFilter(request, countingResponse)
        } catch (ex: IOException) {
            failed = true
            throw ex
        } catch (ex: ServletException) {
            failed = true
            throw ex
        } finally {
            logAccess(request, countingResponse, requestId, startedAt, failed)
        }
    }

    private fun logAccess(
        request: HttpServletRequest,
        response: CountingHttpServletResponse,
        requestId: String,
        startedAt: Long,
        failed: Boolean,
    ) {
        val status = response.accessLogStatus(failed)
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val fields =
            AccessLogFields(
                method = request.method,
                path = request.requestURI,
                query = request.queryString,
                status = status,
                durationMs = durationMs,
                responseBytes = response.bodyBytes,
                remoteAddress = request.remoteAddr,
                protocol = request.protocol,
                userAgent = request.getHeader("User-Agent"),
                referer = request.getHeader("Referer"),
                requestId = requestId,
            )

        withMdc(fields) {
            val message = fields.message()
            if (status >= SERVER_ERROR_STATUS) {
                accessLogger.warn(message)
            } else {
                accessLogger.info(message)
            }
        }
    }

    private fun CountingHttpServletResponse.accessLogStatus(failed: Boolean): Int =
        if (failed && status < BAD_REQUEST_STATUS) {
            HttpServletResponse.SC_INTERNAL_SERVER_ERROR
        } else {
            status
        }

    private fun withMdc(
        fields: AccessLogFields,
        block: () -> Unit,
    ) {
        fields.mdcValues().forEach { (key, value) -> MDC.put(key, value) }
        try {
            block()
        } finally {
            fields.mdcValues().keys.forEach(MDC::remove)
        }
    }

    private data class AccessLogFields(
        val method: String,
        val path: String,
        val query: String?,
        val status: Int,
        val durationMs: Long,
        val responseBytes: Long,
        val remoteAddress: String,
        val protocol: String,
        val userAgent: String?,
        val referer: String?,
        val requestId: String,
    ) {
        fun message(): String =
            listOf(
                "http_access",
                "requestId=${quoted(requestId)}",
                "method=${token(method)}",
                "path=${quoted(path)}",
                "query=${nullableQuoted(query)}",
                "status=$status",
                "durationMs=$durationMs",
                "responseBytes=$responseBytes",
                "remoteAddress=${quoted(remoteAddress)}",
                "protocol=${quoted(protocol)}",
                "userAgent=${nullableQuoted(userAgent)}",
                "referer=${nullableQuoted(referer)}",
            ).joinToString(" ")

        fun mdcValues(): Map<String, String> =
            mapOf(
                "http.method" to method,
                "http.path" to path,
                "http.status_code" to status.toString(),
                "http.duration_ms" to durationMs.toString(),
                "http.response_bytes" to responseBytes.toString(),
                "client.address" to remoteAddress,
                "requestId" to requestId,
            )
    }

    private class CountingHttpServletResponse(
        response: HttpServletResponse,
    ) : HttpServletResponseWrapper(response) {
        private val counter = ResponseByteCounter()
        private var outputStream: ServletOutputStream? = null
        private var writer: PrintWriter? = null

        val bodyBytes: Long
            get() = counter.bytes

        override fun getOutputStream(): ServletOutputStream {
            check(writer == null) { "getWriter() has already been called" }
            val existing = outputStream
            if (existing != null) {
                return existing
            }
            return CountingServletOutputStream(super.getOutputStream(), counter)
                .also { outputStream = it }
        }

        override fun getWriter(): PrintWriter {
            check(outputStream == null) { "getOutputStream() has already been called" }
            val existing = writer
            if (existing != null) {
                return existing
            }
            return PrintWriter(CountingWriter(super.getWriter(), counter))
                .also { writer = it }
        }
    }

    private class ResponseByteCounter {
        var bytes: Long = 0
            private set

        fun add(count: Int) {
            bytes += count.toLong()
        }
    }

    private class CountingServletOutputStream(
        private val delegate: ServletOutputStream,
        private val counter: ResponseByteCounter,
    ) : ServletOutputStream() {
        override fun isReady(): Boolean = delegate.isReady

        override fun setWriteListener(writeListener: WriteListener?) {
            delegate.setWriteListener(writeListener)
        }

        override fun write(value: Int) {
            delegate.write(value)
            counter.add(SINGLE_BYTE)
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            delegate.write(buffer, offset, length)
            counter.add(length)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    private class CountingWriter(
        private val delegate: Writer,
        private val counter: ResponseByteCounter,
    ) : Writer() {
        override fun write(
            buffer: CharArray,
            offset: Int,
            length: Int,
        ) {
            delegate.write(buffer, offset, length)
            counter.add(length)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    private companion object {
        private const val BAD_REQUEST_STATUS = 400
        private const val SERVER_ERROR_STATUS = 500
        private const val SINGLE_BYTE = 1
        private val accessLogger = LoggerFactory.getLogger("com.finaxis.platform.http.access")

        private fun HttpServletRequest.requestId(): String =
            ApiProblemFactory.requestId(this).also { requestId ->
                setAttribute(ApiProblemFactory.REQUEST_ID_ATTRIBUTE, requestId)
            }

        private fun token(value: String): String = value.replace(WHITESPACE_REGEX, "_")

        private fun nullableQuoted(value: String?): String = value?.let(::quoted) ?: "-"

        private fun quoted(value: String): String =
            "\"" +
                value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r") +
                "\""

        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
