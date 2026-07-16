package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.persistence.SystemActor
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Temporary JobRunr handler that records pending welcome-email delivery without provider access.
 * The audited outcome reflects this stub's completion, not a real provider's delivery result,
 * until a real email provider replaces it.
 */
@Component
class SendWelcomeEmailJobRequestHandler(
    private val auditService: AuditService,
) : JobRequestHandler<SendWelcomeEmailJobRequest> {
    /**
     * Logs the explicit email-provider follow-up while keeping the job successfully executable.
     *
     * @param jobRequest the welcome email job details
     */
    override fun run(jobRequest: SendWelcomeEmailJobRequest) {
        logger.info(
            "would send welcome email organisationId={} userId={}",
            jobRequest.organisationId,
            jobRequest.userId,
        )
        auditService.recordExternalDispatch(
            actorId = SystemActor.ID,
            tenantId = jobRequest.organisationId,
            action = WELCOME_EMAIL_ACTION,
            outcome = AuditOutcome.SUCCESS,
            externalSystemRef = WELCOME_EMAIL,
            resourceType = USER_RESOURCE,
            resourceId = jobRequest.userId.toString(),
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SendWelcomeEmailJobRequestHandler::class.java)
        const val WELCOME_EMAIL = "WELCOME_EMAIL"
        const val WELCOME_EMAIL_ACTION = "user.welcome_email"
        const val USER_RESOURCE = "USER"
    }
}
