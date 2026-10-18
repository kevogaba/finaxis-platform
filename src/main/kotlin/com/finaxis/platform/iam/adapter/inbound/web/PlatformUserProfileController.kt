package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.GlobalUserDetailResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.UpdateUserProfileRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.toResponse
import com.finaxis.platform.iam.application.query.GlobalUserQueryService
import com.finaxis.platform.lifecycle.adapter.inbound.web.CallerContextResolver
import com.finaxis.platform.lifecycle.application.ProfileUpdateService
import com.finaxis.platform.lifecycle.application.UpdateUserProfileCommand
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
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Edits of a global user account's profile (#161), by the platform. */
@RestController
@RequestMapping(ApiPaths.PLATFORM_USERS)
@Tag(name = "Platform Users", description = "Platform-wide user lifecycle APIs")
@SecurityRequirement(name = "bearer-key")
@Validated
class PlatformUserProfileController(
    private val profileUpdateService: ProfileUpdateService,
    private val globalUserQueryService: GlobalUserQueryService,
) {
    /** Edits a global user account's display name and phone. */
    @PatchMapping("/{user_id}")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('user.update')")
    @Operation(
        summary = "Update a user's profile",
        description =
            "Edits a user account's display name and phone, across organisations. Requires " +
                "`user.update` and its view permission `user.view` in the platform " +
                "organisation: a caller holding `user.update` without `user.view` gets 403 " +
                "`forbidden` naming the first missing code, and nothing is changed. At least " +
                "one field is required. Audited as `user.update`, naming the changed fields " +
                "and never their values.",
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
            description = "Profile updated",
            content = [Content(schema = Schema(implementation = GlobalUserDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden: missing `user.update` or its view `user.view`",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
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
        ApiResponse(
            responseCode = "422",
            description = "No field was given",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun update(
        @PathVariable("user_id") userId: UUID,
        @RequestBody @Valid request: UpdateUserProfileRequest,
    ): GlobalUserDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        profileUpdateService.updateUser(
            UpdateUserProfileCommand(
                userId = userId,
                displayName = request.displayName,
                phoneE164 = request.phoneE164,
                actorId = caller.actorId,
            ),
        )
        return globalUserQueryService.getUser(userId, caller).toResponse()
    }
}
