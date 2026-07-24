package com.finaxis.platform.iam.application.role

import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.iam.application.authorization.PermissionCacheInvalidator
import com.finaxis.platform.iam.application.port.outbound.IamAdministrationPersistence
import com.finaxis.platform.iam.application.port.outbound.RoleSnapshot
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** Coordinates organisation-scoped role, permission, and user-role assignment administration. */
@Service
class RoleManagementService(
    private val persistence: IamAdministrationPersistence,
    private val auditService: AuditService,
    private val eventPublisher: TransitionEventPublisher,
    private val permissionCacheInvalidator: PermissionCacheInvalidator,
) {
    /** Creates an active tenant-managed role after validating its unique code and name. */
    @Transactional
    fun createTenantRole(command: CreateTenantRole): RoleResult {
        invalidOperationUnless(command.roleCode.isNotBlank())
        invalidOperationUnless(command.roleName.isNotBlank())
        conflictUnless(!persistence.roleCodeExists(command.organisationId, command.roleCode))
        val roleId =
            persistence.createRole(
                command.organisationId,
                command.roleCode,
                command.roleName,
                command.description,
                command.actorId,
            )
        audit(command.organisationId, roleId, "role.create", command.actorId, command.requestId)
        return RoleResult(roleId, RoleStatus.ACTIVE)
    }

    /** Updates mutable descriptive attributes of a tenant-managed role. */
    @Transactional
    fun updateTenantRole(command: UpdateTenantRole): RoleResult {
        val role = requiredRole(command.organisationId, command.roleId)
        requireMutable(role)
        persistence.updateRole(
            command.organisationId,
            command.roleId,
            command.roleName,
            command.description,
            role.rowVersion,
            command.actorId,
        )
        audit(
            command.organisationId,
            command.roleId,
            "role.update",
            command.actorId,
            command.requestId,
        )
        return RoleResult(command.roleId, role.status)
    }

    /** Activates a role that is visible in the selected organisation. */
    @Transactional
    fun activateRole(command: ActivateRole): RoleResult = setStatus(command, RoleStatus.ACTIVE)

    /** Disables a tenant-managed role that is visible in the selected organisation. */
    @Transactional
    fun deactivateRole(command: DeactivateRole): RoleResult {
        val role = requiredRole(command.organisationId, command.roleId)
        requireMutable(role)
        persistence.setRoleStatus(
            command.organisationId,
            command.roleId,
            RoleStatus.DISABLED,
            role.rowVersion,
            command.actorId,
        )
        invalidateRoleMemberships(command.organisationId, command.roleId)
        audit(
            command.organisationId,
            command.roleId,
            "role.deactivate",
            command.actorId,
            command.requestId,
        )
        return RoleResult(command.roleId, RoleStatus.DISABLED)
    }

    /** Idempotently grants a catalogue permission and evicts affected permission caches. */
    @Transactional
    fun assignPermissionToRole(command: AssignPermissionToRole) {
        val role = requiredRole(command.organisationId, command.roleId)
        requireMutable(role)
        val permissionId = persistence.permissionIdByCode(command.permissionCode).orResourceNotFound()
        persistence.grantPermission(
            command.organisationId,
            command.roleId,
            permissionId,
            command.actorId,
        )
        invalidateRoleMemberships(command.organisationId, command.roleId)
        audit(
            command.organisationId,
            command.roleId,
            "role.assign_permission",
            command.actorId,
            command.requestId,
            mapOf(
                "permissionCode" to command.permissionCode,
                "riskLevel" to
                    (persistence.permissionRiskLevel(command.permissionCode) ?: "UNKNOWN"),
            ),
        )
    }

    /** Removes a permission from a mutable role and evicts affected permission caches. */
    @Transactional
    fun removePermissionFromRole(command: RemovePermissionFromRole) {
        val role = requiredRole(command.organisationId, command.roleId)
        requireMutable(role)
        val permissionId = persistence.permissionIdByCode(command.permissionCode).orResourceNotFound()
        persistence.removePermission(
            command.organisationId,
            command.roleId,
            permissionId,
            command.actorId,
        )
        invalidateRoleMemberships(command.organisationId, command.roleId)
        audit(
            command.organisationId,
            command.roleId,
            "role.remove_permission",
            command.actorId,
            command.requestId,
            mapOf("permissionCode" to command.permissionCode),
        )
    }

    /** Assigns a role idempotently after validating organisation membership and scope. */
    @Transactional
    fun assignRoleToUser(command: AssignRoleToUser): RoleAssignmentResult {
        val membership = persistence.membership(command.organisationId, command.userId).orResourceNotFound()
        conflictUnless(membership.status != MembershipStatus.REVOKED)
        conflictUnless(persistence.organisationStatus(command.organisationId) == OrganisationStatus.ACTIVE)
        requiredRole(command.organisationId, command.roleId)
        validateScope(command)
        persistence
            .activeRoleAssignment(
                command.organisationId,
                command.userId,
                command.roleId,
                command.scopeType,
                command.branchId,
            )?.let { return RoleAssignmentResult(it, ACTIVE) }
        val assignmentId =
            persistence.assignRole(
                command.organisationId,
                command.userId,
                command.roleId,
                command.scopeType,
                command.branchId,
                command.actorId,
            )
        permissionCacheInvalidator.evictMembership(membership.id)
        audit(
            command.organisationId,
            assignmentId,
            "user.assign_role",
            command.actorId,
            command.requestId,
            assignmentMetadata(
                command.organisationId,
                command.roleId,
                command.scopeType,
                command.branchId,
            ),
            "USER_ROLE_ASSIGNMENT",
        )
        publishAssignment(command, ASSIGN, INACTIVE, ACTIVE, ROLE_ASSIGNED_TARGET)
        return RoleAssignmentResult(assignmentId, ACTIVE)
    }

    /** Revokes an active role assignment, preserving an idempotent no-op for absent assignments. */
    @Transactional
    fun revokeRoleFromUser(command: RevokeRoleFromUser) {
        val assignmentId =
            persistence.activeRoleAssignment(
                command.organisationId,
                command.userId,
                command.roleId,
                command.scopeType,
                command.branchId,
            ) ?: return
        if (!persistence.revokeRole(
                command.organisationId,
                command.userId,
                command.roleId,
                command.scopeType,
                command.branchId,
                command.actorId,
            )
        ) {
            return
        }
        persistence.membership(command.organisationId, command.userId)?.let {
            permissionCacheInvalidator.evictMembership(it.id)
        }
        audit(
            command.organisationId,
            assignmentId,
            "user.revoke_role",
            command.actorId,
            command.requestId,
            assignmentMetadata(
                command.organisationId,
                command.roleId,
                command.scopeType,
                command.branchId,
            ),
            "USER_ROLE_ASSIGNMENT",
        )
        publishRevocation(command)
    }

    private fun setStatus(
        command: ActivateRole,
        status: RoleStatus,
    ): RoleResult {
        val role = requiredRole(command.organisationId, command.roleId)
        requireMutable(role)
        persistence.setRoleStatus(
            command.organisationId,
            command.roleId,
            status,
            role.rowVersion,
            command.actorId,
        )
        invalidateRoleMemberships(command.organisationId, command.roleId)
        audit(
            command.organisationId,
            command.roleId,
            "role.activate",
            command.actorId,
            command.requestId,
        )
        return RoleResult(command.roleId, status)
    }

    private fun requiredRole(
        organisationId: UUID,
        roleId: UUID,
    ): RoleSnapshot = persistence.findRole(organisationId, roleId).orResourceNotFound()

    private fun requireMutable(role: RoleSnapshot) {
        conflictUnless(!role.systemRole)
    }

    private fun validateScope(command: AssignRoleToUser) {
        when (command.scopeType) {
            RoleScopeType.BRANCH -> {
                conflictUnless(
                    command.branchId != null &&
                        persistence.hasActiveBranchAssignment(
                            command.organisationId,
                            command.userId,
                            command.branchId,
                        ),
                )
            }

            RoleScopeType.TENANT -> {
                invalidOperationUnless(command.branchId == null)
            }
        }
    }

    private fun invalidateRoleMemberships(
        organisationId: UUID,
        roleId: UUID,
    ) {
        persistence.membershipIdsWithRole(organisationId, roleId).forEach(
            permissionCacheInvalidator::evictMembership,
        )
    }

    private fun audit(
        organisationId: UUID,
        resourceId: UUID,
        action: String,
        actorId: UUID,
        requestId: String?,
        metadata: Map<String, String> = emptyMap(),
        resourceType: String = "ROLE",
    ) {
        auditService.record(
            AuditCommand(
                actorType = "USER",
                actorId = actorId.toString(),
                tenantId = organisationId.toString(),
                action = action,
                resourceType = resourceType,
                resourceId = resourceId.toString(),
                outcome = AuditOutcome.SUCCESS,
                requestId = requestId,
                metadata = metadata,
            ),
        )
    }

    private fun assignmentMetadata(
        organisationId: UUID,
        roleId: UUID,
        scopeType: RoleScopeType,
        branchId: UUID?,
    ): Map<String, String> =
        buildMap {
            put("organisationId", organisationId.toString())
            put("roleId", roleId.toString())
            put("scopeType", scopeType.name)
            branchId?.let { put("branchId", it.toString()) }
        }

    /** Scopes the event aggregate id by branch so branch-scoped assignments stay distinct. */
    private fun roleAssignmentAggregateId(
        userId: UUID,
        roleId: UUID,
        scopeType: RoleScopeType,
        branchId: UUID?,
    ): String = "$userId:$roleId:$scopeType" + (branchId?.let { ":$it" } ?: "")

    private fun publishAssignment(
        command: AssignRoleToUser,
        transition: String,
        fromState: String,
        toState: String,
        target: String,
    ) {
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = target,
                aggregateType = "USER_ROLE_ASSIGNMENT",
                aggregateId =
                    roleAssignmentAggregateId(
                        command.userId,
                        command.roleId,
                        command.scopeType,
                        command.branchId,
                    ),
                transition = transition,
                fromState = fromState,
                toState = toState,
                actor = TransitionActor("USER", command.actorId.toString()),
                occurredAt = Instant.now(),
                metadata =
                    assignmentMetadata(
                        command.organisationId,
                        command.roleId,
                        command.scopeType,
                        command.branchId,
                    ),
            ),
        )
    }

    private fun publishRevocation(command: RevokeRoleFromUser) {
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = ROLE_REVOKED_TARGET,
                aggregateType = "USER_ROLE_ASSIGNMENT",
                aggregateId =
                    roleAssignmentAggregateId(
                        command.userId,
                        command.roleId,
                        command.scopeType,
                        command.branchId,
                    ),
                transition = REVOKE,
                fromState = ACTIVE,
                toState = REVOKED,
                actor = TransitionActor("USER", command.actorId.toString()),
                occurredAt = Instant.now(),
                metadata =
                    assignmentMetadata(
                        command.organisationId,
                        command.roleId,
                        command.scopeType,
                        command.branchId,
                    ),
            ),
        )
    }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val INACTIVE = "INACTIVE"
        const val REVOKED = "REVOKED"
        const val ASSIGN = "ASSIGN"
        const val REVOKE = "REVOKE"
        const val ROLE_ASSIGNED_TARGET = "finaxis.iam.user.role-assigned"
        const val ROLE_REVOKED_TARGET = "finaxis.iam.user.role-revoked"
    }
}

private fun invalidOperationUnless(condition: Boolean) {
    if (!condition) throw InvalidOperationException()
}

private fun conflictUnless(condition: Boolean) {
    if (!condition) throw ConflictException()
}

private fun <T : Any> T?.orResourceNotFound(): T = this ?: throw ResourceNotFoundException()
