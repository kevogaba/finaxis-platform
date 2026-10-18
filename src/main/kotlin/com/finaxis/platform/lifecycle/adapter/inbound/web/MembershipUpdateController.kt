package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.MembershipDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.UpdateMembershipRequest
import com.finaxis.platform.lifecycle.application.ProfileUpdateService
import com.finaxis.platform.lifecycle.application.UpdateMembershipCommand
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
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Edits of a tenant membership's type and primary branch (#161). */
@RestController
@RequestMapping(ApiPaths.MEMBERSHIPS)
@Tag(name = "Memberships", description = "Tenant membership lifecycle APIs")
@SecurityRequirement(name = "bearer-key")
@Validated
class MembershipUpdateController(
    private val profileUpdateService: ProfileUpdateService,
    private val lifecycleIamReadService: LifecycleIamReadService,
) {
    /** Edits a membership of the active tenant. */
    @PatchMapping("/{membership_id}")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('membership.update')")
    @Operation(
        summary = "Update a membership",
        description =
            "Edits a membership's type and primary branch. Requires `membership.update` and its " +
                "view permission `membership.view` in the active tenant: a caller holding " +
                "`membership.update` without `membership.view` gets 403 `forbidden` naming the " +
                "first missing code, and nothing is changed. A revoked membership is refused " +
                "with 422, as is `SYSTEM` or a primary branch that is not an ACTIVE branch of " +
                "the tenant. Audited as `membership.update`, naming the changed fields.",
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
            description = "Membership updated",
            content = [Content(schema = Schema(implementation = MembershipDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description =
                "Forbidden: missing `membership.update` or its view `membership.view`",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Membership not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "422",
            description = "No field, a revoked membership, SYSTEM, or an unusable branch",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun update(
        @PathVariable("membership_id") membershipId: UUID,
        @RequestBody @Valid request: UpdateMembershipRequest,
    ): MembershipDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        profileUpdateService.updateMembership(
            UpdateMembershipCommand(
                organisationId = caller.activeOrganisationId,
                membershipId = membershipId,
                membershipType = request.membershipType,
                changesPrimaryBranch = request.primaryBranchId != null,
                primaryBranchId = request.primaryBranchId?.orElse(null),
                actorId = caller.actorId,
            ),
        )
        return lifecycleIamReadService
            .getMembership(caller.activeOrganisationId, membershipId, caller)
            .toResponse()
    }
}
