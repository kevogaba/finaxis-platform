package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.MembershipDetailResponse
import com.finaxis.platform.lifecycle.application.InvitationRecoveryService
import com.finaxis.platform.lifecycle.application.ResendInvitationCommand
import com.finaxis.platform.lifecycle.application.RetryInvitationCommand
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
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Recovery of a member's invitation (#274): run failed provisioning again, or resend the email. */
@RestController
@RequestMapping(ApiPaths.MEMBERSHIPS)
@Tag(name = "Memberships", description = "Tenant membership lifecycle APIs")
@SecurityRequirement(name = "bearer-key")
@Validated
class MembershipInvitationController(
    private val invitationRecoveryService: InvitationRecoveryService,
    private val lifecycleIamReadService: LifecycleIamReadService,
) {
    /** Runs a membership's failed identity provisioning again. */
    @PostMapping("/{membership_id}/invitation/retry")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('user.approve')")
    @Operation(
        summary = "Retry a failed invitation",
        description =
            "Runs the identity provisioning job of a membership still awaiting approval again, " +
                "when its last attempt failed (a job still scheduled or running is left alone). " +
                "It is no approval: it needs `user.approve` and its view permission " +
                "`membership.view`, and a caller holding `user.approve` without " +
                "`membership.view` gets 403 `forbidden` naming the first missing code. A " +
                "membership that is not awaiting approval with provisioning pending, or whose " +
                "provisioning succeeded, is 409. Audited as `user.invitation_retry`. The answer " +
                "is the membership detail, whose `invitation` shows the new status.",
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
            description = "Provisioning requeued or still queued",
            content = [Content(schema = Schema(implementation = MembershipDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden: missing `user.approve` or its view `membership.view`",
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
            description = "Nothing to retry: not awaiting approval, or provisioning succeeded",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun retry(
        @PathVariable("membership_id") membershipId: UUID,
    ): MembershipDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        invitationRecoveryService.retry(
            RetryInvitationCommand(
                caller.activeOrganisationId,
                membershipId,
                caller.actorId,
                uuidV7().toString(),
            ),
        )
        return lifecycleIamReadService
            .getMembership(caller.activeOrganisationId, membershipId, caller)
            .toResponse()
    }

    /** Sends a member's invitation email again. */
    @PostMapping("/{membership_id}/invitation/resend")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('user.invite')")
    @Operation(
        summary = "Resend an invitation",
        description =
            "Sends the Keycloak invitation email again to a member whose account is invited and " +
                "has not signed in. Needs `user.invite` and its view permissions " +
                "`membership.view` and `user.view`, checked first. Rate limited per member: a " +
                "second resend inside the cooldown (5 minutes by default, " +
                "`finaxis.invitation.resend-cooldown`) is 409 " +
                "`lifecycle.invitation_resend_too_soon`; an account that is not invited is 409 " +
                "`lifecycle.invitation_resend_not_applicable`. The email is queued as a durable " +
                "job. Audited as `user.invitation_resend`.",
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
            description = "Resend queued",
            content = [Content(schema = Schema(implementation = MembershipDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden: missing `user.invite` or a view it implies",
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
            description = "Too soon after the last send, or the account is not invited",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun resend(
        @PathVariable("membership_id") membershipId: UUID,
    ): MembershipDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        invitationRecoveryService.resend(
            ResendInvitationCommand(
                caller.activeOrganisationId,
                membershipId,
                caller.actorId,
                uuidV7().toString(),
            ),
        )
        return lifecycleIamReadService
            .getMembership(caller.activeOrganisationId, membershipId, caller)
            .toResponse()
    }
}
