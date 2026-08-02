package com.finaxis.platform.iam.application.selection

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.port.outbound.BranchSelectionPage
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.OrganisationSelectionPage
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.allowsLogin
import org.springframework.stereotype.Service
import java.util.UUID

private const val PERM_SELECT_ORG = "auth.select_organisation"
private const val PERM_SELECT_BRANCH = "auth.select_branch"

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
    private val authorizationService: AuthorizationService,
) {
    /** Lists active branches available for the authenticated user's selected organisation. */
    fun availableBranches(
        keycloakSubject: String,
        currentContext: ActiveOrganisationContext?,
        page: Int,
        size: Int,
    ): BranchSelectionPage {
        require(page >= 0) { "Page must not be negative" }
        require(size in MINIMUM_PAGE_SIZE..MAXIMUM_PAGE_SIZE) {
            "Page size must be between $MINIMUM_PAGE_SIZE and $MAXIMUM_PAGE_SIZE"
        }
        val existingContext = currentContext ?: denied("Select an organisation first")
        val userId = eligibleUserId(keycloakSubject)
        if (existingContext.userId != userId) {
            denied("Active organisation context does not belong to the authenticated user")
        }
        val membership =
            lookup.findMembership(userId, existingContext.organisationId)
                ?: denied("User is not an active member of the organisation")
        if (membership.status != MembershipStatus.ACTIVE ||
            membership.membershipId != existingContext.membershipId ||
            lookup.organisationStatus(existingContext.organisationId) != OrganisationStatus.ACTIVE
        ) {
            denied("User is not an active member of the organisation")
        }
        if (!authorizationService.hasPermission(
                userId,
                existingContext.organisationId,
                PERM_SELECT_BRANCH,
            )
        ) {
            denied("Missing permission: $PERM_SELECT_BRANCH")
        }
        return lookup.findBranchSelections(membership.membershipId, page, size)
    }

    /** Lists organisations the authenticated user can select before an active context exists. */
    fun availableOrganisations(
        keycloakSubject: String,
        page: Int,
        size: Int,
    ): OrganisationSelectionPage {
        require(page >= 0) { "Page must not be negative" }
        require(size in MINIMUM_PAGE_SIZE..MAXIMUM_PAGE_SIZE) {
            "Page size must be between $MINIMUM_PAGE_SIZE and $MAXIMUM_PAGE_SIZE"
        }
        val userId = eligibleUserId(keycloakSubject)
        val result = lookup.findOrganisationSelections(userId, page, size)
        return result.copy(
            items =
                result.items.filter { selection ->
                    selection.membershipStatus == MembershipStatus.ACTIVE &&
                        selection.organisationStatus == OrganisationStatus.ACTIVE &&
                        authorizationService.hasPermission(
                            userId,
                            selection.organisationId,
                            PERM_SELECT_ORG,
                        )
                },
        )
    }

    /**
     * Selects an active organisation and returns the resulting tenant context.
     *
     * Requires the actor to hold [PERM_SELECT_ORG] in the target organisation.
     */
    fun selectOrganisation(
        keycloakSubject: String,
        organisationId: UUID,
    ): SelectOrganisationResult {
        val userId = eligibleUserId(keycloakSubject)
        val membership =
            lookup.findMembership(userId, organisationId)
                ?: denied("User is not an active member of the organisation")

        if (membership.status != MembershipStatus.ACTIVE) {
            denied("User is not an active member of the organisation")
        }
        if (lookup.organisationStatus(organisationId) != OrganisationStatus.ACTIVE) {
            denied("User is not an active member of the organisation")
        }

        if (!authorizationService.hasPermission(userId, organisationId, PERM_SELECT_ORG)) {
            denied("Missing permission: $PERM_SELECT_ORG")
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
     *
     * Requires the actor to hold [PERM_SELECT_BRANCH] in the target organisation.
     */
    fun selectBranch(
        keycloakSubject: String,
        branchId: UUID,
        currentContext: ActiveOrganisationContext?,
    ): SelectBranchResult {
        val existingContext =
            currentContext
                ?: denied("Select an active organisation before selecting a branch")
        val userId = eligibleUserId(keycloakSubject)
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

        if (!authorizationService.hasPermission(
                userId = userId,
                organisationId = existingContext.organisationId,
                permissionCode = PERM_SELECT_BRANCH,
            )
        ) {
            denied("Missing permission: $PERM_SELECT_BRANCH")
        }

        val selectedContext = existingContext.copy(branchId = branchId)
        return SelectBranchResult(
            organisationId = selectedContext.organisationId,
            membershipId = selectedContext.membershipId,
            branchId = branchId,
            context = selectedContext,
        )
    }

    /** Revalidates durable organisation-selection state before a replay restores it. */
    fun revalidateOrganisationReplay(
        keycloakSubject: String,
        context: ActiveOrganisationContext,
        expectedAssignedBranchIds: List<UUID>,
    ) {
        val membership = activeReplayMembership(keycloakSubject, context)
        if (!authorizationService.hasPermission(
                userId = membership.userId,
                organisationId = context.organisationId,
                permissionCode = PERM_SELECT_ORG,
            )
        ) {
            denied("Missing permission: $PERM_SELECT_ORG")
        }
        if (lookup.findAssignedBranchIds(membership.membershipId).toSet() !=
            expectedAssignedBranchIds.toSet()
        ) {
            denied("Organisation branch assignments changed after the original request")
        }
        context.branchId?.let { branchId ->
            if (!lookup.hasAssignedBranch(membership.membershipId, branchId)) {
                denied("Selected branch is no longer assigned")
            }
        }
    }

    /** Revalidates durable branch-selection state before a replay restores it. */
    fun revalidateBranchReplay(
        keycloakSubject: String,
        context: ActiveOrganisationContext,
    ) {
        val membership = activeReplayMembership(keycloakSubject, context)
        if (!authorizationService.hasPermission(
                userId = membership.userId,
                organisationId = context.organisationId,
                permissionCode = PERM_SELECT_BRANCH,
            )
        ) {
            denied("Missing permission: $PERM_SELECT_BRANCH")
        }
        val branchId = context.branchId ?: denied("Durable branch selection is incomplete")
        if (!lookup.hasAssignedBranch(membership.membershipId, branchId)) {
            denied("Selected branch is no longer assigned")
        }
    }

    private fun activeReplayMembership(
        keycloakSubject: String,
        context: ActiveOrganisationContext,
    ): MembershipSelection {
        val userId = eligibleUserId(keycloakSubject)
        if (userId != context.userId) {
            denied("Durable context does not belong to the authenticated user")
        }
        val membership =
            lookup.findMembership(userId, context.organisationId)
                ?: denied("User is not an active member of the organisation")
        if (membership.membershipId != context.membershipId ||
            membership.status != MembershipStatus.ACTIVE ||
            lookup.organisationStatus(context.organisationId) != OrganisationStatus.ACTIVE
        ) {
            denied("User is not an active member of the organisation")
        }
        return membership
    }

    private fun eligibleUserId(keycloakSubject: String): UUID {
        val userId =
            lookup.findUserIdByKeycloakSubject(keycloakSubject)
                ?: denied("Authenticated user is not registered")
        if (lookup.userStatus(userId)?.allowsLogin() != true) {
            denied("Authenticated user is not eligible to access the application")
        }
        return userId
    }

    private fun denied(message: String): Nothing =
        throw OrganisationSelectionDeniedException(message)

    private companion object {
        const val MINIMUM_PAGE_SIZE = 1
        const val MAXIMUM_PAGE_SIZE = 100
    }
}
