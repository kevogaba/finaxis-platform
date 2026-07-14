package com.finaxis.platform.iam.application.port.outbound

import com.finaxis.platform.iam.application.role.RoleScopeType
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import java.util.UUID

/** Outbound persistence contract for organisation-scoped role administration. */
interface IamAdministrationPersistence :
    RolePersistence,
    RolePermissionPersistence,
    RoleAssignmentPersistence

/** Persists organisation-scoped role definitions and lifecycle state. */
interface RolePersistence {
    /** Returns whether [roleCode] already belongs to [organisationId]. */
    fun roleCodeExists(
        organisationId: UUID,
        roleCode: String,
    ): Boolean

    /** Finds a role only when it belongs to [organisationId]. */
    fun findRole(
        organisationId: UUID,
        roleId: UUID,
    ): RoleSnapshot?

    /** Creates an active tenant role and returns its identifier. */
    fun createRole(
        organisationId: UUID,
        roleCode: String,
        roleName: String,
        description: String?,
        actorId: UUID,
    ): UUID

    /** Updates mutable role fields using [rowVersion] for optimistic locking. */
    fun updateRole(
        organisationId: UUID,
        roleId: UUID,
        roleName: String?,
        description: String?,
        rowVersion: Long,
        actorId: UUID,
    )

    /** Changes the role status using [rowVersion] for optimistic locking. */
    fun setRoleStatus(
        organisationId: UUID,
        roleId: UUID,
        status: RoleStatus,
        rowVersion: Long,
        actorId: UUID,
    )
}

/** Resolves permission catalogue entries and role-permission mappings. */
interface RolePermissionPersistence {
    /** Resolves a global permission catalogue identifier by its stable code. */
    fun permissionIdByCode(permissionCode: String): UUID?

    /** Returns a permission risk level for audit-sensitive administration decisions. */
    fun permissionRiskLevel(permissionCode: String): String?

    /** Grants a permission idempotently and returns whether a row was created. */
    fun grantPermission(
        organisationId: UUID,
        roleId: UUID,
        permissionId: UUID,
        actorId: UUID,
    ): Boolean

    /** Removes a role permission and returns whether an active mapping existed. */
    fun removePermission(
        organisationId: UUID,
        roleId: UUID,
        permissionId: UUID,
        actorId: UUID,
    ): Boolean
}

/** Persists organisation membership role assignments and the branch scope they require. */
interface RoleAssignmentPersistence {
    /** Finds a user's membership only inside [organisationId]. */
    fun membership(
        organisationId: UUID,
        userId: UUID,
    ): MembershipSnapshot?

    /** Returns the lifecycle status for [organisationId], or null when it is absent. */
    fun organisationStatus(organisationId: UUID): OrganisationStatus?

    /** Checks whether a user has an active branch assignment in the selected organisation. */
    fun hasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
    ): Boolean

    /** Finds an active role assignment for the full scope key. */
    fun activeRoleAssignment(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleScopeType,
        branchId: UUID?,
    ): UUID?

    /** Creates an active user-role assignment idempotently and returns its identifier. */
    fun assignRole(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleScopeType,
        branchId: UUID?,
        actorId: UUID,
    ): UUID

    /** Revokes one active user-role assignment in the selected organisation. */
    fun revokeRole(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleScopeType,
        branchId: UUID?,
        actorId: UUID,
    ): Boolean

    /** Returns membership identifiers affected by changes to [roleId]. */
    fun membershipIdsWithRole(
        organisationId: UUID,
        roleId: UUID,
    ): List<UUID>
}

/** Minimal role projection required for organisation-bound administration decisions. */
data class RoleSnapshot(
    val id: UUID,
    val roleCode: String,
    val systemRole: Boolean,
    val status: RoleStatus,
    val rowVersion: Long,
)

/** Minimal membership projection required before assigning a role. */
data class MembershipSnapshot(
    val id: UUID,
    val status: MembershipStatus,
    val type: String,
)
