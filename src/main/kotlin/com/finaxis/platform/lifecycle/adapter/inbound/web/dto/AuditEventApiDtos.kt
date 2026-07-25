package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import java.time.Instant
import java.util.UUID

/** Summary representation of an audit event returned in a paginated search. */
data class AuditEventSummaryResponse(
    val id: UUID,
    val occurredAt: Instant,
    val actorType: String,
    val actorId: UUID?,
    val branchId: UUID?,
    val action: String,
    val resourceType: String,
    val resourceId: String?,
    val outcome: String,
    val severity: String,
    val reason: String?,
)

/** Detailed representation of one tenant-scoped audit event. */
data class AuditEventDetailResponse(
    val id: UUID,
    val organisationId: UUID,
    val occurredAt: Instant,
    val actorUserId: UUID?,
    val actorExternalSubject: String?,
    val actorType: String,
    val branchId: UUID?,
    val eventType: String,
    val entityType: String,
    val entityId: UUID?,
    val action: String,
    val outcome: String,
    val severity: String,
    val ipAddress: String?,
    val userAgent: String?,
    val correlationId: String?,
    val requestId: String?,
    val beforeJson: String?,
    val afterJson: String?,
    val metadataJson: String,
    val reason: String?,
)
