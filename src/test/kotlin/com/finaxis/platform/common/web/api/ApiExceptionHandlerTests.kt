package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Verifies every MVC error family has the same safe RFC 9457 representation. */
class ApiExceptionHandlerTests {
    private val handler = ApiExceptionHandler(ApiProblemFactory())

    @Test
    fun `maps malformed JSON without exposing parser details`() {
        val response =
            handler.invalidJson(
                HttpMessageNotReadableException(
                    "parser: secret",
                    mock(HttpInputMessage::class.java),
                ),
                request(),
            )

        assertProblem(response.body!!, 400, "invalid_json", "Malformed request body.")
        assertFalse(response.body!!.detail.contains("secret"))
    }

    @Test
    fun `maps validation errors with bounded field violations`() {
        @Suppress("UNCHECKED_CAST")
        val tenantCode = mock(ConstraintViolation::class.java) as ConstraintViolation<Any>

        @Suppress("UNCHECKED_CAST")
        val name = mock(ConstraintViolation::class.java) as ConstraintViolation<Any>
        val tenantCodePath = mock(jakarta.validation.Path::class.java)
        val namePath = mock(jakarta.validation.Path::class.java)
        `when`(tenantCode.propertyPath).thenReturn(tenantCodePath)
        `when`(tenantCodePath.toString()).thenReturn("request.tenantCode")
        `when`(tenantCode.message).thenReturn("must not be blank")
        `when`(name.propertyPath).thenReturn(namePath)
        `when`(namePath.toString()).thenReturn("request.name")
        `when`(name.message).thenReturn("must not be blank")

        val response =
            handler.constraintViolation(
                ConstraintViolationException(setOf(tenantCode, name)),
                request(),
            )

        assertProblem(
            response.body!!,
            400,
            "validation_failed",
            "One or more request fields are invalid.",
            violationCount = 2,
        )
        assertEquals(2, response.body!!.violations?.size)
        assertEquals(
            setOf("tenantCode", "name"),
            response.body!!
                .violations
                ?.map { it.field }
                ?.toSet(),
        )
    }

    @Test
    fun `maps invalid query parameter without returning its rejected value`() {
        val response =
            handler.typeMismatch(
                MethodArgumentTypeMismatchException(
                    "secret-value",
                    Int::class.java,
                    "page",
                    MethodParameter(
                        ApiExceptionHandlerTests::class.java.getDeclaredMethod(
                            "dummyPageMethod",
                            Int::class.javaPrimitiveType,
                        ),
                        0,
                    ),
                    null,
                ),
                request(),
            )

        assertProblem(
            response.body!!,
            400,
            "invalid_parameter",
            "One or more request parameters are invalid.",
            violationCount = 1,
        )
        assertFalse(response.body!!.detail.contains("secret-value"))
    }

    @Test
    fun `maps missing tenant context to a safe forbidden problem`() {
        val response =
            handler.application(
                ForbiddenOperationException(
                    "missing_tenant_context",
                    "An active tenant context is required.",
                ),
                request(),
            )

        assertProblem(
            response.body!!,
            403,
            "missing_tenant_context",
            "An active tenant context is required.",
        )
    }

    @Test
    fun `maps access denial without permission details`() {
        val response =
            handler.application(
                ForbiddenOperationException(
                    "forbidden",
                    "You are not permitted to perform this action.",
                ),
                request(),
            )

        assertProblem(
            response.body!!,
            403,
            "forbidden",
            "You are not permitted to perform this action.",
        )
    }

    @Test
    fun `maps Spring Security access denial without authority details`() {
        val response =
            handler.accessDenied(
                org.springframework.security.access.AccessDeniedException(
                    "missing internal.permission",
                ),
                request(),
            )

        assertProblem(
            response.body!!,
            403,
            "forbidden",
            "You are not permitted to perform this action.",
        )
        assertFalse(response.body!!.detail.contains("internal.permission"))
    }

    @Test
    fun `maps resource not found and conflicts with safe typed errors`() {
        val notFound =
            handler.application(
                ResourceNotFoundException(
                    "resource_not_found",
                    "The requested resource was not found.",
                ),
                request(),
            )
        val conflict =
            handler.application(
                ConflictException(
                    "conflict",
                    "The request conflicts with the current resource state.",
                ),
                request(),
            )

        assertProblem(
            notFound.body!!,
            404,
            "resource_not_found",
            "The requested resource was not found.",
        )
        assertProblem(
            conflict.body!!,
            409,
            "conflict",
            "The request conflicts with the current resource state.",
        )
    }

    @Test
    fun `maps unsupported media type and unexpected errors safely`() {
        val unsupported =
            handler.unsupportedMediaType(
                HttpMediaTypeNotSupportedException("application/xml"),
                request(),
            )
        val unexpected =
            handler.unexpected(IllegalStateException("SQL password=secret"), request())

        assertProblem(
            unsupported.body!!,
            415,
            "unsupported_media_type",
            "The request media type is not supported.",
        )
        assertProblem(unexpected.body!!, 500, "internal_error", "An unexpected error occurred.")
        assertFalse(unexpected.body!!.detail.contains("secret"))
    }

    private fun request(): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/api/v1/tenants").apply {
            addHeader("X-Request-Id", "request-123")
        }

    @Suppress("unused")
    private fun dummyPageMethod(page: Int) = page

    private fun assertProblem(
        problem: ApiProblem,
        status: Int,
        code: String,
        detail: String,
        violationCount: Int? = null,
    ) {
        assertEquals("urn:finaxis:problem:$code", problem.type)
        assertEquals(HttpStatus.valueOf(status).reasonPhrase, problem.title)
        assertEquals(status, problem.status)
        assertEquals(detail, problem.detail)
        assertEquals("/api/v1/tenants", problem.instance)
        assertEquals(code, problem.code)
        assertEquals("request-123", problem.requestId)
        if (violationCount != null) {
            assertEquals(violationCount, problem.violations?.size)
        } else {
            assertNull(problem.violations)
        }
    }
}
