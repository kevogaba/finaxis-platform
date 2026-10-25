package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.PlatformPendingCountResponse
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

/** REST controller for the platform pending-tenant count behind an approvals badge. */
@RestController
@RequestMapping(ApiPaths.PLATFORM_APPROVALS)
@Tag(
    name = "Platform Approvals",
    description = "Count of pending tenant approvals the platform caller could act on",
)
@SecurityRequirement(name = "bearer-key")
class PlatformApprovalCountController(
    private val approvalCountQueryService: ApprovalCountQueryService,
) {
    /** Counts the pending tenants the platform caller may approve. */
    @GetMapping("/pending-count")
    @PreAuthorize("hasAuthority('tenant.approve')")
    @Operation(
        summary = "Count pending tenant approvals",
        description =
            "Returns `tenants`, the tenants awaiting approval across the platform, for callers " +
                "holding `tenant.approve` (which implies `tenant.view`) in the platform " +
                "organisation. Tenants the caller requested or submitted itself are excluded, so " +
                "the count is an upper bound on what the caller can approve.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Pending tenant count",
            content = [
                Content(schema = Schema(implementation = PlatformPendingCountResponse::class)),
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
    fun pendingCount(): PlatformPendingCountResponse =
        PlatformPendingCountResponse(
            approvalCountQueryService
                .platformPendingCounts(CallerContextResolver.getPlatformCaller())
                .tenants,
        )
}
