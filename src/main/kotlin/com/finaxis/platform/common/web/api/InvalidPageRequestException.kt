package com.finaxis.platform.common.web.api

/**
 * Raised when a client-supplied paging or sort parameter is invalid.
 *
 * Mapped to a 400 `invalid_parameter` problem. [parameter] is the public (snake_case) wire name of
 * the offending query parameter and [message] a static, value-free explanation; both are returned
 * to the client as a violation when present, and neither may echo the rejected value.
 */
class InvalidPageRequestException(
    val parameter: String? = null,
    message: String? = null,
) : RuntimeException(message)
