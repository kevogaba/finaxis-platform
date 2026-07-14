package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.lifecycle.UserFirstLoginActivation
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleTransition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Lifecycle implementation of first-login activation for users already verified by Keycloak.
 */
@Service
class UserFirstLoginActivationService(
    private val lifecycleService: FoundationLifecycleService,
    private val reader: FoundationLifecycleReader,
    private val store: FirstLoginActivationStore,
    private val auditService: AuditService,
) : UserFirstLoginActivation {
    /**
     * Activates invited users through the lifecycle FSM and refreshes last-login metadata.
     */
    @Transactional
    override fun activateOnFirstLogin(
        userId: UUID,
        organisationId: UUID,
    ) {
        val state = reader.findUser(userId)?.state ?: return
        if (state == UserLifecycleState.INVITED) {
            lifecycleService.transition(
                UserTransitionCommand(
                    organisationId = organisationId,
                    userId = userId,
                    transition = UserLifecycleTransition.ACTIVATE,
                    command =
                        TransitionCommand(
                            reason = FIRST_LOGIN_REASON,
                            metadata = mapOf("activationSource" to "first_login"),
                        ),
                ),
            )
            auditFirstLoginActivation(userId, organisationId)
        }
        if (state == UserLifecycleState.ACTIVE || state == UserLifecycleState.INVITED) {
            store.updateLastLoginAt(userId)
        }
    }

    private fun auditFirstLoginActivation(
        userId: UUID,
        organisationId: UUID,
    ) {
        auditService.record(
            AuditCommand(
                actorType = USER,
                actorId = userId.toString(),
                tenantId = organisationId.toString(),
                action = FIRST_LOGIN_ACTION,
                resourceType = USER_ACCOUNT,
                resourceId = userId.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = FIRST_LOGIN_REASON,
                metadata = mapOf("organisationId" to organisationId.toString()),
            ),
        )
    }

    private companion object {
        const val FIRST_LOGIN_ACTION = "user.first_login_activation"
        const val FIRST_LOGIN_REASON = "First login activation"
        const val USER = "USER"
        const val USER_ACCOUNT = "USER_ACCOUNT"
    }
}

/**
 * Persistence port for refreshing login metadata without exposing user storage to IAM.
 */
interface FirstLoginActivationStore {
    /**
     * Updates the user's last successful login timestamp.
     */
    fun updateLastLoginAt(userId: UUID)
}
