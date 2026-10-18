package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ActorRef
import java.time.Instant
import java.util.UUID

/**
 * Summary projection of a user membership in a tenant. [id] is the user id; [createdAt] is the
 * account's, [membershipCreatedAt] the membership's.
 */
data class UserInTenantSummary(
    val id: UUID,
    val username: String,
    val email: String,
    val displayName: String,
    val userStatus: String,
    val membershipStatus: String,
    val membershipId: UUID,
    val membershipType: String,
    val createdAt: Instant,
    val membershipCreatedAt: Instant,
    /** Who invited this member, or null when the system did or the caller may not see them. */
    val createdBy: ActorRef? = null,
)

/** Detailed projection of a tenant user; carries what [UserInTenantSummary] does. */
data class UserInTenantDetail(
    val id: UUID,
    val username: String,
    val email: String,
    val displayName: String,
    val userStatus: String,
    val membershipStatus: String,
    val membershipId: UUID,
    val membershipType: String,
    val createdAt: Instant,
    val membershipCreatedAt: Instant,
    /** Who invited this member, or null when the system did or the caller may not see them. */
    val createdBy: ActorRef? = null,
)

/**
 * A global user account as the platform user lifecycle routes read it back, with no tenant
 * membership: its id and its stored status, which is all those routes answer with.
 */
data class GlobalUserDetail(
    val id: UUID,
    val userStatus: String,
)

/** Detailed projection of a user membership in a tenant. */
data class MembershipDetail(
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
    /** Who invited this member, or null when the system did or the caller may not see them. */
    val createdBy: ActorRef? = null,
)

/** Summary projection of an organisation membership, with the user's names and the dates. */
data class MembershipSummary(
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
    /** Who invited this member, or null when the system did or the caller may not see them. */
    val createdBy: ActorRef? = null,
)

/** Summary projection of a branch assignment, with the user's and the branch's names. */
data class BranchAssignmentSummary(
    val id: UUID,
    val userId: UUID,
    val branchId: UUID,
    val assignmentType: String,
    val status: String,
    val username: String,
    val displayName: String,
    val branchName: String,
    val assignedAt: Instant,
)

/** Detailed projection of a branch assignment; carries the names [BranchAssignmentSummary] does. */
data class BranchAssignmentDetail(
    val id: UUID,
    val organisationId: UUID,
    val userId: UUID,
    val branchId: UUID,
    val assignmentType: String,
    val status: String,
    val assignedAt: Instant,
    val assignedBy: UUID?,
    val revokedAt: Instant?,
    val revokedBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val username: String,
    val displayName: String,
    val branchName: String,
)

/**
 * Summary projection of a security role.
 *
 * [missingViewPermissions] is the sorted flat list of view codes the role's held `ACTIVE`
 * mutation codes need and the role lacks (empty when it complies; ADR 0030 point 3).
 * [permissionCount] is the number of permissions granted to the role, and [activeUserCount] the
 * number of distinct users holding it through an `ACTIVE` assignment; the latter is null for a
 * caller who may not list every role assignment of the organisation.
 */
data class RoleSummary(
    val id: UUID,
    val roleCode: String,
    val roleName: String,
    val systemRole: Boolean,
    val status: String,
    val missingViewPermissions: List<String>,
    val permissionCount: Int,
    val activeUserCount: Int?,
)

/** Detailed projection of a security role; see [RoleSummary] for [missingViewPermissions]. */
data class RoleDetail(
    val id: UUID,
    val organisationId: UUID,
    val roleCode: String,
    val roleName: String,
    val description: String?,
    val systemRole: Boolean,
    val status: String,
    val missingViewPermissions: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Summary projection of a role assignment. */
data class RoleAssignmentSummary(
    val id: UUID,
    val userId: UUID,
    val roleId: UUID,
    val branchId: UUID?,
    val scopeType: String,
    val status: String,
)

/** Detailed projection of a role assignment. */
data class RoleAssignmentDetail(
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

/**
 * Summary projection of a system permission.
 *
 * [kind] is `VIEW`, `MUTATION` or `CONTEXT`, [grantScope] is `TENANT` or `PLATFORM`, and
 * [requiredViewPermissions] holds the codes of the views a mutation implies, sorted (empty for a
 * view or a context code). All three are catalogue metadata (ADR 0030).
 */
data class PermissionSummary(
    val id: UUID,
    val permissionCode: String,
    val permissionName: String,
    val moduleCode: String,
    val riskLevel: String,
    val status: String,
    val kind: String,
    val grantScope: String,
    val requiredViewPermissions: List<String>,
)

/** Detailed projection of a system permission; see [PermissionSummary] for the metadata. */
data class PermissionDetail(
    val id: UUID,
    val permissionCode: String,
    val permissionName: String,
    val moduleCode: String,
    val description: String?,
    val riskLevel: String,
    val status: String,
    val kind: String,
    val grantScope: String,
    val requiredViewPermissions: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Summary projection of a role permission grant. */
data class RolePermissionSummary(
    val id: UUID,
    val roleId: UUID,
    val permissionId: UUID,
    val permissionCode: String,
    val grantedAt: Instant,
)

/** Detailed projection of a role permission grant. */
data class RolePermissionDetail(
    val id: UUID,
    val organisationId: UUID,
    val roleId: UUID,
    val permissionId: UUID,
    val permissionCode: String,
    val grantedAt: Instant,
    val grantedBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
