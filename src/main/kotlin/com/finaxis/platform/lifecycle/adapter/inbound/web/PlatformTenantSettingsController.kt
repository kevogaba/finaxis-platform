package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateOrUpdateTenantSettingRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.DeactivateTenantSettingRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantSettingResponse
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.DeactivateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.GetTenantSettingQuery
import com.finaxis.platform.lifecycle.application.ListTenantSettingsQuery
import com.finaxis.platform.lifecycle.application.TenantSettingsService
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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Platform administration of the settings only the platform may change
 * (`audit_retention_days`), for a tenant the administrator is not a member of (#273).
 */
@RestController
@RequestMapping("${ApiPaths.PLATFORM_TENANTS}/{tenant_id}/settings")
@Tag(
    name = "Platform Tenant Settings",
    description = "Reserved platform administration of a tenant's platform-only settings",
)
@SecurityRequirement(name = "bearer-key")
@Validated
@PreAuthorize("hasAuthority('tenant_setting.manage_platform')")
class PlatformTenantSettingsController(
    private val tenantSettingsService: TenantSettingsService,
) {
    /** Lists the platform-only settings of a tenant. */
    @GetMapping
    @Operation(
        summary = "List a tenant's platform-only settings",
        description = PLATFORM_ONLY_DESCRIPTION,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Platform-only setting page"),
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
            description = "Tenant not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun listSettings(
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<TenantSettingResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        return tenantSettingsService
            .list(
                ListTenantSettingsQuery(
                    organisationId = tenantId,
                    actorId = caller.actorId,
                    page = page,
                    size = size,
                    platformScope = true,
                ),
            ).toApiPage(page, size)
    }

    /** Reads one platform-only setting of a tenant. */
    @GetMapping("/{key}")
    @Operation(
        summary = "Get a tenant's platform-only setting",
        description = PLATFORM_ONLY_DESCRIPTION,
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Setting",
            content = [Content(schema = Schema(implementation = TenantSettingResponse::class))],
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
            description = "Tenant not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "422",
            description = "Not a platform-only key",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun getSetting(
        @PathVariable("tenant_id") tenantId: UUID,
        @PathVariable key: String,
    ): TenantSettingResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        return tenantSettingsService
            .get(GetTenantSettingQuery(tenantId, key, caller.actorId, platformScope = true))
            .toResponse()
    }

    /** Creates or updates one platform-only setting of an active tenant. */
    @PutMapping("/{key}")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @Operation(
        summary = "Create or update a tenant's platform-only setting",
        description = PLATFORM_ONLY_DESCRIPTION,
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
            description = "Setting updated",
            content = [Content(schema = Schema(implementation = TenantSettingResponse::class))],
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
            description = "Tenant not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Tenant is not active",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "422",
            description = "Not a platform-only key, or an invalid value",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun createOrUpdateSetting(
        @PathVariable("tenant_id") tenantId: UUID,
        @PathVariable key: String,
        @RequestBody @Valid request: CreateOrUpdateTenantSettingRequest,
    ): TenantSettingResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        return tenantSettingsService
            .createOrUpdate(
                CreateOrUpdateTenantSettingCommand(
                    organisationId = tenantId,
                    key = key,
                    value = request.value,
                    actorId = caller.actorId,
                    reason = request.reason,
                    platformScope = true,
                ),
            ).toResponse()
    }

    /** Deactivates one platform-only setting of an active tenant. */
    @DeleteMapping("/{key}")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @Operation(
        summary = "Deactivate a tenant's platform-only setting",
        description = PLATFORM_ONLY_DESCRIPTION,
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
        ApiResponse(responseCode = "204", description = "Setting deactivated"),
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
            description = "Tenant not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Tenant is not active",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "422",
            description = "Not a platform-only key",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun deactivateSetting(
        @PathVariable("tenant_id") tenantId: UUID,
        @PathVariable key: String,
        @RequestBody(required = false) @Valid request: DeactivateTenantSettingRequest?,
    ): ResponseEntity<Void> {
        val caller = CallerContextResolver.getPlatformCaller()
        tenantSettingsService.deactivate(
            DeactivateTenantSettingCommand(
                organisationId = tenantId,
                key = key,
                actorId = caller.actorId,
                reason = request?.reason,
                platformScope = true,
            ),
        )
        return ResponseEntity.noContent().build()
    }

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
        const val PLATFORM_ONLY_DESCRIPTION =
            "Only keys marked `platform_admin_only` are reachable here. Requires " +
                "`tenant_setting.manage_platform` in the platform organisation, checked first, " +
                "so a caller without it learns nothing about the tenant."
    }
}
