package com.finaxis.platform.iam.application.selection

import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Result returned after selecting an active organisation.
 */
data class SelectOrganisationResult(
    val organisationId: UUID,
    val membershipId: UUID,
    val contextToken: String,
    val context: ActiveOrganisationContext,
    val branchId: UUID?,
    val requiresBranchSelection: Boolean,
    val assignedBranchIds: List<UUID>,
)

/**
 * Result returned after selecting an active branch.
 */
data class SelectBranchResult(
    val organisationId: UUID,
    val membershipId: UUID,
    val branchId: UUID,
    val contextToken: String,
    val context: ActiveOrganisationContext,
)

/**
 * Raised when the authenticated user cannot select the requested organisation or branch.
 */
class OrganisationSelectionDeniedException(message: String) : RuntimeException(message)

/**
 * Coordinates organisation and branch selection for the authenticated user.
 */
@Service
class AuthSelectionService(
    private val lookup: MembershipSelectionLookup,
    private val contextService: ActiveOrganisationContextService,
) {
    fun selectOrganisation(
        keycloakSubject: String,
        organisationId: UUID,
    ): SelectOrganisationResult {
        val userId = lookup.findUserIdByKeycloakSubject(keycloakSubject)
            ?: throw OrganisationSelectionDeniedException("Authenticated user is not registered")
        val membership = lookup.findMembership(userId, organisationId)
            ?: throw OrganisationSelectionDeniedException("User is not an active member of the organisation")

        if (membership.status != MembershipStatus.ACTIVE) {
            throw OrganisationSelectionDeniedException("User is not an active member of the organisation")
        }

        val assignedBranchIds = lookup.findAssignedBranchIds(membership.membershipId)
        val branchId = assignedBranchIds.singleOrNull()
        val context = ActiveOrganisationContext(userId, organisationId, membership.membershipId, branchId)

        return SelectOrganisationResult(
            organisationId = organisationId,
            membershipId = membership.membershipId,
            contextToken = contextService.issue(context),
            context = context,
            branchId = branchId,
            requiresBranchSelection = assignedBranchIds.size > 1,
            assignedBranchIds = assignedBranchIds,
        )
    }

    fun selectBranch(
        keycloakSubject: String,
        branchId: UUID,
        currentContext: ActiveOrganisationContext?,
    ): SelectBranchResult {
        val existingContext = currentContext
            ?: throw OrganisationSelectionDeniedException("Select an active organisation before selecting a branch")
        val userId = lookup.findUserIdByKeycloakSubject(keycloakSubject)
            ?: throw OrganisationSelectionDeniedException("Authenticated user is not registered")
        if (existingContext.userId != userId) {
            throw OrganisationSelectionDeniedException("Active organisation context does not belong to the authenticated user")
        }

        val membership = lookup.findMembership(userId, existingContext.organisationId)
            ?: throw OrganisationSelectionDeniedException("User is not an active member of the organisation")
        if (membership.status != MembershipStatus.ACTIVE || membership.membershipId != existingContext.membershipId) {
            throw OrganisationSelectionDeniedException("User is not an active member of the organisation")
        }
        if (!lookup.hasAssignedBranch(existingContext.membershipId, branchId)) {
            throw OrganisationSelectionDeniedException("User is not assigned to the selected branch")
        }

        val selectedContext = existingContext.copy(branchId = branchId)
        return SelectBranchResult(
            organisationId = selectedContext.organisationId,
            membershipId = selectedContext.membershipId,
            branchId = branchId,
            contextToken = contextService.issue(selectedContext),
            context = selectedContext,
        )
    }
}
