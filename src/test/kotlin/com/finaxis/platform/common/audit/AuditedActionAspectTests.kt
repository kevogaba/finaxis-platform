package com.finaxis.platform.common.audit

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.BeanFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuditedActionAspectTests {
    @Test
    fun `around records success with before and after summaries`() {
        val repository = CapturingAspectAuditRepository()
        val aspect = aspect(repository)
        val target = SampleTarget()
        val method =
            SampleTarget::class.java.getMethod(
                "updateSettings",
                UUID::class.java,
                Map::class.java,
                String::class.java,
            )
        val organisationId = UUID.randomUUID()
        val before = mapOf("settings.operational" to "true")
        val joinPoint = joinPoint(target, method, arrayOf(organisationId, before, "manual update"))
        `when`(joinPoint.proceed()).thenReturn(mapOf("settings.operational" to "false"))

        val result = aspect.around(joinPoint, annotationOf(method))

        assertEquals(mapOf("settings.operational" to "false"), result)
        val event = repository.events.single()
        assertEquals(AuditOutcome.SUCCESS, event.outcome)
        assertEquals(organisationId.toString(), event.tenantId)
        assertEquals(before, event.before)
        assertEquals(mapOf("settings.operational" to "false"), event.after)
        assertEquals("manual update", event.reason)
    }

    @Test
    fun `around records failure and rethrows the original exception`() {
        val repository = CapturingAspectAuditRepository()
        val aspect = aspect(repository)
        val target = SampleTarget()
        val method = SampleTarget::class.java.getMethod("failingUpdate", UUID::class.java)
        val organisationId = UUID.randomUUID()
        val joinPoint = joinPoint(target, method, arrayOf(organisationId))
        val failure = IllegalStateException("boom")
        `when`(joinPoint.proceed()).thenThrow(failure)

        val thrown =
            assertFailsWith<IllegalStateException> {
                aspect.around(joinPoint, annotationOf(method))
            }

        assertEquals(failure, thrown)
        val event = repository.events.single()
        assertEquals(AuditOutcome.FAILURE, event.outcome)
        assertEquals("boom", event.reason)
        assertEquals(organisationId.toString(), event.tenantId)
    }

    private fun aspect(repository: CapturingAspectAuditRepository): AuditedActionAspect {
        val clock = Clock.fixed(Instant.parse("2026-07-15T09:00:00Z"), ZoneOffset.UTC)
        return AuditedActionAspect(AuditService(repository, clock), mock(BeanFactory::class.java))
    }

    private fun annotationOf(method: java.lang.reflect.Method): AuditedAction =
        requireNotNull(method.getAnnotation(AuditedAction::class.java))

    private fun joinPoint(
        target: Any,
        method: java.lang.reflect.Method,
        args: Array<Any?>,
    ): ProceedingJoinPoint {
        val joinPoint = mock(ProceedingJoinPoint::class.java)
        val signature = mock(MethodSignature::class.java)
        `when`(joinPoint.signature).thenReturn(signature)
        `when`(signature.method).thenReturn(method)
        `when`(joinPoint.target).thenReturn(target)
        `when`(joinPoint.args).thenReturn(args)
        return joinPoint
    }

    class SampleTarget {
        // Parameter names below only need to exist so the reflected Method exposes real names for
        // the aspect's SpEL binding (#organisationId, #before, #reason) - joinPoint.proceed() is
        // mocked in these tests, so these bodies never actually run.
        @Suppress("UnusedParameter")
        @AuditedAction(
            action = "settings.update",
            resourceType = "ORGANISATION_SETTING",
            tenantId = "#organisationId",
            resourceId = "#organisationId",
            before = "#before",
            after = "#result",
            reason = "#reason",
        )
        fun updateSettings(
            organisationId: UUID,
            before: Map<String, String>,
            reason: String?,
        ): Map<String, String> = mapOf("settings.operational" to "false")

        @Suppress("UnusedParameter")
        @AuditedAction(
            action = "settings.update",
            resourceType = "ORGANISATION_SETTING",
            tenantId = "#organisationId",
        )
        fun failingUpdate(organisationId: UUID): Map<String, String> = error("unreachable in tests")
    }
}

private class CapturingAspectAuditRepository : AuditEventRepository {
    val events = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        events.add(event)
    }
}
