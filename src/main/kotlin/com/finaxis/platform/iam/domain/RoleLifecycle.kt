package com.finaxis.platform.iam.domain

import com.finaxis.platform.common.transitions.TransitionDefinition
import com.finaxis.platform.common.transitions.TransitionGraph
import com.finaxis.platform.common.transitions.TransitionGuard
import com.finaxis.platform.common.transitions.TransitionGuardException
import com.finaxis.platform.common.transitions.Transitionable
import java.util.UUID

/**
 * The named state changes a role can undergo.
 *
 * The transition graph maps one name to exactly one source state, so archiving, which is legal
 * from two states, has two names: [ARCHIVE] from `ACTIVE` and [ARCHIVE_DISABLED] from `DISABLED`
 * (the membership lifecycle's `REVOKE` and `REVOKE_PENDING` are the precedent). Both write the one
 * audit action `role.archive`; the transition log keeps the exact edge.
 */
enum class RoleTransition {
    /** `DISABLED` back to `ACTIVE`. Audit action `role.activate`. */
    ACTIVATE,

    /** `ACTIVE` to `DISABLED`. Audit action `role.deactivate`. */
    DEACTIVATE,

    /** `ACTIVE` to the terminal `ARCHIVED`. Audit action `role.archive`. */
    ARCHIVE,

    /** `DISABLED` to the terminal `ARCHIVED`. Audit action `role.archive`. */
    ARCHIVE_DISABLED,
}

/**
 * A role as the transition executor sees it.
 *
 * Carries its organisation because the archive guard counts that tenant's assignments. The
 * transition log's own organisation comes from the `TransitionCommand` metadata, as for every
 * other aggregate, so the caller supplies it there too.
 */
class RoleAggregate(
    val roleId: UUID,
    val organisationId: UUID,
    initialStatus: RoleStatus,
) : Transitionable<RoleStatus> {
    override val aggregateId: String = roleId.toString()

    override val aggregateType: String = AGGREGATE_TYPE

    override var state: RoleStatus = initialStatus
        private set

    override fun transitionTo(state: RoleStatus) {
        this.state = state
    }

    /** Identifies role rows in the dispatching transition-log repository. */
    companion object {
        const val AGGREGATE_TYPE = "ROLE"
    }
}

/** The one fact the archive guard needs from the outside world. */
fun interface RoleArchivePrerequisites {
    /** How many ACTIVE assignments [roleId] has in [organisationId]. */
    fun activeAssignmentCount(
        organisationId: UUID,
        roleId: UUID,
    ): Long
}

/**
 * The role state machine (ADR 0035, superseding ADR 0034 decision 1).
 *
 * `ACTIVE` and `DISABLED` are reversible configuration states. `ARCHIVED` is terminal: no edge
 * leaves it, so a retired role can never be revived by accident and its code stays reserved
 * (`uq_role_organisation_code` ignores status). Archiving is guarded by a count of the role's
 * ACTIVE assignments, which is only sound if the caller holds the role's row lock, as
 * `RoleManagementService` does, and the paths that create an assignment take the same lock.
 *
 * No `eventFactories`: nothing consumes a role status change, and publishing a broker event merely
 * because a transition occurred is what ADR 0002 warns against.
 */
object RoleLifecycle {
    /** Builds the graph around the assignment-count prerequisite. */
    fun graph(
        prerequisites: RoleArchivePrerequisites,
    ): TransitionGraph<RoleStatus, RoleTransition, RoleAggregate> {
        val noActiveAssignments = noActiveAssignmentsGuard(prerequisites)
        return TransitionGraph(
            listOf(
                definition(RoleTransition.ACTIVATE, RoleStatus.DISABLED, RoleStatus.ACTIVE),
                definition(RoleTransition.DEACTIVATE, RoleStatus.ACTIVE, RoleStatus.DISABLED),
                definition(
                    RoleTransition.ARCHIVE,
                    RoleStatus.ACTIVE,
                    RoleStatus.ARCHIVED,
                    noActiveAssignments,
                ),
                definition(
                    RoleTransition.ARCHIVE_DISABLED,
                    RoleStatus.DISABLED,
                    RoleStatus.ARCHIVED,
                    noActiveAssignments,
                ),
            ),
        )
    }

    private fun noActiveAssignmentsGuard(
        prerequisites: RoleArchivePrerequisites,
    ): TransitionGuard<RoleStatus, RoleTransition, RoleAggregate> =
        TransitionGuard { context ->
            val count =
                prerequisites.activeAssignmentCount(
                    context.aggregate.organisationId,
                    context.aggregate.roleId,
                )
            if (count > 0) {
                val (noun, pronoun) =
                    if (count == 1L) "assignment" to "it" else "assignments" to "them"
                throw TransitionGuardException(
                    "The role has $count active $noun; revoke $pronoun before archiving it. " +
                        "Archiving is final and the role's code stays reserved.",
                )
            }
        }

    private fun definition(
        transition: RoleTransition,
        from: RoleStatus,
        to: RoleStatus,
        vararg guards: TransitionGuard<RoleStatus, RoleTransition, RoleAggregate>,
    ) = TransitionDefinition<RoleStatus, RoleTransition, RoleAggregate>(
        transition = transition,
        from = from,
        to = to,
        guards = guards.toList(),
    )
}
