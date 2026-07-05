package com.finaxis.platform.common.audit

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class AuditServiceTests {
    @Test
    fun `audit service records critical action with request metadata`() {
        val repository = CapturingAuditEventRepository()
        val service =
            AuditService(
                repository,
                Clock.fixed(Instant.parse("2026-07-06T08:00:00Z"), ZoneOffset.UTC),
            )

        service.record(
            AuditCommand(
                actorType = "USER",
                actorId = "user-1",
                tenantId = "tenant-1",
                action = "iam.role.updated",
                resourceType = "ROLE",
                resourceId = "role-1",
                outcome = AuditOutcome.SUCCESS,
                reason = "approved change",
                requestId = "request-1",
                sourceIp = "203.0.113.10",
                metadata = mapOf("permission" to "iam.role.write"),
            ),
        )

        val event = repository.events.single()
        assertEquals("iam.role.updated", event.action)
        assertEquals(AuditOutcome.SUCCESS, event.outcome)
        assertEquals("request-1", event.requestId)
        assertEquals("permission", event.metadata.keys.single())
    }
}

private class CapturingAuditEventRepository : AuditEventRepository {
    val events = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        events.add(event)
    }
}
