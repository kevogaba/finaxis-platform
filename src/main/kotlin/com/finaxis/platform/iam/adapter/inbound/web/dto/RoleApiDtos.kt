package com.finaxis.platform.iam.adapter.inbound.web.dto

import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

/** Request payload for creating a tenant-managed role. */
data class CreateRoleRequest(
    @field:NotBlank
    @field:Pattern(
        regexp = "^[A-Z0-9_-]{2,20}$",
        message = "Role code must be 2-20 uppercase letters, numbers, hyphens, or underscores.",
    )
    val roleCode: String,
    @field:NotBlank
    val roleName: String,
    val description: String? = null,
)

/** Request payload for updating a tenant-managed role's mutable metadata. */
data class UpdateRoleRequest(
    val roleName: String?,
    val description: String?,
)

/** Request payload for granting one catalogue permission to a role. */
data class AssignPermissionRequest(
    @field:NotBlank
    val permissionCode: String,
)

/** Request payload for assigning a role to a tenant membership. */
@BranchScopeRequiresBranch
data class AssignRoleRequest(
    @field:NotNull
    val userId: UUID,
    @field:NotNull
    val roleId: UUID,
    @field:NotNull
    override val scopeType: RoleAssignmentScopeType,
    override val branchId: UUID? = null,
) : BranchScoped

/** Summary response for a tenant role. */
data class RoleSummaryResponse(
    val id: UUID,
    val roleCode: String,
    val roleName: String,
    val systemRole: Boolean,
    val status: String,
)

/** Detailed response for a tenant role. */
data class RoleDetailResponse(
    val id: UUID,
    val organisationId: UUID,
    val roleCode: String,
    val roleName: String,
    val description: String?,
    val systemRole: Boolean,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Summary response for a user role assignment. */
data class RoleAssignmentSummaryResponse(
    val id: UUID,
    val userId: UUID,
    val roleId: UUID,
    val branchId: UUID?,
    val scopeType: String,
    val status: String,
)

/** Detailed response for a user role assignment. */
data class RoleAssignmentDetailResponse(
    val id: UUID,
    val organisationId: UUID,
    val userId: UUID,
    val roleId: UUID,
    val branchId: UUID?,
    val scopeType: String,
    val status: String,
    val assignedAt: Instant,
    val assignedBy: UUID?,
    val revokedAt: Instant?,
    val revokedBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Summary response for an immutable permission catalogue entry. */
data class PermissionSummaryResponse(
    val id: UUID,
    val permissionCode: String,
    val permissionName: String,
    val moduleCode: String,
    val riskLevel: String,
    val status: String,
    @field:Schema(
        description =
            "VIEW reads a resource, MUTATION changes state, CONTEXT gates the " +
                "caller's own session or a platform-only setting.",
        allowableValues = ["VIEW", "MUTATION", "CONTEXT"],
    )
    val kind: String,
    @field:Schema(
        description =
            "TENANT: evaluated in a tenant organisation. PLATFORM: only ever " +
                "evaluated in the platform organisation.",
        allowableValues = ["TENANT", "PLATFORM"],
    )
    val grantScope: String,
    @field:Schema(
        description =
            "Codes of the view permissions a MUTATION requires, sorted. A role or " +
                "caller holding the mutation must also hold these. Empty for VIEW and CONTEXT.",
    )
    val requiredViewPermissions: List<String>,
)

/** Detailed response for an immutable permission catalogue entry. */
data class PermissionDetailResponse(
    val id: UUID,
    val permissionCode: String,
    val permissionName: String,
    val moduleCode: String,
    val description: String?,
    val riskLevel: String,
    val status: String,
    @field:Schema(
        description =
            "VIEW reads a resource, MUTATION changes state, CONTEXT gates the " +
                "caller's own session or a platform-only setting.",
        allowableValues = ["VIEW", "MUTATION", "CONTEXT"],
    )
    val kind: String,
    @field:Schema(
        description =
            "TENANT: evaluated in a tenant organisation. PLATFORM: only ever " +
                "evaluated in the platform organisation.",
        allowableValues = ["TENANT", "PLATFORM"],
    )
    val grantScope: String,
    @field:Schema(
        description =
            "Codes of the view permissions a MUTATION requires, sorted. A role or " +
                "caller holding the mutation must also hold these. Empty for VIEW and CONTEXT.",
    )
    val requiredViewPermissions: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Summary response for a permission grant on a role. */
data class RolePermissionSummaryResponse(
    val id: UUID,
    val roleId: UUID,
    val permissionId: UUID,
    val permissionCode: String,
    val grantedAt: Instant,
)
