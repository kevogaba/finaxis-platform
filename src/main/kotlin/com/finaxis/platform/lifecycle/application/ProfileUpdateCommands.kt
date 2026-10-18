package com.finaxis.platform.lifecycle.application

import java.util.UUID

/**
 * Edits a global user account's profile. A null field is left as it is; there is no way to clear
 * the phone, only to replace it.
 */
data class UpdateUserProfileCommand(
    val userId: UUID,
    val displayName: String? = null,
    val phoneE164: String? = null,
    val actorId: UUID,
)

/**
 * Edits a membership of [organisationId]. A null [membershipType] is left as it is;
 * [changesPrimaryBranch] says whether [primaryBranchId] (null clears it) is applied.
 */
data class UpdateMembershipCommand(
    val organisationId: UUID,
    val membershipId: UUID,
    val membershipType: String? = null,
    val changesPrimaryBranch: Boolean = false,
    val primaryBranchId: UUID? = null,
    val actorId: UUID,
)

/** The membership fields an edit judges. */
data class MembershipEditTarget(
    val userId: UUID,
    val status: String,
    val type: String,
)

/** Outbound port for profile and membership edits. */
interface ProfileUpdateStore {
    /** The account's status, locked for the transaction, or null when there is no such account. */
    fun lockUserStatus(userId: UUID): String?

    /** Applies the non-null fields to the account. */
    fun updateUserProfile(
        userId: UUID,
        displayName: String?,
        phoneE164: String?,
        actorId: UUID,
    )

    /** The membership as an edit judges it, locked for the transaction, or null. */
    fun lockMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipEditTarget?

    /** True when the user holds an ACTIVE assignment to an ACTIVE branch of [organisationId]. */
    fun hasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean

    /** True when [branchId] is an ACTIVE branch of [organisationId]. */
    fun activeBranchExists(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean

    /** Applies the edit to the membership. */
    fun updateMembership(
        command: UpdateMembershipCommand,
        now: java.time.OffsetDateTime,
    )
}
