package com.finaxis.platform.common.audit

import java.time.Instant
import java.util.UUID

/**
 * Pagination-safe filter for audit event administration queries. Every query is scoped to one
 * tenant; audit events must never be readable across organisation boundaries.
 */
data class AuditEventFilter(
    val organisationId: UUID,
    val entityType: String? = null,
    val entityId: UUID? = null,
    val actorId: UUID? = null,
    val action: String? = null,
    val occurredFrom: Instant? = null,
    val occurredTo: Instant? = null,
    val page: Int = 0,
    val size: Int = 25,
)

/** Read projection of a persisted audit event for administration queries. */
data class AuditEventSummary(
    val id: UUID,
    val occurredAt: Instant,
    val actorType: String,
    val actorId: UUID?,
    val branchId: UUID?,
    val action: String,
    val resourceType: String,
    val resourceId: String?,
    val outcome: AuditOutcome,
    val severity: AuditSeverity,
    val reason: String?,
)

/** Detailed read projection of a persisted audit event for entity details. */
data class AuditEventDetail(
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
    val outcome: AuditOutcome,
    val severity: AuditSeverity,
    val ipAddress: String?,
    val userAgent: String?,
    val correlationId: String?,
    val requestId: String?,
    val beforeJson: String?,
    val afterJson: String?,
    val metadataJson: String,
    val reason: String?,
)

/** Page response for audit event administration queries. */
data class AuditEventPage(
    val items: List<AuditEventSummary>,
    val totalItems: Long,
)

/** Output port for paginated, tenant-scoped audit event reads. */
interface AuditEventQueries {
    /** Returns a bounded page of audit events matching [filter]. */
    fun search(filter: AuditEventFilter): AuditEventPage

    /** Finds the detailed audit event by id within an organisation scope. */
    fun findById(
        id: UUID,
        organisationId: UUID,
    ): AuditEventDetail?
}

/**
 * Port interface for verifying audit-module access permissions.
 * Implemented outside the common module to invert the dependency on IAM/authorization.
 */
interface AuditPermissionGuard {
    /** Requires the actor to have the specified permission code in the tenant organisation. */
    fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    )
}
