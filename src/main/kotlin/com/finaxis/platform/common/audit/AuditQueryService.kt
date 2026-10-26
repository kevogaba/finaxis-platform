package com.finaxis.platform.common.audit

import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.common.web.api.requireValidPage
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Read-side application service for paginated audit event queries. Every tenant method is scoped
 * to, and authorised in, one organisation; there is no cross-organisation lookup path. The two
 * `*ForPlatform` methods are the sole exception: they read one named organisation's log but
 * authorise the actor in the reserved PLATFORM organisation instead.
 *
 * Every search validates its filter ([requireValid]) after the permission check and before any
 * read, so a bad filter is a 400 with no query run. [prepareExport] does the same, in the order
 * permission (403), then filter and window (400), then the bounded count (400 when too large).
 */
@Service
class AuditQueryService(
    private val queries: AuditEventQueries,
    private val permissionGuard: AuditPermissionGuard,
    private val auditService: AuditService,
    private val clock: Clock = Clock.systemUTC(),
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
     *
     * [AuditEventFilter.actorSubject] is refused with a 400: the platform pages withhold
     * `actor_external_subject`, and a filter on it would reveal the withheld value one guess at
     * a time.
     */
    @GatedRead
    fun searchForPlatform(
        filter: AuditEventFilter,
        actorId: UUID,
    ): AuditEventPage {
        requirePlatformAuditView(actorId)
        if (filter.actorSubject != null) {
            throw InvalidPageRequestException(
                "actor_subject",
                "Not available on platform audit searches, which withhold the actor subject.",
            )
        }
        return boundedSearch(filter)
    }

    /**
     * Authorises and validates a CSV export of the tenant audit log and returns it ready to
     * stream (#167, ADR 0039). The order is fixed: the caller must hold `audit.export` and, the
     * catalogue pairing it with the view, `audit.view` (403 naming the first missing code, raised
     * before anything is parsed or read); then the filter is parsed and bounded (400, with
     * `occurred_from` required and a window of at most 31 days; an absent `occurred_to` is pinned
     * to the request instant and a later one is clamped to it, so the counted and the streamed
     * sets share an upper edge no later than the request); then the
     * matches are counted, stopping at one more than [AUDIT_EXPORT_ROW_CAP]. More than the cap is a
     * 400 `audit.export_too_large`, never a truncated file, and writes a `FAILURE` audit row of 0
     * rows independently of the refusal. A 403 or a bad filter writes no export row.
     */
    @GatedRead
    fun prepareExport(request: AuditExportRequest): AuditExport {
        permissionGuard.requireTenantPermission(
            request.actorId,
            request.organisationId,
            AUDIT_EXPORT_PERMISSION,
        )
        val now = clock.instant()
        val parsed =
            request.parameters.toFilter(request.organisationId, 0, AUDIT_EXPORT_BATCH_SIZE)
        val filter = parsed.copy(occurredTo = minOf(parsed.occurredTo ?: now, now))
        filter.requireExportWindow(now)
        filter.requireValid(now)
        if (queries.countBounded(filter, AUDIT_EXPORT_ROW_CAP + 1) > AUDIT_EXPORT_ROW_CAP) {
            writeExportAudit(
                auditService,
                request,
                AuditOutcome.FAILURE,
                rowCount = 0,
                errorClass = null,
                independently = true,
            )
            throw InvalidRequestException(
                AUDIT_EXPORT_TOO_LARGE_CODE,
                "Too many audit events match for one export. " +
                    "Narrow the occurred_from and occurred_to window or add filters.",
            )
        }
        return AuditExport(now, filter, request, queries, auditService)
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
        filter.requireValid(clock.instant())
        return queries.search(filter)
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 25
    }
}
