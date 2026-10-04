package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import org.springframework.dao.DuplicateKeyException
import java.util.UUID

private const val MEMBERSHIP_EXISTS_DETAIL =
    "This user already has a membership in the selected organisation."
private const val USERNAME_IN_USE_DETAIL = "That username is already in use."
private const val USER_RACE_DETAIL =
    "A user with this email or username already exists; retry the invitation."

/**
 * Resolves the invitee by email, creating the account only for an unknown email. The refusals (a
 * membership already held here, a username already taken) come before the first write.
 */
internal fun UserProvisioningStore.findOrCreateInvitee(command: InviteUserCommand): UUID {
    val existing = findUserIdByEmail(command.email)
    if (existing == null) return createRefusingDuplicates(command)
    if (membershipExists(command.organisationId, existing)) {
        throw ConflictException(safeDetail = MEMBERSHIP_EXISTS_DETAIL)
    }
    return existing
}

/**
 * The username lookup and the lookup in [findOrCreateInvitee] are courtesies, not the checks: two
 * concurrent invitations for one new email or username both pass them and the loser fails the
 * unique index (`uq_user_account_lower_email` / `uq_user_account_lower_username`). Postgres has
 * aborted the transaction by then, so the translation rethrows rather than carrying on.
 */
private fun UserProvisioningStore.createRefusingDuplicates(command: InviteUserCommand): UUID {
    if (usernameInUse(command.username)) {
        throw ConflictException(safeDetail = USERNAME_IN_USE_DETAIL)
    }
    try {
        return createUserAccount(
            command.email,
            command.username,
            command.displayName,
            command.phoneE164,
            command.invitedBy,
        )
    } catch (ex: DuplicateKeyException) {
        throw ConflictException(safeDetail = USER_RACE_DETAIL, cause = ex)
    }
}
