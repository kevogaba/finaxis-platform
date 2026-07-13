package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Temporary JobRunr handler that records pending welcome-email delivery without provider access.
 */
@Component
class SendWelcomeEmailJobRequestHandler : JobRequestHandler<SendWelcomeEmailJobRequest> {
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
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SendWelcomeEmailJobRequestHandler::class.java)
    }
}
