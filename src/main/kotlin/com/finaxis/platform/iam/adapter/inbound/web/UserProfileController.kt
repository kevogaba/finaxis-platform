package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.profile.UserProfile
import com.finaxis.platform.iam.application.profile.UserProfileService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST adapter for authenticated user profile and tenant-context details.
 */
@RestController
@RequestMapping(ApiPaths.AUTH)
@Tag(name = "User Profile")
class UserProfileController(
    private val service: UserProfileService,
) {
    /**
     * Returns the authenticated user's active organisation, branch, roles, and permissions.
     */
    @Operation(
        summary = "Get current user profile",
        description =
            "Returns the authenticated application user, selected organisation, selected branch, " +
                "assigned branches, roles, and effective permissions.",
        security = [SecurityRequirement(name = "bearer-key")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Profile returned"),
        ApiResponse(
            responseCode = "403",
            description = "Active organisation context or iam.profile.read permission is missing",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    @PreAuthorize("hasAuthority('iam.profile.read')")
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal principal: AppPrincipal,
    ): UserProfile = service.profile(principal)
}
