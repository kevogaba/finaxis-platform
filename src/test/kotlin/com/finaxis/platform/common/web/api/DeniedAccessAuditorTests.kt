package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.ratelimit.RateLimitDecision
import com.finaxis.platform.common.web.ratelimit.RateLimitPolicy
import com.finaxis.platform.common.web.ratelimit.RateLimiterService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.HandlerMapping
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeniedAccessAuditorTests {
    private val audits = mutableListOf<AuditEvent>()
    private var failWrites = false
    private val buckets = mutableMapOf<String, Long>()
    private val limiter =
        RateLimiterService { key, policy ->
            val left = buckets.getOrPut(key) { policy.capacity }
            if (left > 0) buckets[key] = left - 1
            RateLimitDecision(left > 0, policy.capacity, 0, Duration.ZERO, Duration.ZERO)
        }
    private val provider = mock<ObjectProvider<RateLimiterService>>()
    private val actor = ActorContext(uuidV7(), "subject", "user", "user@x.test")
    private val tenant = uuidV7()
    private val request =
        MockHttpServletRequest("PATCH", "/api/v1/memberships/${uuidV7()}").apply {
            setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/memberships/{id}")
            addHeader("Authorization", "Bearer secret-token")
        }

    private fun auditor(properties: DeniedAccessAuditProperties = DeniedAccessAuditProperties()) =
        DeniedAccessAuditor(
            AuditService(
                AuditEventRepository {
                    if (failWrites) error("database down")
                    audits += it
                },
                Clock.systemUTC(),
            ),
            provider,
            properties,
        )

    private fun asCaller(
        tenantId: java.util.UUID? = tenant,
        block: () -> Unit,
    ) = RequestContexts.with(
        RequestContext(tenantId?.let(::TenantContext), actor = actor),
        block,
    )

    @Test
    fun `a denial writes one denied high row naming the route and the missing permission`() {
        whenever(provider.ifAvailable).thenReturn(limiter)

        asCaller { auditor().applicationDenial(request, MissingPermissionException("user.update")) }

        val row = audits.single()
        assertEquals("api.access_denied", row.action)
        assertEquals(AuditOutcome.DENIED, row.outcome)
        assertEquals(AuditSeverity.HIGH, row.severity)
        assertEquals(actor.userId.toString(), row.actorId)
        assertEquals(tenant.toString(), row.tenantId)
        assertEquals("forbidden", row.reason)
        assertEquals("/api/v1/memberships/{id}", row.metadata["route"])
        assertEquals("user.update", row.metadata["permission"])
        assertTrue(
            row.metadata.values.none { it.toString().contains("secret-token") },
            "no header value is audited",
        )
    }

    @Test
    fun `a caller over its bucket writes no more rows`() {
        whenever(provider.ifAvailable).thenReturn(limiter)
        val auditor = auditor(DeniedAccessAuditProperties(capacity = 3))

        asCaller { repeat(50) { auditor.securityDenial(request, "forbidden") } }

        assertEquals(3, audits.size)
    }

    @Test
    fun `a refusal with its own row, an anonymous caller and a disabled audit write nothing`() {
        whenever(provider.ifAvailable).thenReturn(limiter)

        asCaller {
            auditor().applicationDenial(
                request,
                ForbiddenOperationException("lifecycle.approver_is_tenant_maker", "no"),
            )
            auditor(DeniedAccessAuditProperties(enabled = false))
                .securityDenial(request, "forbidden")
        }
        auditor().securityDenial(request, "forbidden")

        assertTrue(audits.isEmpty())
    }

    @Test
    fun `a refusal that names its own user is audited when no request context has an actor`() {
        whenever(provider.ifAvailable).thenReturn(limiter)
        val selecting = uuidV7()
        val refusal =
            object : ForbiddenOperationException() {
                override val actorUserId = selecting
            }

        auditor().applicationDenial(request, refusal)

        val row = audits.single()
        assertEquals(selecting.toString(), row.actorId)
        assertEquals(PlatformOrganisation.ID.toString(), row.tenantId)
        assertEquals("none", row.metadata["tenant_context"])
    }

    @Test
    fun `bad bucket settings are refused when the properties are bound`() {
        assertFailsWith<IllegalArgumentException> { DeniedAccessAuditProperties(capacity = 0) }
        assertFailsWith<IllegalArgumentException> {
            DeniedAccessAuditProperties(refillPeriod = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            DeniedAccessAuditProperties(refillPeriod = Duration.ofSeconds(-1))
        }
    }

    @Test
    fun `a denial without tenant context is recorded under the platform organisation`() {
        whenever(provider.ifAvailable).thenReturn(limiter)

        asCaller(tenantId = null) { auditor().securityDenial(request, "access_denied") }

        val row = audits.single()
        assertEquals(PlatformOrganisation.ID.toString(), row.tenantId)
        assertEquals("none", row.metadata["tenant_context"])
    }

    @Test
    fun `a failing write or an unavailable bucket never changes the answer`() {
        whenever(provider.ifAvailable).thenReturn(limiter)
        failWrites = true
        asCaller { auditor().securityDenial(request, "forbidden") }

        whenever(provider.ifAvailable).thenReturn(
            RateLimiterService { _: String, _: RateLimitPolicy -> error("redis down") },
        )
        failWrites = false
        asCaller { auditor().securityDenial(request, "forbidden") }

        assertTrue(audits.isEmpty())
    }
}
