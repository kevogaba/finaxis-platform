package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.PermissionDetailResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.PermissionSummaryResponse
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.PermissionDetail
import com.finaxis.platform.iam.application.query.PermissionFilter
import com.finaxis.platform.iam.application.query.PermissionSummary
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.adapter.inbound.web.CallerContextResolver
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
import java.util.UUID

/** REST controller for the immutable permission catalogue. */
@RestController
@RequestMapping(ApiPaths.PERMISSIONS)
@Tag(name = "Permissions", description = "Immutable permission catalogue APIs")
@SecurityRequirement(name = "bearer-key")
@Validated
class PermissionController(
    private val iamQueryService: IamQueryService,
    private val permissionGuard: PermissionGuard,
) {
    /** Searches immutable permission catalogue entries. */
    @GetMapping
    @PreAuthorize("hasAuthority('permission.view')")
    @Operation(
        summary = "Search permissions",
        description = "Searches immutable permission catalogue entries.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Permission page",
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
    fun searchPermissions(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, name = "risk_level") riskLevel: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
        @RequestParam(required = false, name = "sort_by") sortBy: String?,
        @RequestParam(required = false, name = "sort_dir") sortDir: String?,
    ): ApiPage<PermissionSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "permission.view",
        )
        val result =
            iamQueryService.searchPermissions(
                caller.activeOrganisationId,
                PermissionFilter(q, riskLevel, status, page, size, sortBy, sortDir),
                caller,
            )
        return ApiPage(items = result.items.map { it.toResponse() }, page = result.page)
    }

    /** Retrieves an immutable permission catalogue entry. */
    @GetMapping("/{permission_id}")
    @PreAuthorize("hasAuthority('permission.view')")
    @Operation(summary = "Get permission", description = "Retrieves immutable permission metadata.")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Permission details",
            content = [Content(schema = Schema(implementation = PermissionDetailResponse::class))],
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
            description = "Permission not found",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun getPermission(
        @PathVariable("permission_id") permissionId: UUID,
    ): PermissionDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "permission.view",
        )
        return iamQueryService
            .getPermission(
                caller.activeOrganisationId,
                permissionId,
                caller,
            ).toResponse()
    }

    private fun PermissionSummary.toResponse() =
        PermissionSummaryResponse(id, permissionCode, permissionName, moduleCode, riskLevel, status)

    private fun PermissionDetail.toResponse() =
        PermissionDetailResponse(
            id,
            permissionCode,
            permissionName,
            moduleCode,
            description,
            riskLevel,
            status,
            createdAt,
            updatedAt,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
