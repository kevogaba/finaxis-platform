package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.toAuditFailureReason
import com.finaxis.platform.common.jobs.JobStepGuard
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import org.jobrunr.JobRunrException
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

/** Sends the real organisation-invite email, at most once per JobRunr job. */
@Component
class ApplicationInviteJobRequestHandler(
    private val store: UserProvisioningStore,
    private val emailGateway: EmailGateway,
    private val jobStepGuard: JobStepGuard,
    private val dispatchOutcomeAuditor: DispatchOutcomeAuditor,
) : JobRequestHandler<ApplicationInviteJobRequest> {
    /**
     * Resolves the membership and organisation, sends the organisation-invite email at most once
     * per job, and audits the outcome. Permanent delivery failures stop further JobRunr retries;
     * a missing membership/organisation, a persistence failure, or a retryable delivery failure
     * all rethrow so JobRunr retries the job.
     *
     * @param jobRequest the application-invite job details
     */
    override fun run(jobRequest: ApplicationInviteJobRequest) {
        if (store.dispatchStatus(jobRequest.dispatchKey) == SUCCEEDED) return
        try {
            val snapshot =
                checkNotNull(
                    store.membershipSnapshot(jobRequest.organisationId, jobRequest.membershipId),
                ) { "Membership not found for application invite." }
            val organisationName =
                checkNotNull(store.organisationDisplayName(jobRequest.organisationId)) {
                    "Organisation not found for application invite."
                }
            jobStepGuard.runOnce("send-application-invite-email") {
                emailGateway.send(
                    EmailMessage(
                        category = EmailCategory.ORGANISATION_INVITE,
                        recipientEmail = jobRequest.email,
                        recipientDisplayName = snapshot.displayName,
                        organisationDisplayName = organisationName,
                    ),
                )
            }
            dispatchOutcomeAuditor.recordSuccess(
                dispatchKey = jobRequest.dispatchKey,
                dispatchRef = APPLICATION_INVITE,
                externalSystemRef = APPLICATION_INVITE,
                actorId = SystemActor.ID,
                tenantId = jobRequest.organisationId,
                action = APPLICATION_INVITE_ACTION,
                resourceId = jobRequest.userId.toString(),
                metadata = mapOf("dispatchKey" to jobRequest.dispatchKey),
            )
        } catch (ex: IllegalStateException) {
            recordFailure(jobRequest, ex)
        } catch (ex: DataAccessException) {
            recordFailure(jobRequest, ex)
        } catch (ex: PermanentEmailDeliveryException) {
            recordFailureAndFailPermanently(jobRequest, ex)
        } catch (ex: RetryableEmailDeliveryException) {
            recordFailure(jobRequest, ex)
        }
    }

    private fun recordFailure(
        jobRequest: ApplicationInviteJobRequest,
        ex: RuntimeException,
    ): Nothing {
        auditFailure(jobRequest, ex)
        throw ex
    }

    private fun recordFailureAndFailPermanently(
        jobRequest: ApplicationInviteJobRequest,
        ex: RuntimeException,
    ): Nothing {
        auditFailure(jobRequest, ex)
        throw JobRunrException(ex.toAuditFailureReason(), true, ex)
    }

    private fun auditFailure(
        jobRequest: ApplicationInviteJobRequest,
        ex: RuntimeException,
    ) {
        dispatchOutcomeAuditor.recordFailure(
            dispatchKey = jobRequest.dispatchKey,
            externalSystemRef = APPLICATION_INVITE,
            actorId = SystemActor.ID,
            tenantId = jobRequest.organisationId,
            action = APPLICATION_INVITE_ACTION,
            resourceId = jobRequest.userId.toString(),
            reason = ex.toAuditFailureReason(),
            detail = ex.message ?: ex.javaClass.name,
            metadata = mapOf("dispatchKey" to jobRequest.dispatchKey),
        )
    }

    private companion object {
        const val SUCCEEDED = "SUCCEEDED"
        const val APPLICATION_INVITE = "APPLICATION_INVITE"
        const val APPLICATION_INVITE_ACTION = "user.application_invite"
    }
}
