package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.audit.AuditEventDetail
import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.audit.AuditEventSummary
import com.finaxis.platform.common.audit.AuditQueryService
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

/** REST controller for tenant audit event administration queries. */
@RestController
@RequestMapping(ApiPaths.AUDIT_EVENTS)
@Tag(name = "Audit Events", description = "Tenant audit event administration")
@SecurityRequirement(name = "bearer-key")
@Validated
class AuditEventController(
    private val auditQueryService: AuditQueryService,
) {
    /** Searches tenant audit events using the supplied optional filters. */
    @GetMapping
    @PreAuthorize("hasAuthority('audit.view')")
    @Operation(
        summary = "Search audit events",
        description = "Searches audit events in the active tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Audit event page",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
        ),
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
    fun searchAuditEvents(
        @RequestParam(required = false, name = "entity_type") entityType: String?,
        @RequestParam(required = false, name = "entity_id") entityId: UUID?,
        @RequestParam(required = false, name = "actor_id") actorId: UUID?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false, name = "occurred_from") occurredFrom: Instant?,
        @RequestParam(required = false, name = "occurred_to") occurredTo: Instant?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<AuditEventSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val result =
            auditQueryService.search(
                filter =
                    AuditEventFilter(
                        organisationId = caller.activeOrganisationId,
                        entityType = entityType,
                        entityId = entityId,
                        actorId = actorId,
                        action = action,
                        occurredFrom = occurredFrom,
                        occurredTo = occurredTo,
                        page = page,
                        size = size,
                    ),
                actorId = caller.actorId,
            )
        return apiPageOf(
            items = result.items.map { it.toResponse() },
            number = page,
            size = size,
            totalItems = result.totalItems,
        )
    }

    /** Retrieves a detailed audit event in the active tenant organisation. */
    @GetMapping("/{event_id}")
    @PreAuthorize("hasAuthority('audit.view')")
    @Operation(
        summary = "Get audit event",
        description = "Retrieves a detailed audit event in the active tenant organisation.",
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
    fun getAuditEvent(
        @PathVariable("event_id") eventId: UUID,
    ): AuditEventDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return auditQueryService
            .get(eventId, caller.activeOrganisationId, caller.actorId)
            .toResponse()
    }

    private fun AuditEventSummary.toResponse() =
        AuditEventSummaryResponse(
            id = id,
            occurredAt = occurredAt,
            actorType = actorType,
            actorId = actorId,
            branchId = branchId,
            action = action,
            resourceType = resourceType,
            resourceId = resourceId,
            outcome = outcome.name,
            severity = severity.name,
            reason = reason,
        )

    private fun AuditEventDetail.toResponse() =
        AuditEventDetailResponse(
            id = id,
            organisationId = organisationId,
            occurredAt = occurredAt,
            actorUserId = actorUserId,
            actorExternalSubject = actorExternalSubject,
            actorType = actorType,
            branchId = branchId,
            eventType = eventType,
            entityType = entityType,
            entityId = entityId,
            action = action,
            outcome = outcome.name,
            severity = severity.name,
            ipAddress = ipAddress,
            userAgent = userAgent,
            correlationId = correlationId,
            requestId = requestId,
            beforeJson = beforeJson,
            afterJson = afterJson,
            metadataJson = metadataJson,
            reason = reason,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
