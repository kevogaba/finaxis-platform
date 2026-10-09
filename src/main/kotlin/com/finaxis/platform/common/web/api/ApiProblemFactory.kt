package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.common.application.RequestTooLargeException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.id.uuidV7
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/** Constructs safe and consistently correlated public API problems. */
@Component
class ApiProblemFactory {
    /** Converts a transport-neutral application error to its HTTP representation. */
    fun application(
        exception: ApplicationException,
        request: HttpServletRequest,
    ): ApiProblem =
        problem(
            status = statusFor(exception),
            code = exception.code,
            detail = exception.safeDetail,
            request = request,
        )

    /**
     * Creates a framework or transport error from only safe public values. [instance] is the
     * request path unless the caller must not echo it.
     */
    fun problem(
        status: HttpStatus,
        code: String,
        detail: String,
        request: HttpServletRequest,
        violations: List<ApiViolation>? = null,
        instance: String = request.requestURI,
    ): ApiProblem =
        ApiProblem(
            type = "urn:finaxis:problem:$code",
            title = status.reasonPhrase,
            status = status.value(),
            detail = detail,
            instance = instance,
            code = code,
            requestId = requestId(request),
            violations = violations?.take(MAXIMUM_VIOLATIONS),
        )

    private fun statusFor(exception: ApplicationException): HttpStatus =
        when (exception) {
            is ResourceNotFoundException -> HttpStatus.NOT_FOUND
            is ConflictException -> HttpStatus.CONFLICT
            is ForbiddenOperationException -> HttpStatus.FORBIDDEN
            is InvalidOperationException -> HttpStatus.UNPROCESSABLE_CONTENT
            is InvalidRequestException -> HttpStatus.BAD_REQUEST
            is RequestTooLargeException -> HttpStatus.CONTENT_TOO_LARGE
        }

    /** Shared request-correlation contract and public problem limits. */
    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        const val REQUEST_ID_ATTRIBUTE = "com.finaxis.platform.common.web.api.request-id"
        const val MAXIMUM_VIOLATIONS = 100

        /**
         * Resolves one request id for filters, problem bodies, response headers, the MDC and
         * stored rows: the client's `X-Request-Id` when [ClientRequestIds] accepts it, otherwise
         * a generated UUIDv7. A rejected value is discarded here, the only place the header is
         * read, so it is never logged, echoed or recorded. The result is cached on the request.
         */
        fun requestId(request: HttpServletRequest): String =
            (request.getAttribute(REQUEST_ID_ATTRIBUTE) as? String)
                ?.takeIf { it.isNotBlank() }
                ?: (
                    ClientRequestIds.acceptable(request.getHeader(REQUEST_ID_HEADER))
                        ?: uuidV7().toString()
                ).also { request.setAttribute(REQUEST_ID_ATTRIBUTE, it) }

        /**
         * Resolves the correlation id the request context carries into the MDC, audit rows and
         * posting requests: the client's `X-Correlation-Id` when [ClientRequestIds] accepts it
         * (the same 8 to 64 characters of `[A-Za-z0-9._-]` as a request id), otherwise
         * [requestId], exactly as when the header is absent. A rejected value is discarded here,
         * the only place the header is read, so it is never logged, echoed or recorded.
         */
        fun correlationId(request: HttpServletRequest): String =
            ClientRequestIds.acceptable(request.getHeader(CORRELATION_ID_HEADER))
                ?: requestId(request)
    }
}
