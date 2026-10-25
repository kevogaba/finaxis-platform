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

    /**
     * Takes the organisation row's `FOR NO KEY UPDATE` lock to the end of the calling
     * transaction, so a platform checker's window count read after it
     * ([UserProvisioningMembershipStore.hasActiveMembershipBeyondBootstrap]) sees whatever a rival
     * platform checker or tenant approval committed first (ADR 0028 point 8). Requires an active
     * transaction and does nothing for an unknown organisation, whose absence the caller's next
     * read reports.
     */
    fun lockOrganisation(organisationId: UUID)

    /** Resolves the organisation's display name for outbound communications. */
    fun organisationDisplayName(organisationId: UUID): String? = null

    /** Finds an application user by case-insensitive email address. */
    fun findUserIdByEmail(email: String): UUID?

    /** Returns whether any user already holds the username, ignoring case (globally unique). */
    fun usernameInUse(username: String): Boolean

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

    /** Resolves the original inviter (maker) of an organisation membership. */
    fun membershipInvitedBy(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID? = null

    /**
     * Returns whether the organisation has an ACTIVE membership that the bootstrap did not create,
     * that is one whose creator is not the system actor.
     */
    fun hasActiveMembershipBeyondBootstrap(organisationId: UUID): Boolean

    /** The user's membership row (any status) in the organisation, or null when there is none. */
    fun existingMembership(
        organisationId: UUID,
        userId: UUID,
    ): ExistingMembership?

    /**
     * Puts a membership that has just left `REVOKED` back to the state of a fresh invitation, on
     * the same row (`uq_membership_organisation_user` allows no second one): the membership type
     * and primary branch of the new invitation, [actorId] as its maker (`created_by`, which the
     * maker-checker rule reads), the lifecycle dates `joined_at`, `suspended_at` and `revoked_at`
     * and the `status_reason` cleared, and every direct `membership_permission` override of it
     * deleted, so nothing the member held before survives to the new life (ADR 0037). It runs
     * after the FSM's `REINVITE` transition, in the same transaction.
     */
    fun resetMembershipForReinvite(
        organisationId: UUID,
        membershipId: UUID,
        membershipType: MembershipType,
        primaryBranchId: UUID?,
        actorId: UUID,
    )

    /**
     * [membershipSnapshot] read after taking the membership row's `FOR NO KEY UPDATE` lock, held to
     * the end of the caller's transaction. The lock is one statement and the read another, so the
     * read (and the generation in it) sees whatever a rival that held the lock committed: a
     * `REINVITE` cannot commit between the check and the transition it guards. Must run inside a
     * transaction. The default does not lock.
     */
    fun lockedMembershipSnapshot(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot? = membershipSnapshot(organisationId, membershipId)
}

/** A membership row found for an invitee: its id and where it stands in its lifecycle. */
data class ExistingMembership(
    val id: UUID,
    val status: MembershipLifecycleState,
)

/** Persistence contract for branch, role, and assignment grants. */
interface UserProvisioningAccessStore {
    /** Resolves a branch lifecycle state inside the selected organisation. */
    fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ): BranchLifecycleState?

    /**
     * Returns whether an active role belongs to the selected organisation, and takes the role's
     * row lock (`FOR NO KEY UPDATE`) until the caller's transaction ends, so an archive of it
     * cannot slip between this check and the assignment the invitation inserts later in the same
     * transaction (ADR 0035). Must run inside a transaction.
     */
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

    /**
     * Returns a FAILED dispatch to PENDING when its job has been requeued under the same id. Any
     * other status is left as it is, so a SUCCEEDED dispatch is never reopened.
     */
    fun markDispatchPending(dispatchKey: String)

    /**
     * Records one invitation resend against the user's newest SUCCEEDED Keycloak dispatch, in any
     * organisation (the account is global: a tenant that activated an already-provisioned user has
     * no dispatch of its own), adding one to its attempts, only when it last changed at least
     * [cooldown] ago, which is the per-account rate limit: the check and the touch are one
     * statement, so concurrent resends cannot both pass. The result says what happened: the
     * dispatch's key and new attempt count, a dispatch still inside the cooldown, or no SUCCEEDED
     * dispatch at all.
     */
    fun touchDispatchForResend(
        userId: UUID,
        cooldown: java.time.Duration,
    ): ResendTouch = ResendTouch.NoDispatch

    /** The Keycloak subject the user is linked to, or null when there is no active link. */
    fun linkedKeycloakSubject(userId: UUID): String? = null
}

/** What recording an invitation resend against the user's dispatch came to. */
sealed interface ResendTouch {
    /** The resend was recorded against [dispatchKey], which now holds [attempt] attempts. */
    data class Touched(
        val dispatchKey: String,
        val attempt: Int,
    ) : ResendTouch

    /** The account has a succeeded dispatch, touched less than the cooldown ago. */
    data object TooSoon : ResendTouch

    /** The account has no succeeded dispatch, so there is no invitation to send again. */
    data object NoDispatch : ResendTouch
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
    /**
     * How many times the membership has been re-invited (the count of its `REINVITE` transition
     * log rows): 0 for a membership never revoked and invited again. It is part of the invite
     * dispatch keys, so each life of a membership has its own jobs (ADR 0037).
     */
    val generation: Int = 0,
)

/** Dispatch categories persisted for downstream identity and invitation workers. */
enum class IdentityDispatchType {
    KEYCLOAK_PROVISIONING,
    APPLICATION_INVITE,
}
