package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.jobs.FailedJobRequeuer
import com.finaxis.platform.common.jobs.JobRequeueOutcome
import com.finaxis.platform.common.jobs.deterministicJobId
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Service coordinating the asynchronous bootstrapping of the organisation's initial administrator.
 */
@Service
@Suppress("TooGenericExceptionCaught")
class InitialAdministratorBootstrapService(
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
    private val bootstrapStore: OrganisationBootstrapStore,
    private val userProvisioningStore: UserProvisioningStore,
    private val userProvisioningService: UserProvisioningService,
    private val eventPublisher: TransitionEventPublisher,
    private val failureRecorder: InitialAdministratorBootstrapFailureRecorder,
    private val clock: Clock,
    private val failedJobRequeuer: FailedJobRequeuer,
) {
    /**
     * Executes the bootstrap process for the organisation's initial administrator.
     */
    @Transactional
    fun bootstrap(organisationId: UUID) {
        val record =
            adminBootstrapStore.find(organisationId)
                ?: throw IllegalArgumentException(
                    "Bootstrap record not found for organisation: $organisationId",
                )

        if (record.status == InitialAdministratorBootstrapStatus.COMPLETED) return

        try {
            adminBootstrapStore.updateStatus(
                organisationId = organisationId,
                status = InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY,
                incrementAttempts = true,
            )

            val headOfficeId =
                record.headOfficeId
                    ?: bootstrapStore.ensureHeadOfficeDraft(organisationId).branchId

            val roleId =
                record.roleId
                    ?: userProvisioningStore.findRoleIdByCode(organisationId, "TENANT_ADMIN")
                    ?: error("Default role TENANT_ADMIN was not created.")

            if (record.headOfficeId == null || record.roleId == null) {
                adminBootstrapStore.linkResolvedEntities(
                    organisationId = organisationId,
                    userId = record.userId,
                    membershipId = record.membershipId,
                    headOfficeId = headOfficeId,
                    roleId = roleId,
                )
            }

            val (userId, membershipId) = inviteUserIfNeeded(record, headOfficeId, roleId)
            approveOrRepublish(record, userId, membershipId)
        } catch (ex: Exception) {
            failureRecorder.recordFailure(organisationId, ex)
            throw ex
        }
    }

    private fun inviteUserIfNeeded(
        record: InitialAdministratorBootstrapRecord,
        headOfficeId: UUID,
        roleId: UUID,
    ): Pair<UUID, UUID> {
        val organisationId = record.organisationId
        var userId = record.userId
        var membershipId = record.membershipId

        if (userId == null || membershipId == null) {
            val inviteCmd =
                InviteUserCommand(
                    organisationId = organisationId,
                    email = record.adminEmail,
                    username = record.adminUsername,
                    displayName = record.adminDisplayName,
                    phoneE164 = record.adminPhoneE164,
                    membershipType = MembershipType.ADMIN,
                    primaryBranchId = headOfficeId,
                    branchAssignments =
                        listOf(
                            BranchAssignmentRequest(headOfficeId, BranchAssignmentType.HOME),
                        ),
                    roleAssignments =
                        listOf(
                            RoleAssignmentRequest(roleId, RoleAssignmentScopeType.TENANT),
                        ),
                    invitedBy = SystemActor.ID,
                    sendKeycloakInvite = true,
                    sendApplicationInvite = record.sendApplicationInvite,
                    requestId = "BOOTSTRAP-$organisationId",
                    bootstrapRequestId = "BOOTSTRAP-$organisationId",
                    bootstrapAttempt = record.attempts + 1,
                )
            val inviteResult = userProvisioningService.inviteAsSystem(inviteCmd)
            userId = inviteResult.userId
            membershipId = inviteResult.membershipId
            adminBootstrapStore.linkResolvedEntities(
                organisationId = organisationId,
                userId = userId,
                membershipId = membershipId,
                headOfficeId = headOfficeId,
                roleId = roleId,
            )
        }
        return Pair(userId, membershipId)
    }

    private fun approveOrRepublish(
        record: InitialAdministratorBootstrapRecord,
        userId: UUID,
        membershipId: UUID,
    ) {
        val organisationId = record.organisationId
        val dispatchKey = keycloakDispatchKey(organisationId, userId)
        val dispatchStatus = userProvisioningStore.dispatchStatus(dispatchKey)

        if (dispatchStatus == null) {
            val approveCmd =
                ApproveUserCommand(
                    organisationId = organisationId,
                    membershipId = membershipId,
                    approvedBy =
                        requireNotNull(record.approvedBy) {
                            "Bootstrap record must be approved before identity provisioning."
                        },
                    requestId = "BOOTSTRAP-$organisationId",
                    bootstrapRequestId = "BOOTSTRAP-$organisationId",
                    bootstrapAttempt = record.attempts + 1,
                )
            val approvalResult = userProvisioningService.approveAsSystem(approveCmd)
            if (!approvalResult.keycloakProvisioningRequested) {
                completeBootstrapIfCorrelated(organisationId, userId)
            }
        } else if (dispatchStatus == "SUCCEEDED") {
            completeBootstrapIfCorrelated(organisationId, userId)
        } else {
            // PENDING or FAILED: a FAILED job runs again under its own id (#162). One still
            // scheduled, enqueued or processing is left alone, and a job that is gone is queued
            // again under the same key, so the dispatch never has two jobs. The requeue is an
            // external write on JobRunr's connection that commits at once, so a later rollback of
            // this retry still leaves the job running; the handler tolerates that (it skips only
            // SUCCEEDED).
            when (failedJobRequeuer.requeueIfFailed(deterministicJobId(dispatchKey))) {
                JobRequeueOutcome.REQUEUED -> {
                    userProvisioningStore.markDispatchPending(dispatchKey)
                }

                JobRequeueOutcome.NOT_FAILED -> {
                    // nothing to do: the job is still scheduled or running
                }

                JobRequeueOutcome.NOT_FOUND -> {
                    userProvisioningStore.markDispatchPending(dispatchKey)
                    republishKeycloakProvisioning(record, userId, membershipId, dispatchKey)
                }
            }
        }
    }

    private fun republishKeycloakProvisioning(
        record: InitialAdministratorBootstrapRecord,
        userId: UUID,
        membershipId: UUID,
        dispatchKey: String,
    ) {
        val organisationId = record.organisationId
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = "finaxis.lifecycle.user.keycloak-provisioning-requested",
                aggregateType = "USER_ACCOUNT",
                aggregateId = userId.toString(),
                transition = "KEYCLOAK_PROVISIONING_REQUESTED",
                fromState = "PROVISIONING_IDP",
                toState = "PROVISIONING_IDP",
                actor = TransitionActor("USER", record.approvedBy.toString()),
                occurredAt = clock.instant(),
                metadata =
                    mapOf(
                        "organisationId" to organisationId.toString(),
                        "membershipId" to membershipId.toString(),
                        "userId" to userId.toString(),
                        "email" to record.adminEmail,
                        "username" to record.adminUsername,
                        "displayName" to record.adminDisplayName,
                        "sendKeycloakInvite" to "true",
                        "dispatchKey" to dispatchKey,
                        "bootstrapRequestId" to "BOOTSTRAP-$organisationId",
                        "bootstrapAttempt" to (record.attempts + 1),
                    ),
            ),
        )
    }

    /**
     * Whether [userId] is the user the organisation's bootstrap record resolved as its initial
     * administrator. The same correlation [completeBootstrapIfCorrelated] applies on success.
     */
    fun isInitialAdministrator(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = adminBootstrapStore.find(organisationId)?.userId == userId

    /**
     * Idempotently completes the bootstrap record when identity provisioning succeeds.
     */
    @Transactional
    fun completeBootstrapIfCorrelated(
        organisationId: UUID,
        userId: UUID,
    ) {
        val record = adminBootstrapStore.find(organisationId) ?: return
        if (record.userId == userId &&
            record.status != InitialAdministratorBootstrapStatus.COMPLETED
        ) {
            adminBootstrapStore.updateStatus(
                organisationId = organisationId,
                status = InitialAdministratorBootstrapStatus.COMPLETED,
            )
        }
    }
}
