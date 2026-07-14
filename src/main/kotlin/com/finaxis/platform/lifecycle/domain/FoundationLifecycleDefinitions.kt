package com.finaxis.platform.lifecycle.domain

import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.InternalTransitionEvent
import com.finaxis.platform.common.transitions.TransitionDefinition
import com.finaxis.platform.common.transitions.TransitionEventFactory
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
    START_SUSPENDED_DEPROVISIONING,
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
    SUSPEND_DRAFT,
    SUSPEND_PENDING_APPROVAL,
    CONFIRM_SUSPENDED,
    REACTIVATE,
    CLOSE,
    CLOSE_SUSPENDED,
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
    REACTIVATE,
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
    REVOKE_PENDING,
    REVOKE_SUSPENDED,
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

    /** Reason attached to the status currently being persisted, when the command supplied one. */
    var transitionReason: String? = null

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

    /** Returns whether an active child branch prevents closure of its parent. */
    fun branchHasActiveChildren(
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
            organisationProvisioningDefinitions() + organisationDeprovisioningDefinitions(),
        )

    private fun organisationProvisioningDefinitions(): List<
        TransitionDefinition<
            OrganisationLifecycleState,
            OrganisationLifecycleTransition,
            LifecycleAggregate<OrganisationLifecycleState>,
        >,
    > =
        listOf(
            definition(
                OrganisationLifecycleTransition.SUBMIT,
                OrganisationLifecycleState.DRAFT,
                OrganisationLifecycleState.PENDING_APPROVAL,
                externalizedEventFactories(ORGANISATION_APPROVAL_REQUESTED_TARGET),
            ),
            definition(
                OrganisationLifecycleTransition.START_PROVISIONING,
                OrganisationLifecycleState.PENDING_APPROVAL,
                OrganisationLifecycleState.PROVISIONING,
                internalEventFactories(),
            ),
            definition(
                OrganisationLifecycleTransition.ACTIVATE,
                OrganisationLifecycleState.PROVISIONING,
                OrganisationLifecycleState.ACTIVE,
                externalizedEventFactories(ORGANISATION_ACTIVATED_TARGET),
            ),
            definition(
                OrganisationLifecycleTransition.REJECT,
                OrganisationLifecycleState.PENDING_APPROVAL,
                OrganisationLifecycleState.REJECTED,
                externalizedEventFactories(ORGANISATION_REJECTED_TARGET),
            ),
            definition(
                OrganisationLifecycleTransition.SUSPEND,
                OrganisationLifecycleState.ACTIVE,
                OrganisationLifecycleState.SUSPENDED,
                externalizedEventFactories(ORGANISATION_SUSPENDED_TARGET),
            ),
            definition(
                OrganisationLifecycleTransition.REACTIVATE,
                OrganisationLifecycleState.SUSPENDED,
                OrganisationLifecycleState.ACTIVE,
                externalizedEventFactories(ORGANISATION_REACTIVATED_TARGET),
            ),
        )

    private fun organisationDeprovisioningDefinitions(): List<
        TransitionDefinition<
            OrganisationLifecycleState,
            OrganisationLifecycleTransition,
            LifecycleAggregate<OrganisationLifecycleState>,
        >,
    > =
        listOf(
            definition(
                OrganisationLifecycleTransition.START_DEPROVISIONING,
                OrganisationLifecycleState.ACTIVE,
                OrganisationLifecycleState.DEPROVISIONING,
                internalEventFactories(),
            ),
            definition(
                OrganisationLifecycleTransition.START_SUSPENDED_DEPROVISIONING,
                OrganisationLifecycleState.SUSPENDED,
                OrganisationLifecycleState.DEPROVISIONING,
                internalEventFactories(),
            ),
            definition(
                OrganisationLifecycleTransition.COMPLETE_DEPROVISIONING,
                OrganisationLifecycleState.DEPROVISIONING,
                OrganisationLifecycleState.DEPROVISIONED,
                externalizedEventFactories(ORGANISATION_DEPROVISIONED_TARGET),
            ),
            definition(
                OrganisationLifecycleTransition.ARCHIVE,
                OrganisationLifecycleState.DEPROVISIONED,
                OrganisationLifecycleState.ARCHIVED,
            ),
        )

    /** Defines the branch lifecycle graph and its organisation/assignment guards. */
    fun branchGraph(
        prerequisites: LifecyclePrerequisites,
        organisationId: UUID,
        branchId: UUID,
    ): BranchGraph =
        TransitionGraph(
            BranchDefinitions.approval(prerequisites, organisationId) +
                BranchDefinitions.operational(prerequisites, organisationId, branchId),
        )

    private object BranchDefinitions {
        fun approval(
            prerequisites: LifecyclePrerequisites,
            organisationId: UUID,
        ): List<
            TransitionDefinition<
                BranchLifecycleState,
                BranchLifecycleTransition,
                LifecycleAggregate<BranchLifecycleState>,
            >,
        > =
            listOf(
                definition(
                    BranchLifecycleTransition.SUBMIT,
                    BranchLifecycleState.DRAFT,
                    BranchLifecycleState.PENDING_APPROVAL,
                    externalizedEventFactories(BRANCH_APPROVAL_REQUESTED_TARGET),
                ),
                definition(
                    BranchLifecycleTransition.ACTIVATE,
                    BranchLifecycleState.PENDING_APPROVAL,
                    BranchLifecycleState.ACTIVE,
                    externalizedEventFactories(BRANCH_ACTIVATED_TARGET),
                    guards =
                        listOf(
                            branchActivationGuard(prerequisites, organisationId),
                        ),
                ),
            )

        fun operational(
            prerequisites: LifecyclePrerequisites,
            organisationId: UUID,
            branchId: UUID,
        ): List<
            TransitionDefinition<
                BranchLifecycleState,
                BranchLifecycleTransition,
                LifecycleAggregate<BranchLifecycleState>,
            >,
        > =
            listOf(
                definition(
                    BranchLifecycleTransition.SUSPEND,
                    BranchLifecycleState.ACTIVE,
                    BranchLifecycleState.SUSPENDED,
                    externalizedEventFactories(BRANCH_SUSPENDED_TARGET),
                ),
                definition(
                    BranchLifecycleTransition.SUSPEND_DRAFT,
                    BranchLifecycleState.DRAFT,
                    BranchLifecycleState.SUSPENDED,
                    internalEventFactories(),
                ),
                definition(
                    BranchLifecycleTransition.SUSPEND_PENDING_APPROVAL,
                    BranchLifecycleState.PENDING_APPROVAL,
                    BranchLifecycleState.SUSPENDED,
                    internalEventFactories(),
                ),
                definition(
                    BranchLifecycleTransition.CONFIRM_SUSPENDED,
                    BranchLifecycleState.SUSPENDED,
                    BranchLifecycleState.SUSPENDED,
                    internalEventFactories(),
                ),
                definition(
                    BranchLifecycleTransition.REACTIVATE,
                    BranchLifecycleState.SUSPENDED,
                    BranchLifecycleState.ACTIVE,
                    externalizedEventFactories(BRANCH_REACTIVATED_TARGET),
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
                    eventFactories = externalizedEventFactories(BRANCH_CLOSED_TARGET),
                ),
                definition(
                    BranchLifecycleTransition.CLOSE_SUSPENDED,
                    BranchLifecycleState.SUSPENDED,
                    BranchLifecycleState.CLOSED,
                    guards = listOf(branchClosureGuard(prerequisites, organisationId, branchId)),
                    eventFactories = externalizedEventFactories(BRANCH_CLOSED_TARGET),
                ),
                definition(
                    BranchLifecycleTransition.ARCHIVE,
                    BranchLifecycleState.CLOSED,
                    BranchLifecycleState.ARCHIVED,
                ),
            )
    }

    /** Defines the user lifecycle graph and its Keycloak-link guard. */
    fun userGraph(
        prerequisites: LifecyclePrerequisites,
        userId: UUID,
    ): UserGraph =
        TransitionGraph(
            userProvisioningDefinitions(prerequisites, userId) +
                listOf(
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
                    listOf(membershipActivationEventFactory()),
                    membershipActivationGuards(prerequisites, organisationId, userId),
                ),
                definition(
                    MembershipLifecycleTransition.SUSPEND,
                    MembershipLifecycleState.ACTIVE,
                    MembershipLifecycleState.SUSPENDED,
                    internalEventFactories(),
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
                definition(
                    MembershipLifecycleTransition.REVOKE_PENDING,
                    MembershipLifecycleState.PENDING_APPROVAL,
                    MembershipLifecycleState.REVOKED,
                ),
                definition(
                    MembershipLifecycleTransition.REVOKE_SUSPENDED,
                    MembershipLifecycleState.SUSPENDED,
                    MembershipLifecycleState.REVOKED,
                ),
            ),
        )

    private fun <S : Enum<S>, T : Enum<T>> definition(
        transition: T,
        from: S,
        to: S,
        eventFactories: List<TransitionEventFactory<S, T, LifecycleAggregate<S>>> = emptyList(),
        guards: List<TransitionGuard<S, T, LifecycleAggregate<S>>> = emptyList(),
    ): TransitionDefinition<S, T, LifecycleAggregate<S>> =
        TransitionDefinition(transition, from, to, guards = guards, eventFactories = eventFactories)
}

private fun <S : Enum<S>, T : Enum<T>> internalEventFactories():
    List<TransitionEventFactory<S, T, LifecycleAggregate<S>>> =
    listOf(
        TransitionEventFactory { context ->
            InternalTransitionEvent(
                aggregateType = context.aggregate.aggregateType,
                aggregateId = context.aggregate.aggregateId,
                transition = context.transition.name,
                fromState = context.fromState.name,
                toState = context.toState.name,
                actor = context.actor,
                occurredAt = context.occurredAt,
            )
        },
    )

private fun <S : Enum<S>, T : Enum<T>> externalizedEventFactories(
    target: String,
): List<TransitionEventFactory<S, T, LifecycleAggregate<S>>> =
    listOf(
        TransitionEventFactory { context ->
            ExternalizedTransitionEvent(
                target = target,
                aggregateType = context.aggregate.aggregateType,
                aggregateId = context.aggregate.aggregateId,
                transition = context.transition.name,
                fromState = context.fromState.name,
                toState = context.toState.name,
                actor = context.actor,
                occurredAt = context.occurredAt,
                metadata = context.command.metadata,
            )
        },
    )

private fun membershipActivationEventFactory(): TransitionEventFactory<
    MembershipLifecycleState,
    MembershipLifecycleTransition,
    LifecycleAggregate<MembershipLifecycleState>,
> =
    TransitionEventFactory { context ->
        ExternalizedTransitionEvent(
            target = MEMBERSHIP_ACTIVATED_TARGET,
            aggregateType = context.aggregate.aggregateType,
            aggregateId = context.aggregate.aggregateId,
            transition = context.transition.name,
            fromState = context.fromState.name,
            toState = context.toState.name,
            actor = context.actor,
            occurredAt = context.occurredAt,
            metadata =
                mapOf(
                    MEMBERSHIP_ID to context.aggregate.aggregateId,
                    USER_ID to requireNotNull(context.command.metadata[USER_ID]),
                    ORGANISATION_ID to requireNotNull(context.command.metadata[ORGANISATION_ID]),
                    BRANCH_ID to context.command.metadata[BRANCH_ID],
                    OCCURRED_AT to context.occurredAt.toString(),
                ),
        )
    }

private const val MEMBERSHIP_ACTIVATED_TARGET = "finaxis.lifecycle.membership.activated"
private const val ORGANISATION_APPROVAL_REQUESTED_TARGET =
    "finaxis.lifecycle.organisation.approval-requested"
private const val ORGANISATION_ACTIVATED_TARGET = "finaxis.lifecycle.organisation.activated"
private const val ORGANISATION_REJECTED_TARGET = "finaxis.lifecycle.organisation.rejected"
private const val ORGANISATION_DEPROVISIONED_TARGET = "finaxis.lifecycle.organisation.deprovisioned"
private const val ORGANISATION_SUSPENDED_TARGET = "finaxis.lifecycle.organisation.suspended"
private const val ORGANISATION_REACTIVATED_TARGET = "finaxis.lifecycle.organisation.reactivated"
private const val BRANCH_APPROVAL_REQUESTED_TARGET = "finaxis.lifecycle.branch.approval-requested"
private const val BRANCH_ACTIVATED_TARGET = "finaxis.lifecycle.branch.activated"
private const val BRANCH_SUSPENDED_TARGET = "finaxis.lifecycle.branch.suspended"
private const val BRANCH_REACTIVATED_TARGET = "finaxis.lifecycle.branch.reactivated"
private const val BRANCH_CLOSED_TARGET = "finaxis.lifecycle.branch.closed"
private const val MEMBERSHIP_ID = "membershipId"
private const val USER_ID = "userId"
private const val ORGANISATION_ID = "organisationId"
private const val BRANCH_ID = "branchId"
private const val OCCURRED_AT = "occurredAt"

private fun userProvisioningDefinitions(
    prerequisites: LifecyclePrerequisites,
    userId: UUID,
): List<
    TransitionDefinition<
        UserLifecycleState,
        UserLifecycleTransition,
        LifecycleAggregate<UserLifecycleState>,
    >,
> =
    listOf(
        TransitionDefinition(
            transition = UserLifecycleTransition.SUBMIT,
            from = UserLifecycleState.DRAFT,
            to = UserLifecycleState.PENDING_APPROVAL,
        ),
        internalDefinition(
            UserLifecycleTransition.START_IDP_PROVISIONING,
            UserLifecycleState.PENDING_APPROVAL,
            UserLifecycleState.PROVISIONING_IDP,
        ),
        internalDefinition(
            UserLifecycleTransition.INVITE,
            UserLifecycleState.PROVISIONING_IDP,
            UserLifecycleState.INVITED,
        ),
        internalDefinition(
            UserLifecycleTransition.ACTIVATE,
            UserLifecycleState.INVITED,
            UserLifecycleState.ACTIVE,
            guards = listOf(userIdentityGuard(prerequisites, userId)),
        ),
        internalDefinition(
            UserLifecycleTransition.SUSPEND,
            UserLifecycleState.ACTIVE,
            UserLifecycleState.SUSPENDED,
        ),
        internalDefinition(
            UserLifecycleTransition.REACTIVATE,
            UserLifecycleState.SUSPENDED,
            UserLifecycleState.ACTIVE,
            guards = listOf(userIdentityGuard(prerequisites, userId)),
        ),
    )

private fun membershipActivationGuards(
    prerequisites: LifecyclePrerequisites,
    organisationId: UUID,
    userId: UUID,
): List<
    TransitionGuard<
        MembershipLifecycleState,
        MembershipLifecycleTransition,
        LifecycleAggregate<MembershipLifecycleState>,
    >,
> =
    listOf(
        TransitionGuard {
            requireLifecycleGuard(
                prerequisites.organisationState(
                    organisationId,
                ) == OrganisationLifecycleState.ACTIVE,
                "Membership can be activated only for an active organisation.",
            )
        },
        TransitionGuard {
            requireLifecycleGuard(
                prerequisites.userState(userId) in
                    setOf(UserLifecycleState.ACTIVE, UserLifecycleState.INVITED),
                "Membership requires an active or invited user account.",
            )
        },
        TransitionGuard {
            requireLifecycleGuard(
                prerequisites.membershipHasActiveBranchAssignment(organisationId, userId),
                "Membership requires an active branch assignment.",
            )
        },
        TransitionGuard {
            requireLifecycleGuard(
                prerequisites.membershipHasActiveRoleAssignment(organisationId, userId),
                "Membership requires an active role assignment.",
            )
        },
    )

private fun <S : Enum<S>, T : Enum<T>> internalDefinition(
    transition: T,
    from: S,
    to: S,
    guards: List<TransitionGuard<S, T, LifecycleAggregate<S>>> = emptyList(),
): TransitionDefinition<S, T, LifecycleAggregate<S>> =
    TransitionDefinition(
        transition = transition,
        from = from,
        to = to,
        guards = guards,
        eventFactories = internalEventFactories(),
    )

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
        requireLifecycleGuard(
            !prerequisites.branchHasActiveAssignments(organisationId, branchId),
            "Reassign or revoke active branch assignments before closing this branch.",
        )
        requireLifecycleGuard(
            !prerequisites.branchHasActiveChildren(organisationId, branchId),
            "Close or re-parent active child branches before closing this branch.",
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
