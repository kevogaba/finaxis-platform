package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ActivateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchDraftResultResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchSummaryResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReturnBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SubmitBranchRequest
import com.finaxis.platform.lifecycle.application.ActingScope
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.DecisionRemark
import com.finaxis.platform.lifecycle.application.Reason
import com.finaxis.platform.lifecycle.application.ReturnBranchCommand
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
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
@SecurityRequirement(name = "bearer-key")
@Validated
class PlatformTenantBranchController(
    private val branchProvisioningService: BranchProvisioningService,
    private val foundationQueryService: FoundationQueryService,
    private val apiJsonCodec: ApiJsonCodec,
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
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid page, sort or filter",
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
        @Parameter(
            description = "Field to sort by. Values are camelCase, unlike other wire names.",
            schema =
                Schema(
                    allowableValues =
                        ["branchCode", "branchName", "branchType", "status", "createdAt"],
                ),
        )
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
            description = "Tenant not found (the platform organisation is never a valid tenant)",
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
                scope = ActingScope.PLATFORM,
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

    /**
     * Submits a tenant branch draft for approval as the platform administrator.
     */
    @PostMapping("/{branch_id}/submit")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('branch.create')")
    @Operation(
        summary = "Submit tenant branch draft as platform administrator",
        description =
            "Submits a branch draft of the path tenant for operational approval. Requires " +
                "`branch.create` in the platform organisation.",
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
            description = "Invalid request body (for example a reason over 500 characters)",
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
            description = "Forbidden or not in platform context",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "Tenant or branch not found (the platform organisation is never a valid tenant)",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description =
                "Branch or tenant state conflicts with submission, or the tenant already has an " +
                    "active branch beyond its bootstrap head office " +
                    "(code `lifecycle.platform_checker_closed`)",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun submit(
        @PathVariable("tenant_id") tenantId: UUID,
        @PathVariable("branch_id") branchId: UUID,
        @RequestBody(required = false) @Valid request: SubmitBranchRequest?,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        branchProvisioningService.submitForApproval(
            SubmitBranchForApprovalCommand(
                organisationId = tenantId,
                branchId = branchId,
                reason = DecisionRemark.optional(request?.reason),
                actorId = caller.actorId,
                requestId = uuidV7(),
                scope = ActingScope.PLATFORM,
            ),
        )
        return foundationQueryService
            .getBranchAfterAuthorizedMutation(
                tenantId,
                branchId,
            ).toResponse()
    }

    /**
     * Activates a tenant branch as the audited platform checker.
     */
    @PostMapping("/{branch_id}/activate")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('branch.approve')")
    @Operation(
        summary = "Activate tenant branch as platform checker",
        description =
            "Activates a pending branch of the path tenant on the tenant's behalf. Requires " +
                "`branch.approve` in the platform organisation. The branch's creator cannot " +
                "activate it, whether a tenant user or a platform administrator; the action is " +
                "audited with the platform actor.",
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
            description = "Invalid request body (for example a reason over 500 characters)",
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
                "Forbidden, not in platform context, or the caller created or submitted the branch",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "Tenant or branch not found (the platform organisation is never a valid tenant)",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description =
                "Branch or tenant state conflicts with activation, or the tenant already has an " +
                    "active branch beyond its bootstrap head office " +
                    "(code `lifecycle.platform_checker_closed`)",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun activate(
        @PathVariable("tenant_id") tenantId: UUID,
        @PathVariable("branch_id") branchId: UUID,
        @RequestBody(required = false) @Valid request: ActivateBranchRequest?,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        branchProvisioningService.activate(
            ActivateBranchCommand(
                organisationId = tenantId,
                branchId = branchId,
                reason = DecisionRemark.optional(request?.reason),
                actorId = caller.actorId,
                requestId = uuidV7(),
                scope = ActingScope.PLATFORM,
            ),
        )
        return foundationQueryService
            .getBranchAfterAuthorizedMutation(
                tenantId,
                branchId,
            ).toResponse()
    }

    /**
     * Returns a pending tenant branch to draft, or withdraws it, as a platform administrator.
     */
    @PostMapping("/{branch_id}/return")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAnyAuthority('branch.create', 'branch.approve')")
    @Operation(
        summary = "Return or withdraw tenant branch as platform administrator",
        description =
            "Returns a pending branch of the path tenant to draft with a required reason. The " +
                "branch's creator or latest submitter withdraws their own request and needs " +
                "`branch.create` in the platform organisation, with no window; anyone else " +
                "returns it as the audited platform checker, needs `branch.approve` in the " +
                "platform organisation, and only while the tenant has no active branch beyond " +
                "its bootstrap head office.",
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
            description = "Branch returned to draft",
            content = [Content(schema = Schema(implementation = BranchDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description =
                "Missing body or reason (`invalid_json`), or a reason that is blank or not " +
                    "3 to 500 characters (`validation_failed`)",
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
            description = "Forbidden, not in platform context, or lacking the intent's permission",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "Tenant or branch not found (the platform organisation is never a valid tenant)",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description =
                "Branch is not pending approval, the tenant is not active or provisioning, or " +
                    "a checker's return finds the tenant with an active branch beyond its " +
                    "bootstrap head office (code `lifecycle.platform_checker_closed`)",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun returnForChanges(
        @PathVariable("tenant_id") tenantId: UUID,
        @PathVariable("branch_id") branchId: UUID,
        @RequestBody @Valid request: ReturnBranchRequest,
    ): BranchDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        branchProvisioningService.returnForChanges(
            ReturnBranchCommand(
                organisationId = tenantId,
                branchId = branchId,
                reason = Reason.required(request.reason),
                actorId = caller.actorId,
                scope = ActingScope.PLATFORM,
            ),
        )
        return foundationQueryService
            .getBranchAfterAuthorizedMutation(
                tenantId,
                branchId,
            ).toResponse()
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
            address = apiJsonCodec.branchAddress(addressJson),
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
