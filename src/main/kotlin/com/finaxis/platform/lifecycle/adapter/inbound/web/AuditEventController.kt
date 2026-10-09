package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.audit.AuditQueryService
import com.finaxis.platform.common.audit.AuditSearchParameters
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AuditEventDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AuditEventSummaryResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
        description =
            "Searches audit events in the active tenant organisation. Filters combine with " +
                "AND; results are ordered by event time (sort_dir), then id.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Audit event page; every item has the detail response's fields",
        ),
        ApiResponse(
            responseCode = "400",
            description =
                "Invalid page, sort or filter: a value outside its set or bounds, severity " +
                    "with min_severity, or q without a window of at most 31 days",
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
        @Parameter(description = AUDIT_ACTION_DOC)
        @RequestParam(required = false) action: String?,
        @Parameter(description = AUDIT_ACTION_PREFIX_DOC)
        @RequestParam(required = false, name = "action_prefix") actionPrefix: String?,
        @RequestParam(required = false, name = "occurred_from") occurredFrom: Instant?,
        @RequestParam(required = false, name = "occurred_to") occurredTo: Instant?,
        @Parameter(
            description = AUDIT_OUTCOME_DOC,
            schema = Schema(allowableValues = ["SUCCESS", "FAILURE", "DENIED"]),
        )
        @RequestParam(required = false) outcome: String?,
        @Parameter(
            description = AUDIT_SEVERITY_DOC,
            schema = Schema(allowableValues = ["INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"]),
        )
        @RequestParam(required = false) severity: String?,
        @Parameter(
            description = AUDIT_MIN_SEVERITY_DOC,
            schema = Schema(allowableValues = ["INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"]),
        )
        @RequestParam(required = false, name = "min_severity") minSeverity: String?,
        @Parameter(description = AUDIT_BRANCH_DOC)
        @RequestParam(required = false, name = "branch_id") branchId: UUID?,
        @Parameter(description = AUDIT_Q_DOC)
        @RequestParam(required = false) q: String?,
        @Parameter(
            description = AUDIT_ACTOR_TYPE_DOC,
            schema = Schema(allowableValues = ["USER", "SYSTEM"]),
        )
        @RequestParam(required = false, name = "actor_type") actorType: String?,
        @Parameter(description = AUDIT_ACTOR_SUBJECT_DOC)
        @RequestParam(required = false, name = "actor_subject") actorSubject: String?,
        @Parameter(description = AUDIT_SORT_DIR_DOC)
        @RequestParam(required = false, name = "sort_dir") sortDir: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<AuditEventSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val filter =
            AuditSearchParameters(
                entityType = entityType,
                entityId = entityId,
                actorId = actorId,
                action = action,
                occurredFrom = occurredFrom,
                occurredTo = occurredTo,
                outcome = outcome,
                severity = severity,
                minSeverity = minSeverity,
                branchId = branchId,
                actionPrefix = actionPrefix,
                q = q,
                actorType = actorType,
                actorSubject = actorSubject,
                sortDir = sortDir,
            ).toFilter(caller.activeOrganisationId, page, size)
        val result = auditQueryService.search(filter = filter, actorId = caller.actorId)
        return apiPageOf(
            items = result.items.map { it.toSummaryResponse() },
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
            description = "Audit event details; the same fields as a search item",
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

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
