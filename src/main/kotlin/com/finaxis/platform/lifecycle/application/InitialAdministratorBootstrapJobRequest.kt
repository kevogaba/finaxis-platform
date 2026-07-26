package com.finaxis.platform.lifecycle.application

import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import java.util.UUID

/** Serializable JobRunr request for initial administrator bootstrap process. */
data class InitialAdministratorBootstrapJobRequest
    @JvmOverloads
    constructor(
        val organisationId: UUID = UUID(0, 0),
    ) : JobRequest {
        /** Identifies the Spring-managed handler that processes this background job. */
        override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> =
            InitialAdministratorBootstrapJobRequestHandler::class.java
    }
