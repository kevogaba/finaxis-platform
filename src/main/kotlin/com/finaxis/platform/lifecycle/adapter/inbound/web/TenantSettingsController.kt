package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.api.apiPageOf
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
import com.finaxis.platform.lifecycle.application.TenantSettingPage
import com.finaxis.platform.lifecycle.application.TenantSettingView
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
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** REST controller for setup-level configuration settings in the active tenant. */
@RestController
@RequestMapping(ApiPaths.TENANT_SETTINGS)
@Tag(name = "Tenant Settings", description = "Tenant setup-level configuration settings")
@SecurityRequirement(name = "bearer-key")
@Validated
class TenantSettingsController(
    private val tenantSettingsService: TenantSettingsService,
) {
    /** Lists the active tenant's catalog and stored setup-level settings. */
    @GetMapping
    @Operation(
        summary = "List tenant settings",
        description = "Lists setup-level configuration settings in the active tenant.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Tenant setting page",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
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
            description = "Tenant setting not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun listSettings(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<TenantSettingResponse> {
        val caller = CallerContextResolver.getTenantCaller()
        val result =
            tenantSettingsService.list(
                ListTenantSettingsQuery(
                    organisationId = caller.activeOrganisationId,
                    actorId = caller.actorId,
                    page = page,
                    size = size,
                ),
            )
        return result.toApiPage(page, size)
    }

    /** Retrieves one setting from the active tenant. */
    @GetMapping("/{key}")
    @Operation(
        summary = "Get tenant setting",
        description = "Retrieves one setup-level configuration setting in the active tenant.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Tenant setting",
            content = [Content(schema = Schema(implementation = TenantSettingResponse::class))],
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
            description = "Tenant setting not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "422",
            description = "Invalid setting operation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun getSetting(
        @PathVariable key: String,
    ): TenantSettingResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return tenantSettingsService
            .get(
                GetTenantSettingQuery(
                    organisationId = caller.activeOrganisationId,
                    key = key,
                    actorId = caller.actorId,
                ),
            ).toResponse()
    }

    /** Creates or updates one setup-level setting in the active tenant. */
    @PutMapping("/{key}")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @Operation(
        summary = "Create or update tenant setting",
        description =
            "Creates or updates one setup-level configuration setting in the active tenant.",
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
            description = "Tenant setting updated",
            content = [Content(schema = Schema(implementation = TenantSettingResponse::class))],
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
            description = "Tenant setting not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Organisation is not active",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "422",
            description = "Invalid setting operation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun createOrUpdateSetting(
        @PathVariable key: String,
        @RequestBody @Valid request: CreateOrUpdateTenantSettingRequest,
    ): TenantSettingResponse {
        val caller = CallerContextResolver.getTenantCaller()
        return tenantSettingsService
            .createOrUpdate(
                CreateOrUpdateTenantSettingCommand(
                    organisationId = caller.activeOrganisationId,
                    key = key,
                    value = request.value,
                    actorId = caller.actorId,
                    reason = request.reason,
                ),
            ).toResponse()
    }

    /** Deactivates one setup-level setting in the active tenant. */
    @DeleteMapping("/{key}")
    @IdempotentMutation(scope = IdempotencyScopeKind.TENANT)
    @Operation(
        summary = "Deactivate tenant setting",
        description = "Deactivates one setup-level configuration setting in the active tenant.",
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
        ApiResponse(responseCode = "204", description = "Tenant setting deactivated"),
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
            description = "Tenant setting not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Organisation is not active",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "422",
            description = "Invalid setting operation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun deactivateSetting(
        @PathVariable key: String,
        @RequestBody(required = false) @Valid request: DeactivateTenantSettingRequest?,
    ): ResponseEntity<Void> {
        val caller = CallerContextResolver.getTenantCaller()
        tenantSettingsService.deactivate(
            DeactivateTenantSettingCommand(
                organisationId = caller.activeOrganisationId,
                key = key,
                actorId = caller.actorId,
                reason = request?.reason,
            ),
        )
        return ResponseEntity.noContent().build()
    }

    private fun TenantSettingPage.toApiPage(
        page: Int,
        size: Int,
    ): ApiPage<TenantSettingResponse> =
        apiPageOf(items.map { it.toResponse() }, page, size, totalItems)

    private fun TenantSettingView.toResponse() =
        TenantSettingResponse(
            key = key,
            value = value,
            valueType = valueType,
            sensitive = sensitive,
            platformAdminOnly = platformAdminOnly,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
