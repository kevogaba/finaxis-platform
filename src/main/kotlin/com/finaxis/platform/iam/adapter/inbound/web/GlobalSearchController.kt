package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.BranchSearchHitResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.GlobalSearchResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.MembershipSearchHitResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.RoleSearchHitResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.UserSearchHitResponse
import com.finaxis.platform.iam.application.query.GlobalSearchQueryService
import com.finaxis.platform.iam.application.query.GlobalSearchResult
import com.finaxis.platform.lifecycle.adapter.inbound.web.CallerContextResolver
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for the tenant global search (#167, ADR 0039). It carries no `@PreAuthorize`:
 * no single authority gates it, so the per-type checks in the application layer are the only
 * authorisation, and a coarse gate here would hide types the caller may in fact view.
 */
@RestController
@RequestMapping(ApiPaths.SEARCH)
@Tag(name = "Tenant Search", description = "Tenant-wide directory search")
@SecurityRequirement(name = "bearer-key")
class GlobalSearchController(
    private val globalSearchQueryService: GlobalSearchQueryService,
) {
    /**
     * Searches users, branches, roles and memberships of the active tenant organisation for one
     * text, returning at most 10 hits of each type the caller may view.
     */
    @GetMapping
    @Operation(
        summary = "Search the tenant directory",
        description =
            "Searches users (username, email, display name), branches (code, name), roles " +
                "(code, name) and memberships (username, email, display name) of the active " +
                "organisation for `q`, case-insensitively, as plain text (`%` and `_` match " +
                "themselves). Returns one array per type with at most 10 hits each, in the " +
                "order of that type's own list, and no counts: use the resource's own list " +
                "with the same `q` for more. A type the caller may not view (user.view, " +
                "branch.view, role.view, membership.view, at the scope of that type's own " +
                "list) has no key in the response, so an empty array always means \"viewable, " +
                "no match\". Branch hits are limited to the branches the caller holds " +
                "branch.view on while they are ACTIVE. Archived roles are never hits.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Hits per viewable type",
            content = [Content(schema = Schema(implementation = GlobalSearchResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description =
                "`q` is missing, is not 2 to 64 characters after trimming, or holds a control " +
                    "character (violation on `q`). Checked before permissions, so it applies " +
                    "to every caller.",
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
                "The caller may view none of the four types: 403 `forbidden` naming the first " +
                    "missing code in the order users, branches, roles, memberships, which is " +
                    "always `user.view`. A caller who may view at least one type never gets " +
                    "this response.",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun search(
        @Parameter(
            required = true,
            description =
                "Text to find: required, trimmed, 2 to 64 characters, no control characters. " +
                    "Not a pattern: wildcard characters match themselves.",
            schema =
                Schema(
                    minLength = GlobalSearchQueryService.MIN_QUERY_LENGTH,
                    maxLength = GlobalSearchQueryService.MAX_QUERY_LENGTH,
                ),
        )
        @RequestParam(required = false) q: String?,
    ): GlobalSearchResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return globalSearchQueryService.search(caller, q).toResponse()
    }
}

private fun GlobalSearchResult.toResponse() =
    GlobalSearchResponse(
        users =
            users?.map {
                UserSearchHitResponse(it.id, it.displayName, it.userStatus, it.email)
            },
        branches =
            branches?.map {
                BranchSearchHitResponse(it.id, it.branchName, it.status, it.branchCode)
            },
        roles =
            roles?.map {
                RoleSearchHitResponse(it.id, it.roleName, it.status, it.roleCode)
            },
        memberships =
            memberships?.map {
                MembershipSearchHitResponse(
                    it.id,
                    it.displayName,
                    it.membershipStatus,
                    it.membershipType,
                )
            },
    )
