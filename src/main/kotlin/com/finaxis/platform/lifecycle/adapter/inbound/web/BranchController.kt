package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ActivateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchDraftResultResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchSummaryResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CloseBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReactivateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SubmitBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SuspendBranchRequest
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CloseBranchCommand
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.ReactivateBranchCommand
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import com.finaxis.platform.lifecycle.application.SuspendBranchCommand
import com.finaxis.platform.lifecycle.application.query.BranchDetail
import com.finaxis.platform.lifecycle.application.query.BranchFilter
import com.finaxis.platform.lifecycle.application.query.BranchSummary
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
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
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/**
 * REST controller for tenant-facing branch management operations.
 */
@RestController
@RequestMapping(ApiPaths.BRANCHES)
@Tag(name = "Branches", description = "Tenant branch management APIs")
@SecurityRequirement(name = "bearer-key")
@Validated
// Required endpoint-level OpenAPI response documentation is intentionally colocated.
@Suppress("LargeClass")
class BranchController(
    private val branchProvisioningService: BranchProvisioningService,
    private val foundationQueryService: FoundationQueryService,
    private val permissionGuard: PermissionGuard,
) {
    /**
     * Searches branches in the active tenant organisation.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('branch.view')")
    @Operation(
        summary = "Search branches",
        description = "Searches branches in the active tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Branch page",
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
    fun searchBranches(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
        @RequestParam(required = false, name = "sort_by") sortBy: String?,
        @RequestParam(required = false, name = "sort_dir") sortDir: String?,
    ): ApiPage<BranchSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val filter =
            BranchFilter(
                q = q,
                status = status,
                type = type,
                page = page,
                size = size,
                sortBy = sortBy,
                sortDir = sortDir,
            )
        val pageResult =
            foundationQueryService.searchBranches(
                caller.activeOrganisationId,
                filter,
                caller,
            )
        return ApiPage(
            items = pageResult.items.map { it.toResponse() },
            page = pageResult.page,
        )
    }

    /**
     * Drafts a new operating branch under the active tenant.
     */
    @PostMapping
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('branch.create')")
    @Operation(
        summary = "Create branch draft",
        description = "Drafts a new operating branch under the active tenant.",
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
            responseCode = "201",
            description = "Branch draft created",
            content = [Content(schema = Schema(implementation = BranchDraftResultResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
        ApiResponse(
            responseCode = "409",
            description = "Branch code already exists",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun createDraft(
        @RequestBody @Valid request: CreateBranchRequest,
    ): ResponseEntity<BranchDraftResultResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "branch.create",
        )

        val command =
            CreateBranchCommand(
                organisationId = caller.activeOrganisationId,
                branchCode = request.branchCode,
                branchName = request.branchName,
                branchType = request.branchType,
                parentBranchId = request.parentBranchId,
                timezone = request.timezone,
                address = request.address,
                requestedBy = caller.actorId,
            )
        val result = branchProvisioningService.createDraft(command)
        val location = URI.create("${ApiPaths.BRANCHES}/${result.branchId}")
        return ResponseEntity
            .created(location)
            .body(BranchDraftResultResponse(result.branchId, result.status.name))
    }

    /**
     * Retrieves detailed metadata for a specific branch under the active tenant.
     */
    @GetMapping("/{branch_id}")
    @PreAuthorize("hasAuthority('branch.view')")
    @Operation(
        summary = "Get branch details",
        description = "Retrieves detailed metadata for a specific branch under the active tenant.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Branch details",
            content = [Content(schema = Schema(implementation = BranchDetailResponse::class))],
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
            description = "Branch not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun getBranch(
        @PathVariable("branch_id") branchId: UUID,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        verifyBranchContext(caller, branchId)
        val detail =
            foundationQueryService.getBranch(
                caller.activeOrganisationId,
                branchId,
                caller,
            )
        return detail.toResponse()
    }

    /**
     * Submits a branch draft for operational approval.
     */
    @PostMapping("/{branch_id}/submit")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('branch.create')")
    @Operation(
        summary = "Submit branch draft",
        description = "Submits a branch draft for operational approval.",
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
            description = "Branch submitted",
            content = [Content(schema = Schema(implementation = BranchDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
        ApiResponse(
            responseCode = "404",
            description = "Branch not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Branch state conflicts with submission",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun submit(
        @PathVariable("branch_id") branchId: UUID,
        @RequestBody(required = false) request: SubmitBranchRequest?,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        verifyBranchContext(caller, branchId)
        permissionGuard.requireBranchPermission(
            caller.actorId,
            caller.activeOrganisationId,
            branchId,
            "branch.create",
        )

        val command =
            SubmitBranchForApprovalCommand(
                organisationId = caller.activeOrganisationId,
                branchId = branchId,
                reason = request?.reason,
                actorId = caller.actorId,
                requestId = UUID.randomUUID(),
            )
        branchProvisioningService.submitForApproval(command)
        val updated =
            foundationQueryService.getBranch(
                caller.activeOrganisationId,
                branchId,
                caller,
            )
        return updated.toResponse()
    }

    /**
     * Activates an approved branch.
     */
    @PostMapping("/{branch_id}/activate")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('branch.activate')")
    @Operation(
        summary = "Activate branch",
        description = "Activates an approved branch.",
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
            description = "Branch activated",
            content = [Content(schema = Schema(implementation = BranchDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
            description = "Forbidden or maker-checker violation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Branch not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Branch state conflicts with activation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun activate(
        @PathVariable("branch_id") branchId: UUID,
        @RequestBody(required = false) request: ActivateBranchRequest?,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        verifyBranchContext(caller, branchId)
        permissionGuard.requireBranchPermission(
            caller.actorId,
            caller.activeOrganisationId,
            branchId,
            "branch.activate",
        )

        val command =
            ActivateBranchCommand(
                organisationId = caller.activeOrganisationId,
                branchId = branchId,
                reason = request?.reason,
                actorId = caller.actorId,
                requestId = UUID.randomUUID(),
            )
        branchProvisioningService.activate(command)
        val updated =
            foundationQueryService.getBranch(
                caller.activeOrganisationId,
                branchId,
                caller,
            )
        return updated.toResponse()
    }

    /**
     * Suspends an active branch.
     */
    @PostMapping("/{branch_id}/suspend")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('branch.suspend')")
    @Operation(
        summary = "Suspend branch",
        description = "Suspends an active branch.",
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
            description = "Branch suspended",
            content = [Content(schema = Schema(implementation = BranchDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
        ApiResponse(
            responseCode = "404",
            description = "Branch not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Branch state conflicts with suspension",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun suspend(
        @PathVariable("branch_id") branchId: UUID,
        @RequestBody @Valid request: SuspendBranchRequest,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        verifyBranchContext(caller, branchId)
        permissionGuard.requireBranchPermission(
            caller.actorId,
            caller.activeOrganisationId,
            branchId,
            "branch.suspend",
        )

        val command =
            SuspendBranchCommand(
                organisationId = caller.activeOrganisationId,
                branchId = branchId,
                reason = request.reason,
                actorId = caller.actorId,
            )
        branchProvisioningService.suspend(command)
        val updated =
            foundationQueryService.getBranch(
                caller.activeOrganisationId,
                branchId,
                caller,
            )
        return updated.toResponse()
    }

    /**
     * Reactivates a suspended branch.
     */
    @PostMapping("/{branch_id}/reactivate")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('branch.reactivate')")
    @Operation(
        summary = "Reactivate branch",
        description = "Reactivates a suspended branch.",
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
            description = "Branch reactivated",
            content = [Content(schema = Schema(implementation = BranchDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
        ApiResponse(
            responseCode = "404",
            description = "Branch not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Branch state conflicts with reactivation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun reactivate(
        @PathVariable("branch_id") branchId: UUID,
        @RequestBody(required = false) request: ReactivateBranchRequest?,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        verifyBranchContext(caller, branchId)
        permissionGuard.requireBranchPermission(
            caller.actorId,
            caller.activeOrganisationId,
            branchId,
            "branch.reactivate",
        )

        val command =
            ReactivateBranchCommand(
                organisationId = caller.activeOrganisationId,
                branchId = branchId,
                reason = request?.reason,
                actorId = caller.actorId,
            )
        branchProvisioningService.reactivate(command)
        val updated =
            foundationQueryService.getBranch(
                caller.activeOrganisationId,
                branchId,
                caller,
            )
        return updated.toResponse()
    }

    /**
     * Closes a branch without deleting data.
     */
    @PostMapping("/{branch_id}/close")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('branch.close')")
    @Operation(
        summary = "Close branch",
        description = "Closes a branch without deleting data.",
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
            description = "Branch closed",
            content = [Content(schema = Schema(implementation = BranchDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
        ApiResponse(
            responseCode = "404",
            description = "Branch not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Branch state conflicts with closing",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun close(
        @PathVariable("branch_id") branchId: UUID,
        @RequestBody @Valid request: CloseBranchRequest,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        verifyBranchContext(caller, branchId)
        permissionGuard.requireBranchPermission(
            caller.actorId,
            caller.activeOrganisationId,
            branchId,
            "branch.close",
        )

        val command =
            CloseBranchCommand(
                organisationId = caller.activeOrganisationId,
                branchId = branchId,
                reason = request.reason,
                actorId = caller.actorId,
            )
        branchProvisioningService.close(command)
        val updated =
            foundationQueryService.getBranch(
                caller.activeOrganisationId,
                branchId,
                caller,
            )
        return updated.toResponse()
    }

    private fun verifyBranchContext(
        caller: TenantCaller,
        targetBranchId: UUID,
    ) {
        if (caller.activeBranchId != null && caller.activeBranchId != targetBranchId) {
            throw ResourceNotFoundException(safeDetail = "Branch not found: $targetBranchId")
        }
    }

    private fun BranchSummary.toResponse() =
        BranchSummaryResponse(
            id = id,
            organisationId = organisationId,
            branchCode = branchCode,
            branchName = branchName,
            branchType = branchType,
            status = status,
            createdAt = createdAt,
        )

    private fun BranchDetail.toResponse() =
        BranchDetailResponse(
            id = id,
            organisationId = organisationId,
            branchCode = branchCode,
            branchName = branchName,
            branchType = branchType,
            parentBranchId = parentBranchId,
            status = status,
            timezone = timezone,
            address = emptyMap(),
            openedOn = openedOn,
            closedOn = closedOn,
            statusReason = statusReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
