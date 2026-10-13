package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.rootCauseClassNameOrUnavailable
import com.finaxis.platform.common.audit.toAuditFailureReason
import com.finaxis.platform.common.audit.toMessageFreeStackTraceOrUnavailable
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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Collections
import java.util.IdentityHashMap

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
     * throw a plain exception so JobRunr retries the job. Neither thrown exception carries the
     * original failure: its message can quote the recipient's address, so JobRunr would store and
     * log it. Only the failure's class name is kept.
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
                subjectUserId = jobRequest.userId,
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
        logFailure(jobRequest, cause)
        throw JobRunrException(cause.toAuditFailureReason(), true)
    }

    private fun recordFailureAndRethrow(
        jobRequest: SendWelcomeEmailJobRequest,
        cause: RetryableEmailDeliveryException,
    ): Nothing {
        auditFailure(jobRequest, cause)
        logFailure(jobRequest, cause)
        // JobRunr retries it with its default count and backoff. It carries no cause but a
        // message-free InterruptedException (JobRunr recognises a stopped server or a deleted job
        // by one), so the SMTP text the original may hold never reaches jobrunr_jobs or the log.
        throw SendWelcomeEmailRetryException(
            cause.toAuditFailureReason(),
            cause.holdsInterruption(),
        )
    }

    /**
     * Logs the failure at `WARN` without its message, the throwable is never passed on: the
     * exception class, the root-cause class and the message-free frames, so an operator can still
     * find an SMTP failure's cause. The job id is a user id, so no address is logged.
     */
    private fun logFailure(
        jobRequest: SendWelcomeEmailJobRequest,
        cause: Throwable,
    ) {
        log.warn(
            "{} failed: exceptionClass={} rootCauseClass={}\n{}",
            "Welcome email job for user ${jobRequest.userId}",
            cause.javaClass.name,
            cause.rootCauseClassNameOrUnavailable(),
            cause.toMessageFreeStackTraceOrUnavailable(),
        )
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
            subjectUserId = jobRequest.userId,
            reason = cause.toAuditFailureReason(),
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(SendWelcomeEmailJobRequestHandler::class.java)
        const val SEND_STEP = "send-welcome-email"
        const val WELCOME_EMAIL = "WELCOME_EMAIL"
        const val WELCOME_EMAIL_ACTION = "user.welcome_email"
        const val USER_RESOURCE = "USER"
    }
}

/**
 * The message-free failure a retryable welcome email throws to JobRunr. Its message is the
 * failure's class name and it has no cause, except a message-free [InterruptedException] when the
 * original chain held one: JobRunr retries any exception that is not a do-not-retry
 * [JobRunrException], so this one is retried like the original.
 */
private class SendWelcomeEmailRetryException(
    failureClassName: String,
    interrupted: Boolean,
) : RuntimeException(failureClassName, if (interrupted) InterruptedException() else null)

private fun Throwable.holdsInterruption(): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(this) {
        it.cause
    }.takeWhile { seen.add(it) }.any { it is InterruptedException }
}
