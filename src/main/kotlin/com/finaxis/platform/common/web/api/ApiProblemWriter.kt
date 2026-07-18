package com.finaxis.platform.common.web.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component

/** Writes safe API problems from servlet filters that execute before MVC exception handling. */
@Component
class ApiProblemWriter(
    private val problemFactory: ApiProblemFactory,
    private val apiJsonCodec: ApiJsonCodec,
) {
    /** Writes a safe RFC 9457 response for a failure outside MVC exception handling. */
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        code: String,
        detail: String,
    ) {
        val problem = problemFactory.problem(status, code, detail, request)
        response.status = problem.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(ApiProblemFactory.REQUEST_ID_HEADER, problem.requestId)
        apiJsonCodec.mapper.writeValue(response.outputStream, problem)
    }

    /** Writes the stable response used when tenant context cannot be trusted or resolved. */
    fun writeForbiddenTenantContext(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        write(
            request,
            response,
            HttpStatus.FORBIDDEN,
            "invalid_active_tenant_context",
            "Active tenant context is invalid or unavailable.",
        )
    }
}
