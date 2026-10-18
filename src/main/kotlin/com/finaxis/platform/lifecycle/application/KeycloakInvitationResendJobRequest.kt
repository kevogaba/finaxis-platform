package com.finaxis.platform.lifecycle.application

import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import java.util.UUID

/** Serializable JobRunr request to send a member's Keycloak invitation email again. */
data class KeycloakInvitationResendJobRequest
    @JvmOverloads
    constructor(
        val organisationId: UUID = UUID(0, 0),
        val userId: UUID = UUID(0, 0),
        val dispatchKey: String = "",
        val attempt: Int = 0,
    ) : JobRequest {
        /** Identifies the Spring-managed handler that processes this background job. */
        override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> =
            KeycloakInvitationResendJobRequestHandler::class.java
    }
