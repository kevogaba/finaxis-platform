package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.finaxis.platform.common.web.api.ActorRefResponse
import com.finaxis.platform.lifecycle.application.DecisionRemark
import com.finaxis.platform.lifecycle.application.Reason
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.Optional
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
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        nullable = true,
        description =
            "Where the member's identity provisioning stands (#274), derived from the " +
                "provisioning dispatch. Present on a membership detail read; `null` on the " +
                "result of a mutation that does not read it.",
    )
    val invitation: InvitationStatusResponse? = null,
)

/** Where a member's identity provisioning stands; a view, not a lifecycle state. */
data class InvitationStatusResponse(
    @field:Schema(
        allowableValues =
            ["AWAITING_APPROVAL", "IN_PROGRESS", "FAILED", "COMPLETED", "NOT_REQUIRED"],
    )
    val status: String,
    @field:Schema(description = "Provisioning attempts plus invitation resends recorded.")
    val attempts: Int,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        nullable = true,
        description =
            "Closed code of the last failure, only while `status` is `FAILED`: " +
                "`IDENTITY_PROVIDER_FAILED`, `CONFLICT`, `NOT_FOUND`, `INVALID_STATE`, " +
                "`DATABASE_ERROR`, `UNEXPECTED`, `CANCELLED` (membership revoked while queued), " +
                "or `UNKNOWN` for a failure recorded before codes existed. Never the " +
                "failure's text.",
    )
    val lastErrorCode: String?,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(nullable = true, description = "When the dispatch last changed, if any.")
    val updatedAt: Instant?,
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

/**
 * Request payload for editing a membership. Absent keys leave a field unchanged; an explicit
 * `null` primary branch clears it. At least one field is required.
 */
data class UpdateMembershipRequest(
    @field:Schema(
        allowableValues = ["STAFF", "ADMIN", "AUDITOR"],
        description = "Absent leaves the type unchanged. `SYSTEM` is reserved and refused.",
    )
    val membershipType: String? = null,
    @field:Schema(
        type = "string",
        format = "uuid",
        nullable = true,
        description =
            "Absent leaves the primary branch unchanged; an explicit null clears it; an id " +
                "sets it, and must be an ACTIVE branch of the tenant.",
    )
    val primaryBranchId: Optional<UUID>? = null,
)
