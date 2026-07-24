package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Request payload for suspending an active membership. */
data class SuspendMembershipRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    val reason: String,
)

/** Request payload for reactivating a suspended membership. */
data class ReactivateMembershipRequest(
    @field:Size(max = 500)
    val reason: String? = null,
)

/** Request payload for revoking a tenant membership. */
data class RevokeMembershipRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    val reason: String,
)

/** Detailed response for a tenant membership. */
data class MembershipDetailResponse(
    val id: UUID,
    val organisationId: UUID,
    val userId: UUID,
    val username: String,
    val email: String,
    val displayName: String,
    val userStatus: String,
    val membershipStatus: String,
    val membershipType: String,
    val primaryBranchId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Summary response for a tenant membership. */
data class MembershipSummaryResponse(
    val id: UUID,
    val userId: UUID,
    val membershipStatus: String,
    val membershipType: String,
    val primaryBranchId: UUID?,
)
