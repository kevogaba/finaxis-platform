package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.finaxis.platform.common.web.api.ActorRefResponse
import com.finaxis.platform.lifecycle.application.DecisionRemark
import com.finaxis.platform.lifecycle.application.Reason
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Optional decision remark for activating (approving) a pending membership. */
data class ActivateMembershipRequest(
    @field:Size(max = DecisionRemark.MAX_LENGTH)
    @field:Schema(example = "Checked against the signed request form.")
    val reason: String? = null,
)

/** Request payload for suspending an active membership. */
data class SuspendMembershipRequest(
    @field:NotBlank
    @field:Size(min = Reason.MIN_LENGTH, max = Reason.MAX_LENGTH)
    val reason: String,
)

/** Request payload for reactivating a suspended membership. */
data class ReactivateMembershipRequest(
    @field:Size(max = DecisionRemark.MAX_LENGTH)
    val reason: String? = null,
)

/** Request payload for revoking a tenant membership. */
data class RevokeMembershipRequest(
    @field:NotBlank
    @field:Size(min = Reason.MIN_LENGTH, max = Reason.MAX_LENGTH)
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
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        nullable = true,
        requiredMode = Schema.RequiredMode.REQUIRED,
        description =
            "Who invited the member. `null` when the system did, or when the caller " +
                "may not see them (a platform operator is shown only to platform " +
                "callers).",
    )
    val createdBy: ActorRefResponse?,
)

/** Summary response for a tenant membership. */
data class MembershipSummaryResponse(
    val id: UUID,
    val userId: UUID,
    val membershipStatus: String,
    val membershipType: String,
    val primaryBranchId: UUID?,
    val username: String,
    val email: String,
    val displayName: String,
    val primaryBranchName: String?,
    val createdAt: Instant,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        nullable = true,
        requiredMode = Schema.RequiredMode.REQUIRED,
        description =
            "Who invited the member. `null` when the system did, or when the caller " +
                "may not see them (a platform operator is shown only to platform " +
                "callers).",
    )
    val createdBy: ActorRefResponse?,
)
