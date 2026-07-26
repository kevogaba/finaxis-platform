package com.finaxis.platform.lifecycle.application

import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.stereotype.Component

/** JobRunr request handler that processes asynchronous initial administrator bootstrap jobs. */
@Component
class InitialAdministratorBootstrapJobRequestHandler(
    private val bootstrapService: InitialAdministratorBootstrapService,
) : JobRequestHandler<InitialAdministratorBootstrapJobRequest> {
    override fun run(jobRequest: InitialAdministratorBootstrapJobRequest) {
        bootstrapService.bootstrap(jobRequest.organisationId)
    }
}
