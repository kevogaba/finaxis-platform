package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantDetailResponse
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.application.query.TenantDetail
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for tenant-facing current organisation operations.
 */
@RestController
@RequestMapping(ApiPaths.TENANT)
@Tag(name = "Current Tenant", description = "Active tenant metadata endpoints")
@SecurityRequirement(name = "bearer-key")
class TenantController(
    private val foundationQueryService: FoundationQueryService,
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
) {
    /**
     * Retrieves metadata for the currently active tenant organisation.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('tenant.view')")
    @Operation(
        summary = "Get current tenant",
        description = "Retrieves metadata for the currently active tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Current tenant",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
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
            description = "Tenant not found",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun getCurrentTenant(): TenantDetailResponse {
        val caller = CallerContextResolver.getTenantCaller()
        val detail = foundationQueryService.getTenant(caller.activeOrganisationId, caller)
        val bootstrapRecord = adminBootstrapStore.find(caller.activeOrganisationId)
        return detail.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    private fun TenantDetail.toResponse(
        bootstrapStatus: String?,
        bootstrapFailureCode: String?,
    ) = TenantDetailResponse(
        id = id,
        tenantCode = tenantCode,
        displayName = displayName,
        countryCode = countryCode,
        baseCurrencyCode = baseCurrencyCode,
        timezone = timezone,
        status = status,
        bootstrapStatus = bootstrapStatus,
        bootstrapFailureCode = bootstrapFailureCode,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
