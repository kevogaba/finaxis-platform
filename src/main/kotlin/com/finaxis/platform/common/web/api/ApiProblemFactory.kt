package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
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

    /** Creates a framework or transport error from only safe public values. */
    fun problem(
        status: HttpStatus,
        code: String,
        detail: String,
        request: HttpServletRequest,
        violations: List<ApiViolation>? = null,
    ): ApiProblem =
        ApiProblem(
            type = "urn:finaxis:problem:$code",
            title = status.reasonPhrase,
            status = status.value(),
            detail = detail,
            instance = request.requestURI,
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
        }

    /** Shared request-correlation contract and public problem limits. */
    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val REQUEST_ID_ATTRIBUTE = "com.finaxis.platform.common.web.api.request-id"
        const val MAXIMUM_VIOLATIONS = 100

        /** Resolves one request id for filters, problem bodies, and response headers. */
        fun requestId(request: HttpServletRequest): String =
            (request.getAttribute(REQUEST_ID_ATTRIBUTE) as? String)
                ?.takeIf { it.isNotBlank() }
                ?: request.getHeader(REQUEST_ID_HEADER)?.takeIf { it.isNotBlank() }
                ?: uuidV7().toString().also { request.setAttribute(REQUEST_ID_ATTRIBUTE, it) }
    }
}
