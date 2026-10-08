package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import java.util.UUID

private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val BRANCH_EXEMPT_TYPES = setOf(MembershipType.SYSTEM, MembershipType.AUDITOR)
private const val ROLE_REFERENCE_DETAIL =
    "A role in the request was not found or is not active in the organisation."
private const val BRANCH_REFERENCE_DETAIL =
    "A branch in the request was not found or is not active in the organisation."

/**
 * Validates an invitation's shape and references before anything is written: the organisation must
 * be active, the identity fields well formed, and every branch and role it names usable.
 */
internal fun UserProvisioningStore.validateInvitation(command: InviteUserCommand) {
    if (organisationState(command.organisationId) != OrganisationLifecycleState.ACTIVE) {
        throw ConflictException(safeDetail = "User invitations require an active organisation.")
    }
    // The DTO's @Email is laxer than this (it accepts "user@localhost"), so a shape the
    // service refuses is still the caller's mistake: 400, never a 500.
    malformedUnless(
        command.email.isNotBlank() && EMAIL_REGEX.matches(command.email),
        "A valid email address is required.",
    )
    malformedUnless(command.username.isNotBlank(), "Username is required.")
    malformedUnless(command.displayName.isNotBlank(), "Display name is required.")
    command.primaryBranchId?.let { branchId ->
        requireActiveBranch(command.organisationId, branchId)
    }
    command.branchAssignments.forEach { assignment ->
        requireActiveBranch(command.organisationId, assignment.branchId)
    }
    command.roleAssignments.forEach { assignment ->
        // One message for unknown, foreign-organisation and inactive alike: the request body
        // referenced a role this tenant cannot grant, and nothing else is disclosed.
        if (!roleExists(command.organisationId, assignment.roleId)) {
            throw InvalidOperationException(safeDetail = ROLE_REFERENCE_DETAIL)
        }
        validateRoleScope(command.organisationId, assignment)
    }
    malformedUnless(
        command.membershipType in BRANCH_EXEMPT_TYPES ||
            command.branchAssignments.isNotEmpty(),
        "At least one branch assignment is required.",
    )
    malformedUnless(
        command.roleAssignments.isNotEmpty(),
        "At least one role assignment is required.",
    )
}

private fun UserProvisioningStore.requireActiveBranch(
    organisationId: UUID,
    branchId: UUID,
) {
    if (branchState(organisationId, branchId) != BranchLifecycleState.ACTIVE) {
        throw InvalidOperationException(safeDetail = BRANCH_REFERENCE_DETAIL)
    }
}

private fun UserProvisioningStore.validateRoleScope(
    organisationId: UUID,
    assignment: RoleAssignmentRequest,
) {
    when (assignment.scopeType) {
        RoleAssignmentScopeType.TENANT -> {
            if (assignment.branchId != null) throw InvalidOperationException()
        }

        RoleAssignmentScopeType.BRANCH -> {
            val branchId =
                assignment.branchId
                    ?: throw InvalidRequestException(
                        "validation_failed",
                        "A branch-scoped role assignment requires a branch.",
                    )
            requireActiveBranch(organisationId, branchId)
        }
    }
}

/** Refuses (409) approving a membership whose user lacks an active branch or role assignment. */
internal fun UserProvisioningStore.requireActiveAccessPrerequisites(
    organisationId: UUID,
    snapshot: MembershipProvisioningSnapshot,
) {
    val branchExempt = snapshot.type in BRANCH_EXEMPT_TYPES
    if (!branchExempt && !hasActiveBranchAssignment(organisationId, snapshot.userId)) {
        throw ConflictException(
            safeDetail =
                "The membership cannot be approved until the user has an active branch " +
                    "assignment.",
        )
    }
    if (!hasActiveRoleAssignment(organisationId, snapshot.userId)) {
        throw ConflictException(
            safeDetail =
                "The membership cannot be approved until the user has an active role " +
                    "assignment.",
        )
    }
}

private fun malformedUnless(
    condition: Boolean,
    detail: String,
) {
    if (!condition) throw InvalidRequestException("validation_failed", detail)
}
