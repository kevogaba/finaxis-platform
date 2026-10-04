package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.audit.AuditQueryService
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AuditEventDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AuditEventSummaryResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * REST controller for platform-operator audit reads: the reserved PLATFORM organisation's own
 * log and any tenant's log. Platform context is required; the application layer re-checks
 * `audit.view` in the PLATFORM organisation.
 */
@RestController
@RequestMapping(ApiPaths.PLATFORM)
@Tag(
    name = "Platform Audit Events",
    description = "Platform operator reads of the platform and per-tenant audit logs",
)
@SecurityRequirement(name = "bearer-key")
@Validated
class PlatformAuditEventController(
    private val auditQueryService: AuditQueryService,
) {
    /** Searches the reserved platform organisation's audit log. */
    @GetMapping("/audit-events")
    @PreAuthorize("hasAuthority('audit.view')")
    @Operation(
        summary = "Search platform audit events",
        description = "Searches audit events recorded under the reserved platform organisation.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Audit event page"),
        ApiResponse(
            responseCode = "400",
            description = "Invalid page or filter",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun searchPlatformAuditEvents(
        @RequestParam(required = false, name = "entity_type") entityType: String?,
        @RequestParam(required = false, name = "entity_id") entityId: UUID?,
        @RequestParam(required = false, name = "actor_id") actorId: UUID?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false, name = "occurred_from") occurredFrom: Instant?,
        @RequestParam(required = false, name = "occurred_to") occurredTo: Instant?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<AuditEventSummaryResponse> =
        search(
            AuditEventFilter(
                organisationId = PlatformOrganisation.ID,
                entityType = entityType,
                entityId = entityId,
                actorId = actorId,
                action = action,
                occurredFrom = occurredFrom,
                occurredTo = occurredTo,
                page = page,
                size = size,
            ),
        )

    /** Retrieves one detailed audit event recorded under the reserved platform organisation. */
    @GetMapping("/audit-events/{event_id}")
    @PreAuthorize("hasAuthority('audit.view')")
    @Operation(
        summary = "Get platform audit event",
        description =
            "Retrieves a detailed audit event recorded under the reserved platform " +
                "organisation. Events of any other organisation are reported as not found.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Audit event details",
            content = [Content(schema = Schema(implementation = AuditEventDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Audit event not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun getPlatformAuditEvent(
        @PathVariable("event_id") eventId: UUID,
    ): AuditEventDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        return auditQueryService
            .getForPlatform(eventId, PlatformOrganisation.ID, caller.actorId)
            .toResponse()
    }

    /** Searches the audit log of one tenant organisation on behalf of a platform operator. */
    @GetMapping("/tenants/{tenant_id}/audit-events")
    @PreAuthorize("hasAuthority('audit.view')")
    @Operation(
        summary = "Search tenant audit events",
        description = "Searches the audit events of one tenant as a platform operator.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Audit event page"),
        ApiResponse(
            responseCode = "400",
            description = "Invalid page or filter",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun searchTenantAuditEvents(
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestParam(required = false, name = "entity_type") entityType: String?,
        @RequestParam(required = false, name = "entity_id") entityId: UUID?,
        @RequestParam(required = false, name = "actor_id") actorId: UUID?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false, name = "occurred_from") occurredFrom: Instant?,
        @RequestParam(required = false, name = "occurred_to") occurredTo: Instant?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<AuditEventSummaryResponse> =
        search(
            AuditEventFilter(
                organisationId = tenantId,
                entityType = entityType,
                entityId = entityId,
                actorId = actorId,
                action = action,
                occurredFrom = occurredFrom,
                occurredTo = occurredTo,
                page = page,
                size = size,
            ),
        )

    private fun search(filter: AuditEventFilter): ApiPage<AuditEventSummaryResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        val result = auditQueryService.searchForPlatform(filter, caller.actorId)
        return apiPageOf(
            items = result.items.map { it.toResponse() },
            number = filter.page,
            size = filter.size,
            totalItems = result.totalItems,
        )
    }

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
