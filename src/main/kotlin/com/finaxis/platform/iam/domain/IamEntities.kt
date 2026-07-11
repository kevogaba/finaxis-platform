package com.finaxis.platform.iam.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * Global user identity mapped to a Keycloak subject.
 */
@Table("app_user")
data class AppUser(
    @Id val id: UUID,
    val keycloakSubject: String,
    val email: String?,
    val fullName: String?,
    val status: UserStatus,
    val createdAt: Instant,
    val createdByUserId: UUID? = null,
    val createdByMembershipId: UUID? = null,
    val updatedAt: Instant,
    val updatedByUserId: UUID? = null,
    val updatedByMembershipId: UUID? = null,
)

/**
 * Organisation tenant that owns memberships and tenant-scoped roles.
 */
@Table("organisation")
data class Organisation(
    @Id val id: UUID,
    val name: String,
    val code: String,
    val status: OrganisationStatus,
    val createdAt: Instant,
    val createdByUserId: UUID? = null,
    val createdByMembershipId: UUID? = null,
    val updatedAt: Instant,
    val updatedByUserId: UUID? = null,
    val updatedByMembershipId: UUID? = null,
)

/**
 * User membership in one organisation.
 */
@Table("organisation_membership")
data class OrganisationMembership(
    @Id val id: UUID,
    val userId: UUID,
    val organisationId: UUID,
    val status: MembershipStatus,
    val joinedAt: Instant,
    val leftAt: Instant? = null,
    val createdByUserId: UUID? = null,
    val createdByMembershipId: UUID? = null,
    val updatedAt: Instant,
    val updatedByUserId: UUID? = null,
    val updatedByMembershipId: UUID? = null,
)

/**
 * Permission catalogue entry addressed by a stable namespaced code.
 */
@Table("permission")
data class Permission(
    @Id val id: UUID,
    val module: String,
    val resource: String,
    val action: String,
    val code: String,
    val description: String?,
    val riskLevel: String? = null,
    val status: PermissionStatus,
    val deprecatedAt: Instant? = null,
    val replacedByPermissionId: UUID? = null,
    val createdAt: Instant,
)

/**
 * Permission bundle used for administration convenience.
 */
@Table("role")
data class Role(
    @Id val id: UUID,
    val organisationId: UUID?,
    val name: String,
    val code: String,
    val description: String?,
    val status: RoleStatus,
    val createdAt: Instant,
    val createdByUserId: UUID? = null,
    val createdByMembershipId: UUID? = null,
    val updatedAt: Instant,
    val updatedByUserId: UUID? = null,
    val updatedByMembershipId: UUID? = null,
)

/**
 * Link between a role bundle and a permission catalogue entry.
 */
@Table("role_permission")
data class RolePermission(
    @Id val id: UUID,
    val roleId: UUID,
    val permissionId: UUID,
)

/**
 * Role assignment granted to an organisation membership.
 */
@Table("membership_role")
data class MembershipRole(
    @Id val id: UUID,
    val membershipId: UUID,
    val roleId: UUID,
    val grantedBy: UUID?,
    val grantedAt: Instant,
)

/**
 * Direct permission assignment granted to an organisation membership.
 */
@Table("membership_permission")
data class MembershipPermission(
    @Id val id: UUID,
    val membershipId: UUID,
    val permissionId: UUID,
    val effect: PermissionEffect,
    val grantedBy: UUID?,
    val grantedAt: Instant,
)

/**
 * Branch scope assigned to an organisation membership.
 */
@Table("membership_branch_scope")
data class MembershipBranchScope(
    @Id val id: UUID,
    val membershipId: UUID,
    val branchId: UUID,
    val grantedBy: UUID?,
    val grantedAt: Instant,
)

/**
 * Warehouse scope assigned to an organisation membership.
 */
@Table("membership_warehouse_scope")
data class MembershipWarehouseScope(
    @Id val id: UUID,
    val membershipId: UUID,
    val warehouseId: UUID,
    val grantedBy: UUID?,
    val grantedAt: Instant,
)
