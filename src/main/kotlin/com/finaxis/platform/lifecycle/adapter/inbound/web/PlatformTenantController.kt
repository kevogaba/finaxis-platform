package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AmendTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ApproveTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.DeprovisionTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReactivateTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.RejectTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReturnTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SuspendTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantDraftResultResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantSummaryResponse
import com.finaxis.platform.lifecycle.application.AmendOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.DecisionRemark
import com.finaxis.platform.lifecycle.application.DeprovisionOrganisationCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ReactivateOrganisationCommand
import com.finaxis.platform.lifecycle.application.Reason
import com.finaxis.platform.lifecycle.application.RejectOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.RetryInitialAdministratorBootstrapCommand
import com.finaxis.platform.lifecycle.application.ReturnOrganisationForChangesCommand
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
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid page, sort or filter",
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
        @Parameter(
            description = "Field to sort by. Values are camelCase, unlike other wire names.",
            schema =
                Schema(
                    allowableValues = ["tenantCode", "displayName", "countryCode", "createdAt"],
                ),
        )
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
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
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
            description =
                "Tenant is no longer amendable, or the tenant code is held by another tenant" +
                    PLATFORM_PROTECTED,
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
                requestId = uuidV7(),
                admin = request.admin.toDomain(),
            )
        organisationProvisioningService.amendDraft(command)
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
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
            description =
                "Tenant state conflicts with submission" + PLATFORM_PROTECTED,
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

        val command =
            SubmitOrganisationForApprovalCommand(
                organisationId = tenantId,
                actorId = caller.actorId,
                requestId = uuidV7(),
            )
        organisationProvisioningService.submitForApproval(command)
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
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
        description =
            "Approves a submitted tenant and queues initial administrator setup. The optional " +
                "body `reason` (at most 500 characters) is a decision remark recorded on the " +
                "approval's transitions and audit rows.",
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
            description =
                "Forbidden, maker-checker violation, or the approver is the account named as the " +
                    "initial administrator (code `lifecycle.approver_is_initial_administrator`)",
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
            description =
                "Tenant state conflicts with approval" + PLATFORM_PROTECTED,
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
        @RequestBody(required = false) @Valid request: ApproveTenantRequest?,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()

        val command =
            ApproveOrganisationProvisioningCommand(
                organisationId = tenantId,
                reason = DecisionRemark.optional(request?.reason),
                actorId = caller.actorId,
                requestId = uuidV7(),
            )
        organisationProvisioningService.approveProvisioning(command)
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
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
            description =
                "Tenant state conflicts with rejection" + PLATFORM_PROTECTED,
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

        val command =
            RejectOrganisationProvisioningCommand(
                organisationId = tenantId,
                reason = Reason.required(request.reason),
                actorId = caller.actorId,
                requestId = uuidV7(),
            )
        organisationProvisioningService.rejectProvisioning(command)
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
    }

    /**
     * Returns a pending tenant approval request to draft for changes.
     */
    @PostMapping("/{tenant_id}/return")
    @IdempotentMutation(scope = IdempotencyScopeKind.PLATFORM)
    @PreAuthorize("hasAuthority('tenant.reject')")
    @Operation(
        summary = "Return tenant for changes",
        description =
            "Returns a pending tenant approval request to draft with a required reason, so " +
                "its maker can amend the draft and submit it again. Checker only: the requester " +
                "and the submitter cannot return their own tenant. A rejected tenant stays " +
                "rejected. The reason is the tenant's `status_reason` until its next transition.",
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
            description = "Tenant returned to draft",
            content = [Content(schema = Schema(implementation = TenantDetailResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description =
                "Missing body or reason (`invalid_json`), or a reason that is blank or not " +
                    "3 to 500 characters (`validation_failed`)",
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
            description =
                "Forbidden: `tenant.reject` is missing, or the caller requested or submitted " +
                    "the tenant",
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
            description =
                "Caller is the system actor (`invalid_operation`); effectively unreachable, " +
                    "as the permission check refuses a sentinel actor first",
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
                "Tenant is not pending approval" + PLATFORM_PROTECTED,
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    fun returnForChanges(
        @PathVariable("tenant_id") tenantId: UUID,
        @RequestBody @Valid request: ReturnTenantRequest,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()
        organisationProvisioningService.returnForChanges(
            ReturnOrganisationForChangesCommand(
                organisationId = tenantId,
                reason = Reason.required(request.reason),
                actorId = caller.actorId,
            ),
        )
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
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
            description =
                "Tenant state conflicts with suspension" + PLATFORM_PROTECTED,
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

        val command =
            SuspendOrganisationCommand(
                organisationId = tenantId,
                reason = Reason.required(request.reason),
                actorId = caller.actorId,
            )
        organisationProvisioningService.suspend(command)
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
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
            description =
                "Tenant state conflicts with reactivation" + PLATFORM_PROTECTED,
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
        @RequestBody(required = false) @Valid request: ReactivateTenantRequest?,
    ): TenantDetailResponse {
        val caller = CallerContextResolver.getPlatformCaller()

        val command =
            ReactivateOrganisationCommand(
                organisationId = tenantId,
                reason = DecisionRemark.optional(request?.reason),
                actorId = caller.actorId,
            )
        organisationProvisioningService.reactivate(command)
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
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
            description =
                "Tenant state conflicts with deprovisioning" + PLATFORM_PROTECTED,
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

        val command =
            DeprovisionOrganisationCommand(
                organisationId = tenantId,
                reason = Reason.required(request.reason),
                actorId = caller.actorId,
            )
        organisationProvisioningService.deprovision(command)
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
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
            description =
                "Bootstrap cannot be retried in the current state" + PLATFORM_PROTECTED,
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

        val command =
            RetryInitialAdministratorBootstrapCommand(
                organisationId = tenantId,
                caller = caller,
            )
        organisationProvisioningService.retryBootstrap(command)
        return foundationQueryService.getTenant(tenantId, caller).toResponse()
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

    private fun TenantDetail.toResponse() =
        TenantDetailResponse(
            id = id,
            tenantCode = tenantCode,
            displayName = displayName,
            countryCode = countryCode,
            baseCurrencyCode = baseCurrencyCode,
            timezone = timezone,
            status = status,
            statusReason = statusReason,
            bootstrapStatus = bootstrapStatus,
            bootstrapFailureCode = bootstrapFailureCode,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L

        // The 409 every tenant action answers for the reserved platform organisation (issue #205),
        // appended to each route's own 409 description.
        const val PLATFORM_PROTECTED =
            ", or the target is the platform organisation " +
                "(`lifecycle.platform_organisation_protected`)"
    }
}
