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
        /**
         * The life of the membership this invite belongs to (ADR 0037). A job stored before the
         * field existed reads as 0, the generation of every membership never re-invited.
         */
        val generation: Int = 0,
    ) : JobRequest {
        /** Identifies the Spring-managed handler that processes this background job. */
        override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> =
            ApplicationInviteJobRequestHandler::class.java
    }
