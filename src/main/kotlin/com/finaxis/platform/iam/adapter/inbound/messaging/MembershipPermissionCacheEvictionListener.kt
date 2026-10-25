package com.finaxis.platform.iam.adapter.inbound.messaging

import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.iam.application.authorization.PermissionCacheInvalidator
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Evicts a membership's cached effective permissions when it is invited again or activated (ADR
 * 0037, #172).
 *
 * The `iam.effective-permissions` cache is keyed by membership id and selected branch, and a
 * re-invited member keeps the membership id, so an entry cached in the first life would otherwise
 * be served again once the new approval makes the membership `ACTIVE`: the gate in the principal
 * loader only looks at the status, so the first life's roles and direct overrides (which the
 * re-invitation deletes) could be served for up to the cache's time-to-live, measured from the last
 * first-life write. The lifecycle module may not call this module, so it publishes its
 * `REINVITE` and `ACTIVATE` transition events and this listener reacts to them.
 *
 * It is a plain [EventListener] that registers a [TransactionSynchronization] whose `afterCommit`
 * evicts, and deliberately **not** a `@TransactionalEventListener`: Spring Modulith's persistent
 * event multicaster would store an `event_publication` row for every transition event of every
 * aggregate (the parameter type is the common event interface) inside the publishing transaction,
 * which ADR 0018 keeps free of after-commit registry listeners. The synchronisation still runs
 * **after commit**, never before the transaction can still roll back, and does nothing on a
 * rollback. With no synchronisation active (an event published outside a transaction) it evicts
 * at once. Like the role and assignment edits, it reaches the entries this instance cached; a
 * Redis failure is logged and not rethrown, because the commit has already happened.
 */
@Component
class MembershipPermissionCacheEvictionListener(
    private val invalidator: PermissionCacheInvalidator,
) {
    /** Evicts the membership of a `REINVITE` or `ACTIVATE` transition once it has committed. */
    @EventListener
    fun onTransition(event: TransitionEvent) {
        if (event.aggregateType != MEMBERSHIP || event.transition !in EVICTING_TRANSITIONS) return
        val membershipId = runCatching { UUID.fromString(event.aggregateId) }.getOrNull() ?: return
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = evict(membershipId, event.transition)
                },
            )
        } else {
            evict(membershipId, event.transition)
        }
    }

    private fun evict(
        membershipId: UUID,
        transition: String,
    ) {
        try {
            invalidator.evictMembership(membershipId)
        } catch (failure: DataAccessException) {
            logger.warn(
                "Could not evict the cached permissions of membership {} after {}: {}",
                membershipId,
                transition,
                failure.javaClass.simpleName,
            )
        }
    }

    private companion object {
        const val MEMBERSHIP = "MEMBERSHIP"
        val EVICTING_TRANSITIONS = setOf("REINVITE", "ACTIVATE")
        private val logger =
            LoggerFactory.getLogger(MembershipPermissionCacheEvictionListener::class.java)
    }
}
