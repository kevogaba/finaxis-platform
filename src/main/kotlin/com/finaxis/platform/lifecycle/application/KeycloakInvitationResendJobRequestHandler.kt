package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.stereotype.Component

/**
 * Sends the Keycloak invitation email again (#274), to the subject the account is linked to: it
 * creates and looks up nothing, so a deleted Keycloak account fails the job for reconciliation
 * instead of being replaced by an identity the platform cannot authenticate. The account is
 * checked again when the job runs, because the member may have signed in since it was queued; an
 * account that is no longer `INVITED` needs no email. A failure leaves JobRunr to retry the one
 * email, and is logged without its message, as every provisioning job's is.
 */
@Component
class KeycloakInvitationResendJobRequestHandler(
    private val gateway: IdentityProvisioningGateway,
    private val store: UserProvisioningStore,
) : JobRequestHandler<KeycloakInvitationResendJobRequest> {
    /** Emails the linked Keycloak account's required actions again, if it is still invited. */
    @Suppress("TooGenericExceptionCaught") // every failure, whatever its type, is sanitised
    override fun run(jobRequest: KeycloakInvitationResendJobRequest) {
        try {
            if (store.userStatus(jobRequest.userId) != UserLifecycleState.INVITED) return
            val subject =
                checkNotNull(store.linkedKeycloakSubject(jobRequest.userId)) {
                    "The account has no linked Keycloak identity."
                }
            gateway.sendRequiredActionsEmail(subject)
        } catch (ex: Exception) {
            throw SanitisedJobFailureException.logged(
                ex,
                "Keycloak invitation resend job ${jobRequest.dispatchKey}",
            )
        }
    }
}
