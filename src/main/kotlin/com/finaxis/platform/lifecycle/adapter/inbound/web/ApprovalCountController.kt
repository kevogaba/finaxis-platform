package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantPendingCountResponse
import com.finaxis.platform.lifecycle.application.query.ApprovalCountQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** REST controller for the tenant pending-approval counts behind an approvals badge. */
@RestController
@RequestMapping(ApiPaths.TENANT_APPROVALS)
@Tag(
    name = "Approvals",
    description = "Counts of pending approvals the caller could act on; not an inbox",
)
@SecurityRequirement(name = "bearer-key")
class ApprovalCountController(
    private val approvalCountQueryService: ApprovalCountQueryService,
) {
    /** Counts the pending memberships and branches the caller may approve. */
    @GetMapping("/pending-count")
    @PreAuthorize("hasAnyAuthority('user.approve', 'branch.approve')")
    @Operation(
        summary = "Count pending approvals",
        description =
            "Returns `memberships` (for `user.approve` holders) and `branches` (for tenant-wide " +
                "`branch.approve` holders) in the active organisation. A key is omitted when the " +
                "caller may not approve that resource; a caller who may approve neither is " +
                "refused. Items the caller made itself are excluded, so each count is an upper " +
                "bound on what the caller can approve. Accounting approvals are not included. " +
                "List the resource itself for the items.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Pending approval counts",
            content = [
                Content(
                    schema = Schema(implementation = TenantPendingCountResponse::class),
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
    )
    fun pendingCount(): TenantPendingCountResponse {
        val counts =
            approvalCountQueryService.tenantPendingCounts(CallerContextResolver.getTenantCaller())
        return TenantPendingCountResponse(counts.memberships, counts.branches)
    }
}
