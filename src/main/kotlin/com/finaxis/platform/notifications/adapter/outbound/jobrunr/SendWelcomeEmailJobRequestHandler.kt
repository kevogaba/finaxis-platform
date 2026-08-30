package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.toAuditFailureReason
import com.finaxis.platform.common.jobs.JobStepGuard
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipientDirectory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import org.jobrunr.JobRunrException
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.stereotype.Component

/** Sends the real welcome email after a membership activation, at most once per JobRunr job. */
@Component
class SendWelcomeEmailJobRequestHandler(
    private val recipientDirectory: WelcomeEmailRecipientDirectory,
    private val emailGateway: EmailGateway,
    private val jobStepGuard: JobStepGuard,
    private val auditService: AuditService,
) : JobRequestHandler<SendWelcomeEmailJobRequest> {
    /**
     * Resolves the recipient, sends the welcome email at most once per job, and audits the
     * outcome. Permanent delivery failures stop further JobRunr retries; retryable failures
     * rethrow so JobRunr retries the job.
     *
     * @param jobRequest the welcome email job details
     */
    override fun run(jobRequest: SendWelcomeEmailJobRequest) {
        try {
            val recipient =
                checkNotNull(
                    recipientDirectory.findRecipient(jobRequest.userId, jobRequest.organisationId),
                ) { "Welcome email recipient not found for user ${jobRequest.userId}" }
            jobStepGuard.runOnce(SEND_STEP) {
                emailGateway.send(
                    EmailMessage(
                        category = EmailCategory.WELCOME,
                        recipientEmail = recipient.email,
                        recipientDisplayName = recipient.displayName,
                        organisationDisplayName = recipient.organisationDisplayName,
                    ),
                )
            }
            auditService.recordExternalDispatch(
                actorId = SystemActor.ID,
                tenantId = jobRequest.organisationId,
                action = WELCOME_EMAIL_ACTION,
                outcome = AuditOutcome.SUCCESS,
                externalSystemRef = WELCOME_EMAIL,
                resourceType = USER_RESOURCE,
                resourceId = jobRequest.userId.toString(),
            )
        } catch (ex: IllegalStateException) {
            recordFailureAndFailPermanently(jobRequest, ex)
        } catch (ex: PermanentEmailDeliveryException) {
            recordFailureAndFailPermanently(jobRequest, ex)
        } catch (ex: RetryableEmailDeliveryException) {
            recordFailureAndRethrow(jobRequest, ex)
        }
    }

    private fun recordFailureAndFailPermanently(
        jobRequest: SendWelcomeEmailJobRequest,
        cause: Throwable,
    ): Nothing {
        auditFailure(jobRequest, cause)
        throw JobRunrException(cause.toAuditFailureReason(), true, cause)
    }

    private fun recordFailureAndRethrow(
        jobRequest: SendWelcomeEmailJobRequest,
        cause: RetryableEmailDeliveryException,
    ): Nothing {
        auditFailure(jobRequest, cause)
        throw cause
    }

    private fun auditFailure(
        jobRequest: SendWelcomeEmailJobRequest,
        cause: Throwable,
    ) {
        auditService.recordExternalDispatch(
            actorId = SystemActor.ID,
            tenantId = jobRequest.organisationId,
            action = WELCOME_EMAIL_ACTION,
            outcome = AuditOutcome.FAILURE,
            externalSystemRef = WELCOME_EMAIL,
            resourceType = USER_RESOURCE,
            resourceId = jobRequest.userId.toString(),
            reason = cause.toAuditFailureReason(),
        )
    }

    private companion object {
        const val SEND_STEP = "send-welcome-email"
        const val WELCOME_EMAIL = "WELCOME_EMAIL"
        const val WELCOME_EMAIL_ACTION = "user.welcome_email"
        const val USER_RESOURCE = "USER"
    }
}
