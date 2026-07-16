package com.finaxis.platform.common.audit

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
) {
    /** Lists audit events for a tenant, most recent first. */
    fun listByTenant(
        organisationId: UUID,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): AuditEventPage =
        search(AuditEventFilter(organisationId = organisationId, page = page, size = size))

    /** Lists audit events recorded against one entity within a tenant. */
    fun listByEntity(
        organisationId: UUID,
        entityType: String,
        entityId: UUID,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): AuditEventPage =
        search(
            AuditEventFilter(
                organisationId = organisationId,
                entityType = entityType,
                entityId = entityId,
                page = page,
                size = size,
            ),
        )

    /** Lists audit events recorded by one actor within a tenant. */
    fun listByActor(
        organisationId: UUID,
        actorId: UUID,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): AuditEventPage =
        search(
            AuditEventFilter(
                organisationId = organisationId,
                actorId = actorId,
                page = page,
                size = size,
            ),
        )

    /** Lists audit events by action and/or occurrence date range within a tenant. */
    fun listByActionAndDateRange(
        organisationId: UUID,
        action: String? = null,
        occurredFrom: Instant? = null,
        occurredTo: Instant? = null,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): AuditEventPage =
        search(
            AuditEventFilter(
                organisationId = organisationId,
                action = action,
                occurredFrom = occurredFrom,
                occurredTo = occurredTo,
                page = page,
                size = size,
            ),
        )

    /** Returns a bounded page of audit events matching an arbitrary [filter]. */
    fun search(filter: AuditEventFilter): AuditEventPage {
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
