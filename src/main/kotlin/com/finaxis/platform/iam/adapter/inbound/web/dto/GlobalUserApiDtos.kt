package com.finaxis.platform.iam.adapter.inbound.web.dto

import com.finaxis.platform.iam.application.query.GlobalUserMembership
import com.finaxis.platform.iam.application.query.GlobalUserProfile
import com.finaxis.platform.iam.application.query.GlobalUserSummary
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** A user account in the platform-wide directory. */
data class GlobalUserSummaryResponse(
    val id: UUID,
    val username: String,
    val email: String,
    val displayName: String,
    val userStatus: String,
    val createdAt: Instant,
    @field:Schema(description = "Memberships of the account in every organisation and status.")
    val membershipCount: Int,
)

/** One membership of a user account, with the organisation it belongs to. */
data class GlobalUserMembershipResponse(
    val membershipId: UUID,
    val organisationId: UUID,
    val tenantCode: String,
    val organisationName: String,
    val membershipStatus: String,
    val membershipType: String,
    val createdAt: Instant,
)

/** A user account with its memberships, newest first. */
data class GlobalUserDetailResponse(
    val id: UUID,
    val username: String,
    val email: String,
    val displayName: String,
    val userStatus: String,
    val createdAt: Instant,
    @field:Schema(description = "The full count, which can exceed the memberships listed.")
    val membershipCount: Int,
    @field:Schema(description = "At most 100 memberships, newest first.")
    val memberships: List<GlobalUserMembershipResponse>,
)

internal fun GlobalUserSummary.toResponse() =
    GlobalUserSummaryResponse(
        id = id,
        username = username,
        email = email,
        displayName = displayName,
        userStatus = userStatus,
        createdAt = createdAt,
        membershipCount = membershipCount,
    )

internal fun GlobalUserMembership.toResponse() =
    GlobalUserMembershipResponse(
        membershipId = membershipId,
        organisationId = organisationId,
        tenantCode = tenantCode,
        organisationName = organisationName,
        membershipStatus = membershipStatus,
        membershipType = membershipType,
        createdAt = createdAt,
    )

internal fun GlobalUserProfile.toResponse() =
    GlobalUserDetailResponse(
        id = user.id,
        username = user.username,
        email = user.email,
        displayName = user.displayName,
        userStatus = user.userStatus,
        createdAt = user.createdAt,
        membershipCount = user.membershipCount,
        memberships = memberships.map { it.toResponse() },
    )
