package com.finaxis.platform.iam.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.finaxis.platform.iam.application.query.GlobalUserMembership
import com.finaxis.platform.iam.application.query.GlobalUserProfile
import com.finaxis.platform.iam.application.query.GlobalUserSummary
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
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
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        nullable = true,
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "The account's phone in E.164, or `null` when none is recorded.",
    )
    val phoneE164: String?,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        nullable = true,
        requiredMode = Schema.RequiredMode.REQUIRED,
        description =
            "When the account last logged in, or `null` when it never has. Recorded only " +
                "while the account is ACTIVE or INVITED.",
    )
    val lastLoginAt: Instant?,
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
        phoneE164 = phoneE164,
        lastLoginAt = lastLoginAt,
    )

/** Request payload for editing a global user account's profile; at least one field is required. */
data class UpdateUserProfileRequest(
    @field:Size(min = 2, max = 100)
    @field:Schema(example = "Jane Wanjiru", description = "Absent leaves the name unchanged.")
    val displayName: String? = null,
    @field:Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Phone must be in E.164 format.")
    @field:Schema(
        example = "+254700000000",
        description = "Absent leaves the phone unchanged. A phone is replaced, never cleared.",
    )
    val phoneE164: String? = null,
)
