package com.finaxis.platform.common.web.api

/**
 * The only shape of client-supplied `X-Request-Id` and `X-Correlation-Id` the platform carries:
 * 8 to 64 characters of `[A-Za-z0-9._-]`. Anything else (CR/LF, spaces, quotes, non-ASCII, too
 * short or too long) is dropped without being inspected further, so the application never puts it
 * in a log line, the MDC, a stored row or a response; [ApiProblemFactory.requestId] then generates
 * an id instead, and [ApiProblemFactory.correlationId] falls back to that request id. (An error
 * monitor such as Sentry may still attach the raw request headers to an event.)
 */
object ClientRequestIds {
    /** Shortest accepted client request id. */
    const val MINIMUM_LENGTH = 8

    /** Longest accepted client request id. */
    const val MAXIMUM_LENGTH = 64

    private val ACCEPTED_CHARACTERS = Regex("[A-Za-z0-9._-]+")

    /** Returns [candidate] when it has the accepted shape, otherwise `null`. */
    fun acceptable(candidate: String?): String? =
        candidate?.takeIf {
            // Length first, so an oversized header never reaches the regex.
            it.length in MINIMUM_LENGTH..MAXIMUM_LENGTH && ACCEPTED_CHARACTERS.matches(it)
        }
}
