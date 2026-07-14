package com.finaxis.platform.lifecycle.application

import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import java.util.UUID

/** Serializable JobRunr request for the future application-invite email provider. */
data class ApplicationInviteJobRequest
    @JvmOverloads
    constructor(
        val organisationId: UUID = UUID(0, 0),
        val membershipId: UUID = UUID(0, 0),
        val userId: UUID = UUID(0, 0),
        val email: String = "",
        val dispatchKey: String = "",
    ) : JobRequest {
        /** Identifies the Spring-managed handler that processes this background job. */
        override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> =
            ApplicationInviteJobRequestHandler::class.java
    }
