package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.lifecycle.PermissionGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Edits of a user's profile (#161) and of a membership's type and primary branch. Neither is a
 * lifecycle transition, so each writes its own audit row, naming the fields it changed and never
 * their values: a phone number is personal data, and a name is not worth repeating.
 */
@Service
class ProfileUpdateService(
    private val store: ProfileUpdateStore,
    private val permissionGuard: PermissionGuard,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    /**
     * Edits a global user account's display name and phone. A deactivated or archived account is
     * terminal and is refused. The actor needs `user.update` and
     * `user.view` in the platform organisation, checked before the request is judged or any row is
     * looked up, so an unknown account never answers a caller without them.
     */
    @Transactional
    fun updateUser(command: UpdateUserProfileCommand) {
        permissionGuard.requirePlatformPermission(command.actorId, USER_UPDATE_PERMISSION)
        val changed =
            listOfNotNull(
                "display_name".takeIf { command.displayName != null },
                "phone_e164".takeIf { command.phoneE164 != null },
            )
        invalidOperationUnless(changed.isNotEmpty())
        val displayName = command.displayName?.trim()
        displayName?.let {
            invalidOperationUnless(it.length in MIN_DISPLAY_NAME..MAX_DISPLAY_NAME)
        }
        command.phoneE164?.let { invalidOperationUnless(PHONE_E164.matches(it)) }
        val status =
            store.lockUserStatus(command.userId)
                ?: throw ResourceNotFoundException(safeDetail = "User account was not found.")
        invalidOperationUnless(status !in TERMINAL_USER_STATUSES)
        store.updateUserProfile(command.userId, displayName, command.phoneE164, command.actorId)
        audit(
            PlatformOrganisation.ID,
            USER_UPDATE_PERMISSION,
            "USER_ACCOUNT",
            command.userId,
            command.userId,
            command.actorId,
            changed,
        )
    }

    /**
     * Edits a membership's type and primary branch. The actor needs `membership.update` and
     * `membership.view` in the tenant, checked first. A revoked membership is not edited, `SYSTEM`
     * is reserved for the platform's own accounts and cannot be assigned, an ACTIVE membership
     * cannot move to a branch-bound type without an active branch assignment (409), and a primary
     * branch must be an ACTIVE branch of the same tenant.
     */
    @Transactional
    fun updateMembership(command: UpdateMembershipCommand) {
        permissionGuard.requireTenantPermission(
            command.actorId,
            command.organisationId,
            MEMBERSHIP_UPDATE_PERMISSION,
        )
        val changed =
            listOfNotNull(
                "membership_type".takeIf { command.membershipType != null },
                "primary_branch_id".takeIf { command.changesPrimaryBranch },
            )
        invalidOperationUnless(changed.isNotEmpty())
        command.membershipType?.let { invalidOperationUnless(it in ASSIGNABLE_TYPES) }
        val target =
            store.lockMembership(command.organisationId, command.membershipId)
                ?: throw ResourceNotFoundException()
        invalidOperationUnless(target.status != REVOKED && target.type != SYSTEM)
        // An ACTIVE membership of a branch-bound type needs an active branch assignment, as
        // activation demands; an AUDITOR may have none, so it cannot be promoted past that.
        if (command.membershipType in BRANCH_BOUND_TYPES && target.status == ACTIVE) {
            if (!store.hasActiveBranchAssignment(command.organisationId, target.userId)) {
                throw ConflictException(
                    safeDetail =
                        "The membership cannot take this type until the user has an active " +
                            "branch assignment.",
                )
            }
        }
        command.primaryBranchId?.let {
            invalidOperationUnless(store.activeBranchExists(command.organisationId, it))
        }
        store.updateMembership(command, OffsetDateTime.now(clock))
        audit(
            command.organisationId,
            MEMBERSHIP_UPDATE_PERMISSION,
            "MEMBERSHIP",
            command.membershipId,
            target.userId,
            command.actorId,
            changed,
        )
    }

    @Suppress("LongParameterList")
    private fun audit(
        organisationId: UUID,
        action: String,
        resourceType: String,
        resourceId: UUID,
        subjectUserId: UUID,
        actorId: UUID,
        changedFields: List<String>,
    ) {
        auditService.record(
            AuditCommand(
                actorType = "USER",
                actorId = actorId.toString(),
                tenantId = organisationId.toString(),
                action = action,
                resourceType = resourceType,
                resourceId = resourceId.toString(),
                outcome = AuditOutcome.SUCCESS,
                metadata = mapOf("changedFields" to changedFields.joinToString(",")),
                subjectUserId = subjectUserId.toString(),
            ),
        )
    }

    private fun invalidOperationUnless(condition: Boolean) {
        if (!condition) throw InvalidOperationException()
    }

    private companion object {
        const val USER_UPDATE_PERMISSION = "user.update"
        const val MEMBERSHIP_UPDATE_PERMISSION = "membership.update"
        const val MIN_DISPLAY_NAME = 2
        const val MAX_DISPLAY_NAME = 100
        val TERMINAL_USER_STATUSES = setOf("DEACTIVATED", "ARCHIVED")
        const val REVOKED = "REVOKED"
        const val ACTIVE = "ACTIVE"
        const val SYSTEM = "SYSTEM"
        val BRANCH_BOUND_TYPES = setOf("STAFF", "ADMIN")
        val ASSIGNABLE_TYPES = setOf("STAFF", "ADMIN", "AUDITOR")
        val PHONE_E164 = Regex("^\\+[1-9]\\d{1,14}$")
    }
}
