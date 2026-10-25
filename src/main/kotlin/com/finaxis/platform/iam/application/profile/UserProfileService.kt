package com.finaxis.platform.iam.application.profile

import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.port.outbound.PermissionScope
import com.finaxis.platform.iam.application.port.outbound.ProfileBranch
import com.finaxis.platform.iam.application.port.outbound.ProfileMembership
import com.finaxis.platform.iam.application.port.outbound.ProfileOrganisation
import com.finaxis.platform.iam.application.port.outbound.ProfileRole
import com.finaxis.platform.iam.application.port.outbound.UserProfileLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.PermissionEffect
import com.finaxis.platform.iam.domain.RoleStatus
import org.springframework.stereotype.Service
import java.util.UUID

private const val ACTIVE_BRANCH = "ACTIVE"

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
    val permissionScopes: List<PermissionScope>,
)

/**
 * Application service that assembles the authenticated user's profile from the principal and
 * tenant-scoped read models.
 */
@Service
class UserProfileService(
    private val lookup: UserProfileLookup,
    private val permissionQueries: PermissionResolutionQueries,
) {
    /**
     * Returns profile details for the active organisation and optional selected branch.
     *
     * Branches and roles are each listed once and only while ACTIVE: a suspended or closed branch
     * and a disabled role are not part of the caller's current access, the same rule the runtime
     * applies when it resolves permissions. The permission list is the principal's own and is not
     * recomputed here; [UserProfile.permissionScopes] is read fresh from the grants of every
     * ACTIVE branch.
     */
    fun profile(principal: AppPrincipal): UserProfile {
        val organisation =
            lookup.organisation(principal.organisationId)
                ?: denied("Selected organisation no longer exists")
        val membership =
            lookup.membership(principal.membershipId)
                ?: denied("Selected membership no longer exists")
        val branches =
            lookup
                .assignedBranches(principal.membershipId)
                .filter { it.status == ACTIVE_BRANCH }
                .distinctBy { it.id }
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
            roles =
                lookup
                    .assignedRoles(principal.membershipId)
                    .filter { it.status == RoleStatus.ACTIVE }
                    .distinctBy { it.id },
            permissions = principal.permissions.sorted(),
            permissionScopes = permissionScopes(membership),
        )
    }

    /**
     * Where each permission is held, from the grants of every ACTIVE branch rather than the
     * selected one, read fresh (never from the effective-permission cache) and sorted by code.
     *
     * The same composition as `EffectivePermissionResolver`: a direct `DENY` removes the code
     * outright, a direct `ALLOW` is tenant-wide, and a code held tenant-wide and on branches is
     * reported once, as [PermissionScope.Scope.TENANT]. A membership that is not ACTIVE holds
     * nothing.
     */
    private fun permissionScopes(membership: ProfileMembership): List<PermissionScope> {
        if (membership.status != MembershipStatus.ACTIVE) return emptyList()
        val effects = permissionQueries.directPermissionEffects(membership.id)
        val denied = effects.codesWith(PermissionEffect.DENY)
        val grants = permissionQueries.grantsByScope(membership.id)
        val tenantWide =
            grants
                .filter { it.scope == PermissionScope.Scope.TENANT }
                .map { it.code }
                .toSet() + effects.codesWith(PermissionEffect.ALLOW)
        val onBranches =
            grants.filter { it.scope == PermissionScope.Scope.BRANCH && it.code !in tenantWide }
        return (tenantWide.map { PermissionScope(it, PermissionScope.Scope.TENANT) } + onBranches)
            .filter { it.code !in denied }
            .sortedBy { it.code }
    }

    private fun List<PermissionEffectAssignment>.codesWith(effect: PermissionEffect): Set<String> =
        filter { it.effect == effect }.map { it.code }.toSet()

    private fun selectedBranch(
        branchId: UUID,
        branches: List<ProfileBranch>,
    ): ProfileBranch =
        branches.firstOrNull { it.id == branchId }
            ?: denied("Selected branch is not assigned to the active membership")

    private fun denied(message: String): Nothing = throw AccessDeniedException(message)
}
