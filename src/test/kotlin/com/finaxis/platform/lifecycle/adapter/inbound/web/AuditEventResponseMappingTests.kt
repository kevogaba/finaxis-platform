package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.audit.AuditEventDetail
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiJsonCodec
import org.junit.jupiter.api.Test
import tools.jackson.core.type.TypeReference
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The audit summary and detail responses carry the same names for the same columns (#187, option
 * 1: additive). The older names stay as aliases with the same values.
 */
class AuditEventResponseMappingTests {
    private val mapper = ApiJsonCodec().mapper

    @Test
    fun `summary and detail responses expose the same names with the same values`() {
        val detail = detail()

        val summaryJson = wire(detail.toSummaryResponse())
        val detailJson = wire(detail.toResponse())

        assertEquals(AUDIT_EVENT_WIRE_FIELDS, summaryJson.keys)
        assertEquals(AUDIT_EVENT_WIRE_FIELDS, detailJson.keys)
        assertEquals(detailJson, summaryJson)
    }

    @Test
    fun `platform page items keep the shape but withhold the sensitive set`() {
        val detail = detail()

        val platformJson = wire(detail.toPlatformSummaryResponse())
        val tenantJson = wire(detail.toSummaryResponse())

        assertEquals(AUDIT_EVENT_WIRE_FIELDS, platformJson.keys)
        PLATFORM_WITHHELD_FIELDS.forEach { field ->
            assertNotNull(tenantJson[field], field)
            assertNull(platformJson[field], field)
        }
        assertEquals(
            tenantJson - PLATFORM_WITHHELD_FIELDS,
            platformJson - PLATFORM_WITHHELD_FIELDS,
        )
    }

    @Test
    fun `the older names are aliases of the canonical ones`() {
        val detail = detail()

        val json = wire(detail.toSummaryResponse())

        assertEquals(detail.entityType, json["resource_type"])
        assertEquals(json["entity_type"], json["resource_type"])
        assertEquals(detail.entityId.toString(), json["resource_id"])
        assertEquals(json["entity_id"], json["resource_id"])
        assertEquals(detail.actorUserId.toString(), json["actor_id"])
        assertEquals(json["actor_user_id"], json["actor_id"])
        assertEquals("organisation.activate", json["event_type"])
    }

    @Test
    fun `a resource id that is not a UUID reads as null under both names`() {
        val json = wire(detail().copy(entityId = null).toResponse())

        assertNull(json["entity_id"])
        assertNull(json["resource_id"])
        assertEquals(true, json.containsKey("resource_id"))
    }

    private fun wire(value: Any): Map<String, Any?> =
        mapper.readValue(
            mapper.writeValueAsString(value),
            object : TypeReference<Map<String, Any?>>() {},
        )

    private fun detail() =
        AuditEventDetail(
            id = uuidV7(),
            organisationId = uuidV7(),
            occurredAt = Instant.parse("2026-07-25T08:12:00Z"),
            actorUserId = uuidV7(),
            actorExternalSubject = "local.admin",
            actorType = "USER",
            branchId = uuidV7(),
            eventType = "organisation.activate",
            entityType = "ORGANISATION",
            entityId = uuidV7(),
            action = "organisation.activate",
            outcome = AuditOutcome.SUCCESS,
            severity = AuditSeverity.HIGH,
            ipAddress = "203.0.113.7",
            userAgent = "curl/8.0",
            correlationId = "correlation-187",
            requestId = "request-187",
            beforeJson = "{\"status\":\"PENDING_APPROVAL\"}",
            afterJson = "{\"status\":\"ACTIVE\"}",
            metadataJson = "{\"from\":\"PENDING_APPROVAL\",\"to\":\"ACTIVE\"}",
            reason = "Approved for onboarding.",
        )

    companion object {
        /** The fields platform audit page items carry as null (owner decision, #187 review). */
        val PLATFORM_WITHHELD_FIELDS =
            setOf(
                "before_json",
                "after_json",
                "metadata_json",
                "user_agent",
                "ip_address",
                "actor_external_subject",
            )

        /** Every field both audit responses carry, in snake_case wire form. */
        val AUDIT_EVENT_WIRE_FIELDS =
            setOf(
                "id",
                "organisation_id",
                "occurred_at",
                "actor_type",
                "actor_id",
                "actor_user_id",
                "actor_external_subject",
                "branch_id",
                "event_type",
                "action",
                "resource_type",
                "resource_id",
                "entity_type",
                "entity_id",
                "outcome",
                "severity",
                "reason",
                "ip_address",
                "user_agent",
                "correlation_id",
                "request_id",
                "before_json",
                "after_json",
                "metadata_json",
            )
    }
}
