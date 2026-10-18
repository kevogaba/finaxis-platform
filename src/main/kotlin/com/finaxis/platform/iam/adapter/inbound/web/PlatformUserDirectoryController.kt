package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.GlobalUserDetailResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.GlobalUserSummaryResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.toResponse
import com.finaxis.platform.iam.application.query.GlobalUserFilter
import com.finaxis.platform.iam.application.query.GlobalUserQueryService
import com.finaxis.platform.lifecycle.adapter.inbound.web.CallerContextResolver
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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

/**
 * The platform-wide user directory (#160): user accounts across every organisation, which the
 * per-tenant user routes cannot give a platform operator. It shares its base path with
 * [PlatformUserLifecycleController], which owns the POST transitions.
 */
@RestController
@RequestMapping(ApiPaths.PLATFORM_USERS)
@Tag(name = "Platform User Directory", description = "Platform-wide read of user accounts")
@SecurityRequirement(name = "bearer-key")
@Validated
class PlatformUserDirectoryController(
    private val globalUserQueryService: GlobalUserQueryService,
) {
    /** Searches user accounts across every organisation. */
    @GetMapping
    @PreAuthorize("hasAuthority('user.view')")
    @Operation(
        summary = "Search users across organisations",
        description =
            "Pages user accounts of every organisation, newest first by default. Requires " +
                "`user.view` in the platform organisation. `q` matches username, email or " +
                "display name; `organisation_id` narrows to accounts holding a membership " +
                "there; `sort_by` is one of createdAt, username, displayName, email, " +
                "userStatus. Each item counts the account's memberships.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "User page"),
        ApiResponse(
            responseCode = "400",
            description = "Invalid page or sort",
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
    fun searchUsers(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, name = "user_status") userStatus: String?,
        @RequestParam(required = false, name = "organisation_id") organisationId: UUID?,
        @Parameter(
            description = "Field to sort by. Values are camelCase, unlike other wire names.",
            schema =
                Schema(
                    allowableValues =
                        ["createdAt", "username", "displayName", "email", "userStatus"],
                ),
        )
        @RequestParam(required = false, name = "sort_by") sortBy: String?,
        @RequestParam(required = false, name = "sort_dir") sortDir: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<GlobalUserSummaryResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        val result =
            globalUserQueryService.searchUsers(
                GlobalUserFilter(q, userStatus, organisationId, page, size, sortBy, sortDir),
                caller,
            )
        return ApiPage(items = result.items.map { it.toResponse() }, page = result.page)
    }

    /** Retrieves one user account with its memberships. */
    @GetMapping("/{user_id}")
    @PreAuthorize("hasAuthority('user.view')")
    @Operation(
        summary = "Get a user across organisations",
        description =
            "Retrieves a user account and its memberships (at most 100, newest first; " +
                "`membership_count` is the full count). Requires `user.view` in the platform " +
                "organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "User account",
            content = [Content(schema = Schema(implementation = GlobalUserDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "User account not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun getUser(
        @PathVariable("user_id") userId: UUID,
    ): GlobalUserDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        return globalUserQueryService.getUser(userId, caller).toResponse()
    }

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
