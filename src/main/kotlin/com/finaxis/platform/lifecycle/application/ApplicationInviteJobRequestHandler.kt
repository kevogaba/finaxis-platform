package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

/** Temporary JobRunr handler for application-invite delivery until an email provider exists. */
@Component
class ApplicationInviteJobRequestHandler(
    private val store: UserProvisioningStore,
) : JobRequestHandler<ApplicationInviteJobRequest> {
    /** Logs the invite-provider follow-up and marks the dispatch as delivered. */
    override fun run(jobRequest: ApplicationInviteJobRequest) {
        if (store.dispatchStatus(jobRequest.dispatchKey) == SUCCEEDED) return
        try {
            logger.info(
                "would send application invite organisationId={} userId={} dispatchKey={}",
                jobRequest.organisationId,
                jobRequest.userId,
                jobRequest.dispatchKey,
            )
            store.markDispatchSucceeded(jobRequest.dispatchKey, APPLICATION_INVITE)
        } catch (ex: IllegalStateException) {
            recordFailure(jobRequest, ex)
        } catch (ex: DataAccessException) {
            recordFailure(jobRequest, ex)
        }
    }

    private fun recordFailure(
        jobRequest: ApplicationInviteJobRequest,
        ex: RuntimeException,
    ): Nothing {
        store.markDispatchFailed(jobRequest.dispatchKey, ex.message ?: ex.javaClass.name)
        throw ex
    }

    private companion object {
        const val SUCCEEDED = "SUCCEEDED"
        const val APPLICATION_INVITE = "APPLICATION_INVITE"
        private val logger =
            LoggerFactory.getLogger(ApplicationInviteJobRequestHandler::class.java)
    }
}
