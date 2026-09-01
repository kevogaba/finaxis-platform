package com.finaxis.platform.accounting.domain

import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.common.transitions.TransitionDefinition
import com.finaxis.platform.common.transitions.TransitionGraph
import com.finaxis.platform.common.transitions.Transitionable

/** The named state changes a fiscal period can undergo. */
enum class FiscalPeriodTransition {
    /** Provisioned but not yet postable, to postable. */
    OPEN,

    /** Postable to closed. Reversible only through [REOPEN], and only by a different actor. */
    CLOSE,

    /** Closed back to postable. Privileged, reason-mandatory and audited. */
    REOPEN,

    /** Closed to permanently final. Terminal: nothing transitions out of `LOCKED`. */
    LOCK,
}

/**
 * The fiscal period as the transition executor sees it.
 *
 * Carries the tenant because [key] is the period's identity and the organisation is half of it.
 *
 * It is **not** what puts the tenant into the transition log: `TransitionExecutor` builds a log's
 * metadata from the `TransitionCommand`, never from the aggregate, so the caller supplies it there.
 * An earlier revision of this KDoc claimed otherwise, which would have led anyone dropping that
 * metadata from a new command into the writer's `requireNotNull` failure inside a committed state
 * change.
 */
class FiscalPeriodAggregate(
    val key: FiscalPeriodKey,
    initialStatus: FiscalPeriodStatus,
) : Transitionable<FiscalPeriodStatus> {
    override val aggregateId: String = key.fiscalPeriodId.toString()

    override val aggregateType: String = AGGREGATE_TYPE

    override var state: FiscalPeriodStatus = initialStatus
        private set

    override fun transitionTo(state: FiscalPeriodStatus) {
        this.state = state
    }

    /** Identifies fiscal-period rows in the dispatching transition-log repository. */
    companion object {
        const val AGGREGATE_TYPE = "FISCAL_PERIOD"
    }
}

/**
 * The fiscal-period state machine.
 *
 * Every legal source state, transition name and target state is declared here, and the direction
 * of each is deterministic — the reusable infrastructure keys one definition per transition name,
 * which is why reaching `OPEN` from `FUTURE` and from `CLOSED` are two differently named
 * transitions rather than one.
 *
 * **`LOCK` is only reachable from `CLOSED`, and that is deliberate.** Locking is *"this period is
 * final"*, which is a decision made about a period whose books are already closed; a direct
 * `OPEN → LOCKED` would let a period skip the closing controls entirely. Nothing transitions out
 * of `LOCKED`, which is what `FiscalPeriodStateChangeGuard` already refused before any transition
 * existed to produce it — this graph is what makes that state reachable at all, instead of a value
 * the enum carried and nothing could ever set.
 *
 * No `eventFactories`. Nothing outside accounting consumes a period state change yet, and
 * publishing a broker event merely because a transition occurred is what
 * `docs/adr/0004-membership-activation-notification-pipeline.md` warns against. Issue #49's
 * reporting read models are the first plausible consumer.
 */
object FiscalPeriodLifecycle {
    /** The declared transitions, as the executor consumes them. */
    val GRAPH: TransitionGraph<FiscalPeriodStatus, FiscalPeriodTransition, FiscalPeriodAggregate> =
        TransitionGraph(
            listOf(
                definition(
                    FiscalPeriodTransition.OPEN,
                    FiscalPeriodStatus.FUTURE,
                    FiscalPeriodStatus.OPEN,
                ),
                definition(
                    FiscalPeriodTransition.CLOSE,
                    FiscalPeriodStatus.OPEN,
                    FiscalPeriodStatus.CLOSED,
                ),
                definition(
                    FiscalPeriodTransition.REOPEN,
                    FiscalPeriodStatus.CLOSED,
                    FiscalPeriodStatus.OPEN,
                ),
                definition(
                    FiscalPeriodTransition.LOCK,
                    FiscalPeriodStatus.CLOSED,
                    FiscalPeriodStatus.LOCKED,
                ),
            ),
        )

    private fun definition(
        transition: FiscalPeriodTransition,
        from: FiscalPeriodStatus,
        to: FiscalPeriodStatus,
    ) = TransitionDefinition<FiscalPeriodStatus, FiscalPeriodTransition, FiscalPeriodAggregate>(
        transition = transition,
        from = from,
        to = to,
    )
}
