package com.finaxis.platform.iam.adapter.inbound.web.dto

import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

/** Request payload for inviting a user into the active tenant. */
data class InviteUserRequest(
    @field:NotBlank
    @field:Email
    val email: String,
    @field:NotBlank
    @field:Pattern(regexp = "^[a-zA-Z0-9._-]{3,50}$")
    val username: String,
    @field:NotBlank
    @field:Size(min = 2, max = 100)
    val displayName: String,
    @field:Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Phone must be in E.164 format.")
    val phoneE164: String? = null,
    @field:NotNull
    val membershipType: MembershipType,
    val primaryBranchId: UUID? = null,
    @field:Valid
    val branchAssignments: List<BranchAssignmentEntryDto> = emptyList(),
    @field:Valid
    val roleAssignments: List<RoleAssignmentEntryDto> = emptyList(),
    val sendKeycloakInvite: Boolean = true,
    val sendApplicationInvite: Boolean = false,
)

/** Requested branch assignment included in a tenant user invitation. */
data class BranchAssignmentEntryDto(
    @field:NotNull
    val branchId: UUID,
    @field:NotNull
    val assignmentType: BranchAssignmentType,
)

/** Requested role assignment included in a tenant user invitation. */
data class RoleAssignmentEntryDto(
    @field:NotNull
    val roleId: UUID,
    @field:NotNull
    val scopeType: RoleAssignmentScopeType,
    val branchId: UUID? = null,
)

/** Local invitation result returned after a user and membership are recorded. */
data class UserInvitationResultResponse(
    val userId: UUID,
    val membershipId: UUID,
    val userStatus: String,
    val membershipStatus: String,
)

/** Tenant-scoped summary of a user and membership state. */
data class UserInTenantSummaryResponse(
    val id: UUID,
    val username: String,
    val email: String,
    val displayName: String,
    val userStatus: String,
    val membershipStatus: String,
)

/** Request payload for suspending a global user account. */
data class SuspendUserRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    val reason: String,
)

/** Request payload for reactivating a global user account. */
data class ReactivateUserRequest(
    @field:Size(max = 500)
    val reason: String? = null,
)

/** Request payload for deactivating a global user account. */
data class DeactivateUserRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    val reason: String,
)

/** Result returned after a global user lifecycle transition completes. */
data class UserLifecycleResultResponse(
    val userId: UUID,
    val status: String,
)
