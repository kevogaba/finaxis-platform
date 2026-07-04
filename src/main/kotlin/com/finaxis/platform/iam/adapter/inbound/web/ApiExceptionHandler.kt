package com.finaxis.platform.iam.adapter.inbound.web

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.selection.OrganisationSelectionDeniedException
import io.micrometer.tracing.Tracer
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Standard API error response returned by REST endpoints.
 */
data class ApiErrorResponse(
    val timestamp: Instant,
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val code: String,
    val traceId: String?,
    val errors: List<ApiErrorDetail> = emptyList(),
    val fieldErrors: Map<String, List<String>> = emptyMap(),
)

/**
 * Machine-readable error detail for a specific field, parameter, or component.
 */
data class ApiErrorDetail(
    val code: String,
    val message: String,
    val attribute: String,
)

/**
 * Central MVC exception handler for all REST controllers.
 */
@RestControllerAdvice
class ApiExceptionHandler(
    private val tracer: Tracer? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(OrganisationSelectionDeniedException::class, AccessDeniedException::class)
    fun forbidden(exception: RuntimeException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.debug("Forbidden API request: {}", exception.message)
        return error(
            status = HttpStatus.FORBIDDEN,
            message = exception.message ?: "Forbidden",
            path = request.requestURI,
            code = "forbidden",
            detail = ApiErrorDetail("forbidden", exception.message ?: "Forbidden", "authorization"),
        )
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun springSecurityForbidden(
        exception: org.springframework.security.access.AccessDeniedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        logger.debug("Spring Security denied API request: {}", exception.message)
        return error(
            status = HttpStatus.FORBIDDEN,
            message = exception.message ?: "Forbidden",
            path = request.requestURI,
            code = "forbidden",
            detail = ApiErrorDetail("forbidden", exception.message ?: "Forbidden", "authorization"),
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(exception: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.debug("Request body validation failed: {}", exception.message)
        return validationError(fieldErrors(exception), request)
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun methodValidation(exception: HandlerMethodValidationException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.debug("Controller method validation failed: {}", exception.message)
        val fields = exception.parameterValidationResults
            .associate { result ->
                val name = result.methodParameter.parameterName ?: "parameter"
                name to result.resolvableErrors.map { it.defaultMessage ?: "Invalid value" }
            }
        return validationError(fields, request)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun constraintViolation(exception: ConstraintViolationException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.debug("Constraint validation failed: {}", exception.message)
        val fields = exception.constraintViolations
            .groupBy { it.propertyPath.toString().substringAfterLast('.') }
            .mapValues { (_, violations) -> violations.map { it.message } }
        return validationError(fields, request)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(exception: MethodArgumentTypeMismatchException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.debug("Type mismatch for parameter '{}': {}", exception.name, exception.message)
        val expectedType = exception.requiredType?.simpleName ?: "unknown"
        return error(
            status = HttpStatus.BAD_REQUEST,
            message = "Invalid value '${exception.value}' for parameter '${exception.name}'. Expected type: $expectedType",
            path = request.requestURI,
            code = "invalid_parameter",
            detail = ApiErrorDetail("invalid_parameter", "Parameter type mismatch", exception.name),
        )
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(exception: MissingServletRequestParameterException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.debug("Missing request parameter '{}': {}", exception.parameterName, exception.message)
        return error(
            status = HttpStatus.BAD_REQUEST,
            message = "Missing required request parameter '${exception.parameterName}'",
            path = request.requestURI,
            code = "missing_parameter",
            detail = ApiErrorDetail("missing_parameter", exception.message, exception.parameterName),
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, JsonProcessingException::class, InvalidFormatException::class)
    fun invalidJson(exception: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.debug("Invalid request body: {}", exception.message)
        return error(
            status = HttpStatus.BAD_REQUEST,
            message = "Invalid JSON format in request",
            path = request.requestURI,
            code = "invalid_json",
            detail = ApiErrorDetail("invalid_json", "Request body could not be parsed", "request_body"),
        )
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResource(exception: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.debug("Resource not found: {}", exception.message)
        return error(
            status = HttpStatus.NOT_FOUND,
            message = "The requested resource was not found",
            path = request.requestURI,
            code = "resource_not_found",
            detail = ApiErrorDetail("resource_not_found", "No resource found for this request", "resource"),
        )
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed(exception: HttpRequestMethodNotSupportedException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val supported = exception.supportedMethods?.joinToString(", ") ?: "none"
        logger.debug("Method not allowed: {}", exception.message)
        return error(
            status = HttpStatus.METHOD_NOT_ALLOWED,
            message = "Method '${exception.method}' is not supported for this resource. Supported methods: $supported",
            path = request.requestURI,
            code = "method_not_allowed",
            detail = ApiErrorDetail("method_not_allowed", "HTTP method not allowed", "http_method"),
        )
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(exception: ResponseStatusException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        logger.debug("Response status exception: {}", exception.reason)
        return error(
            status = status,
            message = exception.reason ?: "Request failed",
            path = request.requestURI,
            code = "response_status_error",
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArgument(exception: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.debug("Illegal argument: {}", exception.message)
        return error(
            status = HttpStatus.BAD_REQUEST,
            message = exception.message ?: "Invalid argument provided",
            path = request.requestURI,
            code = "invalid_argument",
            detail = ApiErrorDetail("invalid_argument", exception.message ?: "Invalid argument", "request"),
        )
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        logger.error("Unexpected API exception", exception)
        return error(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            message = "An unexpected error occurred",
            path = request.requestURI,
            code = "internal_error",
            detail = ApiErrorDetail("internal_error", "Unexpected exception", "server"),
        )
    }

    private fun validationError(
        fieldErrors: Map<String, List<String>>,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        return error(
            status = HttpStatus.BAD_REQUEST,
            message = "Validation failed",
            path = request.requestURI,
            code = "validation_failed",
            fieldErrors = fieldErrors,
        )
    }

    private fun fieldErrors(exception: MethodArgumentNotValidException): Map<String, List<String>> {
        return exception.bindingResult.allErrors
            .groupBy { error -> if (error is FieldError) error.field else error.objectName }
            .mapValues { (_, errors) -> errors.map { it.defaultMessage ?: "Invalid value" } }
    }

    private fun error(
        status: HttpStatus,
        message: String,
        path: String,
        code: String,
        detail: ApiErrorDetail? = null,
        fieldErrors: Map<String, List<String>> = emptyMap(),
    ): ResponseEntity<ApiErrorResponse> {
        return ResponseEntity.status(status).body(
            ApiErrorResponse(
                timestamp = Instant.now(),
                status = status.value(),
                error = status.reasonPhrase,
                message = message,
                path = path,
                code = code,
                traceId = traceId(),
                errors = listOfNotNull(detail),
                fieldErrors = fieldErrors,
            ),
        )
    }

    private fun traceId(): String? {
        return runCatching { tracer?.currentSpan()?.context()?.traceId() }.getOrNull()
    }
}
