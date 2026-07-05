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
    val action: String,
    val resourceType: String,
    val resourceId: String?,
    val outcome: AuditOutcome,
    val reason: String?,
    val requestId: String?,
    val sourceIp: String?,
    val metadata: Map<String, String>,
    val occurredAt: Instant,
)
