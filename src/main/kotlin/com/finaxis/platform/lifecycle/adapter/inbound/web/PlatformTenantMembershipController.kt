package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.MembershipDetailResponse
import com.finaxis.platform.lifecycle.application.ActingScope
import com.finaxis.platform.lifecycle.application.ApproveUserCommand
import com.finaxis.platform.lifecycle.application.UserProvisioningService
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
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST controller for the platform administrator acting as the audited checker of a tenant's
 * membership invitations (ADR 0028). The tenant's own maker-checker rule is unchanged; this is
 * the way out when no tenant user other than the inviter can approve.
 */
@RestController
@RequestMapping("${ApiPaths.PLATFORM_TENANTS}/{tenant_id}/memberships")
@Tag(
    name = "Platform Tenant Memberships",
    description = "Reserved platform checker of tenant membership invitations",
)
@SecurityRequirement(name = "bearer-key")
@Validated
class PlatformTenantMembershipController(
    private val userProvisioningService: UserProvisioningService,
    private val lifecycleIamReadService: LifecycleIamReadService,
) {
    /** Approves and activates a pending membership invitation as the platform checker. */
    @PostMapping("/{membership_id}/activate")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('user.approve')")
    @Operation(
        summary = "Activate tenant membership as platform checker",
        description =
            "Approves a pending membership of the path tenant on the tenant's behalf and starts " +
                "external provisioning when needed. Requires `user.approve` in the platform " +
                "organisation. The inviter cannot approve their own invitation, whether a tenant " +
                "user or a platform administrator; the action is audited with the platform actor.",
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
                "Forbidden, not in platform context, or the caller invited the membership or is " +
                    "the invited user (the checker is neither the maker nor the beneficiary)",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "Tenant or membership not found " +
                    "(the platform organisation is never a valid tenant)",
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
                "Tenant is not active, the membership is not pending approval, or the tenant " +
                    "already " +
                    "has an active member beyond its bootstrap administrator " +
                    "(code `lifecycle.platform_checker_closed`)",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun activate(
        @PathVariable("tenant_id") tenantId: UUID,
        @PathVariable("membership_id") membershipId: UUID,
    ): ResponseEntity<MembershipDetailResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        val result =
            userProvisioningService.approveUser(
                ApproveUserCommand(
                    organisationId = tenantId,
                    membershipId = membershipId,
                    approvedBy = caller.actorId,
                    requestId = uuidV7().toString(),
                    scope = ActingScope.PLATFORM,
                ),
            )
        // The approval above authorised the caller on this membership; the response echoes it
        // without a second permission gate (`membership.view`), which would roll the approval back.
        val response =
            lifecycleIamReadService
                .getMembershipAfterAuthorizedMutation(tenantId, membershipId)
                .toResponse()
        return if (result.keycloakProvisioningRequested) {
            ResponseEntity.accepted().body(response)
        } else {
            ResponseEntity.ok(response)
        }
    }
}
