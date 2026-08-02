package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.idempotency.IdempotencyReplayHandler
import com.finaxis.platform.common.web.idempotency.IdempotencyReplayMode
import com.finaxis.platform.common.web.idempotency.IdempotencyReplayResponse
import com.finaxis.platform.common.web.idempotency.IdempotencyScopeKind
import com.finaxis.platform.common.web.idempotency.IdempotentMutation
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.security.SessionActiveOrganisationContextResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.selection.AuthSelectionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpSession
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Request body for selecting the active organisation after Keycloak authentication.
 */
data class SelectOrganisationRequest(
    @field:NotNull
    @field:Schema(name = "organisation_id")
    val organisationId: UUID?,
)

/** Organisation membership visible to an authenticated user before context selection. */
data class AvailableOrganisationResponse(
    @field:Schema(name = "organisation_id")
    val organisationId: UUID,
    @field:Schema(name = "membership_id")
    val membershipId: UUID,
    @field:Schema(name = "tenant_code")
    val tenantCode: String,
    @field:Schema(name = "display_name")
    val displayName: String,
    @field:Schema(name = "organisation_status")
    val organisationStatus: String,
    @field:Schema(name = "membership_status")
    val membershipStatus: String,
)

/** Branch assignment visible after an authenticated user selects an organisation. */
data class AvailableBranchResponse(
    @field:Schema(name = "branch_id")
    val branchId: UUID,
    @field:Schema(name = "branch_code")
    val branchCode: String,
    @field:Schema(name = "branch_name")
    val branchName: String,
    @field:Schema(name = "branch_status")
    val branchStatus: String,
)

/**
 * Response returned after selecting an active organisation context.
 */
data class SelectOrganisationResponse(
    @field:Schema(name = "organisation_id")
    val organisationId: UUID,
    @field:Schema(name = "membership_id")
    val membershipId: UUID,
    @field:Schema(name = "context_token")
    val contextToken: String,
    @field:Schema(name = "context_header")
    val contextHeader: String,
    @field:Schema(name = "branch_id")
    val branchId: UUID?,
    @field:Schema(name = "requires_branch_selection")
    val requiresBranchSelection: Boolean,
    @field:Schema(name = "assigned_branch_ids")
    val assignedBranchIds: List<UUID>,
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    @get:Schema(hidden = true)
    override val durableBody: SelectOrganisationReplayValue? = null,
) : IdempotencyReplayResponse<SelectOrganisationReplayValue>

/**
 * Request body for selecting the active branch within the active organisation.
 */
data class SelectBranchRequest(
    @field:NotNull
    @field:Schema(name = "branch_id")
    val branchId: UUID?,
)

/**
 * Response returned after selecting an active branch context.
 */
data class SelectBranchResponse(
    @field:Schema(name = "organisation_id")
    val organisationId: UUID,
    @field:Schema(name = "membership_id")
    val membershipId: UUID,
    @field:Schema(name = "branch_id")
    val branchId: UUID,
    @field:Schema(name = "context_token")
    val contextToken: String,
    @field:Schema(name = "context_header")
    val contextHeader: String,
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    @get:Schema(hidden = true)
    override val durableBody: SelectBranchReplayValue? = null,
) : IdempotencyReplayResponse<SelectBranchReplayValue>

/** Safe durable organisation-selection replay state. */
data class SelectOrganisationReplayValue(
    val context: ActiveOrganisationContext,
    val requiresBranchSelection: Boolean,
    val assignedBranchIds: List<UUID>,
)

/** Safe durable branch-selection replay state. */
data class SelectBranchReplayValue(
    val context: ActiveOrganisationContext,
)

/** Restores browser context and issues a fresh signed token from safe replay state. */
@Component
class AuthSelectionReplayHandler(
    private val apiJsonCodec: ApiJsonCodec,
    private val contextService:
        com.finaxis.platform.iam.application.context.ActiveOrganisationContextService,
    private val selectionService: AuthSelectionService,
) : IdempotencyReplayHandler {
    override val mode: IdempotencyReplayMode = IdempotencyReplayMode.REISSUE_CONTEXT_TOKEN

    override fun restore(
        durableJson: String,
        request: jakarta.servlet.http.HttpServletRequest,
    ): IdempotencyReplayResponse<*> {
        val node = requireNotNull(apiJsonCodec.mapper.readTree(durableJson))
        return if (node.has("assigned_branch_ids")) {
            val value =
                apiJsonCodec.mapper.readValue(
                    durableJson,
                    SelectOrganisationReplayValue::class.java,
                )
            selectionService.revalidateOrganisationReplay(
                currentKeycloakSubject(),
                value.context,
                value.assignedBranchIds,
            )
            store(request, value.context)
            SelectOrganisationResponse(
                organisationId = value.context.organisationId,
                membershipId = value.context.membershipId,
                contextToken = contextService.issue(value.context),
                contextHeader =
                    com.finaxis.platform.iam.application.context
                        .ActiveOrganisationContextService.HEADER,
                branchId = value.context.branchId,
                requiresBranchSelection = value.requiresBranchSelection,
                assignedBranchIds = value.assignedBranchIds,
            )
        } else {
            val value =
                apiJsonCodec.mapper.readValue(durableJson, SelectBranchReplayValue::class.java)
            val branchId = requireNotNull(value.context.branchId)
            selectionService.revalidateBranchReplay(currentKeycloakSubject(), value.context)
            store(request, value.context)
            SelectBranchResponse(
                organisationId = value.context.organisationId,
                membershipId = value.context.membershipId,
                branchId = branchId,
                contextToken = contextService.issue(value.context),
                contextHeader =
                    com.finaxis.platform.iam.application.context
                        .ActiveOrganisationContextService.HEADER,
            )
        }
    }

    private fun currentKeycloakSubject(): String {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?: error("Authenticated principal is required for selection replay")
        return keycloakSubject(authentication)
    }

    private fun store(
        request: jakarta.servlet.http.HttpServletRequest,
        context: ActiveOrganisationContext,
    ) {
        request
            .getSession(true)
            .setAttribute(SessionActiveOrganisationContextResolver.ATTRIBUTE, context)
    }
}

/**
 * REST adapter for active organisation and branch selection.
 */
@RestController
@RequestMapping(ApiPaths.AUTH)
@Tag(name = "Authentication Context")
class AuthController(
    private val service: AuthSelectionService,
) {
    /** Lists active branches available after organisation selection. */
    @GetMapping("/branches")
    @Operation(
        summary = "List available branches",
        description =
            "Lists active branches assigned to the authenticated user's active organisation " +
                "membership. This endpoint requires the active organisation context and " +
                "auth.select_branch permission.",
        security = [SecurityRequirement(name = "bearer-key")],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Available branch page",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Active organisation context or branch-selection permission is missing",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun availableBranches(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
        session: HttpSession,
    ): ApiPage<AvailableBranchResponse> {
        val result =
            service.availableBranches(
                keycloakSubject(authentication),
                activeContext(authentication, session),
                page,
                size,
            )
        return apiPageOf(
            items =
                result.items.map { branch ->
                    AvailableBranchResponse(
                        branchId = branch.branchId,
                        branchCode = branch.branchCode,
                        branchName = branch.branchName,
                        branchStatus = branch.branchStatus,
                    )
                },
            number = page,
            size = size,
            totalItems = result.totalItems,
        )
    }

    /** Lists active organisations that the authenticated user can select. */
    @GetMapping("/organisations")
    @Operation(
        summary = "List available organisations",
        description =
            "Lists active organisations where the authenticated user has an active membership " +
                "and the auth.select_organisation permission. This endpoint does not require " +
                "an active organisation context.",
        security = [SecurityRequirement(name = "bearer-key")],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Available organisation page",
            content = [Content(schema = Schema(implementation = ApiPage::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    fun availableOrganisations(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "25") @Min(1) @Max(MAXIMUM_PAGE_SIZE) size: Int,
    ): ApiPage<AvailableOrganisationResponse> {
        val result = service.availableOrganisations(keycloakSubject(authentication), page, size)
        return apiPageOf(
            items =
                result.items.map { selection ->
                    AvailableOrganisationResponse(
                        organisationId = selection.organisationId,
                        membershipId = selection.membershipId,
                        tenantCode = selection.tenantCode,
                        displayName = selection.displayName,
                        organisationStatus = selection.organisationStatus.name,
                        membershipStatus = selection.membershipStatus.name,
                    )
                },
            number = page,
            size = size,
            totalItems = result.totalItems,
        )
    }

    /**
     * Selects an active organisation and stores the resulting context in the browser session.
     */
    @Operation(
        summary = "Select active organisation",
        description =
            "Creates an active organisation context. Browser clients receive session context; " +
                "headless clients may use the returned X-Active-Organisation-Context token.",
        security = [SecurityRequirement(name = "bearer-key")],
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
            description = "Organisation selected",
            content = [
                Content(
                    mediaType = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = SelectOrganisationResponse::class),
                ),
            ],
            headers = [
                Header(
                    name = "Idempotency-Key",
                    description = "Effective UUID used for this mutation.",
                    schema = Schema(type = "string", format = "uuid"),
                ),
                Header(
                    name = "Idempotency-Replayed",
                    description = "True when the durable response was replayed.",
                    schema = Schema(type = "boolean"),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Authenticated user is not an active member of the organisation",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    @PostMapping("/select-organisation")
    @IdempotentMutation(
        scope = IdempotencyScopeKind.ORGANISATION_SELECTION,
        replayMode = IdempotencyReplayMode.REISSUE_CONTEXT_TOKEN,
    )
    @ResponseStatus(HttpStatus.OK)
    fun selectOrganisation(
        authentication: Authentication,
        @Valid @RequestBody request: SelectOrganisationRequest,
    ): SelectOrganisationResponse {
        val result =
            service.selectOrganisation(
                keycloakSubject(authentication),
                requireNotNull(request.organisationId),
            )
        return SelectOrganisationResponse(
            organisationId = result.organisationId,
            membershipId = result.membershipId,
            contextToken = "",
            contextHeader =
                com.finaxis.platform.iam.application.context
                    .ActiveOrganisationContextService.HEADER,
            branchId = result.branchId,
            requiresBranchSelection = result.requiresBranchSelection,
            assignedBranchIds = result.assignedBranchIds,
            durableBody =
                SelectOrganisationReplayValue(
                    context = result.context,
                    requiresBranchSelection = result.requiresBranchSelection,
                    assignedBranchIds = result.assignedBranchIds,
                ),
        )
    }

    /**
     * Selects an assigned branch inside the active organisation context.
     */
    @Operation(
        summary = "Select active branch",
        description =
            "Stores a branch selection inside the active organisation context after organisation " +
                "selection.",
        security = [SecurityRequirement(name = "bearer-key")],
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
            description = "Branch selected",
            content = [
                Content(
                    mediaType = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = SelectBranchResponse::class),
                ),
            ],
            headers = [
                Header(
                    name = "Idempotency-Key",
                    description = "Effective UUID used for this mutation.",
                    schema = Schema(type = "string", format = "uuid"),
                ),
                Header(
                    name = "Idempotency-Replayed",
                    description = "True when the durable response was replayed.",
                    schema = Schema(type = "boolean"),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Authenticated user is not assigned to the selected branch",
            content = [
                Content(
                    mediaType = "application/problem+json",
                    schema = Schema(implementation = ApiProblem::class),
                ),
            ],
        ),
    )
    @PostMapping("/select-branch")
    @IdempotentMutation(
        scope = IdempotencyScopeKind.TENANT,
        replayMode = IdempotencyReplayMode.REISSUE_CONTEXT_TOKEN,
    )
    @ResponseStatus(HttpStatus.OK)
    fun selectBranch(
        authentication: Authentication,
        @Valid @RequestBody request: SelectBranchRequest,
        session: HttpSession,
    ): SelectBranchResponse {
        val result =
            service.selectBranch(
                keycloakSubject(authentication),
                requireNotNull(request.branchId),
                activeContext(authentication, session),
            )
        return SelectBranchResponse(
            organisationId = result.organisationId,
            membershipId = result.membershipId,
            branchId = result.branchId,
            contextToken = "",
            contextHeader =
                com.finaxis.platform.iam.application.context
                    .ActiveOrganisationContextService.HEADER,
            durableBody = SelectBranchReplayValue(result.context),
        )
    }

    private fun keycloakSubject(authentication: Authentication): String {
        val principal = authentication.principal
        return when (principal) {
            is Jwt -> requireNotNull(principal.subject) { "JWT subject is required" }
            is AppPrincipal -> principal.keycloakSubject
            else -> error("Unsupported authenticated principal")
        }
    }

    private fun activeContext(
        authentication: Authentication,
        session: HttpSession,
    ): ActiveOrganisationContext? =
        (authentication.principal as? AppPrincipal)?.let { principal ->
            ActiveOrganisationContext(
                userId = principal.userId,
                organisationId = principal.organisationId,
                membershipId = principal.membershipId,
                branchId = principal.branchId,
            )
        }
            ?: session.getAttribute(
                SessionActiveOrganisationContextResolver.ATTRIBUTE,
            ) as? ActiveOrganisationContext

    private companion object {
        const val MAXIMUM_PAGE_SIZE = 100L
    }
}

private fun keycloakSubject(authentication: Authentication): String {
    val principal = authentication.principal
    return when (principal) {
        is Jwt -> requireNotNull(principal.subject) { "JWT subject is required" }
        is AppPrincipal -> principal.keycloakSubject
        else -> error("Unsupported authenticated principal")
    }
}
