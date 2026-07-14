package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import java.util.UUID

/** Local intake request for an organisation-scoped user invitation. */
data class InviteUserCommand(
    val organisationId: UUID,
    val email: String,
    val username: String,
    val displayName: String,
    val phoneE164: String? = null,
    val membershipType: MembershipType,
    val primaryBranchId: UUID? = null,
    val branchAssignments: List<BranchAssignmentRequest>,
    val roleAssignments: List<RoleAssignmentRequest>,
    val invitedBy: UUID,
    val sendKeycloakInvite: Boolean,
    val sendApplicationInvite: Boolean,
    val requestId: String? = null,
)

/** Requested user-to-branch assignment created during local invitation intake. */
data class BranchAssignmentRequest(
    val branchId: UUID,
    val assignmentType: BranchAssignmentType,
)

/** Scope of a role assignment created during local invitation intake. */
enum class RoleAssignmentScopeType {
    TENANT,
    BRANCH,
}

/** Requested user-to-role assignment created during local invitation intake. */
data class RoleAssignmentRequest(
    val roleId: UUID,
    val scopeType: RoleAssignmentScopeType,
    val branchId: UUID? = null,
)

/** Approval command for a pending organisation membership invitation. */
data class ApproveUserCommand(
    val organisationId: UUID,
    val membershipId: UUID,
    val approvedBy: UUID,
    val requestId: String? = null,
)

/** Suspends a global user account from an organisation workflow context. */
data class SuspendUserCommand(
    val organisationId: UUID,
    val userId: UUID,
    val actorId: UUID,
    val reason: String? = null,
    val requestId: String? = null,
)

/** Reactivates a suspended global user account from an organisation workflow context. */
data class ReactivateUserCommand(
    val organisationId: UUID,
    val userId: UUID,
    val actorId: UUID,
    val reason: String? = null,
    val requestId: String? = null,
)

/** Deactivates a global user account through the two-step lifecycle path. */
data class DeactivateUserCommand(
    val organisationId: UUID,
    val userId: UUID,
    val actorId: UUID,
    val reason: String? = null,
    val requestId: String? = null,
)

/** Revokes a user's tenant membership and local organisation access grants. */
data class RevokeTenantMembershipCommand(
    val organisationId: UUID,
    val membershipId: UUID,
    val actorId: UUID,
    val reason: String? = null,
    val requestId: String? = null,
)

/** Result returned after local invitation intake has been persisted. */
data class UserInvitationResult(
    val userId: UUID,
    val membershipId: UUID,
    val userStatus: UserLifecycleState,
    val membershipStatus: MembershipLifecycleState,
)

/** Result returned after an invitation approval workflow step completes. */
data class UserApprovalResult(
    val userId: UUID,
    val membershipId: UUID,
    val userStatus: UserLifecycleState,
    val membershipStatus: MembershipLifecycleState,
    val keycloakProvisioningRequested: Boolean,
    val applicationInviteRequested: Boolean,
)
