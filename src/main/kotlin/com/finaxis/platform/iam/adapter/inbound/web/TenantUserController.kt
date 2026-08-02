package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.BranchAssignmentEntryDto
import com.finaxis.platform.iam.adapter.inbound.web.dto.InviteUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.RoleAssignmentEntryDto
import com.finaxis.platform.iam.adapter.inbound.web.dto.UserInTenantSummaryResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.UserInvitationResultResponse
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.UserInTenantDetail
import com.finaxis.platform.iam.application.query.UserInTenantFilter
import com.finaxis.platform.iam.application.query.UserInTenantSummary
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.adapter.inbound.web.CallerContextResolver
import com.finaxis.platform.lifecycle.application.BranchAssignmentRequest
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.UserProvisioningService
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
import java.net.URI
import java.util.UUID

/**
 * REST controller for tenant-facing user invitation and lookup operations.
 */
@RestController
@RequestMapping(ApiPaths.TENANT_USERS)
@Tag(name = "Tenant Users", description = "Tenant user invitation and lookup APIs")
@SecurityRequirement(name = "bearer-key")
@Validated
class TenantUserController(
    private val userProvisioningService: UserProvisioningService,
    private val iamQueryService: IamQueryService,
    private val permissionGuard: PermissionGuard,
) {
    /** Searches users in the active tenant organisation. */
    @GetMapping
    @PreAuthorize("hasAuthority('user.view')")
    @Operation(
        summary = "Search tenant users",
        description = "Searches users in the active tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "User page",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
        ),
    )
    fun searchUsers(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, name = "user_status") userStatus: String?,
        @RequestParam(required = false, name = "membership_status") membershipStatus: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<UserInTenantSummaryResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val pageResult =
            iamQueryService.searchUsers(
                caller.activeOrganisationId,
                UserInTenantFilter(
                    q = q,
                    userStatus = userStatus,
                    membershipStatus = membershipStatus,
                    page = page,
                    size = size,
                ),
                caller,
            )
        return ApiPage(items = pageResult.items.map { it.toResponse() }, page = pageResult.page)
    }

    /** Invites a user into the active tenant organisation. */
    @PostMapping
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @PreAuthorize("hasAuthority('user.invite')")
    @Operation(
        summary = "Invite tenant user",
        description = "Records a tenant user invitation and its requested access assignments.",
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
            responseCode = "201",
            description = "User invitation created",
            content = [
                Content(
                    schema = Schema(implementation = UserInvitationResultResponse::class),
                ),
            ],
        ),
    )
    fun inviteUser(
        @RequestBody @Valid request: InviteUserRequest,
    ): ResponseEntity<UserInvitationResultResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        permissionGuard.requireTenantPermission(
            caller.actorId,
            caller.activeOrganisationId,
            "user.invite",
        )
        val result =
            userProvisioningService.inviteUser(
                InviteUserCommand(
                    organisationId = caller.activeOrganisationId,
                    email = request.email,
                    username = request.username,
                    displayName = request.displayName,
                    phoneE164 = request.phoneE164,
                    membershipType = request.membershipType,
                    primaryBranchId = request.primaryBranchId,
                    branchAssignments = request.branchAssignments.map { it.toRequest() },
                    roleAssignments = request.roleAssignments.map { it.toRequest() },
                    invitedBy = caller.actorId,
                    sendKeycloakInvite = request.sendKeycloakInvite,
                    sendApplicationInvite = request.sendApplicationInvite,
                    requestId = uuidV7().toString(),
                ),
            )
        val response =
            UserInvitationResultResponse(
                userId = result.userId,
                membershipId = result.membershipId,
                userStatus = result.userStatus.name,
                membershipStatus = result.membershipStatus.name,
            )
        return ResponseEntity
            .created(URI.create("${ApiPaths.TENANT_USERS}/${result.userId}"))
            .body(response)
    }

    /** Retrieves a user within the active tenant organisation. */
    @GetMapping("/{user_id}")
    @PreAuthorize("hasAuthority('user.view')")
    @Operation(
        summary = "Get tenant user",
        description = "Retrieves user metadata within the active tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Tenant user",
            content = [
                Content(
                    schema = Schema(implementation = UserInTenantSummaryResponse::class),
                ),
            ],
        ),
    )
    fun getUser(
        @PathVariable("user_id") userId: UUID,
    ): UserInTenantSummaryResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return iamQueryService
            .getUserInTenant(caller.activeOrganisationId, userId, caller)
            .toResponse()
    }

    private fun BranchAssignmentEntryDto.toRequest() =
        BranchAssignmentRequest(branchId = branchId, assignmentType = assignmentType)

    private fun RoleAssignmentEntryDto.toRequest() =
        RoleAssignmentRequest(roleId = roleId, scopeType = scopeType, branchId = branchId)

    private fun UserInTenantSummary.toResponse() =
        UserInTenantSummaryResponse(
            id = id,
            username = username,
            email = email,
            displayName = displayName,
            userStatus = userStatus,
            membershipStatus = membershipStatus,
        )

    private fun UserInTenantDetail.toResponse() =
        UserInTenantSummaryResponse(
            id = id,
            username = username,
            email = email,
            displayName = displayName,
            userStatus = userStatus,
            membershipStatus = membershipStatus,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
