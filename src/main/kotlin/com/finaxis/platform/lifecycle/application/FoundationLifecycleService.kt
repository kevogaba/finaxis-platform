package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.transitions.TransitionExecution
import com.finaxis.platform.common.transitions.TransitionExecutor
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
import java.time.Clock
import java.util.UUID

/**
 * Transactional lifecycle application service. All state changes use the shared TransitionExecutor;
 * the service is the only path that persists lifecycle status, logs, audit records, and outbox
 * rows.
 */
@Service
class FoundationLifecycleService(
    private val transitionExecutor: TransitionExecutor,
    private val reader: FoundationLifecycleReader,
    private val writer: FoundationLifecycleWriter,
    private val prerequisites: LifecyclePrerequisites,
    private val outbox: LifecycleOutboxEventStore,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    /** Applies an explicit transition to an organisation lifecycle aggregate. */
    @Transactional
    fun transition(command: OrganisationTransitionCommand): OrganisationTransitionResult {
        val aggregate =
            requireNotNull(reader.findOrganisation(command.organisationId)) {
                "Organisation was not found."
            }
        val result =
            transitionExecutor.execute(
                TransitionExecution(
                    aggregate = aggregate,
                    transition = command.transition,
                    graph = FoundationLifecycleDefinitions.organisationGraph(),
                    command = contextualise(command.command, command.organisationId, null),
                    actor = actor(),
                    persist = writer::saveOrganisation,
                ),
            )
        recordOutcome(result, command.organisationId, null)
        return result
    }

    /** Applies an explicit transition to a branch scoped to an organisation. */
    @Transactional
    fun transition(command: BranchTransitionCommand): BranchTransitionResult {
        val aggregate =
            requireNotNull(reader.findBranch(command.organisationId, command.branchId)) {
                "Branch was not found in the selected organisation."
            }
        val result =
            transitionExecutor.execute(
                TransitionExecution(
                    aggregate = aggregate,
                    transition = command.transition,
                    graph =
                        FoundationLifecycleDefinitions.branchGraph(
                            prerequisites,
                            command.organisationId,
                            command.branchId,
                        ),
                    command =
                        contextualise(
                            command.command,
                            command.organisationId,
                            command.branchId,
                        ),
                    actor = actor(),
                    persist = writer::saveBranch,
                ),
            )
        recordOutcome(result, command.organisationId, command.branchId)
        return result
    }

    /** Applies an explicit transition to a global user in an organisation workflow. */
    @Transactional
    fun transition(command: UserTransitionCommand): UserTransitionResult {
        val aggregate =
            requireNotNull(reader.findUser(command.userId)) {
                "User account was not found."
            }
        val result =
            transitionExecutor.execute(
                TransitionExecution(
                    aggregate = aggregate,
                    transition = command.transition,
                    graph = FoundationLifecycleDefinitions.userGraph(prerequisites, command.userId),
                    command =
                        contextualise(
                            command.command,
                            command.organisationId,
                            command.branchId,
                        ),
                    actor = actor(),
                    persist = writer::saveUser,
                ),
            )
        if (command.transition == UserLifecycleTransition.COMPLETE_DEACTIVATION) {
            writer.revokeActiveAssignments(command.organisationId, command.userId)
        }
        recordOutcome(result, command.organisationId, command.branchId)
        return result
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
        val result =
            transitionExecutor.execute(
                TransitionExecution(
                    aggregate = aggregate,
                    transition = command.transition,
                    graph =
                        FoundationLifecycleDefinitions.membershipGraph(
                            prerequisites,
                            command.organisationId,
                            aggregateUserId(command.organisationId, command.membershipId),
                        ),
                    command =
                        contextualise(
                            command.command,
                            command.organisationId,
                            command.branchId,
                        ),
                    actor = actor(),
                    persist = writer::saveMembership,
                ),
            )
        recordOutcome(result, command.organisationId, command.branchId)
        return result
    }

    /** Emits a role-assignment integration record after IAM grants a role. */
    @Transactional
    fun roleAssigned(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
    ) = enqueueAssignmentEvent(organisationId, userId, roleId, ROLE_ASSIGNED)

    /** Emits a branch-assignment integration record after IAM grants a branch. */
    @Transactional
    fun branchAssigned(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
    ) = enqueueAssignmentEvent(organisationId, userId, branchId, BRANCH_ASSIGNED)

    private fun aggregateUserId(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID =
        reader.membershipUserId(organisationId, membershipId)
            ?: throw IllegalArgumentException(
                "Membership was not found in the selected organisation.",
            )

    private fun <S : Enum<S>, T : Enum<T>> recordOutcome(
        result: TransitionResult<S, T, LifecycleAggregate<S>>,
        organisationId: UUID,
        branchId: UUID?,
    ) {
        val eventType = eventType(result.aggregate.aggregateType, result.transition.name)
        auditService.record(
            AuditCommand(
                actorType = actor().type,
                actorId = actor().id,
                tenantId = organisationId.toString(),
                action =
                    "${result.aggregate.aggregateType.lowercase()}." +
                        result.transition.name.lowercase(),
                resourceType = result.aggregate.aggregateType,
                resourceId = result.aggregate.aggregateId,
                outcome = AuditOutcome.SUCCESS,
                requestId = result.log.requestId,
                metadata = mapOf("from" to result.fromState.name, "to" to result.toState.name),
            ),
        )
        if (eventType != null) {
            outbox.enqueue(
                LifecycleOutboxEvent(
                    organisationId = organisationId,
                    aggregateType = result.aggregate.aggregateType,
                    aggregateId = UUID.fromString(result.aggregate.aggregateId),
                    eventType = eventType,
                    routingKey = "platform.lifecycle.${eventType.toSnakeCase()}",
                    occurredAt = result.log.occurredAt,
                    metadata =
                        mapOf(
                            "branchId" to branchId?.toString(),
                            "from" to result.fromState.name,
                            "to" to result.toState.name,
                            "transition" to result.transition.name,
                        ),
                ),
            )
        }
    }

    private fun enqueueAssignmentEvent(
        organisationId: UUID,
        userId: UUID,
        scopeId: UUID,
        eventType: String,
    ) {
        outbox.enqueue(
            LifecycleOutboxEvent(
                organisationId = organisationId,
                aggregateType = "USER_ACCOUNT",
                aggregateId = userId,
                eventType = eventType,
                routingKey = "platform.lifecycle.${eventType.toSnakeCase()}",
                occurredAt = clock.instant(),
                metadata = mapOf("scopeId" to scopeId.toString()),
            ),
        )
    }

    private fun contextualise(
        command: TransitionCommand,
        organisationId: UUID,
        branchId: UUID?,
    ): TransitionCommand =
        command.copy(
            metadata =
                command.metadata +
                    mapOf(
                        ORGANISATION_ID to organisationId.toString(),
                        BRANCH_ID to branchId?.toString(),
                    ),
        )

    private fun actor(): TransitionActor =
        RequestContexts.actor()?.let { actor ->
            TransitionActor(USER, actor.userId.toString(), actor.username)
        } ?: TransitionActor(SYSTEM, SystemActor.ID.toString(), SYSTEM)

    private fun eventType(
        aggregateType: String,
        transition: String,
    ): String? = lifecycleEvents[aggregateType to transition]

    private fun String.toSnakeCase(): String = replace(Regex("(?<!^)([A-Z])"), "_$1").lowercase()

    private companion object {
        const val ORGANISATION_ID = "organisationId"
        const val BRANCH_ID = "branchId"
        const val USER = "USER"
        const val SYSTEM = "SYSTEM"
        const val ROLE_ASSIGNED = "RoleAssigned"
        const val BRANCH_ASSIGNED = "BranchAssigned"

        val lifecycleEvents =
            mapOf(
                "ORGANISATION" to "START_PROVISIONING" to "TenantProvisioningRequested",
                "ORGANISATION" to "ACTIVATE" to "TenantActivated",
                "ORGANISATION" to "SUSPEND" to "TenantSuspended",
                "ORGANISATION" to "START_DEPROVISIONING" to "TenantDeprovisioningRequested",
                "BRANCH" to "ACTIVATE" to "BranchActivated",
                "BRANCH" to "SUSPEND" to "BranchSuspended",
                "USER_ACCOUNT" to "START_IDP_PROVISIONING" to "UserProvisioningRequested",
                "USER_ACCOUNT" to "INVITE" to "UserInvited",
                "USER_ACCOUNT" to "ACTIVATE" to "UserActivated",
                "USER_ACCOUNT" to "SUSPEND" to "UserSuspended",
                "MEMBERSHIP" to "ACTIVATE" to "MembershipActivated",
            )
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
