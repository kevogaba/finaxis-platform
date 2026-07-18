package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiProblem
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.security.SessionActiveOrganisationContextResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.selection.AuthSelectionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpSession
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Request body for selecting the active organisation after Keycloak authentication.
 */
data class SelectOrganisationRequest(
    @field:NotNull
    val organisationId: UUID?,
)

/**
 * Response returned after selecting an active organisation context.
 */
data class SelectOrganisationResponse(
    val organisationId: UUID,
    val membershipId: UUID,
    val contextToken: String,
    val contextHeader: String,
    val branchId: UUID?,
    val requiresBranchSelection: Boolean,
    val assignedBranchIds: List<UUID>,
)

/**
 * Request body for selecting the active branch within the active organisation.
 */
data class SelectBranchRequest(
    @field:NotNull
    val branchId: UUID?,
)

/**
 * Response returned after selecting an active branch context.
 */
data class SelectBranchResponse(
    val organisationId: UUID,
    val membershipId: UUID,
    val branchId: UUID,
    val contextToken: String,
    val contextHeader: String,
)

/**
 * REST adapter for active organisation and branch selection.
 */
@RestController
@RequestMapping(ApiPaths.AUTH)
@Tag(name = "Authentication Context")
class AuthController(
    private val service: AuthSelectionService,
) {
    /**
     * Selects an active organisation and stores the resulting context in the browser session.
     */
    @Operation(
        summary = "Select active organisation",
        description =
            "Creates an active organisation context. Browser clients receive session context; " +
                "headless clients may use the returned X-Active-Organisation-Context token.",
        security = [SecurityRequirement(name = "bearer-key")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Organisation selected"),
        ApiResponse(
            responseCode = "403",
            description = "Authenticated user is not an active member of the organisation",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    @PostMapping("/select-organisation")
    @ResponseStatus(HttpStatus.OK)
    fun selectOrganisation(
        authentication: Authentication,
        @Valid @RequestBody request: SelectOrganisationRequest,
        session: HttpSession,
    ): SelectOrganisationResponse {
        val result =
            service.selectOrganisation(
                keycloakSubject(authentication),
                requireNotNull(request.organisationId),
            )
        storeContext(session, result.context)
        return SelectOrganisationResponse(
            organisationId = result.organisationId,
            membershipId = result.membershipId,
            contextToken = result.contextToken,
            contextHeader = "X-Active-Organisation-Context",
            branchId = result.branchId,
            requiresBranchSelection = result.requiresBranchSelection,
            assignedBranchIds = result.assignedBranchIds,
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
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Branch selected"),
        ApiResponse(
            responseCode = "403",
            description = "Authenticated user is not assigned to the selected branch",
            content = [Content(schema = Schema(implementation = ApiProblem::class))],
        ),
    )
    @PostMapping("/select-branch")
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
        storeContext(session, result.context)
        return SelectBranchResponse(
            organisationId = result.organisationId,
            membershipId = result.membershipId,
            branchId = result.branchId,
            contextToken = result.contextToken,
            contextHeader = "X-Active-Organisation-Context",
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

    private fun storeContext(
        session: HttpSession,
        context: ActiveOrganisationContext,
    ) {
        session.setAttribute(SessionActiveOrganisationContextResolver.ATTRIBUTE, context)
    }
}
