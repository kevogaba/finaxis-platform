package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AdvanceBusinessDateRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BusinessDateAdvanceResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BusinessDateHistoryEntryResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BusinessDateResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BusinessDateStatusTransitionRequest
import com.finaxis.platform.lifecycle.application.AdvanceBusinessDateCommand
import com.finaxis.platform.lifecycle.application.BusinessDateAdvanceResult
import com.finaxis.platform.lifecycle.application.BusinessDateHistoryRecord
import com.finaxis.platform.lifecycle.application.BusinessDateService
import com.finaxis.platform.lifecycle.application.BusinessDateView
import com.finaxis.platform.lifecycle.application.CompleteCobCommand
import com.finaxis.platform.lifecycle.application.GetBusinessDateQuery
import com.finaxis.platform.lifecycle.application.ListBusinessDateHistoryQuery
import com.finaxis.platform.lifecycle.application.ReopenBusinessDateCommand
import com.finaxis.platform.lifecycle.application.StartCobCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** REST controller for controlled tenant business-date and close-of-business operations. */
@RestController
@RequestMapping(ApiPaths.BUSINESS_DATE)
@Tag(
    name = "Business Date",
    description = "Controlled organisation business date and close-of-business operations",
)
@SecurityRequirement(name = "bearerAuth")
@Validated
class BusinessDateController(
    private val businessDateService: BusinessDateService,
) {
    /** Retrieves the active organisation's current business date. */
    @GetMapping
    @PreAuthorize("hasAuthority('business_date.view')")
    @Operation(
        summary = "Get current business date",
        description = "Retrieves the controlled business date for the active organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Current business date",
            content = [Content(schema = Schema(implementation = BusinessDateResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Business date not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun getCurrentBusinessDate(): BusinessDateResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return businessDateService
            .get(GetBusinessDateQuery(caller.activeOrganisationId, caller.actorId))
            .toResponse()
    }

    /** Lists newest-first business-date and close-of-business history. */
    @GetMapping("/history")
    @PreAuthorize("hasAuthority('business_date.view')")
    @Operation(
        summary = "List business date history",
        description = "Lists newest-first business-date and close-of-business history.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Business date history page",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid page",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Business date not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun listHistory(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<BusinessDateHistoryEntryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val result =
            businessDateService.listHistory(
                ListBusinessDateHistoryQuery(
                    caller.activeOrganisationId,
                    caller.actorId,
                    page,
                    size,
                ),
            )
        return apiPageOf(result.items.map { it.toResponse() }, page, size, result.totalItems)
    }

    /** Advances the active organisation's open business date. */
    @PostMapping("/advance")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('business_date.advance')")
    @Operation(
        summary = "Advance business date",
        description = "Advances the active organisation's open business date.",
        parameters = [
            Parameter(
                name = "Idempotency-Key",
                description = "Optional UUID; the server generates one when omitted.",
                `in` = ParameterIn.HEADER,
                schema = Schema(type = "string", format = "uuid"),
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Business date advanced",
            content = [
                Content(
                    schema = Schema(implementation = BusinessDateAdvanceResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Business date not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Business date conflict",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun advance(
        @RequestBody @Valid request: AdvanceBusinessDateRequest,
    ): BusinessDateAdvanceResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return businessDateService
            .advance(
                AdvanceBusinessDateCommand(
                    caller.activeOrganisationId,
                    request.newBusinessDate,
                    caller.actorId,
                    request.reason,
                ),
            ).toResponse()
    }

    /** Starts close-of-business processing for the active organisation. */
    @PostMapping("/cob/start")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('cob.start')")
    @Operation(
        summary = "Start close of business",
        description = "Starts close-of-business processing for the current business date.",
        parameters = [
            Parameter(
                name = "Idempotency-Key",
                description = "Optional UUID; the server generates one when omitted.",
                `in` = ParameterIn.HEADER,
                schema = Schema(type = "string", format = "uuid"),
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Close of business started",
            content = [Content(schema = Schema(implementation = BusinessDateResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Business date not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Business date conflict",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun startCob(
        @RequestBody @Valid request: BusinessDateStatusTransitionRequest,
    ): BusinessDateResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return businessDateService
            .startCob(StartCobCommand(caller.activeOrganisationId, caller.actorId, request.reason))
            .toResponse()
    }

    /** Completes close-of-business processing for the active organisation. */
    @PostMapping("/cob/complete")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('cob.complete')")
    @Operation(
        summary = "Complete close of business",
        description = "Completes close-of-business processing for the current business date.",
        parameters = [
            Parameter(
                name = "Idempotency-Key",
                description = "Optional UUID; the server generates one when omitted.",
                `in` = ParameterIn.HEADER,
                schema = Schema(type = "string", format = "uuid"),
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Close of business completed",
            content = [Content(schema = Schema(implementation = BusinessDateResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Business date not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Business date conflict",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun completeCob(
        @RequestBody @Valid request: BusinessDateStatusTransitionRequest,
    ): BusinessDateResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return businessDateService
            .completeCob(
                CompleteCobCommand(caller.activeOrganisationId, caller.actorId, request.reason),
            ).toResponse()
    }

    /** Reopens a closed business date for the active organisation. */
    @PostMapping("/reopen")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('business_date.reopen')")
    @Operation(
        summary = "Reopen business date",
        description = "Reopens a closed business date for the active organisation.",
        parameters = [
            Parameter(
                name = "Idempotency-Key",
                description = "Optional UUID; the server generates one when omitted.",
                `in` = ParameterIn.HEADER,
                schema = Schema(type = "string", format = "uuid"),
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Business date reopened",
            content = [Content(schema = Schema(implementation = BusinessDateResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Business date not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Business date conflict",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun reopen(
        @RequestBody @Valid request: BusinessDateStatusTransitionRequest,
    ): BusinessDateResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return businessDateService
            .reopen(
                ReopenBusinessDateCommand(
                    caller.activeOrganisationId,
                    caller.actorId,
                    request.reason,
                ),
            ).toResponse()
    }

    private fun BusinessDateView.toResponse() =
        BusinessDateResponse(organisationId, currentBusinessDate, status)

    private fun BusinessDateAdvanceResult.toResponse() =
        BusinessDateAdvanceResponse(organisationId, previousBusinessDate, newBusinessDate)

    private fun BusinessDateHistoryRecord.toResponse() =
        BusinessDateHistoryEntryResponse(
            eventType,
            fromStatus,
            toStatus,
            fromBusinessDate,
            toBusinessDate,
            actorId,
            reason,
            occurredAt,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
