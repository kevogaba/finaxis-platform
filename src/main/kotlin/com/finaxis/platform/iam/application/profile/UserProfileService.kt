package com.finaxis.platform.iam.application.profile

import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.port.outbound.ProfileBranch
import com.finaxis.platform.iam.application.port.outbound.ProfileMembership
import com.finaxis.platform.iam.application.port.outbound.ProfileOrganisation
import com.finaxis.platform.iam.application.port.outbound.ProfileRole
import com.finaxis.platform.iam.application.port.outbound.UserProfileLookup
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Authenticated user profile including the selected tenant context and effective access.
 */
data class UserProfile(
    val userId: UUID,
    val keycloakSubject: String,
    val email: String?,
    val fullName: String?,
    val organisation: ProfileOrganisation,
    val membership: ProfileMembership,
    val selectedBranch: ProfileBranch?,
    val branches: List<ProfileBranch>,
    val roles: List<ProfileRole>,
    val permissions: List<String>,
)

/**
 * Application service that assembles the authenticated user's profile from the principal and
 * tenant-scoped read models.
 */
@Service
class UserProfileService(
    private val lookup: UserProfileLookup,
) {
    /**
     * Returns profile details for the active organisation and optional selected branch.
     */
    fun profile(principal: AppPrincipal): UserProfile {
        val organisation =
            lookup.organisation(principal.organisationId)
                ?: denied("Selected organisation no longer exists")
        val membership =
            lookup.membership(principal.membershipId)
                ?: denied("Selected membership no longer exists")
        val branches = lookup.assignedBranches(principal.membershipId)
        val selectedBranch = principal.branchId?.let { selectedBranch(it, branches) }

        return UserProfile(
            userId = principal.userId,
            keycloakSubject = principal.keycloakSubject,
            email = principal.email,
            fullName = principal.fullName,
            organisation = organisation,
            membership = membership,
            selectedBranch = selectedBranch,
            branches = branches,
            roles = lookup.assignedRoles(principal.membershipId),
            permissions = principal.permissions.sorted(),
        )
    }

    private fun selectedBranch(
        branchId: UUID,
        branches: List<ProfileBranch>,
    ): ProfileBranch =
        branches.firstOrNull { it.id == branchId }
            ?: denied("Selected branch is not assigned to the active membership")

    private fun denied(message: String): Nothing = throw AccessDeniedException(message)
}
