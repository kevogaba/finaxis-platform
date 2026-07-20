package com.finaxis.platform.lifecycle.application.port.outbound

import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import java.util.UUID

/** Aggregated persistence contract for local user provisioning workflows. */
interface UserProvisioningStore :
    UserProvisioningAccountStore,
    UserProvisioningMembershipStore,
    UserProvisioningAccessStore,
    UserProvisioningDispatchStore

/** Persistence contract for user account and organisation lifecycle facts. */
interface UserProvisioningAccountStore {
    /** Resolves the current organisation state within the local lifecycle boundary. */
    fun organisationState(organisationId: UUID): OrganisationLifecycleState?

    /** Finds an application user by case-insensitive email address. */
    fun findUserIdByEmail(email: String): UUID?

    /** Creates a global draft user account and returns its identifier. */
    fun createUserAccount(
        email: String,
        username: String,
        displayName: String,
        phoneE164: String?,
        actorId: UUID,
    ): UUID

    /** Resolves the current global user lifecycle status. */
    fun userStatus(userId: UUID): UserLifecycleState?

    /** Returns whether the global user already has an active Keycloak identity link. */
    fun hasKeycloakIdentity(userId: UUID): Boolean
}

/** Persistence contract for organisation membership invitation facts. */
interface UserProvisioningMembershipStore {
    /** Creates or resolves the user's organisation membership. */
    fun createMembership(
        organisationId: UUID,
        userId: UUID,
        membershipType: MembershipType,
        primaryBranchId: UUID?,
        actorId: UUID,
    ): UUID

    /** Saves invitation delivery preferences until the pending membership is approved. */
    fun saveInvitationPreferences(
        organisationId: UUID,
        membershipId: UUID,
        sendKeycloakInvite: Boolean,
        sendApplicationInvite: Boolean,
        actorId: UUID,
    )

    /** Loads a membership and its user identity facts within one organisation. */
    fun membershipSnapshot(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot?

    /** Returns whether the user already has a membership row (any status) in the organisation. */
    fun membershipExists(
        organisationId: UUID,
        userId: UUID,
    ): Boolean
}

/** Persistence contract for branch, role, and assignment grants. */
interface UserProvisioningAccessStore {
    /** Resolves a branch lifecycle state inside the selected organisation. */
    fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ): BranchLifecycleState?

    /** Returns whether an active role belongs to the selected organisation. */
    fun roleExists(
        organisationId: UUID,
        roleId: UUID,
    ): Boolean

    /** Finds a role ID by its code within the selected organisation. */
    fun findRoleIdByCode(
        organisationId: UUID,
        roleCode: String,
    ): UUID?

    /** Creates an active role assignment idempotently and returns the durable assignment id. */
    fun assignRole(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleAssignmentScopeType,
        branchId: UUID?,
        actorId: UUID,
    ): UUID

    /** Returns whether the membership currently has at least one active branch assignment. */
    fun hasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean

    /** Returns whether the membership currently has at least one active role assignment. */
    fun hasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean

    /** Revokes all active branch assignments for a membership without ordinary-member guards. */
    fun revokeBranchAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int

    /** Revokes all active role assignments for a membership in the selected organisation. */
    fun revokeRoleAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int
}

/** Persistence contract for downstream identity dispatch coordination. */
interface UserProvisioningDispatchStore {
    /** Records an idempotent pending dispatch row for downstream identity workers. */
    fun recordDispatch(
        organisationId: UUID,
        userId: UUID,
        dispatchType: IdentityDispatchType,
        dispatchKey: String,
        actorId: UUID,
    )

    /** Links a local user account to a Keycloak subject, idempotently by provider and subject. */
    fun linkKeycloakIdentity(
        userId: UUID,
        subject: String,
        realm: String,
        actorId: UUID,
    )

    /** Marks a dispatch as succeeded after downstream delivery and local workflow completion. */
    fun markDispatchSucceeded(
        dispatchKey: String,
        externalRef: String,
    )

    /** Marks a dispatch attempt as failed while preserving retry visibility. */
    fun markDispatchFailed(
        dispatchKey: String,
        error: String,
    )

    /** Resolves the current dispatch status for idempotent worker skip decisions. */
    fun dispatchStatus(dispatchKey: String): String?
}

/** Snapshot used by the local provisioning workflow before approval or revocation. */
data class MembershipProvisioningSnapshot(
    val id: UUID,
    val userId: UUID,
    val status: MembershipLifecycleState,
    val type: MembershipType,
    val email: String,
    val username: String,
    val displayName: String,
    val userStatus: UserLifecycleState,
    val sendKeycloakInvite: Boolean,
    val sendApplicationInvite: Boolean,
)

/** Dispatch categories persisted for downstream identity and invitation workers. */
enum class IdentityDispatchType {
    KEYCLOAK_PROVISIONING,
    APPLICATION_INVITE,
}
