package com.finaxis.platform.common.audit

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuditServiceTests {
    @Test
    fun `audit service records critical action with request metadata`() {
        val service = service()

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

    @Test
    fun `record defaults to INFO severity`() {
        val service = service()

        service.record(
            AuditCommand(
                actorType = "USER",
                actorId = "user-1",
                tenantId = "tenant-1",
                action = "iam.role.updated",
                resourceType = "ROLE",
                resourceId = "role-1",
                outcome = AuditOutcome.SUCCESS,
            ),
        )

        assertEquals(AuditSeverity.INFO, repository.events.single().severity)
    }

    @Test
    fun `record masks metadata values matching sensitive key names`() {
        val service = service()

        service.record(
            AuditCommand(
                actorType = "SYSTEM",
                actorId = null,
                tenantId = "tenant-1",
                action = "user.keycloak_provisioning",
                resourceType = "USER",
                resourceId = "user-1",
                outcome = AuditOutcome.SUCCESS,
                metadata = mapOf("authToken" to "abc123", "national_id" to "12345", "note" to "ok"),
            ),
        )

        val metadata = repository.events.single().metadata
        assertEquals("***REDACTED***", metadata["authToken"])
        assertEquals("***REDACTED***", metadata["national_id"])
        assertEquals("ok", metadata["note"])
    }

    @Test
    fun `record masks values explicitly wrapped in Redacted regardless of key name`() {
        val service = service()

        service.record(
            AuditCommand(
                actorType = "SYSTEM",
                actorId = null,
                tenantId = "tenant-1",
                action = "settings.update",
                resourceType = "ORGANISATION_SETTING",
                resourceId = "tenant-1",
                outcome = AuditOutcome.SUCCESS,
                metadata = mapOf("accountNumber" to Redacted("KE00112233")),
            ),
        )

        assertEquals("***REDACTED***", repository.events.single().metadata["accountNumber"])
    }

    @Test
    fun `recordSuccess derives SYSTEM actor type for the system actor id`() {
        val service = service()
        val systemActorId = UUID.fromString("00000000-0000-0000-0000-000000000001")

        service.recordSuccess(
            actorId = systemActorId,
            tenantId = UUID.randomUUID(),
            action = "organisation.activate",
            resourceType = "ORGANISATION",
            resourceId = "org-1",
        )

        assertEquals("SYSTEM", repository.events.single().actorType)
    }

    @Test
    fun `recordFailure records the supplied outcome and reason`() {
        val service = service()
        val tenantId = UUID.randomUUID()

        service.recordFailure(
            actorId = UUID.randomUUID(),
            tenantId = tenantId,
            action = "organisation.suspend",
            resourceType = "ORGANISATION",
            resourceId = tenantId.toString(),
            outcome = AuditOutcome.DENIED,
            reason = "guard rejected the transition",
        )

        val event = repository.events.single()
        assertEquals(AuditOutcome.DENIED, event.outcome)
        assertEquals("guard rejected the transition", event.reason)
    }

    @Test
    fun `recordLifecycleTransition maps from and to state into metadata`() {
        val service = service()
        val tenantId = UUID.randomUUID()

        service.recordLifecycleTransition(
            actorId = null,
            tenantId = tenantId,
            aggregateType = "ORGANISATION",
            aggregateId = tenantId.toString(),
            transition = "ACTIVATE",
            fromState = "PROVISIONING",
            toState = "ACTIVE",
            outcome = AuditOutcome.SUCCESS,
        )

        val event = repository.events.single()
        assertEquals("organisation.activate", event.action)
        assertEquals("PROVISIONING", event.metadata["from"])
        assertEquals("ACTIVE", event.metadata["to"])
    }

    @Test
    fun `recordSecurityEvent defaults to HIGH severity`() {
        val service = service()

        service.recordSecurityEvent(
            actorId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            action = "auth.access_denied",
            resourceType = "SESSION",
            resourceId = null,
            outcome = AuditOutcome.DENIED,
        )

        assertEquals(AuditSeverity.HIGH, repository.events.single().severity)
    }

    @Test
    fun `recordIamChange carries before and after summaries`() {
        val service = service()
        val tenantId = UUID.randomUUID()

        service.recordIamChange(
            actorId = UUID.randomUUID(),
            tenantId = tenantId,
            action = "role.assign_permission",
            resourceType = "ROLE",
            resourceId = "role-1",
            before = mapOf("permissions" to listOf("a")),
            after = mapOf("permissions" to listOf("a", "b")),
        )

        val event = repository.events.single()
        assertEquals(mapOf("permissions" to listOf("a")), event.before)
        assertEquals(mapOf("permissions" to listOf("a", "b")), event.after)
    }

    @Test
    fun `recordExternalDispatch records the external system reference`() {
        val service = service()
        val tenantId = UUID.randomUUID()

        service.recordExternalDispatch(
            actorId = UUID.randomUUID(),
            tenantId = tenantId,
            action = "user.keycloak_provisioning",
            outcome = AuditOutcome.SUCCESS,
            externalSystemRef = "KEYCLOAK",
            resourceType = "USER",
            resourceId = "user-1",
        )

        val event = repository.events.single()
        assertEquals("KEYCLOAK", event.metadata[AuditMetadataKeys.EXTERNAL_SYSTEM_REFERENCE])
    }

    @Test
    fun `recordSettingsChange records action, before, and after`() {
        val service = service()
        val tenantId = UUID.randomUUID()

        service.recordSettingsChange(
            actorId = UUID.randomUUID(),
            tenantId = tenantId,
            resourceId = tenantId.toString(),
            before = mapOf("settings.operational" to "false"),
            after = mapOf("settings.operational" to "true"),
        )

        val event = repository.events.single()
        assertEquals("settings.update", event.action)
        assertEquals("false", event.before?.get("settings.operational"))
        assertEquals("true", event.after?.get("settings.operational"))
    }

    @Test
    fun `recordSuccess with no actor id or explicit type falls back to USER`() {
        val service = service()

        service.recordSuccess(
            actorId = null,
            tenantId = UUID.randomUUID(),
            action = "user.invite",
            resourceType = "USER",
            resourceId = "user-1",
        )

        assertEquals("USER", repository.events.single().actorType)
        assertNull(repository.events.single().actorId)
    }

    private val repository = CapturingAuditEventRepository()

    private fun service(): AuditService =
        AuditService(
            repository,
            Clock.fixed(Instant.parse("2026-07-06T08:00:00Z"), ZoneOffset.UTC),
        )
}

private class CapturingAuditEventRepository : AuditEventRepository {
    val events = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        events.add(event)
    }
}
