package com.finaxis.platform.lifecycle.domain

import com.finaxis.platform.common.transitions.TransitionDefinition
import com.finaxis.platform.common.transitions.TransitionGraph
import com.finaxis.platform.common.transitions.TransitionGuard
import com.finaxis.platform.common.transitions.TransitionGuardException
import com.finaxis.platform.common.transitions.Transitionable
import java.util.UUID

/** Lifecycle states for an organisation. */
enum class OrganisationLifecycleState {
    DRAFT,
    PENDING_APPROVAL,
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    DEPROVISIONING,
    DEPROVISIONED,
    REJECTED,
    ARCHIVED,
}

/** Explicit transitions permitted for an organisation. */
enum class OrganisationLifecycleTransition {
    SUBMIT,
    START_PROVISIONING,
    ACTIVATE,
    REJECT,
    SUSPEND,
    REACTIVATE,
    START_DEPROVISIONING,
    COMPLETE_DEPROVISIONING,
    ARCHIVE,
}

/** Lifecycle states for a branch. */
enum class BranchLifecycleState {
    DRAFT,
    PENDING_APPROVAL,
    ACTIVE,
    SUSPENDED,
    CLOSED,
    ARCHIVED,
}

/** Explicit transitions permitted for a branch. */
enum class BranchLifecycleTransition {
    SUBMIT,
    ACTIVATE,
    SUSPEND,
    REACTIVATE,
    CLOSE,
    ARCHIVE,
}

/** Lifecycle states for a global application user. */
enum class UserLifecycleState {
    DRAFT,
    PENDING_APPROVAL,
    PROVISIONING_IDP,
    INVITED,
    ACTIVE,
    SUSPENDED,
    LOCKED,
    DEACTIVATING,
    DEACTIVATED,
    ARCHIVED,
}

/** Explicit transitions permitted for a global application user. */
enum class UserLifecycleTransition {
    SUBMIT,
    START_IDP_PROVISIONING,
    INVITE,
    ACTIVATE,
    SUSPEND,
    LOCK,
    UNLOCK,
    START_DEACTIVATION,
    COMPLETE_DEACTIVATION,
    ARCHIVE,
}

/** Lifecycle states for an organisation membership. */
enum class MembershipLifecycleState {
    PENDING_APPROVAL,
    ACTIVE,
    SUSPENDED,
    REVOKED,
}

/** Explicit transitions permitted for an organisation membership. */
enum class MembershipLifecycleTransition {
    ACTIVATE,
    SUSPEND,
    REACTIVATE,
    REVOKE,
}

/** Mutable transition target owned by the lifecycle application service. */
class LifecycleAggregate<S : Enum<S>>(
    private val id: UUID,
    override var state: S,
    override val aggregateType: String,
    /** Owning organisation for tenant-scoped aggregates; null for global aggregates like users. */
    val organisationId: UUID? = null,
    /** Row version read alongside [state]; the save must fail if it no longer matches. */
    val rowVersion: Long? = null,
) : Transitionable<S> {
    override val aggregateId: String = id.toString()

    override fun transitionTo(state: S) {
        this.state = state
    }
}

/** Read-only facts required by deterministic lifecycle guards. */
interface LifecyclePrerequisites {
    /** Looks up the current lifecycle state of an organisation. */
    fun organisationState(organisationId: UUID): OrganisationLifecycleState?

    /** Returns whether an active branch assignment would prevent a branch closure. */
    fun branchHasActiveAssignments(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean

    /** Returns whether the user has an active Keycloak identity link. */
    fun userHasKeycloakIdentity(userId: UUID): Boolean

    /** Looks up the current lifecycle state of a global application user. */
    fun userState(userId: UUID): UserLifecycleState?

    /** Returns whether the membership grants at least one active branch assignment. */
    fun membershipHasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean

    /** Returns whether the membership grants at least one active role assignment. */
    fun membershipHasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean
}

/** Foundation graphs. Legal directions are explicit and independent of UI or role names. */
object FoundationLifecycleDefinitions {
    /** Defines the complete explicit organisation lifecycle graph. */
    fun organisationGraph(): OrganisationGraph =
        TransitionGraph(
            listOf(
                definition(
                    OrganisationLifecycleTransition.SUBMIT,
                    OrganisationLifecycleState.DRAFT,
                    OrganisationLifecycleState.PENDING_APPROVAL,
                ),
                definition(
                    OrganisationLifecycleTransition.START_PROVISIONING,
                    OrganisationLifecycleState.PENDING_APPROVAL,
                    OrganisationLifecycleState.PROVISIONING,
                ),
                definition(
                    OrganisationLifecycleTransition.ACTIVATE,
                    OrganisationLifecycleState.PROVISIONING,
                    OrganisationLifecycleState.ACTIVE,
                ),
                definition(
                    OrganisationLifecycleTransition.REJECT,
                    OrganisationLifecycleState.PENDING_APPROVAL,
                    OrganisationLifecycleState.REJECTED,
                ),
                definition(
                    OrganisationLifecycleTransition.SUSPEND,
                    OrganisationLifecycleState.ACTIVE,
                    OrganisationLifecycleState.SUSPENDED,
                ),
                definition(
                    OrganisationLifecycleTransition.REACTIVATE,
                    OrganisationLifecycleState.SUSPENDED,
                    OrganisationLifecycleState.ACTIVE,
                ),
                definition(
                    OrganisationLifecycleTransition.START_DEPROVISIONING,
                    OrganisationLifecycleState.ACTIVE,
                    OrganisationLifecycleState.DEPROVISIONING,
                ),
                definition(
                    OrganisationLifecycleTransition.COMPLETE_DEPROVISIONING,
                    OrganisationLifecycleState.DEPROVISIONING,
                    OrganisationLifecycleState.DEPROVISIONED,
                ),
                definition(
                    OrganisationLifecycleTransition.ARCHIVE,
                    OrganisationLifecycleState.DEPROVISIONED,
                    OrganisationLifecycleState.ARCHIVED,
                ),
            ),
        )

    /** Defines the branch lifecycle graph and its organisation/assignment guards. */
    fun branchGraph(
        prerequisites: LifecyclePrerequisites,
        organisationId: UUID,
        branchId: UUID,
    ): BranchGraph =
        TransitionGraph(
            listOf(
                definition(
                    BranchLifecycleTransition.SUBMIT,
                    BranchLifecycleState.DRAFT,
                    BranchLifecycleState.PENDING_APPROVAL,
                ),
                definition(
                    BranchLifecycleTransition.ACTIVATE,
                    BranchLifecycleState.PENDING_APPROVAL,
                    BranchLifecycleState.ACTIVE,
                    guards =
                        listOf(
                            branchActivationGuard(prerequisites, organisationId),
                        ),
                ),
                definition(
                    BranchLifecycleTransition.SUSPEND,
                    BranchLifecycleState.ACTIVE,
                    BranchLifecycleState.SUSPENDED,
                ),
                definition(
                    BranchLifecycleTransition.REACTIVATE,
                    BranchLifecycleState.SUSPENDED,
                    BranchLifecycleState.ACTIVE,
                    guards =
                        listOf(
                            branchActivationGuard(prerequisites, organisationId, "reactivated"),
                        ),
                ),
                definition(
                    BranchLifecycleTransition.CLOSE,
                    BranchLifecycleState.ACTIVE,
                    BranchLifecycleState.CLOSED,
                    guards =
                        listOf(
                            branchClosureGuard(prerequisites, organisationId, branchId),
                        ),
                ),
                definition(
                    BranchLifecycleTransition.ARCHIVE,
                    BranchLifecycleState.CLOSED,
                    BranchLifecycleState.ARCHIVED,
                ),
            ),
        )

    /** Defines the user lifecycle graph and its Keycloak-link guard. */
    fun userGraph(
        prerequisites: LifecyclePrerequisites,
        userId: UUID,
    ): UserGraph =
        TransitionGraph(
            listOf(
                definition(
                    UserLifecycleTransition.SUBMIT,
                    UserLifecycleState.DRAFT,
                    UserLifecycleState.PENDING_APPROVAL,
                ),
                definition(
                    UserLifecycleTransition.START_IDP_PROVISIONING,
                    UserLifecycleState.PENDING_APPROVAL,
                    UserLifecycleState.PROVISIONING_IDP,
                ),
                definition(
                    UserLifecycleTransition.INVITE,
                    UserLifecycleState.PROVISIONING_IDP,
                    UserLifecycleState.INVITED,
                ),
                definition(
                    UserLifecycleTransition.ACTIVATE,
                    UserLifecycleState.INVITED,
                    UserLifecycleState.ACTIVE,
                    guards =
                        listOf(
                            userIdentityGuard(prerequisites, userId),
                        ),
                ),
                definition(
                    UserLifecycleTransition.SUSPEND,
                    UserLifecycleState.ACTIVE,
                    UserLifecycleState.SUSPENDED,
                ),
                definition(
                    UserLifecycleTransition.LOCK,
                    UserLifecycleState.ACTIVE,
                    UserLifecycleState.LOCKED,
                ),
                definition(
                    UserLifecycleTransition.UNLOCK,
                    UserLifecycleState.LOCKED,
                    UserLifecycleState.ACTIVE,
                ),
                definition(
                    UserLifecycleTransition.START_DEACTIVATION,
                    UserLifecycleState.ACTIVE,
                    UserLifecycleState.DEACTIVATING,
                ),
                definition(
                    UserLifecycleTransition.COMPLETE_DEACTIVATION,
                    UserLifecycleState.DEACTIVATING,
                    UserLifecycleState.DEACTIVATED,
                ),
                definition(
                    UserLifecycleTransition.ARCHIVE,
                    UserLifecycleState.DEACTIVATED,
                    UserLifecycleState.ARCHIVED,
                ),
            ),
        )

    /** Defines the membership lifecycle graph and its access-readiness guards. */
    fun membershipGraph(
        prerequisites: LifecyclePrerequisites,
        organisationId: UUID,
        userId: UUID,
    ): MembershipGraph =
        TransitionGraph(
            listOf(
                definition(
                    MembershipLifecycleTransition.ACTIVATE,
                    MembershipLifecycleState.PENDING_APPROVAL,
                    MembershipLifecycleState.ACTIVE,
                    guards =
                        listOf(
                            guard(
                                {
                                    prerequisites.organisationState(organisationId) ==
                                        OrganisationLifecycleState.ACTIVE
                                },
                                "Membership can be activated only for an active organisation.",
                            ),
                            guard(
                                {
                                    prerequisites.userState(userId) in
                                        setOf(UserLifecycleState.ACTIVE, UserLifecycleState.INVITED)
                                },
                                "Membership requires an active or invited user account.",
                            ),
                            guard(
                                {
                                    prerequisites.membershipHasActiveBranchAssignment(
                                        organisationId,
                                        userId,
                                    )
                                },
                                "Membership requires an active branch assignment.",
                            ),
                            guard(
                                {
                                    prerequisites.membershipHasActiveRoleAssignment(
                                        organisationId,
                                        userId,
                                    )
                                },
                                "Membership requires an active role assignment.",
                            ),
                        ),
                ),
                definition(
                    MembershipLifecycleTransition.SUSPEND,
                    MembershipLifecycleState.ACTIVE,
                    MembershipLifecycleState.SUSPENDED,
                ),
                definition(
                    MembershipLifecycleTransition.REACTIVATE,
                    MembershipLifecycleState.SUSPENDED,
                    MembershipLifecycleState.ACTIVE,
                ),
                definition(
                    MembershipLifecycleTransition.REVOKE,
                    MembershipLifecycleState.ACTIVE,
                    MembershipLifecycleState.REVOKED,
                ),
            ),
        )

    private fun <S : Enum<S>, T : Enum<T>> definition(
        transition: T,
        from: S,
        to: S,
        guards: List<TransitionGuard<S, T, LifecycleAggregate<S>>> = emptyList(),
    ): TransitionDefinition<S, T, LifecycleAggregate<S>> =
        TransitionDefinition(transition, from, to, guards = guards)

    private fun <S : Enum<S>, T : Enum<T>> guard(
        predicate: () -> Boolean,
        message: String,
    ): TransitionGuard<S, T, LifecycleAggregate<S>> =
        TransitionGuard {
            require(predicate(), message)
        }

    private fun require(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) {
            throw TransitionGuardException(message)
        }
    }

    const val ASSIGNMENTS_HANDLED = "assignmentsHandled"
}

private typealias BranchLifecycleGuard =
    TransitionGuard<
        BranchLifecycleState,
        BranchLifecycleTransition,
        LifecycleAggregate<BranchLifecycleState>,
    >

private typealias UserLifecycleGuard =
    TransitionGuard<
        UserLifecycleState,
        UserLifecycleTransition,
        LifecycleAggregate<UserLifecycleState>,
    >

private fun branchActivationGuard(
    prerequisites: LifecyclePrerequisites,
    organisationId: UUID,
    action: String = "activated",
): BranchLifecycleGuard =
    TransitionGuard {
        requireLifecycleGuard(
            prerequisites.organisationState(organisationId) in
                setOf(
                    OrganisationLifecycleState.ACTIVE,
                    OrganisationLifecycleState.PROVISIONING,
                ),
            "A branch can be $action only for an active or provisioning organisation.",
        )
    }

private fun branchClosureGuard(
    prerequisites: LifecyclePrerequisites,
    organisationId: UUID,
    branchId: UUID,
): BranchLifecycleGuard =
    TransitionGuard { context ->
        val assignmentsHandled =
            context.command.metadata[FoundationLifecycleDefinitions.ASSIGNMENTS_HANDLED] == true
        requireLifecycleGuard(
            assignmentsHandled ||
                !prerequisites.branchHasActiveAssignments(organisationId, branchId),
            "Reassign or revoke active branch assignments before closing this branch.",
        )
    }

private fun userIdentityGuard(
    prerequisites: LifecyclePrerequisites,
    userId: UUID,
): UserLifecycleGuard =
    TransitionGuard {
        requireLifecycleGuard(
            prerequisites.userHasKeycloakIdentity(userId),
            "A user can be activated only after Keycloak identity linking is complete.",
        )
    }

private fun requireLifecycleGuard(
    condition: Boolean,
    message: String,
) {
    if (!condition) {
        throw TransitionGuardException(message)
    }
}

typealias OrganisationGraph =
    TransitionGraph<
        OrganisationLifecycleState,
        OrganisationLifecycleTransition,
        LifecycleAggregate<OrganisationLifecycleState>,
    >

typealias BranchGraph =
    TransitionGraph<
        BranchLifecycleState,
        BranchLifecycleTransition,
        LifecycleAggregate<BranchLifecycleState>,
    >

typealias UserGraph =
    TransitionGraph<
        UserLifecycleState,
        UserLifecycleTransition,
        LifecycleAggregate<UserLifecycleState>,
    >

typealias MembershipGraph =
    TransitionGraph<
        MembershipLifecycleState,
        MembershipLifecycleTransition,
        LifecycleAggregate<MembershipLifecycleState>,
    >
