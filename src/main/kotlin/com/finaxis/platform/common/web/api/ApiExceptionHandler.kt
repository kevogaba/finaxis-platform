package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.application.ApplicationException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Central MVC mapping from framework and application errors to safe RFC 9457 problems. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler(
    private val problemFactory: ApiProblemFactory = ApiProblemFactory(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Maps safe, transport-neutral application failures. */
    @ExceptionHandler(ApplicationException::class)
    fun application(
        exception: ApplicationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(problemFactory.application(exception, request), exception)

    /** Maps Spring Security authorization denials without leaking authority details. */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun accessDenied(
        exception: org.springframework.security.access.AccessDeniedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(
                HttpStatus.FORBIDDEN,
                "forbidden",
                "You are not permitted to perform this action.",
                request,
            ),
            exception,
        )

    /** Maps malformed request JSON without disclosing parser messages. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidJson(
        exception: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(HttpStatus.BAD_REQUEST, "invalid_json", "Malformed request body.", request),
            exception,
        )

    /** Maps body validation failures to safe field violations. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "One or more request fields are invalid.",
                request,
                exception.bindingResult.allErrors.map { error ->
                    ApiViolation(
                        field = publicFieldName((error as? FieldError)?.field ?: error.objectName),
                        code = error.code ?: "invalid",
                        message = error.defaultMessage ?: "Invalid value.",
                    )
                },
            ),
            exception,
        )

    /** Maps method and query validation failures to safe field violations. */
    @ExceptionHandler(ConstraintViolationException::class)
    fun constraintViolation(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "One or more request fields are invalid.",
                request,
                exception.constraintViolations.map { violation ->
                    ApiViolation(
                        field =
                            publicFieldName(
                                violation.propertyPath.toString().substringAfterLast('.'),
                            ),
                        code = "invalid",
                        message = violation.message,
                    )
                },
            ),
            exception,
        )

    /** Maps method-level validation errors that Spring MVC raises before controller invocation. */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun methodValidation(
        exception: HandlerMethodValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "One or more request fields are invalid.",
                request,
            ),
            exception,
        )

    /** Maps type mismatches without returning rejected parameter values. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(
        exception: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(
                HttpStatus.BAD_REQUEST,
                "invalid_parameter",
                "One or more request parameters are invalid.",
                request,
                listOf(
                    ApiViolation(
                        publicFieldName(exception.name),
                        "invalid_parameter",
                        "Invalid parameter value.",
                    ),
                ),
            ),
            exception,
        )

    /** Maps omitted required parameters without disclosing framework exception details. */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(
        exception: MissingServletRequestParameterException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(
                HttpStatus.BAD_REQUEST,
                "missing_parameter",
                "A required request parameter is missing.",
                request,
                listOf(
                    ApiViolation(
                        publicFieldName(exception.parameterName),
                        "missing_parameter",
                        "Required parameter is missing.",
                    ),
                ),
            ),
            exception,
        )

    /** Maps invalid public pagination bounds to a safe client error. */
    @ExceptionHandler(InvalidPageRequestException::class)
    fun invalidPageRequest(
        exception: InvalidPageRequestException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(
                HttpStatus.BAD_REQUEST,
                "invalid_parameter",
                "One or more request parameters are invalid.",
                request,
            ),
            exception,
        )

    /** Maps missing static resources and unmapped API routes to the public not-found response. */
    @ExceptionHandler(NoResourceFoundException::class)
    fun noResourceFound(
        exception: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(
                HttpStatus.NOT_FOUND,
                "resource_not_found",
                "The requested resource was not found.",
                request,
            ),
            exception,
        )

    /** Maps an unsupported content type to the public problem representation. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun unsupportedMediaType(
        exception: HttpMediaTypeNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> =
        response(
            problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_media_type",
                "The request media type is not supported.",
                request,
            ),
            exception,
        )

    /** Maps unhandled failures while retaining diagnostic information only in server logs. */
    @ExceptionHandler(Exception::class)
    fun unexpected(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> {
        logger.error("Unexpected API exception for {}", request.requestURI, exception)
        return response(
            problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error",
                "An unexpected error occurred.",
                request,
            ),
            exception,
        )
    }

    private fun problem(
        status: HttpStatus,
        code: String,
        detail: String,
        request: HttpServletRequest,
        violations: List<ApiViolation>? = null,
    ): ApiProblem = problemFactory.problem(status, code, detail, request, violations)

    private fun response(
        problem: ApiProblem,
        exception: Exception,
    ): ResponseEntity<ApiProblem> {
        logger.debug("API problem code={}", problem.code, exception)
        return ResponseEntity
            .status(
                problem.status,
            ).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem)
    }

    private fun publicFieldName(field: String): String =
        field.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}
