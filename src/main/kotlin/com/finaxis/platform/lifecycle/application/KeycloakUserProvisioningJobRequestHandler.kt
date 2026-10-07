package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.toAuditFailureReason
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserProvisioningRequest
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleTransition
import org.jobrunr.JobRunrException
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

/** Processes durable Keycloak user provisioning jobs emitted by invitation approval. */
@Component
class KeycloakUserProvisioningJobRequestHandler(
    private val gateway: IdentityProvisioningGateway,
    private val store: UserProvisioningStore,
    private val lifecycleService: FoundationLifecycleService,
    private val dispatchOutcomeAuditor: DispatchOutcomeAuditor,
    private val bootstrapService: InitialAdministratorBootstrapService,
    private val failureRecorder: InitialAdministratorBootstrapFailureRecorder,
    @Value($$"""${finaxis.keycloak.admin.realm:finaxis}""") private val realm: String = "finaxis",
) : JobRequestHandler<KeycloakUserProvisioningJobRequest> {
    /**
     * Runs Keycloak provisioning idempotently and advances the local user and membership FSMs.
     * A failure the handler records leaves as a [SanitisedJobFailureException] carrying the closed
     * code; any other failure (a type not caught below, or one on the already-succeeded branch) is
     * logged without its message and sanitised the same way, without being recorded, so JobRunr
     * never receives an original message.
     */
    @Suppress("TooGenericExceptionCaught") // every failure, whatever its type, is sanitised
    override fun run(jobRequest: KeycloakUserProvisioningJobRequest) {
        try {
            provision(jobRequest)
        } catch (ex: Exception) {
            // Already sanitised, or a do-not-retry JobRunrException (wrapping it would make it
            // retryable): propagate unchanged; anything else is logged and sanitised.
            throw when (ex) {
                is SanitisedJobFailureException, is JobRunrException -> {
                    ex
                }

                else -> {
                    SanitisedJobFailureException.logged(
                        ex,
                        "Keycloak provisioning job ${jobRequest.dispatchKey}",
                    )
                }
            }
        }
    }

    private fun provision(jobRequest: KeycloakUserProvisioningJobRequest) {
        if (store.dispatchStatus(jobRequest.dispatchKey) == SUCCEEDED) {
            bootstrapService.completeBootstrapIfCorrelated(
                jobRequest.organisationId,
                jobRequest.userId,
            )
            return
        }
        try {
            val keycloakUser =
                gateway.findOrCreateUser(
                    KeycloakUserProvisioningRequest(
                        email = jobRequest.email,
                        username = jobRequest.username,
                        displayName = jobRequest.displayName,
                    ),
                )
            store.linkKeycloakIdentity(
                userId = jobRequest.userId,
                subject = keycloakUser.subject,
                realm = realm,
                actorId = jobRequest.actorId,
            )
            if (jobRequest.sendKeycloakInvite) {
                gateway.sendRequiredActionsEmail(keycloakUser.subject)
            }
            inviteUserIfNeeded(jobRequest)
            activateMembershipIfNeeded(jobRequest)
            bootstrapService.completeBootstrapIfCorrelated(
                jobRequest.organisationId,
                jobRequest.userId,
            )

            dispatchOutcomeAuditor.recordSuccess(
                dispatchKey = jobRequest.dispatchKey,
                dispatchRef = keycloakUser.subject,
                externalSystemRef = KEYCLOAK,
                actorId = jobRequest.actorId,
                tenantId = jobRequest.organisationId,
                action = KEYCLOAK_PROVISIONING_ACTION,
                resourceId = jobRequest.userId.toString(),
                metadata = mapOf("dispatchKey" to jobRequest.dispatchKey),
            )
        } catch (ex: IdentityProvisioningException) {
            recordFailure(jobRequest, ex)
        } catch (ex: ConflictException) {
            recordFailure(jobRequest, ex)
        } catch (ex: ResourceNotFoundException) {
            recordFailure(jobRequest, ex)
        } catch (ex: IllegalArgumentException) {
            recordFailure(jobRequest, ex)
        } catch (ex: IllegalStateException) {
            recordFailure(jobRequest, ex)
        } catch (ex: DataAccessException) {
            recordFailure(jobRequest, ex)
        }
    }

    private fun recordFailure(
        jobRequest: KeycloakUserProvisioningJobRequest,
        ex: RuntimeException,
    ): Nothing {
        dispatchOutcomeAuditor.recordFailure(
            dispatchKey = jobRequest.dispatchKey,
            externalSystemRef = KEYCLOAK,
            actorId = jobRequest.actorId,
            tenantId = jobRequest.organisationId,
            action = KEYCLOAK_PROVISIONING_ACTION,
            resourceId = jobRequest.userId.toString(),
            reason = ex.toAuditFailureReason(),
            detail = ex.message ?: ex.javaClass.name,
            metadata = mapOf("dispatchKey" to jobRequest.dispatchKey),
        )
        // Only the bootstrap administrator's own job can fail the bootstrap, as only its success
        // can complete it: another invitee's failure belongs to that user's dispatch alone.
        if (bootstrapService.isInitialAdministrator(jobRequest.organisationId, jobRequest.userId)) {
            failureRecorder.recordFailure(jobRequest.organisationId, ex)
            // JobRunr logs and stores what it is given with its message and causes; give it only
            // the closed code, so retries and backoff are unchanged but no email or SQL text
            // reaches it. The recorder has logged the failure (message-free).
            throw SanitisedJobFailureException.forFailure(ex)
        }
        // Nothing is recorded for another invitee, so no recorder line exists: log a
        // message-free WARN here so an operator still finds the class names and frames.
        throw SanitisedJobFailureException.logged(
            ex,
            "Keycloak provisioning job ${jobRequest.dispatchKey}",
        )
    }

    private fun inviteUserIfNeeded(jobRequest: KeycloakUserProvisioningJobRequest) {
        when (store.userStatus(jobRequest.userId)) {
            UserLifecycleState.PROVISIONING_IDP -> {
                lifecycleService.transition(
                    UserTransitionCommand(
                        organisationId = jobRequest.organisationId,
                        userId = jobRequest.userId,
                        transition = UserLifecycleTransition.INVITE,
                    ),
                )
            }

            UserLifecycleState.INVITED,
            UserLifecycleState.ACTIVE,
            -> {}

            else -> {
                error("User is not waiting for Keycloak provisioning.")
            }
        }
    }

    private fun activateMembershipIfNeeded(jobRequest: KeycloakUserProvisioningJobRequest) {
        when (
            store.membershipSnapshot(jobRequest.organisationId, jobRequest.membershipId)?.status
        ) {
            MembershipLifecycleState.PENDING_APPROVAL -> {
                lifecycleService.transition(
                    MembershipTransitionCommand(
                        organisationId = jobRequest.organisationId,
                        membershipId = jobRequest.membershipId,
                        transition = MembershipLifecycleTransition.ACTIVATE,
                    ),
                )
            }

            MembershipLifecycleState.ACTIVE -> {}

            else -> {
                error("Membership is not waiting for activation.")
            }
        }
    }

    private companion object {
        const val SUCCEEDED = "SUCCEEDED"
        const val KEYCLOAK = "KEYCLOAK"
        const val KEYCLOAK_PROVISIONING_ACTION = "user.keycloak_provisioning"
    }
}
