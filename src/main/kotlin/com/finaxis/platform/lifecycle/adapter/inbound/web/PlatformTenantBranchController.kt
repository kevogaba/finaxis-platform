package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchDraftResultResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchSummaryResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
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
 * REST controller for platform administration of nested tenant branches.
 */
@RestController
@RequestMapping("${ApiPaths.PLATFORM_TENANTS}/{tenant_id}/branches")
@Tag(
    name = "Platform Tenant Branches",
    description = "Reserved platform administration of nested tenant branches",
)
@SecurityRequirement(name = "bearerAuth")
@Validated
class PlatformTenantBranchController(
    private val branchProvisioningService: BranchProvisioningService,
    private val foundationQueryService: FoundationQueryService,
    private val permissionGuard: PermissionGuard,
) {
    /**
     * Searches branches belonging to a specific tenant organisation.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('branch.view')")
    @Operation(
        summary = "Search tenant branches",
        description = "Searches branches belonging to a specific tenant organisation.",
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
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
        @RequestParam(required = false, name = "sort_by") sortBy: String?,
        @RequestParam(required = false, name = "sort_dir") sortDir: String?,
    ): ApiPage<BranchSummaryResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
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
                tenantId,
                filter,
                caller,
            )
        return ApiPage(
            items = pageResult.items.map { it.toResponse() },
            page = pageResult.page,
        )
    }

    /**
     * Retrieves detailed metadata for a specific tenant branch.
     */
    @GetMapping("/{branch_id}")
    @PreAuthorize("hasAuthority('branch.view')")
    @Operation(
        summary = "Get tenant branch details",
        description = "Retrieves detailed metadata for a specific tenant branch.",
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
        @PathVariable("tenant_id") tenantId: UUID,
        @PathVariable("branch_id") branchId: UUID,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        val detail =
            foundationQueryService.getBranch(
                tenantId,
                branchId,
                caller,
            )
        return detail.toResponse()
    }

    /**
     * Drafts a new branch under a specific tenant organisation.
     */
    @PostMapping
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('branch.create')")
    @Operation(
        summary = "Create tenant branch draft",
        description = "Drafts a new branch under a specific tenant organisation.",
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
            responseCode = "404",
            description = "Tenant not found",
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
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestBody @Valid request: CreateBranchRequest,
    ): ResponseEntity<BranchDraftResultResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "branch.create")

        val command =
            CreateBranchCommand(
                organisationId = tenantId,
                branchCode = request.branchCode,
                branchName = request.branchName,
                branchType = request.branchType,
                parentBranchId = request.parentBranchId,
                timezone = request.timezone,
                address = request.address,
                requestedBy = caller.actorId,
            )
        val result = branchProvisioningService.createDraft(command)
        val location =
            URI.create(
                "${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/${result.branchId}",
            )
        return ResponseEntity
            .created(location)
            .body(BranchDraftResultResponse(result.branchId, result.status.name))
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
            address = emptyMap(), // addressJson parsed if needed, or simple map
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
