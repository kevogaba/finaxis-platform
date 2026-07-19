package com.finaxis.platform.common.audit

import com.finaxis.platform.common.application.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Read-side application service for paginated audit event queries. Every method is scoped to a
 * tenant; there is no cross-organisation lookup path.
 */
@Service
class AuditQueryService(
    private val queries: AuditEventQueries,
    private val permissionGuard: AuditPermissionGuard,
) {
    /** Gets an individual detailed audit event by id, verifying tenant scope and permissions. */
    fun get(
        eventId: UUID,
        organisationId: UUID,
        actorId: UUID,
    ): AuditEventDetail {
        permissionGuard.requireTenantPermission(actorId, organisationId, "audit.view")
        return queries.findById(eventId, organisationId)
            ?: throw ResourceNotFoundException(
                code = "audit_event_not_found",
                safeDetail = "Audit event not found: $eventId",
            )
    }

    /** Lists audit events for a tenant, most recent first. */
    fun listByTenant(
        organisationId: UUID,
        actorId: UUID,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): AuditEventPage =
        search(
            filter = AuditEventFilter(organisationId = organisationId, page = page, size = size),
            actorId = actorId,
        )

    /** Lists audit events recorded against one entity within a tenant. */
    fun listByEntity(
        organisationId: UUID,
        actorId: UUID,
        entityType: String,
        entityId: UUID,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): AuditEventPage =
        search(
            filter =
                AuditEventFilter(
                    organisationId = organisationId,
                    entityType = entityType,
                    entityId = entityId,
                    page = page,
                    size = size,
                ),
            actorId = actorId,
        )

    /** Lists audit events recorded by one actor within a tenant. */
    fun listByActor(
        organisationId: UUID,
        actorId: UUID,
        targetActorId: UUID,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): AuditEventPage =
        search(
            filter =
                AuditEventFilter(
                    organisationId = organisationId,
                    actorId = targetActorId,
                    page = page,
                    size = size,
                ),
            actorId = actorId,
        )

    /** Lists audit events by action and/or occurrence date range within a tenant. */
    fun listByActionAndDateRange(
        organisationId: UUID,
        actorId: UUID,
        action: String? = null,
        occurredFrom: Instant? = null,
        occurredTo: Instant? = null,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): AuditEventPage =
        search(
            filter =
                AuditEventFilter(
                    organisationId = organisationId,
                    action = action,
                    occurredFrom = occurredFrom,
                    occurredTo = occurredTo,
                    page = page,
                    size = size,
                ),
            actorId = actorId,
        )

    /** Returns a bounded page of audit events matching an arbitrary [filter]. */
    fun search(
        filter: AuditEventFilter,
        actorId: UUID,
    ): AuditEventPage {
        permissionGuard.requireTenantPermission(actorId, filter.organisationId, "audit.view")
        require(filter.page >= 0) { "Page must not be negative." }
        require(filter.size in 1..MAXIMUM_PAGE_SIZE) {
            "Page size must be between 1 and $MAXIMUM_PAGE_SIZE."
        }
        return queries.search(filter)
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 25
        const val MAXIMUM_PAGE_SIZE = 100
    }
}
