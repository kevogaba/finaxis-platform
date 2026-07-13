package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import java.util.UUID

/**
 * Serializable JobRunr request for the future welcome-email provider integration.
 */
data class SendWelcomeEmailJobRequest
    @JvmOverloads
    constructor(
        val membershipId: UUID = UUID(0, 0),
        val userId: UUID = UUID(0, 0),
        val organisationId: UUID = UUID(0, 0),
    ) : JobRequest {
        /**
         * Identifies the Spring-managed handler that processes this background job.
         */
        override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> =
            SendWelcomeEmailJobRequestHandler::class.java
    }
