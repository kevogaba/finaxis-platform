package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.web.api.ApiPage
import java.time.Instant
import java.util.UUID

/** Filter parameters for the platform-wide user directory. */
data class GlobalUserFilter(
    val q: String? = null,
    val userStatus: String? = null,
    /** Narrows to users holding a membership in this organisation, whatever its status. */
    val organisationId: UUID? = null,
    val page: Int = 0,
    val size: Int = 25,
    val sortBy: String? = null,
    val sortDir: String? = null,
)

/** A user account in the platform-wide directory, with how many memberships it holds. */
data class GlobalUserSummary(
    val id: UUID,
    val username: String,
    val email: String,
    val displayName: String,
    val userStatus: String,
    val createdAt: Instant,
    /** Every membership of the account, in any organisation and status. */
    val membershipCount: Int,
)

/** One membership of a user account, with the organisation it belongs to. */
data class GlobalUserMembership(
    val membershipId: UUID,
    val organisationId: UUID,
    val tenantCode: String,
    val organisationName: String,
    val membershipStatus: String,
    val membershipType: String,
    val createdAt: Instant,
)

/**
 * A user account with its memberships, newest first and capped at
 * [GlobalUserQueries.MAX_MEMBERSHIPS]; [GlobalUserSummary.membershipCount] is the full count.
 */
data class GlobalUserProfile(
    val user: GlobalUserSummary,
    val memberships: List<GlobalUserMembership>,
)

/** Outbound port for the platform-wide user directory. */
interface GlobalUserQueries {
    /** Pages the user accounts that match [filter], across every organisation. */
    fun searchUsers(filter: GlobalUserFilter): ApiPage<GlobalUserSummary>

    /** The user account [userId] with its memberships, or null when there is none. */
    fun findUser(userId: UUID): GlobalUserProfile?

    /** Limits of the directory. */
    companion object {
        /** The most memberships one profile lists, so a read stays bounded. */
        const val MAX_MEMBERSHIPS = 100
    }
}
