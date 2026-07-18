package com.finaxis.platform.common.web.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Writes safe API problems from servlet filters that execute before MVC exception handling. */
@Component
class ApiProblemWriter(
    private val problemFactory: ApiProblemFactory,
    private val jsonMapper: ObjectMapper,
) {
    /** Writes the stable response used when tenant context cannot be trusted or resolved. */
    fun writeForbiddenTenantContext(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val problem =
            problemFactory.problem(
                HttpStatus.FORBIDDEN,
                "invalid_active_tenant_context",
                "Active tenant context is invalid or unavailable.",
                request,
            )
        response.status = problem.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(ApiProblemFactory.REQUEST_ID_HEADER, problem.requestId)
        jsonMapper.writeValue(response.outputStream, problem)
    }
}
