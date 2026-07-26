package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AmendTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.DeprovisionTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReactivateTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.RejectTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SuspendTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantDraftResultResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantSummaryResponse
import com.finaxis.platform.lifecycle.application.AmendOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.DeprovisionOrganisationCommand
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ReactivateOrganisationCommand
import com.finaxis.platform.lifecycle.application.RejectOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.RetryInitialAdministratorBootstrapCommand
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.application.SuspendOrganisationCommand
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.application.query.TenantDetail
import com.finaxis.platform.lifecycle.application.query.TenantFilter
import com.finaxis.platform.lifecycle.application.query.TenantSummary
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
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * REST controller for platform-level tenant organisation administration.
 */
@RestController
@RequestMapping(ApiPaths.PLATFORM_TENANTS)
@Tag(
    name = "Platform Tenant Administration",
    description = "Platform lifecycle endpoints for tenant organisation onboarding and governance",
)
@SecurityRequirement(name = "bearer-key")
@Validated
// Required endpoint-level OpenAPI response documentation is intentionally colocated.
@Suppress("LargeClass")
class PlatformTenantController(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val foundationQueryService: FoundationQueryService,
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
    private val permissionGuard: PermissionGuard,
) {
    /**
     * Drafts a new tenant organisation with initial admin details.
     */
    @PostMapping
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('tenant.create')")
    @Operation(
        summary = "Create tenant draft",
        description = "Drafts a new tenant organisation with initial admin details.",
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
            description = "Tenant draft created",
            content = [Content(schema = Schema(implementation = TenantDraftResultResponse::class))],
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
            responseCode = "409",
            description = "Tenant code already exists",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun createDraft(
        @RequestBody @Valid request: CreateTenantDraftRequest,
    ): ResponseEntity<TenantDraftResultResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "tenant.create")

        val command =
            CreateOrganisationDraftCommand(
                tenantCode = request.tenantCode,
                displayName = request.displayName,
                legalName = request.legalName,
                registrationNumber = request.registrationNumber,
                countryCode = request.countryCode,
                baseCurrencyCode = request.baseCurrencyCode,
                timezone = request.timezone,
                initialSettings = request.initialSettings,
                businessDate = request.businessDate,
                requestedBy = caller.actorId,
                admin = request.admin.toDomain(),
            )
        val result = organisationProvisioningService.createDraft(command)
        val location =
            URI.create(
                "${ApiPaths.PLATFORM_TENANTS}/${result.organisationId}",
            )
        return ResponseEntity
            .created(location)
            .body(TenantDraftResultResponse(result.organisationId, result.status.name))
    }

    /**
     * Searches tenant organisations using pagination and filters.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('tenant.view')")
    @Operation(
        summary = "Search tenants",
        description = "Searches tenant organisations using pagination and filters.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Tenant page",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
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
    fun searchTenants(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) country: String?,
        @RequestParam(required = false, name = "created_from") createdFrom: Instant?,
        @RequestParam(required = false, name = "created_to") createdTo: Instant?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
        @RequestParam(required = false, name = "sort_by") sortBy: String?,
        @RequestParam(required = false, name = "sort_dir") sortDir: String?,
    ): ApiPage<TenantSummaryResponse> {
        val caller = CallerContextResolver.getPlatformCaller()
        val filter =
            TenantFilter(
                q = q,
                status = status,
                country = country,
                createdFrom = createdFrom,
                createdTo = createdTo,
                page = page,
                size = size,
                sortBy = sortBy,
                sortDir = sortDir,
            )
        val pageResult = foundationQueryService.searchTenants(filter, caller)
        return ApiPage(
            items = pageResult.items.map { it.toResponse() },
            page = pageResult.page,
        )
    }

    /**
     * Retrieves detailed metadata for a tenant organisation.
     */
    @GetMapping("/{tenant_id}")
    @PreAuthorize("hasAuthority('tenant.view')")
    @Operation(
        summary = "Get tenant details",
        description = "Retrieves detailed metadata for a tenant organisation.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Tenant details",
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
    fun getTenant(
        @PathVariable("tenant_id") tenantId: UUID,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        val detail = foundationQueryService.getTenant(tenantId, caller)
        val bootstrapRecord = adminBootstrapStore.find(tenantId)
        return detail.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    /**
     * Amends an unsubmitted tenant organisation draft.
     */
    @PatchMapping("/{tenant_id}")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('tenant.update_draft')")
    @Operation(
        summary = "Amend tenant draft",
        description = "Amends an unsubmitted tenant organisation draft.",
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
            description = "Tenant draft amended",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
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
            description = "Tenant is no longer amendable",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun amendDraft(
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestBody @Valid request: AmendTenantDraftRequest,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "tenant.update_draft")

        val command =
            AmendOrganisationDraftCommand(
                organisationId = tenantId,
                tenantCode = request.tenantCode,
                displayName = request.displayName,
                legalName = request.legalName,
                registrationNumber = request.registrationNumber,
                countryCode = request.countryCode,
                baseCurrencyCode = request.baseCurrencyCode,
                timezone = request.timezone,
                initialSettings = request.initialSettings,
                businessDate = request.businessDate,
                actorId = caller.actorId,
                requestId = UUID.randomUUID(),
                admin = request.admin.toDomain(),
            )
        organisationProvisioningService.amendDraft(command)
        val updated = foundationQueryService.getTenant(tenantId, caller)
        val bootstrapRecord = adminBootstrapStore.find(tenantId)
        return updated.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    /**
     * Submits a tenant draft to the approval workflow.
     */
    @PostMapping("/{tenant_id}/submit")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('tenant.submit_for_approval')")
    @Operation(
        summary = "Submit tenant draft",
        description = "Submits a tenant draft to the approval workflow.",
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
            description = "Tenant submitted",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
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
            description = "Tenant state conflicts with submission",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun submit(
        @PathVariable("tenant_id") tenantId: UUID,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "tenant.submit_for_approval")

        val command =
            SubmitOrganisationForApprovalCommand(
                organisationId = tenantId,
                actorId = caller.actorId,
                requestId = UUID.randomUUID(),
            )
        organisationProvisioningService.submitForApproval(command)
        val updated = foundationQueryService.getTenant(tenantId, caller)
        val bootstrapRecord = adminBootstrapStore.find(tenantId)
        return updated.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    /**
     * Approves a submitted tenant and queues initial administrator setup.
     */
    @PostMapping("/{tenant_id}/approve")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('tenant.approve')")
    @Operation(
        summary = "Approve tenant",
        description = "Approves a submitted tenant and queues initial administrator setup.",
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
            responseCode = "202",
            description = "Tenant approved and bootstrap queued",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
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
            description = "Forbidden or maker-checker violation",
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
            description = "Tenant state conflicts with approval",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun approve(
        @PathVariable("tenant_id") tenantId: UUID,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "tenant.approve")

        val command =
            ApproveOrganisationProvisioningCommand(
                organisationId = tenantId,
                actorId = caller.actorId,
                requestId = UUID.randomUUID(),
            )
        organisationProvisioningService.approveProvisioning(command)
        val updated = foundationQueryService.getTenant(tenantId, caller)
        val bootstrapRecord = adminBootstrapStore.find(tenantId)
        return updated.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    /**
     * Rejects a submitted tenant approval request.
     */
    @PostMapping("/{tenant_id}/reject")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('tenant.reject')")
    @Operation(
        summary = "Reject tenant",
        description = "Rejects a submitted tenant approval request.",
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
            description = "Tenant rejected",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
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
            description = "Tenant state conflicts with rejection",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun reject(
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestBody @Valid request: RejectTenantRequest,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "tenant.reject")

        val command =
            RejectOrganisationProvisioningCommand(
                organisationId = tenantId,
                reason = request.reason,
                actorId = caller.actorId,
                requestId = UUID.randomUUID(),
            )
        organisationProvisioningService.rejectProvisioning(command)
        val updated = foundationQueryService.getTenant(tenantId, caller)
        val bootstrapRecord = adminBootstrapStore.find(tenantId)
        return updated.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    /**
     * Suspends an active tenant organisation.
     */
    @PostMapping("/{tenant_id}/suspend")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('tenant.suspend')")
    @Operation(
        summary = "Suspend tenant",
        description = "Suspends an active tenant organisation.",
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
            description = "Tenant suspended",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
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
            description = "Tenant state conflicts with suspension",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun suspend(
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestBody @Valid request: SuspendTenantRequest,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "tenant.suspend")

        val command =
            SuspendOrganisationCommand(
                organisationId = tenantId,
                reason = request.reason,
            )
        organisationProvisioningService.suspend(command)
        val updated = foundationQueryService.getTenant(tenantId, caller)
        val bootstrapRecord = adminBootstrapStore.find(tenantId)
        return updated.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    /**
     * Reactivates a suspended tenant organisation.
     */
    @PostMapping("/{tenant_id}/reactivate")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('tenant.reactivate')")
    @Operation(
        summary = "Reactivate tenant",
        description = "Reactivates a suspended tenant organisation.",
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
            description = "Tenant reactivated",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
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
            description = "Tenant state conflicts with reactivation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun reactivate(
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestBody(required = false) request: ReactivateTenantRequest?,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "tenant.reactivate")

        val command =
            ReactivateOrganisationCommand(
                organisationId = tenantId,
                reason = request?.reason,
            )
        organisationProvisioningService.reactivate(command)
        val updated = foundationQueryService.getTenant(tenantId, caller)
        val bootstrapRecord = adminBootstrapStore.find(tenantId)
        return updated.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    /**
     * Performs metadata-only deprovisioning of a tenant organisation.
     */
    @PostMapping("/{tenant_id}/deprovision")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('tenant.deprovision')")
    @Operation(
        summary = "Deprovision tenant",
        description = "Performs metadata-only deprovisioning of a tenant organisation.",
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
            description = "Tenant deprovisioned",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
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
            description = "Tenant state conflicts with deprovisioning",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun deprovision(
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestBody @Valid request: DeprovisionTenantRequest,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "tenant.deprovision")

        val command =
            DeprovisionOrganisationCommand(
                organisationId = tenantId,
                reason = request.reason,
            )
        organisationProvisioningService.deprovision(command)
        val updated = foundationQueryService.getTenant(tenantId, caller)
        val bootstrapRecord = adminBootstrapStore.find(tenantId)
        return updated.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    /**
     * Retries a failed initial administrator bootstrap process.
     */
    @PostMapping("/{tenant_id}/bootstrap/retry")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('tenant.bootstrap_retry')")
    @Operation(
        summary = "Retry administrator bootstrap",
        description = "Retries a failed initial administrator bootstrap process.",
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
            responseCode = "202",
            description = "Bootstrap retry queued",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
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
            description = "Bootstrap cannot be retried in the current state",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun retryBootstrap(
        @PathVariable("tenant_id") tenantId: UUID,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        permissionGuard.requirePlatformPermission(caller.actorId, "tenant.bootstrap_retry")

        val command =
            RetryInitialAdministratorBootstrapCommand(
                organisationId = tenantId,
                caller = caller,
            )
        organisationProvisioningService.retryBootstrap(command)
        val updated = foundationQueryService.getTenant(tenantId, caller)
        val bootstrapRecord = adminBootstrapStore.find(tenantId)
        return updated.toResponse(
            bootstrapStatus = bootstrapRecord?.status?.name,
            bootstrapFailureCode = bootstrapRecord?.lastFailureCode,
        )
    }

    private fun TenantSummary.toResponse() =
        TenantSummaryResponse(
            id = id,
            tenantCode = tenantCode,
            displayName = displayName,
            countryCode = countryCode,
            status = status,
            createdAt = createdAt,
        )

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

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}
