package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.rootCauseClassNameOrUnavailable
import com.finaxis.platform.common.audit.toMessageFreeStackTrace
import com.finaxis.platform.common.audit.toMessageFreeStackTraceOrUnavailable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Records bootstrap failures after an enclosing transaction releases its bootstrap-row lock.
 *
 * Only the closed [InitialAdministratorBootstrapFailureCode] reaches the database, and so the
 * API. The failure is logged here at `ERROR` with the organisation id, the mapped code, the
 * exception class, the root-cause class and the stack frames of the exception and its causes
 * (`Class.method(File:line)`, bounded, cause classes named): that log is where an operator
 * reads what the API deliberately omits. **This line holds no exception message**, neither the
 * exception's, a cause's nor a suppressed exception's, because a message can carry an email
 * address, SQL parameters or identity-provider output; the throwable is therefore never passed
 * to the logger (see [toMessageFreeStackTrace]), and a failure while rendering falls back to the
 * class name instead of skipping the status update. The raw message of a failed Keycloak or
 * invite job is still kept in `identity_dispatch_log.last_error`, which no API returns.
 *
 * The log line is written at the point the failure is **actually recorded** (after the enclosing
 * transaction rolls back, or at once when there is none), never when it is merely registered: a
 * transaction that commits despite the failure records nothing, and logs nothing. This is the one
 * place that logs the failure together with the organisation id and the code. For the failures
 * they catch, the job handlers rethrow only a [SanitisedJobFailureException] (the closed code, no
 * cause but a message-free `InterruptedException` where the original had one) to JobRunr, and the
 * bootstrap and invite handlers always (the Keycloak handler for a failure it does not record)
 * log a message-free `WARN` of their own after any line written here;
 * `ApiExceptionHandler` logs the class and frames only. An exception type a handler does not
 * catch (the invite handler's, for one) still reaches JobRunr as raised, and the synchronous
 * retry route propagates the original exception to that handler for its HTTP mapping.
 */
@Component
class InitialAdministratorBootstrapFailureRecorder(
    private val failureStatusWriter: InitialAdministratorBootstrapFailureStatusWriter,
) {
    /**
     * Persists a failure status independently after rollback, or immediately when no transaction
     * is active.
     */
    fun recordFailure(
        organisationId: UUID,
        failure: Throwable,
    ) {
        val failureCode = InitialAdministratorBootstrapFailureCode.from(failure)
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record(organisationId, failureCode, failure)
            return
        }

        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        record(organisationId, failureCode, failure)
                    }
                }
            },
        )
    }

    private fun record(
        organisationId: UUID,
        failureCode: InitialAdministratorBootstrapFailureCode,
        failure: Throwable,
    ) {
        // The throwable is deliberately not passed to the logger: it would print its message
        // and every cause's, which can carry an email, SQL or identity-provider output.
        log.error(
            "Initial administrator bootstrap failed for organisation {} with code {}: " +
                "exceptionClass={} rootCauseClass={}\n{}",
            organisationId,
            failureCode,
            failure.javaClass.name,
            // Rendering must never keep the status from being recorded: a hostile getStackTrace or
            // getCause falls back to the class name instead of skipping markFailed.
            failure.rootCauseClassNameOrUnavailable(),
            failure.toMessageFreeStackTraceOrUnavailable(),
        )
        failureStatusWriter.markFailed(organisationId, failureCode)
    }

    private companion object {
        private val log =
            LoggerFactory.getLogger(
                InitialAdministratorBootstrapFailureRecorder::class.java,
            )
    }
}

/** Persists initial-administrator bootstrap failures in an isolated transaction. */
@Component
class InitialAdministratorBootstrapFailureStatusWriter(
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
) {
    /** Marks the bootstrap record failed independently of its provisioning transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(
        organisationId: UUID,
        failureCode: InitialAdministratorBootstrapFailureCode,
    ) {
        adminBootstrapStore.updateStatus(
            organisationId = organisationId,
            status = InitialAdministratorBootstrapStatus.FAILED,
            lastFailureCode = failureCode,
        )
    }
}
