package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.transitions.TransitionException
import com.finaxis.platform.common.transitions.TransitionExecution
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.common.transitions.TransitionGraph
import com.finaxis.platform.common.transitions.TransitionGuardException
import com.finaxis.platform.common.transitions.TransitionNotAllowedException
import com.finaxis.platform.common.transitions.TransitionResult
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.FoundationLifecycleDefinitions
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.LifecyclePrerequisites
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleTransition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Transactional lifecycle application service. All state changes use the shared TransitionExecutor;
 * the service is the only path that persists lifecycle status, logs, and audit records.
 */
@Service
class FoundationLifecycleService(
    private val transitionExecutor: TransitionExecutor,
    private val reader: FoundationLifecycleReader,
    private val writer: FoundationLifecycleWriter,
    private val prerequisites: LifecyclePrerequisites,
    private val auditService: AuditService,
) {
    /** Applies an explicit transition to an organisation lifecycle aggregate. */
    @Transactional
    fun transition(command: OrganisationTransitionCommand): OrganisationTransitionResult {
        val aggregate =
            requireNotNull(reader.findOrganisation(command.organisationId)) {
                "Organisation was not found."
            }
        aggregate.transitionReason = command.command.reason
        return executeTransition(
            aggregate = aggregate,
            transition = command.transition,
            graph = FoundationLifecycleDefinitions.organisationGraph(),
            command = contextualise(command.command, command.organisationId, null),
            persist = writer::saveOrganisation,
            organisationId = command.organisationId,
        )
    }

    /** Applies an explicit transition to a branch scoped to an organisation. */
    @Transactional
    fun transition(command: BranchTransitionCommand): BranchTransitionResult {
        val aggregate =
            requireNotNull(reader.findBranch(command.organisationId, command.branchId)) {
                "Branch was not found in the selected organisation."
            }
        aggregate.transitionReason = command.command.reason
        return executeTransition(
            aggregate = aggregate,
            transition = command.transition,
            graph =
                FoundationLifecycleDefinitions.branchGraph(
                    prerequisites,
                    command.organisationId,
                    command.branchId,
                ),
            command = contextualise(command.command, command.organisationId, command.branchId),
            persist = writer::saveBranch,
            organisationId = command.organisationId,
        )
    }

    /** Applies an explicit transition to a global user in an organisation workflow. */
    @Transactional
    fun transition(command: UserTransitionCommand): UserTransitionResult {
        val aggregate =
            requireNotNull(reader.findUser(command.userId)) {
                "User account was not found."
            }
        aggregate.transitionReason = command.command.reason
        return executeTransition(
            aggregate = aggregate,
            transition = command.transition,
            graph = FoundationLifecycleDefinitions.userGraph(prerequisites, command.userId),
            command = contextualise(command.command, command.organisationId, command.branchId),
            persist = writer::saveUser,
            organisationId = command.organisationId,
        )
    }

    /** Applies an explicit transition to an organisation membership. */
    @Transactional
    fun transition(command: MembershipTransitionCommand): MembershipTransitionResult {
        val aggregate =
            requireNotNull(
                reader.findMembership(command.organisationId, command.membershipId),
            ) {
                "Membership was not found in the selected organisation."
            }
        aggregate.transitionReason = command.command.reason
        val userId = aggregateUserId(command.organisationId, command.membershipId)
        return executeTransition(
            aggregate = aggregate,
            transition = command.transition,
            graph =
                FoundationLifecycleDefinitions.membershipGraph(
                    prerequisites,
                    command.organisationId,
                    userId,
                ),
            command =
                contextualise(command.command, command.organisationId, command.branchId, userId),
            persist = writer::saveMembership,
            organisationId = command.organisationId,
        )
    }

    private fun aggregateUserId(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID =
        reader.membershipUserId(organisationId, membershipId)
            ?: throw IllegalArgumentException(
                "Membership was not found in the selected organisation.",
            )

    /**
     * Executes a transition and always leaves an audit trail: a success record when the
     * transition commits, or a rejected/failed record when a guard, policy, or FSM check throws.
     */
    private fun <S : Enum<S>, T : Enum<T>> executeTransition(
        aggregate: LifecycleAggregate<S>,
        transition: T,
        graph: TransitionGraph<S, T, LifecycleAggregate<S>>,
        command: TransitionCommand,
        persist: (LifecycleAggregate<S>) -> LifecycleAggregate<S>,
        organisationId: UUID,
    ): TransitionResult<S, T, LifecycleAggregate<S>> {
        val fromState = aggregate.state.name
        val result =
            try {
                transitionExecutor.execute(
                    TransitionExecution(
                        aggregate = aggregate,
                        transition = transition,
                        graph = graph,
                        command = command,
                        actor = actor(),
                        persist = persist,
                    ),
                )
            } catch (ex: TransitionException) {
                recordTransitionFailure(
                    aggregate,
                    transition,
                    fromState,
                    organisationId,
                    command,
                    ex,
                )
                if (ex is TransitionNotAllowedException) {
                    // The current resource state does not allow this transition (e.g. a caller
                    // retrying a mutation on an already-terminal or already-transitioned
                    // resource). This is a foreseeable client-facing conflict, not a server fault
                    // - map it to the same safe ApplicationException subtype every other lifecycle
                    // state-conflict already uses, instead of leaking the internal FSM exception
                    // type to ApiExceptionHandler's generic 500 fallback.
                    throw ConflictException()
                }
                throw ex
            }
        recordOutcome(result, organisationId)
        return result
    }

    private fun <S : Enum<S>, T : Enum<T>> recordTransitionFailure(
        aggregate: LifecycleAggregate<S>,
        transition: T,
        fromState: String,
        organisationId: UUID,
        command: TransitionCommand,
        ex: TransitionException,
    ) {
        // Recorded in a new transaction (recordIndependently) because this method's caller
        // rethrows ex, which rolls back the enclosing @Transactional transition(...) call - without
        // that isolation this audit row would be rolled back along with it.
        auditService.recordIndependently(
            auditService.lifecycleTransitionCommand(
                actorId = RequestContexts.actor()?.userId ?: SystemActor.ID,
                tenantId = organisationId,
                aggregateType = aggregate.aggregateType,
                aggregateId = aggregate.aggregateId,
                transition = transition.name,
                fromState = fromState,
                toState = TRANSITION_NOT_REACHED,
                outcome =
                    if (ex is TransitionGuardException) {
                        AuditOutcome.DENIED
                    } else {
                        AuditOutcome.FAILURE
                    },
                reason = ex.message,
                requestId = command.requestId,
            ),
        )
    }

    private fun <S : Enum<S>, T : Enum<T>> recordOutcome(
        result: TransitionResult<S, T, LifecycleAggregate<S>>,
        organisationId: UUID,
    ) {
        auditService.recordLifecycleTransition(
            actorId = RequestContexts.actor()?.userId ?: SystemActor.ID,
            tenantId = organisationId,
            aggregateType = result.aggregate.aggregateType,
            aggregateId = result.aggregate.aggregateId,
            transition = result.transition.name,
            fromState = result.fromState.name,
            toState = result.toState.name,
            outcome = AuditOutcome.SUCCESS,
            reason = result.log.reason,
            requestId = result.log.requestId,
        )
    }

    private fun contextualise(
        command: TransitionCommand,
        organisationId: UUID,
        branchId: UUID?,
        userId: UUID? = null,
    ): TransitionCommand =
        command.copy(
            metadata =
                command.metadata +
                    mapOf(
                        ORGANISATION_ID to organisationId.toString(),
                        BRANCH_ID to branchId?.toString(),
                    ) +
                    (userId?.let { mapOf(USER_ID to it.toString()) } ?: emptyMap()),
        )

    private fun actor(): TransitionActor =
        RequestContexts.actor()?.let { actor ->
            if (SystemActor.isSystemActor(actor.userId)) {
                TransitionActor(SYSTEM, actor.userId.toString(), SYSTEM)
            } else {
                TransitionActor(USER, actor.userId.toString(), actor.username)
            }
        } ?: TransitionActor(SYSTEM, SystemActor.ID.toString(), SYSTEM)

    private companion object {
        const val ORGANISATION_ID = "organisationId"
        const val BRANCH_ID = "branchId"
        const val USER_ID = "userId"
        const val TRANSITION_NOT_REACHED = "N/A"
        const val USER = "USER"
        const val SYSTEM = "SYSTEM"
    }
}

typealias OrganisationTransitionResult =
    TransitionResult<
        OrganisationLifecycleState,
        OrganisationLifecycleTransition,
        LifecycleAggregate<OrganisationLifecycleState>,
    >

typealias BranchTransitionResult =
    TransitionResult<
        BranchLifecycleState,
        BranchLifecycleTransition,
        LifecycleAggregate<BranchLifecycleState>,
    >

typealias UserTransitionResult =
    TransitionResult<
        UserLifecycleState,
        UserLifecycleTransition,
        LifecycleAggregate<UserLifecycleState>,
    >

typealias MembershipTransitionResult =
    TransitionResult<
        MembershipLifecycleState,
        MembershipLifecycleTransition,
        LifecycleAggregate<MembershipLifecycleState>,
    >
