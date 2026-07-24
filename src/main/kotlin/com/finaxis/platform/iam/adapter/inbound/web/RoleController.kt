package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignPermissionRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.CreateRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.RoleDetailResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.RolePermissionDetailResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.RolePermissionSummaryResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.RoleSummaryResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.UpdateRoleRequest
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.RoleDetail
import com.finaxis.platform.iam.application.query.RoleFilter
import com.finaxis.platform.iam.application.query.RolePermissionDetail
import com.finaxis.platform.iam.application.query.RolePermissionFilter
import com.finaxis.platform.iam.application.query.RolePermissionSummary
import com.finaxis.platform.iam.application.query.RoleSummary
import com.finaxis.platform.iam.application.role.ActivateRole
import com.finaxis.platform.iam.application.role.AssignPermissionToRole
import com.finaxis.platform.iam.application.role.CreateTenantRole
import com.finaxis.platform.iam.application.role.DeactivateRole
import com.finaxis.platform.iam.application.role.RemovePermissionFromRole
import com.finaxis.platform.iam.application.role.RoleManagementService
import com.finaxis.platform.iam.application.role.UpdateTenantRole
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.adapter.inbound.web.CallerContextResolver
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
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/** REST controller for tenant-facing role and role-permission administration. */
@RestController
@RequestMapping(ApiPaths.ROLES)
@Tag(name = "Roles", description = "Tenant role and role-permission APIs")
@SecurityRequirement(name = "bearerAuth")
@Validated
@Suppress("LargeClass")
class RoleController(
    private val roleManagementService: RoleManagementService,
    private val iamQueryService: IamQueryService,
    private val permissionGuard: PermissionGuard,
) {
    /** Searches roles in the active tenant organisation. */
    @GetMapping
    @PreAuthorize("hasAuthority('role.view')")
    @Operation(summary = "Search roles", description = "Searches roles in the active tenant.")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Role page",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
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
    fun searchRoles(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, name = "system_role") systemRole: Boolean?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
        @RequestParam(required = false, name = "sort_by") sortBy: String?,
        @RequestParam(required = false, name = "sort_dir") sortDir: String?,
    ): ApiPage<RoleSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "role.view",
        )
        val result =
            iamQueryService.searchRoles(
                caller.activeOrganisationId,
                RoleFilter(q, status, systemRole, page, size, sortBy, sortDir),
                caller,
            )
        return ApiPage(items = result.items.map { it.toResponse() }, page = result.page)
    }

    /** Creates a tenant-managed role. */
    @PostMapping
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('role.create')")
    @Operation(
        summary = "Create role",
        description = "Creates a tenant-managed role.",
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
            description = "Role created",
            content = [Content(schema = Schema(implementation = RoleDetailResponse::class))],
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
            responseCode = "409",
            description = "Role code already exists",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun createRole(
        @RequestBody @Valid request: CreateRoleRequest,
    ): ResponseEntity<RoleDetailResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "role.create",
        )
        val result =
            roleManagementService.createTenantRole(
                CreateTenantRole(
                    caller.activeOrganisationId,
                    request.roleCode,
                    request.roleName,
                    request.description,
                    caller.actorId,
                    UUID.randomUUID().toString(),
                ),
            )
        val response =
            iamQueryService
                .getRole(
                    caller.activeOrganisationId,
                    result.roleId,
                    caller,
                ).toResponse()
        return ResponseEntity
            .created(
                URI.create("${ApiPaths.ROLES}/${result.roleId}"),
            ).body(response)
    }

    /** Retrieves role metadata within the active tenant. */
    @GetMapping("/{role_id}")
    @PreAuthorize("hasAuthority('role.view')")
    @Operation(summary = "Get role", description = "Retrieves role metadata in the active tenant.")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Role details",
            content = [Content(schema = Schema(implementation = RoleDetailResponse::class))],
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
            description = "Role not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun getRole(
        @PathVariable("role_id") roleId: UUID,
    ): RoleDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "role.view",
        )
        return iamQueryService.getRole(caller.activeOrganisationId, roleId, caller).toResponse()
    }

    /** Updates a tenant-managed role's mutable metadata. */
    @PatchMapping("/{role_id}")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('role.update')")
    @Operation(
        summary = "Update role",
        description = "Updates a tenant-managed role.",
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
            description = "Role updated",
            content = [Content(schema = Schema(implementation = RoleDetailResponse::class))],
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
            description = "Role not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Immutable role",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun updateRole(
        @PathVariable("role_id") roleId: UUID,
        @RequestBody @Valid request: UpdateRoleRequest,
    ): RoleDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "role.update",
        )
        roleManagementService.updateTenantRole(
            UpdateTenantRole(
                caller.activeOrganisationId,
                roleId,
                request.roleName,
                request.description,
                caller.actorId,
                UUID.randomUUID().toString(),
            ),
        )
        return iamQueryService.getRole(caller.activeOrganisationId, roleId, caller).toResponse()
    }

    /** Activates a tenant-managed role. */
    @PostMapping("/{role_id}/activate")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('role.activate')")
    @Operation(
        summary = "Activate role",
        description = "Activates a tenant-managed role.",
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
            description = "Role activated",
            content = [Content(schema = Schema(implementation = RoleDetailResponse::class))],
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
            description = "Role not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Immutable role",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun activateRole(
        @PathVariable("role_id") roleId: UUID,
    ): RoleDetailResponse = mutateStatus(roleId, true)

    /** Deactivates a tenant-managed role. */
    @PostMapping("/{role_id}/deactivate")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('role.deactivate')")
    @Operation(
        summary = "Deactivate role",
        description = "Deactivates a tenant-managed role.",
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
            description = "Role deactivated",
            content = [Content(schema = Schema(implementation = RoleDetailResponse::class))],
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
            description = "Role not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Immutable role",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun deactivateRole(
        @PathVariable("role_id") roleId: UUID,
    ): RoleDetailResponse = mutateStatus(roleId, false)

    /** Lists permission grants on a role. */
    @GetMapping("/{role_id}/permissions")
    @PreAuthorize("hasAuthority('role.view')")
    @Operation(
        summary = "List role permissions",
        description = "Lists permission grants on a role.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Role-permission page",
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
    )
    fun listRolePermissions(
        @PathVariable("role_id") roleId: UUID,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<RolePermissionSummaryResponse> = rolePermissions(roleId, page, size)

    /** Grants a catalogue permission to a tenant-managed role. */
    @PostMapping("/{role_id}/permissions")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('role.assign_permission')")
    @Operation(
        summary = "Grant role permission",
        description = "Grants a catalogue permission to a role.",
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
            description = "Permission granted",
            content = [
                Content(
                    schema = Schema(implementation = RolePermissionSummaryResponse::class),
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
            description = "Role or permission not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Immutable role",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun assignPermission(
        @PathVariable("role_id") roleId: UUID,
        @RequestBody @Valid request: AssignPermissionRequest,
    ): ResponseEntity<RolePermissionSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "role.assign_permission",
        )
        roleManagementService.assignPermissionToRole(
            AssignPermissionToRole(
                caller.activeOrganisationId,
                roleId,
                request.permissionCode,
                caller.actorId,
                UUID.randomUUID().toString(),
            ),
        )
        val grant =
            iamQueryService
                .listRolePermissions(
                    caller.activeOrganisationId,
                    roleId,
                    RolePermissionFilter(size = MAXIMUM_PAGE_SIZE.toInt()),
                    caller,
                ).items
                .firstOrNull { it.permissionCode == request.permissionCode }
                ?: throw ResourceNotFoundException(
                    safeDetail = "Role permission not found after grant",
                )
        val response = grant.toResponse()
        return ResponseEntity
            .created(URI.create("${ApiPaths.ROLES}/$roleId/permissions/${grant.id}"))
            .body(response)
    }

    /** Removes a permission grant after resolving its server-owned permission code. */
    @DeleteMapping("/{role_id}/permissions/{role_permission_id}")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('role.remove_permission')")
    @Operation(
        summary = "Remove role permission",
        description = "Removes a role permission grant.",
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
            description = "Permission removed",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
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
            description = "Role permission not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Immutable role",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun removePermission(
        @PathVariable("role_id") roleId: UUID,
        @PathVariable("role_permission_id") rolePermissionId: UUID,
    ): ApiPage<RolePermissionSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val grant =
            iamQueryService.getRolePermission(
                caller.activeOrganisationId,
                rolePermissionId,
                caller,
            )
        if (grant.roleId !=
            roleId
        ) {
            throw ResourceNotFoundException(safeDetail = "Role permission not found")
        }
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "role.remove_permission",
        )
        roleManagementService.removePermissionFromRole(
            RemovePermissionFromRole(
                caller.activeOrganisationId,
                roleId,
                grant.permissionCode,
                caller.actorId,
                UUID.randomUUID().toString(),
            ),
        )
        return rolePermissions(roleId, 0, MAXIMUM_PAGE_SIZE.toInt())
    }

    private fun mutateStatus(
        roleId: UUID,
        activate: Boolean,
    ): RoleDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        val permission = if (activate) "role.activate" else "role.deactivate"
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            permission,
        )
        if (activate) {
            roleManagementService.activateRole(
                ActivateRole(
                    caller.activeOrganisationId,
                    roleId,
                    caller.actorId,
                    UUID.randomUUID().toString(),
                ),
            )
        } else {
            roleManagementService.deactivateRole(
                DeactivateRole(
                    caller.activeOrganisationId,
                    roleId,
                    caller.actorId,
                    UUID.randomUUID().toString(),
                ),
            )
        }
        return iamQueryService.getRole(caller.activeOrganisationId, roleId, caller).toResponse()
    }

    private fun rolePermissions(
        roleId: UUID,
        page: Int,
        size: Int,
    ): ApiPage<RolePermissionSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "role.view",
        )
        val result =
            iamQueryService.listRolePermissions(
                caller.activeOrganisationId,
                roleId,
                RolePermissionFilter(page, size),
                caller,
            )
        return ApiPage(items = result.items.map { it.toResponse() }, page = result.page)
    }

    private fun RoleSummary.toResponse() =
        RoleSummaryResponse(id, roleCode, roleName, systemRole, status)

    private fun RoleDetail.toResponse() =
        RoleDetailResponse(
            id,
            organisationId,
            roleCode,
            roleName,
            description,
            systemRole,
            status,
            createdAt,
            updatedAt,
        )

    private fun RolePermissionSummary.toResponse() =
        RolePermissionSummaryResponse(id, roleId, permissionId, permissionCode, grantedAt)

    @Suppress("UnusedPrivateMember")
    private fun RolePermissionDetail.toResponse() =
        RolePermissionDetailResponse(
            id,
            organisationId,
            roleId,
            permissionId,
            permissionCode,
            grantedAt,
            grantedBy,
            createdAt,
            updatedAt,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
