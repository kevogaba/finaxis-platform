package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.toMessageFreeStackTraceOrUnavailable
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.web.ratelimit.RateLimitPolicy
import com.finaxis.platform.common.web.ratelimit.RateLimiterService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * Writes the audit row of an authenticated caller's `403` (#184): a `DENIED` / `HIGH`
 * `api.access_denied` event naming the caller, the route pattern and, when known, the permission
 * that was missing, never a header, a token, a path value or a body. Every route that answers
 * `403` reaches it through the one of three places that writes the problem: the application's
 * [ForbiddenOperationException], method security's `AccessDeniedException` and the filter chain's
 * access-denied handler.
 *
 * A caller is limited to [DeniedAccessAuditProperties.capacity] rows per refill period, through the
 * same distributed bucket as request rate limiting, so a hostile client cannot flood the log. The
 * audit never changes the answer: a failed write or an unavailable bucket is a message-free `WARN`
 * and the `403` goes out as it would have.
 *
 * A refusal that already writes its own durable `DENIED` row (`lifecycle.approver_is_*`) is not
 * written twice. A refusal raised before any request context names the caller (a JWT-only user
 * choosing an organisation) carries the user it is about in
 * [ForbiddenOperationException.actorUserId]. A denial with no tenant context is recorded under the
 * platform organisation explicitly, with `tenant_context=none`, instead of through the repository's
 * warned fallback.
 *
 * The bound is the distributed request rate limiter's bucket. With
 * `finaxis.rate-limit.enabled=false` there is none, and every denial is written (with a startup
 * `WARN` saying so): turn the rows off with `finaxis.audit.denied-access.enabled=false` instead if
 * that is unwanted. No per-instance fallback is used, as no production path may rely on one.
 */
@Component
class DeniedAccessAuditor(
    private val auditService: AuditService,
    private val rateLimiter: ObjectProvider<RateLimiterService>,
    private val properties: DeniedAccessAuditProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        if (properties.enabled && rateLimiter.ifAvailable == null) {
            logger.warn(
                "Denied-access auditing is ON but no distributed rate limiter is configured " +
                    "(finaxis.rate-limit.enabled=false): a caller's denial rows are not bounded.",
            )
        }
    }

    /** Audits a `403` raised by application code. */
    fun applicationDenial(
        request: HttpServletRequest,
        exception: ForbiddenOperationException,
    ) {
        if (exception.code.startsWith(OWN_ROW_PREFIX)) return
        record(
            request,
            exception.code,
            (exception as? MissingPermissionException)?.permissionCode,
            exception.actorUserId,
        )
    }

    /** Audits a `403` raised by method security or the filter chain. */
    fun securityDenial(
        request: HttpServletRequest,
        code: String,
    ) = record(request, code, null, null)

    private fun record(
        request: HttpServletRequest,
        code: String,
        permission: String?,
        hintedActor: UUID?,
    ) {
        val context = RequestContexts.current()
        val actorId = context?.actor?.userId ?: hintedActor ?: return
        if (!properties.enabled) return
        val tenant = context?.tenant?.organisationId
        try {
            if (!admitted(actorId.toString(), tenant?.toString())) return
            auditService.recordSecurityEvent(
                actorId = actorId,
                tenantId = tenant ?: PlatformOrganisation.ID,
                action = ACTION,
                resourceType = RESOURCE_TYPE,
                resourceId = null,
                outcome = AuditOutcome.DENIED,
                actorType = "USER",
                reason = code,
                metadata =
                    buildMap {
                        put("method", request.method)
                        put("route", route(request))
                        permission?.let { put("permission", it) }
                        if (tenant == null) put("tenant_context", "none")
                    },
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            logger.warn(
                "denied-access audit not written: {}",
                ex.toMessageFreeStackTraceOrUnavailable(),
            )
        }
    }

    /** True when the caller still has a row in its bucket; a skipped row is logged, not stored. */
    private fun admitted(
        actor: String,
        tenant: String?,
    ): Boolean {
        val limiter = rateLimiter.ifAvailable ?: return true
        val allowed =
            limiter
                .tryConsume(
                    "audit-denied:$actor:${tenant ?: "none"}",
                    RateLimitPolicy(
                        properties.capacity,
                        properties.capacity,
                        properties.refillPeriod,
                    ),
                ).allowed
        if (!allowed) logger.debug("denied-access audit row skipped: caller over its bucket")
        return allowed
    }

    /** The matched route pattern, never the raw path: a path can carry ids and personal data. */
    private fun route(request: HttpServletRequest): String =
        (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)
            ?: UNMATCHED_ROUTE

    private companion object {
        const val ACTION = "api.access_denied"
        const val RESOURCE_TYPE = "API_ROUTE"
        const val OWN_ROW_PREFIX = "lifecycle.approver_is_"
        const val UNMATCHED_ROUTE = "unmatched"
    }
}
