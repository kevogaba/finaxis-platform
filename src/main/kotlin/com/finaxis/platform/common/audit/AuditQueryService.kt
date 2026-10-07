package com.finaxis.platform.common.audit

import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.web.api.requireValidPage
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Read-side application service for paginated audit event queries. Every tenant method is scoped
 * to, and authorised in, one organisation; there is no cross-organisation lookup path. The two
 * `*ForPlatform` methods are the sole exception: they read one named organisation's log but
 * authorise the actor in the reserved PLATFORM organisation instead.
 */
@Service
class AuditQueryService(
    private val queries: AuditEventQueries,
    private val permissionGuard: AuditPermissionGuard,
) {
    /** Gets an individual detailed audit event by id, verifying tenant scope and permissions. */
    @GatedRead
    fun get(
        eventId: UUID,
        organisationId: UUID,
        actorId: UUID,
    ): AuditEventDetail {
        permissionGuard.requireTenantPermission(actorId, organisationId, "audit.view")
        return findOrThrow(eventId, organisationId)
    }

    /**
     * Gets one detailed audit event of [organisationId] (a tenant, or the PLATFORM organisation
     * itself) for a platform operator, who must hold `audit.view` in the PLATFORM organisation.
     */
    @GatedRead
    fun getForPlatform(
        eventId: UUID,
        organisationId: UUID,
        actorId: UUID,
    ): AuditEventDetail {
        requirePlatformAuditView(actorId)
        return findOrThrow(eventId, organisationId)
    }

    /** Lists audit events for a tenant, most recent first. */
    @GatedRead
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
    @GatedRead
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
    @GatedRead
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
    @GatedRead
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
    @GatedRead
    fun search(
        filter: AuditEventFilter,
        actorId: UUID,
    ): AuditEventPage {
        permissionGuard.requireTenantPermission(actorId, filter.organisationId, "audit.view")
        return boundedSearch(filter)
    }

    /**
     * Returns a bounded page of the log of [AuditEventFilter.organisationId] (a tenant, or the
     * PLATFORM organisation itself) for a platform operator, who must hold `audit.view` in the
     * PLATFORM organisation. Tenant users never reach this path.
     */
    @GatedRead
    fun searchForPlatform(
        filter: AuditEventFilter,
        actorId: UUID,
    ): AuditEventPage {
        requirePlatformAuditView(actorId)
        return boundedSearch(filter)
    }

    private fun requirePlatformAuditView(actorId: UUID) =
        permissionGuard.requireTenantPermission(actorId, PlatformOrganisation.ID, "audit.view")

    private fun findOrThrow(
        eventId: UUID,
        organisationId: UUID,
    ): AuditEventDetail =
        queries.findById(eventId, organisationId)
            ?: throw ResourceNotFoundException(
                code = "audit_event_not_found",
                safeDetail = "Audit event not found: $eventId",
            )

    private fun boundedSearch(filter: AuditEventFilter): AuditEventPage {
        requireValidPage(filter.page, filter.size)
        return queries.search(filter)
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 25
    }
}
