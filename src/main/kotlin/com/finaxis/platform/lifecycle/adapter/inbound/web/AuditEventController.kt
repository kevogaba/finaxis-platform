package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.audit.AuditExportRequest
import com.finaxis.platform.common.audit.AuditQueryService
import com.finaxis.platform.common.audit.AuditSearchParameters
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.web.api.ApiDateFormats
import com.finaxis.platform.common.web.api.ApiJsonCodec
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
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** REST controller for tenant audit event administration queries. */
@RestController
@RequestMapping(ApiPaths.AUDIT_EVENTS)
@Tag(name = "Audit Events", description = "Tenant audit event administration")
@SecurityRequirement(name = "bearer-key")
@Validated
class AuditEventController(
    private val auditQueryService: AuditQueryService,
    private val apiJsonCodec: ApiJsonCodec,
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
        @Parameter(description = AUDIT_SUBJECT_USER_ID_DOC)
        @RequestParam(required = false, name = "subject_user_id") subjectUserId: UUID?,
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
                subjectUserId = subjectUserId,
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

    /**
     * Streams the tenant audit log as CSV (#167, ADR 0039). It carries no `@PreAuthorize`: the
     * service check is its only authorisation, so a refusal always names the missing code, and the
     * OpenAPI 403 text is written by hand below. Everything the stream needs is captured here, on
     * the request thread, before the body starts.
     */
    @GetMapping("/export")
    @Operation(
        summary = "Export audit events as CSV",
        description =
            "Streams the audit events of the active tenant organisation that match the same " +
                "filters as the search (no page or size) as `text/csv; charset=UTF-8` " +
                "(RFC 4180, CRLF records, a header row). Requires `audit.export` and its view " +
                "`audit.view`. `occurred_from` is required and the window to `occurred_to` (or " +
                "now; a later `occurred_to` is clamped to the request instant) is at most 31 " +
                "days. More than 10,000 matching rows is refused with " +
                "`audit.export_too_large`, never truncated (a row arriving mid-stream that " +
                "passes the cap aborts the transfer instead). Columns, in order: occurred_at, " +
                "action, outcome, severity, actor_type, actor_user_id, branch_id, " +
                "subject_user_id, entity_type, entity_id, request_id; the address, user agent, " +
                "actor subject, JSON documents, reason and correlation id are never exported. A " +
                "cell starting with = + - @ TAB or CR is prefixed with a single quote. Each " +
                "export request writes one `audit.export` audit row (filters, with `q` and " +
                "`actor_subject` redacted, and the row count; never the contents).",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "The CSV file, as an attachment named audit-events-dd-MM-yyyy.csv",
            content = [Content(mediaType = "text/csv", schema = Schema(type = "string"))],
        ),
        ApiResponse(
            responseCode = "400",
            description =
                "Invalid filter or window: `occurred_from` missing, after the end or more than " +
                    "31 days before it (violation on `occurred_from`), a value outside its set " +
                    "or bounds, or more than 10,000 matching rows (`audit.export_too_large`: " +
                    "narrow the window or add filters)",
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
            description =
                "Forbidden: the caller lacks `audit.export`, or its view `audit.view` that the " +
                    "catalogue pairs with it. The problem names the first missing code " +
                    "(`Missing permission: audit.export.`), checked before the filter is " +
                    "validated.",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Over the `tenant-export` rate limit (5 a minute by default)",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun exportAuditEvents(
        @RequestParam(required = false, name = "entity_type") entityType: String?,
        @RequestParam(required = false, name = "entity_id") entityId: UUID?,
        @RequestParam(required = false, name = "actor_id") actorId: UUID?,
        @Parameter(description = AUDIT_SUBJECT_USER_ID_DOC)
        @RequestParam(required = false, name = "subject_user_id") subjectUserId: UUID?,
        @Parameter(description = AUDIT_ACTION_DOC)
        @RequestParam(required = false) action: String?,
        @Parameter(description = AUDIT_ACTION_PREFIX_DOC)
        @RequestParam(required = false, name = "action_prefix") actionPrefix: String?,
        @Parameter(description = AUDIT_EXPORT_OCCURRED_FROM_DOC, required = true)
        @RequestParam(required = false, name = "occurred_from") occurredFrom: Instant?,
        @Parameter(description = AUDIT_EXPORT_OCCURRED_TO_DOC)
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
        @Parameter(hidden = true) servletRequest: HttpServletRequest,
    ): ResponseEntity<StreamingResponseBody> {
        val parameters =
            AuditSearchParameters(
                entityType = entityType,
                entityId = entityId,
                actorId = actorId,
                subjectUserId = subjectUserId,
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
            )
        return csvResponse(parameters, servletRequest)
    }

    /**
     * Authorises, validates and counts the export on the request thread, capturing everything the
     * stream will need as plain values, and returns the response whose body streams the CSV. A
     * refusal is raised here, before the response is committed, so it is a real status code.
     */
    private fun csvResponse(
        parameters: AuditSearchParameters,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<StreamingResponseBody> {
        val caller = CallerContextResolver.getTenantCaller()
        val export =
            auditQueryService.prepareExport(
                AuditExportRequest(
                    organisationId = caller.activeOrganisationId,
                    actorId = caller.actorId,
                    parameters = parameters,
                    suppliedFilters =
                        AuditSearchParameters.NAMES
                            .mapNotNull { name ->
                                // A repeated parameter binds as its values joined by commas.
                                servletRequest
                                    .getParameterValues(name)
                                    ?.joinToString(",")
                                    ?.let { name to it }
                            }.toMap(),
                    requestContext = RequestContexts.current(),
                ),
            )
        val instantText: (Instant) -> String =
            { apiJsonCodec.mapper.writeValueAsString(it).trim('"') }
        val body =
            StreamingResponseBody { output ->
                val writer = output.bufferedWriter(StandardCharsets.UTF_8)
                export.stream(
                    start = {
                        writer.write(AuditEventCsv.header())
                        writer.flush()
                    },
                    batch = { rows ->
                        rows.forEach { writer.write(AuditEventCsv.record(it, instantText)) }
                        writer.flush()
                    },
                )
            }
        val day = LocalDate.ofInstant(export.requestedAt, ZoneOffset.UTC)
        return ResponseEntity
            .ok()
            .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"audit-events-${ApiDateFormats.DATE.format(day)}.csv\"",
            ).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body)
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
