package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.RoleAssignmentDetailResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.RoleAssignmentSummaryResponse
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.RoleAssignmentDetail
import com.finaxis.platform.iam.application.query.RoleAssignmentFilter
import com.finaxis.platform.iam.application.query.RoleAssignmentSummary
import com.finaxis.platform.iam.application.role.AssignRoleToUser
import com.finaxis.platform.iam.application.role.RevokeRoleAssignment
import com.finaxis.platform.iam.application.role.RoleManagementService
import com.finaxis.platform.iam.application.role.RoleScopeType
import com.finaxis.platform.iam.application.role.requireBranchScopeBranchId
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.adapter.inbound.web.CallerContextResolver
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
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

/** REST controller for tenant-facing user role assignments. */
@RestController
@RequestMapping(ApiPaths.ROLE_ASSIGNMENTS)
@Tag(name = "Role assignments", description = "Tenant user role-assignment APIs")
@SecurityRequirement(name = "bearer-key")
@Validated
class RoleAssignmentController(
    private val roleManagementService: RoleManagementService,
    private val iamQueryService: IamQueryService,
    private val permissionGuard: PermissionGuard,
) {
    /** Searches user role assignments in the active tenant. */
    @GetMapping
    @Operation(
        summary = "Search role assignments",
        description =
            "Searches user role assignments. A tenant-wide role_assignment.view sees every " +
                "row; a branch-scoped holder sees only BRANCH-scope rows on the branches it " +
                "holds the view on (any other scope_type filter is an empty page). A branch_id " +
                "outside that set, or no grant at all, is 403.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Role-assignment page",
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid page or filter",
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
    )
    fun searchRoleAssignments(
        @RequestParam(required = false, name = "user_id") userId: UUID?,
        @RequestParam(required = false, name = "role_id") roleId: UUID?,
        @RequestParam(required = false, name = "branch_id") branchId: UUID?,
        @RequestParam(required = false, name = "scope_type") scopeType: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<RoleAssignmentSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val result =
            iamQueryService.searchRoleAssignments(
                caller.activeOrganisationId,
                RoleAssignmentFilter(userId, roleId, branchId, scopeType, status, page, size),
                caller,
            )
        return ApiPage(items = result.items.map { it.toResponse() }, page = result.page)
    }

    /** Retrieves a user role assignment within the active tenant. */
    @GetMapping("/{assignment_id}")
    @Operation(
        summary = "Get role assignment",
        description =
            "Retrieves a user role assignment. BRANCH-scope rows need role_assignment.view " +
                "tenant-wide or on the row's branch; TENANT-scope rows need the tenant-wide " +
                "view. An unknown id is 404 to a tenant-wide holder and 403 to a branch-scoped " +
                "holder.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Role-assignment details",
            content = [
                Content(
                    schema = Schema(implementation = RoleAssignmentDetailResponse::class),
                ),
            ],
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
            description = "Role assignment not found (a branch-scoped holder gets 403 instead)",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun getRoleAssignment(
        @PathVariable("assignment_id") assignmentId: UUID,
    ): RoleAssignmentDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return iamQueryService
            .getRoleAssignment(
                caller.activeOrganisationId,
                assignmentId,
                caller,
            ).toResponse()
    }

    /** Assigns a role to a user membership. */
    @PostMapping
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('user.assign_role')")
    @Operation(
        summary = "Assign role to user",
        description = "Assigns a role to a user membership.",
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
            description = "Role assignment created",
            content = [
                Content(
                    schema = Schema(implementation = RoleAssignmentSummaryResponse::class),
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
            description = "User or role not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Assignment conflicts with current state",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun assignRole(
        @RequestBody @Valid request: AssignRoleRequest,
    ): ResponseEntity<RoleAssignmentSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        requireAssignmentPermission(caller, request.scopeType, request.branchId, "user.assign_role")
        val result =
            roleManagementService.assignRoleToUser(
                AssignRoleToUser(
                    caller.activeOrganisationId,
                    request.userId,
                    request.roleId,
                    RoleScopeType.valueOf(request.scopeType.name),
                    request.branchId,
                    caller.actorId,
                    uuidV7().toString(),
                ),
            )
        val response =
            RoleAssignmentSummaryResponse(
                result.assignmentId,
                request.userId,
                request.roleId,
                request.branchId,
                request.scopeType.name,
                result.status,
            )
        return ResponseEntity
            .created(URI.create("${ApiPaths.ROLE_ASSIGNMENTS}/${result.assignmentId}"))
            .body(response)
    }

    /**
     * Revokes a role assignment. The service resolves the assignment's tuple and scope itself, in
     * an authorised combined lookup, and the response is the gated read of the revoked row.
     */
    @DeleteMapping("/{assignment_id}")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('user.revoke_role')")
    @Operation(
        summary = "Revoke user role",
        description =
            "Revokes a user role assignment. The service resolves the assignment's scope " +
                "itself and authorises at that scope. A tenant-wide holder gets 404 for an " +
                "unknown id; any other caller gets the same 403 for an unknown id, a row on a " +
                "branch it may not act on and a tenant-scope row.",
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
            description = "Role assignment revoked",
            content = [
                Content(
                    schema = Schema(implementation = RoleAssignmentDetailResponse::class),
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
            description = "Role assignment not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun revokeRole(
        @PathVariable("assignment_id") assignmentId: UUID,
    ): RoleAssignmentDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        roleManagementService.revokeRoleAssignment(
            RevokeRoleAssignment(
                caller.activeOrganisationId,
                assignmentId,
                caller.actorId,
                uuidV7().toString(),
            ),
        )
        return iamQueryService
            .getRoleAssignment(
                caller.activeOrganisationId,
                assignmentId,
                caller,
            ).toResponse()
    }

    private fun requireAssignmentPermission(
        caller: TenantCaller,
        scopeType: RoleAssignmentScopeType,
        branchId: UUID?,
        permissionCode: String,
    ) {
        if (scopeType == RoleAssignmentScopeType.BRANCH) {
            val targetBranchId = requireBranchScopeBranchId(branchId)
            permissionGuard.requireBranchPermission(
                caller.actorId,
                caller.activeOrganisationId,
                targetBranchId,
                permissionCode,
            )
            return
        }
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            permissionCode,
        )
    }

    private fun RoleAssignmentSummary.toResponse() =
        RoleAssignmentSummaryResponse(id, userId, roleId, branchId, scopeType, status)

    private fun RoleAssignmentDetail.toResponse() =
        RoleAssignmentDetailResponse(
            id,
            organisationId,
            userId,
            roleId,
            branchId,
            scopeType,
            status,
            assignedAt,
            assignedBy,
            revokedAt,
            revokedBy,
            createdAt,
            updatedAt,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
