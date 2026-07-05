package com.finaxis.platform.common.web.ratelimit

/**
 * Raised when the distributed rate-limit backend cannot evaluate a request.
 */
class RateLimitUnavailableException(
    cause: Throwable,
) : RuntimeException("Distributed rate-limit backend is unavailable", cause)
