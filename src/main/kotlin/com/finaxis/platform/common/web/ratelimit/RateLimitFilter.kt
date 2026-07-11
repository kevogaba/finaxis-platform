package com.finaxis.platform.common.web.ratelimit

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration

/**
 * Servlet filter enforcing API-wide distributed request rate limits.
 */
open class RateLimitFilter(
    private val properties: RateLimitProperties,
    private val keyResolver: RateLimitKeyResolver,
    private val rateLimiter: RateLimiterService,
    private val clock: Clock,
) : OncePerRequestFilter() {
    private val pathMatcher = AntPathMatcher()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!properties.enabled || isExcluded(request.servletPath.ifBlank { request.requestURI })) {
            filterChain.doFilter(request, response)
            return
        }

        val identity = keyResolver.resolve(request, properties)
        val decision =
            try {
                rateLimiter.tryConsume(identity.key, identity.policy)
            } catch (ex: RateLimitUnavailableException) {
                handleLimiterFailure(request, response, filterChain, ex)
                return
            }

        if (properties.includeHeaders) {
            addRateLimitHeaders(response, decision)
        }

        if (decision.allowed) {
            filterChain.doFilter(request, response)
            return
        }

        rateLimitLogger.warn(
            "rate_limit_rejected method={} path={} authenticated={} key={} retryAfterSeconds={}",
            request.method,
            request.requestURI,
            identity.authenticated,
            identity.key,
            secondsHeader(decision.retryAfter),
        )
        response.status = TOO_MANY_REQUESTS
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            """
            {"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Rate limit exceeded"}
            """.trimIndent(),
        )
    }

    private fun isExcluded(path: String): Boolean =
        properties.paths.enabled &&
            properties.paths.excluded.any { pattern -> pathMatcher.match(pattern, path) }

    private fun handleLimiterFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
        ex: RuntimeException,
    ) {
        rateLimitLogger.warn(
            "rate_limit_unavailable method={} path={} failOpen={}",
            request.method,
            request.requestURI,
            properties.failOpen,
            ex,
        )
        if (properties.failOpen) {
            filterChain.doFilter(request, response)
        } else {
            response.sendError(TOO_MANY_REQUESTS, "Rate limiter unavailable")
        }
    }

    private fun addRateLimitHeaders(
        response: HttpServletResponse,
        decision: RateLimitDecision,
    ) {
        response.setHeader("RateLimit-Limit", decision.limit.toString())
        response.setHeader("RateLimit-Remaining", decision.remaining.toString())
        response.setHeader(
            "RateLimit-Reset",
            clock
                .instant()
                .plus(decision.resetAfter)
                .epochSecond
                .toString(),
        )
        if (!decision.allowed) {
            response.setHeader(HttpHeaders.RETRY_AFTER, secondsHeader(decision.retryAfter))
        }
    }

    private fun secondsHeader(duration: Duration): String =
        duration.seconds.coerceAtLeast(MINIMUM_RETRY_AFTER_SECONDS).toString()

    private companion object {
        private const val TOO_MANY_REQUESTS = 429
        private const val MINIMUM_RETRY_AFTER_SECONDS = 1L
        private val rateLimitLogger = LoggerFactory.getLogger(RateLimitFilter::class.java)
    }
}
