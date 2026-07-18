package com.finaxis.platform.iam.application.selection

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Result returned after selecting an active organisation.
 */
data class SelectOrganisationResult(
    val organisationId: UUID,
    val membershipId: UUID,
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
    val context: ActiveOrganisationContext,
)

/**
 * Raised when the authenticated user cannot select the requested organisation or branch.
 */
class OrganisationSelectionDeniedException(
    @Suppress("UNUSED_PARAMETER") message: String,
) : ForbiddenOperationException()

/**
 * Coordinates organisation and branch selection for the authenticated user.
 */
@Service
class AuthSelectionService(
    private val lookup: MembershipSelectionLookup,
) {
    /**
     * Selects an active organisation and returns the resulting tenant context.
     */
    fun selectOrganisation(
        keycloakSubject: String,
        organisationId: UUID,
    ): SelectOrganisationResult {
        val userId =
            lookup.findUserIdByKeycloakSubject(keycloakSubject)
                ?: denied("Authenticated user is not registered")
        val membership =
            lookup.findMembership(userId, organisationId)
                ?: denied("User is not an active member of the organisation")

        if (membership.status != MembershipStatus.ACTIVE) {
            denied("User is not an active member of the organisation")
        }
        if (lookup.organisationStatus(organisationId) != OrganisationStatus.ACTIVE) {
            denied("User is not an active member of the organisation")
        }

        val assignedBranchIds = lookup.findAssignedBranchIds(membership.membershipId)
        val branchId = assignedBranchIds.singleOrNull()
        val context =
            ActiveOrganisationContext(userId, organisationId, membership.membershipId, branchId)

        return SelectOrganisationResult(
            organisationId = organisationId,
            membershipId = membership.membershipId,
            context = context,
            branchId = branchId,
            requiresBranchSelection = assignedBranchIds.size > 1,
            assignedBranchIds = assignedBranchIds,
        )
    }

    /**
     * Selects an assigned branch inside the active organisation context.
     */
    fun selectBranch(
        keycloakSubject: String,
        branchId: UUID,
        currentContext: ActiveOrganisationContext?,
    ): SelectBranchResult {
        val existingContext =
            currentContext
                ?: denied("Select an active organisation before selecting a branch")
        val userId =
            lookup.findUserIdByKeycloakSubject(keycloakSubject)
                ?: denied("Authenticated user is not registered")
        if (existingContext.userId != userId) {
            denied(
                "Active organisation context does not belong to the authenticated user",
            )
        }

        val membership =
            lookup.findMembership(userId, existingContext.organisationId)
                ?: denied("User is not an active member of the organisation")
        if (membership.status != MembershipStatus.ACTIVE ||
            membership.membershipId != existingContext.membershipId
        ) {
            denied("User is not an active member of the organisation")
        }
        if (lookup.organisationStatus(existingContext.organisationId) !=
            OrganisationStatus.ACTIVE
        ) {
            denied("User is not an active member of the organisation")
        }
        if (!lookup.hasAssignedBranch(existingContext.membershipId, branchId)) {
            denied("User is not assigned to the selected branch")
        }

        val selectedContext = existingContext.copy(branchId = branchId)
        return SelectBranchResult(
            organisationId = selectedContext.organisationId,
            membershipId = selectedContext.membershipId,
            branchId = branchId,
            context = selectedContext,
        )
    }

    private fun denied(message: String): Nothing =
        throw OrganisationSelectionDeniedException(message)
}
