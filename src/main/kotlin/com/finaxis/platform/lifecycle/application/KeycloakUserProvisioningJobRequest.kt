package com.finaxis.platform.lifecycle.application

import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import java.util.UUID

/** Serializable JobRunr request for Keycloak user provisioning. */
data class KeycloakUserProvisioningJobRequest
    @JvmOverloads
    constructor(
        val organisationId: UUID = UUID(0, 0),
        val membershipId: UUID = UUID(0, 0),
        val userId: UUID = UUID(0, 0),
        val email: String = "",
        val username: String = "",
        val displayName: String = "",
        val sendKeycloakInvite: Boolean = false,
        val dispatchKey: String = "",
        val actorId: UUID = UUID(0, 0),
    ) : JobRequest {
        /** Identifies the Spring-managed handler that processes this background job. */
        override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> =
            KeycloakUserProvisioningJobRequestHandler::class.java
    }
