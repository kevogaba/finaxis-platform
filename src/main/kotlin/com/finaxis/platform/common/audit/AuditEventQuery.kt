package com.finaxis.platform.common.audit

import java.time.Instant
import java.util.UUID

/**
 * Pagination-safe filter for audit event administration queries. Every query is scoped to one
 * tenant; audit events must never be readable across organisation boundaries. Every other field
 * narrows the page further and they combine with AND (#183): [action] is exact, [actionPrefix]
 * a literal prefix of it, [q] a case-insensitive substring of the action, entity type or reason,
 * [severity] exact and [minSeverity] that severity or above. Results are ordered by event time,
 * newest first unless [ascending], with the id as tie-break. [requireValid] holds the bounds.
 */
data class AuditEventFilter(
    val organisationId: UUID,
    val entityType: String? = null,
    val entityId: UUID? = null,
    val actorId: UUID? = null,
    val subjectUserId: UUID? = null,
    val action: String? = null,
    val occurredFrom: Instant? = null,
    val occurredTo: Instant? = null,
    val outcome: AuditOutcome? = null,
    val severity: AuditSeverity? = null,
    val minSeverity: AuditSeverity? = null,
    val branchId: UUID? = null,
    val actionPrefix: String? = null,
    val q: String? = null,
    val actorType: AuditActorType? = null,
    val actorSubject: String? = null,
    val ascending: Boolean = false,
    val page: Int = 0,
    val size: Int = 25,
)

/** The actor kinds an audit search can filter on: the two the application writes today. */
enum class AuditActorType {
    /** A person acting through the API (`actor_user_id` is set). */
    USER,

    /** A system process: a job, a listener or the bootstrap (`actor_user_id` is null). */
    SYSTEM,
}

/**
 * Read projection of a persisted audit event, used by both the paginated search and the by-id
 * read so the two return the same columns (#187).
 */
data class AuditEventDetail(
    val id: UUID,
    val organisationId: UUID,
    val occurredAt: Instant,
    val actorUserId: UUID?,
    val subjectUserId: UUID?,
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
    val items: List<AuditEventDetail>,
    val totalItems: Long,
)

/**
 * One audit event as the CSV export reads it (ADR 0039, point 14): exactly the exported columns,
 * as typed values. The columns that can hold personal data or caller-supplied text (address, user
 * agent, actor subject, the JSON documents, reason, correlation id) and the event id are not part
 * of it: the statement behind it does not select them.
 */
data class AuditExportRow(
    val occurredAt: Instant,
    val action: String,
    val outcome: String,
    val severity: String,
    val actorType: String,
    val actorUserId: UUID?,
    val branchId: UUID?,
    val subjectUserId: UUID?,
    val entityType: String,
    val entityId: UUID?,
    val requestId: String?,
)

/** Where the next export batch starts: strictly after this event in the export's order. */
data class AuditExportCursor(
    val eventTime: Instant,
    val id: UUID,
)

/** One bounded batch of an export and the cursor of the batch after it. */
data class AuditExportBatch(
    val rows: List<AuditExportRow>,
    val next: AuditExportCursor?,
)

/** Output port for paginated, tenant-scoped audit event reads. */
interface AuditEventQueries {
    /** Returns a bounded page of audit events matching [filter]. */
    fun search(filter: AuditEventFilter): AuditEventPage

    /**
     * Counts the events matching [filter], stopping at [limit]: the result is the count when it is
     * below [limit] and [limit] otherwise, so the cost is bounded whatever the log holds.
     */
    fun countBounded(
        filter: AuditEventFilter,
        limit: Int,
    ): Int

    /**
     * Reads at most [limit] export rows matching [filter] in the filter's order, strictly after
     * [after] (the start when null): one short statement per call, keyed on `(event_time, id)`,
     * with no cursor or transaction held between calls. The filter's page and size are ignored.
     */
    fun exportBatch(
        filter: AuditEventFilter,
        after: AuditExportCursor?,
        limit: Int,
    ): AuditExportBatch

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
