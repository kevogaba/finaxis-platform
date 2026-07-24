package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.DeactivateUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.ReactivateUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.SuspendUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.UserInTenantSummaryResponse
import com.finaxis.platform.iam.adapter.inbound.web.dto.UserLifecycleResultResponse
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.UserInTenantDetail
import com.finaxis.platform.iam.application.query.UserInTenantFilter
import com.finaxis.platform.iam.application.query.UserInTenantSummary
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.adapter.inbound.web.CallerContextResolver
import com.finaxis.platform.lifecycle.application.DeactivateUserCommand
import com.finaxis.platform.lifecycle.application.ReactivateUserCommand
import com.finaxis.platform.lifecycle.application.SuspendUserCommand
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
import java.util.UUID

/**
 * REST controller for platform administration of users nested under a tenant.
 */
@RestController
@RequestMapping("${ApiPaths.PLATFORM_TENANTS}/{tenant_id}/users")
@Tag(name = "Platform Tenant Users", description = "Platform administration of tenant users")
@SecurityRequirement(name = "bearerAuth")
@Validated
class PlatformUserController(
    private val iamQueryService: IamQueryService,
) {
    /** Searches users belonging to a specified tenant organisation. */
    @GetMapping
    @PreAuthorize("hasAuthority('user.view')")
    @Operation(
        summary = "Search tenant users as platform administrator",
        description = "Searches users belonging to a specific tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "User page",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
        ),
    )
    fun searchUsers(
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, name = "user_status") userStatus: String?,
        @RequestParam(required = false, name = "membership_status") membershipStatus: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<UserInTenantSummaryResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        val pageResult =
            iamQueryService.searchUsers(
                tenantId,
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

    /** Retrieves a user within the specified tenant organisation. */
    @GetMapping("/{user_id}")
    @PreAuthorize("hasAuthority('user.view')")
    @Operation(
        summary = "Get tenant user as platform administrator",
        description = "Retrieves a user within a specific tenant organisation.",
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
        @PathVariable("tenant_id") tenantId: UUID,
        @PathVariable("user_id") userId: UUID,
    ): UserInTenantSummaryResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        return iamQueryService.getUserInTenant(tenantId, userId, caller).toResponse()
    }

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

/**
 * REST controller for global platform-only user lifecycle transitions.
 *
 * This is separate from [PlatformUserController] because Spring combines class and method mappings.
 */
@RestController
@Tag(name = "Platform Users", description = "Platform-wide user lifecycle APIs")
@SecurityRequirement(name = "bearerAuth")
@Validated
class PlatformUserLifecycleController(
    private val userProvisioningService: UserProvisioningService,
    private val permissionGuard: PermissionGuard,
) {
    /** Suspends a global user account. */
    @PostMapping("${ApiPaths.PLATFORM_USERS}/{user_id}/suspend")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('user.suspend')")
    @Operation(
        summary = "Suspend global user",
        description = "Suspends a global user account from the reserved platform context.",
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
            description = "User suspended",
            content = [
                Content(
                    schema = Schema(implementation = UserLifecycleResultResponse::class),
                ),
            ],
        ),
    )
    fun suspendUser(
        @PathVariable("user_id") userId: UUID,
        @RequestBody @Valid request: SuspendUserRequest,
    ): ResponseEntity<UserLifecycleResultResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "user.suspend")
        userProvisioningService.suspendUser(
            SuspendUserCommand(
                organisationId = PlatformOrganisation.ID,
                userId = userId,
                actorId = caller.actorId,
                reason = request.reason,
                requestId = UUID.randomUUID().toString(),
            ),
        )
        return ResponseEntity.ok(UserLifecycleResultResponse(userId, "SUSPENDED"))
    }

    /** Reactivates a suspended global user account. */
    @PostMapping("${ApiPaths.PLATFORM_USERS}/{user_id}/reactivate")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('user.activate')")
    @Operation(
        summary = "Reactivate global user",
        description =
            "Reactivates a suspended global user account from the reserved platform context.",
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
            description = "User reactivated",
            content = [
                Content(
                    schema = Schema(implementation = UserLifecycleResultResponse::class),
                ),
            ],
        ),
    )
    fun reactivateUser(
        @PathVariable("user_id") userId: UUID,
        @RequestBody @Valid request: ReactivateUserRequest,
    ): ResponseEntity<UserLifecycleResultResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "user.activate")
        userProvisioningService.reactivateUser(
            ReactivateUserCommand(
                organisationId = PlatformOrganisation.ID,
                userId = userId,
                actorId = caller.actorId,
                reason = request.reason,
                requestId = UUID.randomUUID().toString(),
            ),
        )
        return ResponseEntity.ok(UserLifecycleResultResponse(userId, "ACTIVE"))
    }

    /** Deactivates a global user account. */
    @PostMapping("${ApiPaths.PLATFORM_USERS}/{user_id}/deactivate")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('user.deactivate')")
    @Operation(
        summary = "Deactivate global user",
        description = "Deactivates a global user account from the reserved platform context.",
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
            description = "User deactivated",
            content = [
                Content(
                    schema = Schema(implementation = UserLifecycleResultResponse::class),
                ),
            ],
        ),
    )
    fun deactivateUser(
        @PathVariable("user_id") userId: UUID,
        @RequestBody @Valid request: DeactivateUserRequest,
    ): ResponseEntity<UserLifecycleResultResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "user.deactivate")
        userProvisioningService.deactivateUser(
            DeactivateUserCommand(
                organisationId = PlatformOrganisation.ID,
                userId = userId,
                actorId = caller.actorId,
                reason = request.reason,
                requestId = UUID.randomUUID().toString(),
            ),
        )
        return ResponseEntity.ok(UserLifecycleResultResponse(userId, "DEACTIVATED"))
    }
}
