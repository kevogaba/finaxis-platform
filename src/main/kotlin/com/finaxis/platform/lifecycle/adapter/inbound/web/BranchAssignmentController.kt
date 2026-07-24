package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AssignBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchAssignmentDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BranchAssignmentSummaryResponse
import com.finaxis.platform.lifecycle.application.AssignUserToBranchCommand
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.RevokeUserBranchAssignmentCommand
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentDetail
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentFilter
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentSummary
import com.finaxis.platform.lifecycle.application.query.LifecycleIamReadService
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
import org.springframework.web.bind.annotation.DeleteMapping
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
 * REST controller for tenant-facing user branch-assignment operations.
 */
@RestController
@RequestMapping(ApiPaths.BRANCH_ASSIGNMENTS)
@Tag(name = "Branch assignments", description = "Tenant user branch-assignment APIs")
@SecurityRequirement(name = "bearerAuth")
@Validated
@Suppress("LargeClass")
class BranchAssignmentController(
    private val branchProvisioningService: BranchProvisioningService,
    private val lifecycleIamReadService: LifecycleIamReadService,
    private val permissionGuard: PermissionGuard,
) {
    /**
     * Searches branch assignments in the active tenant organisation.
     *
     * Sort parameters are accepted for a stable list contract but Phase A's assignment query
     * filter does not yet expose sorting fields.
     */
    @GetMapping
    @Suppress("UnusedParameter")
    @PreAuthorize("hasAuthority('branch_assignment.view')")
    @Operation(
        summary = "Search branch assignments",
        description = "Searches user branch assignments in the active tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Branch-assignment page",
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
    fun searchBranchAssignments(
        @RequestParam(required = false, name = "branch_id") branchId: UUID?,
        @RequestParam(required = false, name = "assignment_type") assignmentType: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
        @RequestParam(required = false, name = "sort_by") sortBy: String?,
        @RequestParam(required = false, name = "sort_dir") sortDir: String?,
    ): ApiPage<BranchAssignmentSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val pageResult =
            lifecycleIamReadService.searchBranchAssignments(
                caller.activeOrganisationId,
                LifecycleBranchAssignmentFilter(
                    branchId = branchId,
                    assignmentType = assignmentType,
                    status = status,
                    page = page,
                    size = size,
                ),
                caller,
            )
        return ApiPage(items = pageResult.items.map { it.toResponse() }, page = pageResult.page)
    }

    /** Retrieves a user branch assignment in the active tenant. */
    @GetMapping("/{assignment_id}")
    @PreAuthorize("hasAuthority('branch_assignment.view')")
    @Operation(
        summary = "Get branch assignment details",
        description =
            "Retrieves user branch-assignment metadata in the active tenant " +
                "organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Branch assignment details",
            content = [
                Content(schema = Schema(implementation = BranchAssignmentDetailResponse::class)),
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
            description = "Branch assignment not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun getBranchAssignment(
        @PathVariable("assignment_id") assignmentId: UUID,
    ): BranchAssignmentDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        val detail =
            lifecycleIamReadService.getBranchAssignment(
                caller.activeOrganisationId,
                assignmentId,
                caller,
            )
        verifyAssignmentContext(caller, detail)
        return detail.toResponse()
    }

    /** Assigns a user to an operating branch. */
    @PostMapping
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('user.assign_branch')")
    @Operation(
        summary = "Assign user to branch",
        description = "Creates or reactivates a user branch assignment.",
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
            description = "Branch assignment created",
            content = [
                Content(schema = Schema(implementation = BranchAssignmentSummaryResponse::class)),
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
            description = "User or branch not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Branch or membership state conflicts with assignment",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun assign(
        @RequestBody @Valid request: AssignBranchRequest,
    ): ResponseEntity<BranchAssignmentSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        verifyBranchContext(caller, request.branchId)
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "user.assign_branch",
        )
        branchProvisioningService.assignUser(
            AssignUserToBranchCommand(
                organisationId = caller.activeOrganisationId,
                userId = request.userId,
                branchId = request.branchId,
                assignmentType = request.assignmentType,
                assignedBy = caller.actorId,
            ),
        )
        val assignment = findAssignment(caller, request)
        return ResponseEntity
            .created(URI.create("${ApiPaths.BRANCH_ASSIGNMENTS}/${assignment.id}"))
            .body(assignment.toResponse())
    }

    /** Revokes a user branch assignment resolved safely by assignment identifier. */
    @DeleteMapping("/{assignment_id}")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('user.revoke_branch')")
    @Operation(
        summary = "Revoke branch assignment",
        description = "Revokes the assignment identified in the active tenant organisation.",
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
            description = "Branch assignment revoked",
            content = [
                Content(schema = Schema(implementation = BranchAssignmentDetailResponse::class)),
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
            description = "Branch assignment not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Assignment revocation conflicts with membership access requirements",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun revoke(
        @PathVariable("assignment_id") assignmentId: UUID,
    ): BranchAssignmentDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        val assignment =
            lifecycleIamReadService.getBranchAssignment(
                caller.activeOrganisationId,
                assignmentId,
                caller,
            )
        verifyAssignmentContext(caller, assignment)
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "user.revoke_branch",
        )
        branchProvisioningService.revokeUserAssignment(
            RevokeUserBranchAssignmentCommand(
                organisationId = caller.activeOrganisationId,
                userId = assignment.userId,
                branchId = assignment.branchId,
                assignmentType = BranchAssignmentType.valueOf(assignment.assignmentType),
                revokedBy = caller.actorId,
            ),
        )
        return lifecycleIamReadService
            .getBranchAssignment(caller.activeOrganisationId, assignmentId, caller)
            .toResponse()
    }

    private fun findAssignment(
        caller: TenantCaller,
        request: AssignBranchRequest,
    ): LifecycleBranchAssignmentSummary {
        val assignments =
            lifecycleIamReadService.searchBranchAssignments(
                caller.activeOrganisationId,
                LifecycleBranchAssignmentFilter(
                    branchId = request.branchId,
                    assignmentType = request.assignmentType.name,
                    status = "ACTIVE",
                    size = MAXIMUM_PAGE_SIZE.toInt(),
                ),
                caller,
            )
        return assignments.items.firstOrNull { it.userId == request.userId }
            ?: throw ResourceNotFoundException(
                safeDetail = "Branch assignment not found after assignment",
            )
    }

    private fun verifyAssignmentContext(
        caller: TenantCaller,
        assignment: LifecycleBranchAssignmentDetail,
    ) = verifyBranchContext(caller, assignment.branchId)

    private fun verifyBranchContext(
        caller: TenantCaller,
        targetBranchId: UUID,
    ) {
        if (caller.activeBranchId != null && caller.activeBranchId != targetBranchId) {
            throw ResourceNotFoundException(safeDetail = "Branch assignment not found")
        }
    }

    private fun LifecycleBranchAssignmentSummary.toResponse() =
        BranchAssignmentSummaryResponse(
            id = id,
            userId = userId,
            branchId = branchId,
            assignmentType = assignmentType,
            status = status,
        )

    private fun LifecycleBranchAssignmentDetail.toResponse() =
        BranchAssignmentDetailResponse(
            id = id,
            organisationId = organisationId,
            userId = userId,
            branchId = branchId,
            assignmentType = assignmentType,
            status = status,
            assignedAt = assignedAt,
            assignedBy = assignedBy,
            revokedAt = revokedAt,
            revokedBy = revokedBy,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
