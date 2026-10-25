package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** What [ProvisionedMembershipActivation.activate] did for a Keycloak provisioning job. */
enum class ProvisionedActivation {
    /** The membership was activated, or was already active or revoked: nothing is left to do. */
    ACTIVATED_OR_SETTLED,

    /** The job belongs to an earlier life of the membership and changed nothing. */
    STALE,
}

/**
 * The activation step of a Keycloak provisioning job, refused for a job of an earlier life of a
 * re-invited membership (ADR 0037).
 */
@Service
class ProvisionedMembershipActivation(
    private val store: UserProvisioningStore,
    private val lifecycleService: FoundationLifecycleService,
) {
    /**
     * Activates the membership a Keycloak provisioning job has just provisioned, but only while
     * the job's [generation] is still the membership's own, and leaves it alone otherwise. A job
     * of an earlier life of a re-invited member must never activate the row the new invitation put
     * back to `PENDING_APPROVAL`: that would bypass the new checker. The check and the transition
     * run under the membership row's lock, so a re-invitation cannot commit between them: it either
     * lands first and this finds a stale job, or waits for this to commit and then finds the
     * member no longer revoked.
     */
    @Transactional
    fun activate(
        organisationId: UUID,
        membershipId: UUID,
        generation: Int,
    ): ProvisionedActivation {
        val snapshot = store.lockedMembershipSnapshot(organisationId, membershipId)
        if (snapshot != null && snapshot.generation != generation) {
            return ProvisionedActivation.STALE
        }
        when (snapshot?.status) {
            MembershipLifecycleState.PENDING_APPROVAL -> {
                lifecycleService.transition(
                    MembershipTransitionCommand(
                        organisationId = organisationId,
                        membershipId = membershipId,
                        transition = MembershipLifecycleTransition.ACTIVATE,
                    ),
                )
            }

            MembershipLifecycleState.ACTIVE,
            MembershipLifecycleState.REVOKED,
            -> {}

            else -> {
                error("Membership is not waiting for activation.")
            }
        }
        return ProvisionedActivation.ACTIVATED_OR_SETTLED
    }
}
