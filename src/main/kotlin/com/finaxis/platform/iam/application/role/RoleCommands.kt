package com.finaxis.platform.iam.application.role

import com.finaxis.platform.iam.domain.RoleStatus
import java.util.UUID

/** Creates an organisation-scoped role owned by the selected organisation. */
data class CreateTenantRole(
    val organisationId: UUID,
    val roleCode: String,
    val roleName: String,
    val description: String?,
    val actorId: UUID,
    val requestId: String? = null,
)

/** Updates mutable descriptive fields of an organisation-scoped role. */
data class UpdateTenantRole(
    val organisationId: UUID,
    val roleId: UUID,
    val roleName: String?,
    val description: String?,
    val actorId: UUID,
    val requestId: String? = null,
)

/** Activates a role that belongs to the selected organisation. */
data class ActivateRole(
    val organisationId: UUID,
    val roleId: UUID,
    val actorId: UUID,
    val requestId: String? = null,
)

/** Disables a mutable role that belongs to the selected organisation. */
data class DeactivateRole(
    val organisationId: UUID,
    val roleId: UUID,
    val actorId: UUID,
    val requestId: String? = null,
)

/** Grants one catalogue permission to a role in the selected organisation. */
data class AssignPermissionToRole(
    val organisationId: UUID,
    val roleId: UUID,
    val permissionCode: String,
    val actorId: UUID,
    val requestId: String? = null,
)

/** Removes one catalogue permission from a mutable role in the selected organisation. */
data class RemovePermissionFromRole(
    val organisationId: UUID,
    val roleId: UUID,
    val permissionCode: String,
    val actorId: UUID,
    val requestId: String? = null,
)

/** Assigns a role to an organisation membership at tenant or branch scope. */
data class AssignRoleToUser(
    val organisationId: UUID,
    val userId: UUID,
    val roleId: UUID,
    val scopeType: RoleScopeType,
    val branchId: UUID?,
    val actorId: UUID,
    val requestId: String? = null,
)

/** Revokes an active role assignment from an organisation membership. */
data class RevokeRoleFromUser(
    val organisationId: UUID,
    val userId: UUID,
    val roleId: UUID,
    val scopeType: RoleScopeType,
    val branchId: UUID?,
    val actorId: UUID,
    val requestId: String? = null,
)

/** Enumerates the scopes supported by the user-role assignment schema. */
enum class RoleScopeType {
    TENANT,
    BRANCH,
}

/** Returns the role identifier and resulting lifecycle status after a role mutation. */
data class RoleResult(
    val roleId: UUID,
    val status: RoleStatus,
)

/** Returns the assignment identifier and resulting status after an assignment operation. */
data class RoleAssignmentResult(
    val assignmentId: UUID,
    val status: String,
)
