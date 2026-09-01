package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.transitions.TransitionDefinition
import com.finaxis.platform.common.transitions.TransitionGraph
import com.finaxis.platform.common.transitions.Transitionable
import java.util.UUID

/** The named state changes a chart-of-accounts entry can undergo. */
enum class GlAccountTransition {
    /** Draft to awaiting a checker. The maker's act. */
    SUBMIT,

    /** Awaiting a checker to in use. The checker's act, and never the maker's. */
    APPROVE,

    /** Awaiting a checker back to draft, with a reason. There is no terminal rejected state. */
    REJECT,

    /** In use to withdrawn. The only withdrawal there is; nothing deletes an account. */
    DEACTIVATE,
}

/**
 * A GL account as the transition executor sees it.
 *
 * Deliberately carries no tenant. `TransitionExecutor` builds a log's metadata from the
 * `TransitionCommand`, never from the aggregate, so the caller supplies the organisation there —
 * an earlier revision took an `organisationId` that nothing ever read and a KDoc that claimed this
 * type was what put it into the log.
 */
class GlAccountAggregate(
    val accountId: UUID,
    initialStatus: GlAccountStatus,
) : Transitionable<GlAccountStatus> {
    override val aggregateId: String = accountId.toString()

    override val aggregateType: String = AGGREGATE_TYPE

    override var state: GlAccountStatus = initialStatus
        private set

    override fun transitionTo(state: GlAccountStatus) {
        this.state = state
    }

    /** Identifies GL-account rows in the dispatching transition-log repository. */
    companion object {
        const val AGGREGATE_TYPE = "GL_ACCOUNT"
    }
}

/**
 * The chart-of-accounts state machine.
 *
 * Four transitions over the four states `chk_gl_account_status` accepts. There is deliberately no
 * terminal rejected state: `account_code` is unique per tenant, so a rejected account that kept its
 * code would hold it forever and the second attempt could not reuse it. `REJECT` returns the
 * account to `DRAFT` with a reason, which keeps the code with the work in progress.
 *
 * **`INACTIVE` is terminal in this graph, and that is a decision rather than an omission.**
 * Reactivating a withdrawn account would need its own transition and its own control: an account is
 * deactivated because it should no longer receive postings, and quietly reversing that is exactly
 * the kind of change the maker-checker controls exist for. Nothing needs it yet, so nothing ships
 * it — adding it later is a graph entry and a permission decision, not a schema change.
 *
 * No `eventFactories`. Nothing outside accounting consumes a chart-of-accounts state change, and
 * publishing a broker event merely because a transition occurred is what the issue explicitly warns
 * against.
 */
object GlAccountLifecycle {
    /** The declared transitions, as the executor consumes them. */
    val GRAPH: TransitionGraph<GlAccountStatus, GlAccountTransition, GlAccountAggregate> =
        TransitionGraph(
            listOf(
                definition(
                    GlAccountTransition.SUBMIT,
                    GlAccountStatus.DRAFT,
                    GlAccountStatus.PENDING_APPROVAL,
                ),
                definition(
                    GlAccountTransition.APPROVE,
                    GlAccountStatus.PENDING_APPROVAL,
                    GlAccountStatus.ACTIVE,
                ),
                definition(
                    GlAccountTransition.REJECT,
                    GlAccountStatus.PENDING_APPROVAL,
                    GlAccountStatus.DRAFT,
                ),
                definition(
                    GlAccountTransition.DEACTIVATE,
                    GlAccountStatus.ACTIVE,
                    GlAccountStatus.INACTIVE,
                ),
            ),
        )

    private fun definition(
        transition: GlAccountTransition,
        from: GlAccountStatus,
        to: GlAccountStatus,
    ) = TransitionDefinition<GlAccountStatus, GlAccountTransition, GlAccountAggregate>(
        transition = transition,
        from = from,
        to = to,
    )
}
