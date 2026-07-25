package com.finaxis.platform.lifecycle.application

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Records bootstrap failures after an enclosing transaction releases its bootstrap-row lock.
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
        val failureCode = (failure.message ?: failure.javaClass.name).take(MAX_FAILURE_CODE_LENGTH)
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            failureStatusWriter.markFailed(organisationId, failureCode)
            return
        }

        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        failureStatusWriter.markFailed(organisationId, failureCode)
                    }
                }
            },
        )
    }

    private companion object {
        const val MAX_FAILURE_CODE_LENGTH = 100
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
        failureCode: String,
    ) {
        adminBootstrapStore.updateStatus(
            organisationId = organisationId,
            status = InitialAdministratorBootstrapStatus.FAILED,
            lastFailureCode = failureCode,
        )
    }
}
