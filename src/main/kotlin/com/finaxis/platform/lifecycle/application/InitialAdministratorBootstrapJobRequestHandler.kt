package com.finaxis.platform.lifecycle.application

import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.stereotype.Component

/** JobRunr request handler that processes asynchronous initial administrator bootstrap jobs. */
@Component
class InitialAdministratorBootstrapJobRequestHandler(
    private val bootstrapService: InitialAdministratorBootstrapService,
) : JobRequestHandler<InitialAdministratorBootstrapJobRequest> {
    /**
     * Runs the bootstrap. JobRunr is given only the closed code, not the original exception: its
     * message and causes would otherwise be logged and stored in `jobrunr_jobs`. The service
     * records most failures (closed code, message-free log line), but not one raised before its
     * `try` (a missing record) or at commit, so this handler also logs the class names and frames
     * without the message before it throws, and an operator always has the location. The
     * synchronous retry route calls the service directly and keeps its exception types.
     */
    @Suppress("TooGenericExceptionCaught") // every failure, whatever its type, is sanitised
    override fun run(jobRequest: InitialAdministratorBootstrapJobRequest) {
        try {
            bootstrapService.bootstrap(jobRequest.organisationId)
        } catch (ex: Exception) {
            throw SanitisedJobFailureException.logged(
                ex,
                "Initial administrator bootstrap job for organisation ${jobRequest.organisationId}",
            )
        }
    }
}
