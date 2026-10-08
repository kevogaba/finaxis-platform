package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ActivateMembershipRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.MembershipDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.MembershipSummaryResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReactivateMembershipRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.RevokeMembershipRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SuspendMembershipRequest
import com.finaxis.platform.lifecycle.application.ApproveUserCommand
import com.finaxis.platform.lifecycle.application.DecisionRemark
import com.finaxis.platform.lifecycle.application.ReactivateMembershipCommand
import com.finaxis.platform.lifecycle.application.Reason
import com.finaxis.platform.lifecycle.application.RevokeTenantMembershipCommand
import com.finaxis.platform.lifecycle.application.SuspendMembershipCommand
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.query.LifecycleIamReadService
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipDetail
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipFilter
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipSummary
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST controller for tenant-facing membership lifecycle operations.
 */
@RestController
@RequestMapping(ApiPaths.MEMBERSHIPS)
@Tag(name = "Memberships", description = "Tenant membership lifecycle APIs")
@SecurityRequirement(name = "bearer-key")
@Validated
@Suppress("LargeClass")
class MembershipController(
    private val userProvisioningService: UserProvisioningService,
    private val lifecycleIamReadService: LifecycleIamReadService,
) {
    /**
     * Searches memberships in the active tenant organisation.
     *
     * Sort parameters are accepted for a stable list contract but Phase A's membership query
     * filter does not yet expose sorting fields.
     */
    @GetMapping
    @Suppress("UnusedParameter")
    @PreAuthorize("hasAuthority('membership.view')")
    @Operation(
        summary = "Search memberships",
        description = "Searches memberships in the active tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Membership page",
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid page or filter",
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
            description = "Forbidden",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun searchMemberships(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, name = "membership_status") membershipStatus: String?,
        @RequestParam(required = false, name = "membership_type") membershipType: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
        @Parameter(hidden = true)
        @RequestParam(required = false, name = "sort_by") sortBy: String?,
        @Parameter(hidden = true)
        @RequestParam(required = false, name = "sort_dir") sortDir: String?,
    ): ApiPage<MembershipSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val pageResult =
            lifecycleIamReadService.searchMemberships(
                caller.activeOrganisationId,
                LifecycleMembershipFilter(
                    q = q,
                    membershipStatus = membershipStatus,
                    membershipType = membershipType,
                    page = page,
                    size = size,
                ),
                caller,
            )
        return ApiPage(items = pageResult.items.map { it.toResponse() }, page = pageResult.page)
    }

    /** Retrieves detailed metadata for a membership in the active tenant. */
    @GetMapping("/{membership_id}")
    @PreAuthorize("hasAuthority('membership.view')")
    @Operation(
        summary = "Get membership details",
        description = "Retrieves membership metadata in the active tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Membership details",
            content = [Content(schema = Schema(implementation = MembershipDetailResponse::class))],
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
            description = "Forbidden",
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
    )
    fun getMembership(
        @PathVariable("membership_id") membershipId: UUID,
    ): MembershipDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return lifecycleIamReadService
            .getMembership(
                caller.activeOrganisationId,
                membershipId,
                caller,
            ).toResponse()
    }

    /** Approves and activates a pending membership invitation. */
    @PostMapping("/{membership_id}/activate")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('user.approve')")
    @Operation(
        summary = "Activate membership",
        description =
            "Approves a pending membership and starts external provisioning when needed. The " +
                "optional body `reason` (at most 500 characters) is a decision remark: it is " +
                "stored as the membership's status reason when the membership activates in this " +
                "call (200), and is always recorded on the `user.approve` audit row. On a 202 " +
                "the membership activates later, without the remark, so it lives only on the " +
                "audit row.",
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
            description = "Membership activated locally",
            content = [Content(schema = Schema(implementation = MembershipDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "202",
            description = "Keycloak provisioning queued",
            content = [Content(schema = Schema(implementation = MembershipDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
            description = "Forbidden",
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
            responseCode = "409",
            description =
                "Membership state conflicts with the operation: it is not pending approval, the " +
                    "user has no active branch or role assignment yet, or the user account is " +
                    "not in a state that allows provisioning or activation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun activate(
        @PathVariable("membership_id") membershipId: UUID,
        @RequestBody(required = false) @Valid request: ActivateMembershipRequest?,
    ): ResponseEntity<MembershipDetailResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val result =
            userProvisioningService.approveUser(
                ApproveUserCommand(
                    organisationId = caller.activeOrganisationId,
                    membershipId = membershipId,
                    approvedBy = caller.actorId,
                    requestId = uuidV7().toString(),
                    reason = DecisionRemark.optional(request?.reason),
                ),
            )
        val response =
            lifecycleIamReadService
                .getMembership(
                    caller.activeOrganisationId,
                    membershipId,
                    caller,
                ).toResponse()
        return if (result.keycloakProvisioningRequested) {
            ResponseEntity.accepted().body(response)
        } else {
            ResponseEntity.ok(response)
        }
    }

    /** Suspends an active membership. */
    @PostMapping("/{membership_id}/suspend")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('membership.suspend')")
    @Operation(
        summary = "Suspend membership",
        description = "Suspends an active membership without revoking its grants.",
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
            description = "Membership suspended",
            content = [Content(schema = Schema(implementation = MembershipDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
            description = "Forbidden",
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
            responseCode = "409",
            description = "Membership state conflicts with the operation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun suspend(
        @PathVariable("membership_id") membershipId: UUID,
        @RequestBody @Valid request: SuspendMembershipRequest,
    ): MembershipDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        userProvisioningService.suspendMembership(
            SuspendMembershipCommand(
                organisationId = caller.activeOrganisationId,
                membershipId = membershipId,
                actorId = caller.actorId,
                reason = Reason.required(request.reason),
                requestId = uuidV7().toString(),
            ),
        )
        return lifecycleIamReadService
            .getMembership(
                caller.activeOrganisationId,
                membershipId,
                caller,
            ).toResponse()
    }

    /** Reactivates a suspended membership. */
    @PostMapping("/{membership_id}/reactivate")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('membership.reactivate')")
    @Operation(
        summary = "Reactivate membership",
        description = "Reactivates a suspended membership without recreating its grants.",
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
            description = "Membership reactivated",
            content = [Content(schema = Schema(implementation = MembershipDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
            description = "Forbidden",
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
            responseCode = "409",
            description = "Membership state conflicts with the operation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun reactivate(
        @PathVariable("membership_id") membershipId: UUID,
        @RequestBody(required = false) @Valid request: ReactivateMembershipRequest?,
    ): MembershipDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        userProvisioningService.reactivateMembership(
            ReactivateMembershipCommand(
                organisationId = caller.activeOrganisationId,
                membershipId = membershipId,
                actorId = caller.actorId,
                reason = DecisionRemark.optional(request?.reason),
                requestId = uuidV7().toString(),
            ),
        )
        return lifecycleIamReadService
            .getMembership(
                caller.activeOrganisationId,
                membershipId,
                caller,
            ).toResponse()
    }

    /** Revokes a membership and its active local grants. */
    @PostMapping("/{membership_id}/revoke")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('membership.revoke')")
    @Operation(
        summary = "Revoke membership",
        description = "Revokes a membership and its active local grants.",
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
            description = "Membership revoked",
            content = [Content(schema = Schema(implementation = MembershipDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request",
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
            description = "Forbidden",
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
            responseCode = "409",
            description = "Membership state conflicts with the operation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun revoke(
        @PathVariable("membership_id") membershipId: UUID,
        @RequestBody @Valid request: RevokeMembershipRequest,
    ): MembershipDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        userProvisioningService.revokeTenantMembership(
            RevokeTenantMembershipCommand(
                organisationId = caller.activeOrganisationId,
                membershipId = membershipId,
                actorId = caller.actorId,
                reason = Reason.required(request.reason),
                requestId = uuidV7().toString(),
            ),
        )
        return lifecycleIamReadService
            .getMembership(
                caller.activeOrganisationId,
                membershipId,
                caller,
            ).toResponse()
    }

    private fun LifecycleMembershipSummary.toResponse() =
        MembershipSummaryResponse(
            id = id,
            userId = userId,
            membershipStatus = membershipStatus,
            membershipType = membershipType,
            primaryBranchId = primaryBranchId,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
