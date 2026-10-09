package com.finaxis.platform.common.audit

import java.time.Instant
import java.util.UUID

/**
 * Immutable audit event captured for administrative and critical state-changing operations.
 */
data class AuditEvent(
    val id: UUID,
    val actorType: String,
    val actorId: String?,
    val tenantId: String?,
    val branchId: String?,
    val action: String,
    val resourceType: String,
    val resourceId: String?,
    val outcome: AuditOutcome,
    val severity: AuditSeverity,
    val reason: String?,
    val requestId: String?,
    val sourceIp: String?,
    val userAgent: String?,
    val before: Map<String, Any?>?,
    val after: Map<String, Any?>?,
    val metadata: Map<String, Any?>,
    val occurredAt: Instant,
) {
    /**
     * The domain event name stored as `audit_event.event_type`: the [action] itself (for example
     * `organisation.activate`), so the column says what happened rather than repeating
     * [resourceType], which is stored separately as `entity_type`.
     */
    val eventType: String get() = action
}
