package com.finaxis.platform.common.application

/** Base class for application failures that have a stable public error contract. */
sealed class ApplicationException(
    val code: String,
    val safeDetail: String,
    cause: Throwable? = null,
) : RuntimeException(safeDetail, cause)

/** Raised when a requested resource is unknown or deliberately hidden. */
class ResourceNotFoundException(
    code: String = "resource_not_found",
    safeDetail: String = "The requested resource was not found.",
) : ApplicationException(code, safeDetail)

/** Raised when a request conflicts with the current resource state. */
class ConflictException(
    code: String = "conflict",
    safeDetail: String = "The request conflicts with the current resource state.",
    cause: Throwable? = null,
) : ApplicationException(code, safeDetail, cause)

/** Raised when an authenticated caller cannot perform an operation. */
open class ForbiddenOperationException(
    code: String = "forbidden",
    safeDetail: String = "You are not permitted to perform this action.",
) : ApplicationException(code, safeDetail)

/** Raised when syntactically valid input is rejected by an application rule. */
class InvalidOperationException(
    code: String = "invalid_operation",
    safeDetail: String = "The request cannot be processed in its current state.",
) : ApplicationException(code, safeDetail)

/** Raised when a bounded request payload exceeds the configured public limit. */
class RequestTooLargeException(
    code: String = "request_body_too_large",
    safeDetail: String = "The request body exceeds the allowed size.",
) : ApplicationException(code, safeDetail)

/** Raised when URL query encoding is malformed and cannot be canonicalized safely. */
class InvalidRequestException(
    code: String = "invalid_request",
    safeDetail: String = "The request is malformed.",
) : ApplicationException(code, safeDetail)
