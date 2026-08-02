package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.toAuditFailureReason
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

/** Temporary JobRunr handler for application-invite delivery until an email provider exists. */
@Component
class ApplicationInviteJobRequestHandler(
    private val store: UserProvisioningStore,
    private val dispatchOutcomeAuditor: DispatchOutcomeAuditor,
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
            dispatchOutcomeAuditor.recordSuccess(
                dispatchKey = jobRequest.dispatchKey,
                dispatchRef = APPLICATION_INVITE,
                externalSystemRef = APPLICATION_INVITE,
                actorId = SystemActor.ID,
                tenantId = jobRequest.organisationId,
                action = APPLICATION_INVITE_ACTION,
                resourceId = jobRequest.userId.toString(),
                metadata = mapOf("dispatchKey" to jobRequest.dispatchKey),
            )
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
        dispatchOutcomeAuditor.recordFailure(
            dispatchKey = jobRequest.dispatchKey,
            externalSystemRef = APPLICATION_INVITE,
            actorId = SystemActor.ID,
            tenantId = jobRequest.organisationId,
            action = APPLICATION_INVITE_ACTION,
            resourceId = jobRequest.userId.toString(),
            reason = ex.toAuditFailureReason(),
            detail = ex.message ?: ex.javaClass.name,
            metadata = mapOf("dispatchKey" to jobRequest.dispatchKey),
        )
        throw ex
    }

    private companion object {
        const val SUCCEEDED = "SUCCEEDED"
        const val APPLICATION_INVITE = "APPLICATION_INVITE"
        const val APPLICATION_INVITE_ACTION = "user.application_invite"
        private val logger =
            LoggerFactory.getLogger(ApplicationInviteJobRequestHandler::class.java)
    }
}
