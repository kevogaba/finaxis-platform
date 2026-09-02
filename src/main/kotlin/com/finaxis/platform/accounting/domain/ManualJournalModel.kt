package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.transitions.TransitionDefinition
import com.finaxis.platform.common.transitions.TransitionGraph
import com.finaxis.platform.common.transitions.Transitionable
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The manual-journal lifecycle, matching `chk_manual_journal_status` exactly. */
enum class ManualJournalStatus {
    /** Being prepared. The only state in which lines and dates may change. */
    DRAFT,

    /** Submitted and awaiting a checker who is not the maker. */
    PENDING_APPROVAL,

    /** Approved, and therefore posted as an ordinary immutable journal. */
    POSTED,

    /** Withdrawn by its maker before submission or after rejection. Terminal. */
    CANCELLED,
}

/** The named state changes a manual journal can undergo. */
enum class ManualJournalTransition {
    /** Draft to awaiting a checker. The maker's act. */
    SUBMIT,

    /** Awaiting a checker to posted: the checker's act, never the maker's, plus the posting. */
    APPROVE,

    /** Awaiting a checker back to draft, with a reason. */
    REJECT,

    /** Draft to cancelled. Terminal. */
    CANCEL,
}

/** A manual journal as the transition executor sees it. */
class ManualJournalAggregate(
    val journalId: UUID,
    initialStatus: ManualJournalStatus,
) : Transitionable<ManualJournalStatus> {
    override val aggregateId: String = journalId.toString()

    override val aggregateType: String = AGGREGATE_TYPE

    override var state: ManualJournalStatus = initialStatus
        private set

    override fun transitionTo(state: ManualJournalStatus) {
        this.state = state
    }

    /** Identifies manual-journal rows in the dispatching transition-log repository. */
    companion object {
        const val AGGREGATE_TYPE = "MANUAL_JOURNAL"
    }
}

/**
 * The manual-journal state machine.
 *
 * `APPROVE` is the only path to `POSTED`, and approval *is* the posting: the transition and the
 * `journal_entry` it produces commit together or not at all. There is no `POST` transition a client
 * could call, and no way to supply the final journal identity - the engine allocates it.
 */
object ManualJournalLifecycle {
    /** The declared transitions, as the executor consumes them. */
    val GRAPH:
        TransitionGraph<ManualJournalStatus, ManualJournalTransition, ManualJournalAggregate> =
        TransitionGraph(
            listOf(
                definition(
                    ManualJournalTransition.SUBMIT,
                    ManualJournalStatus.DRAFT,
                    ManualJournalStatus.PENDING_APPROVAL,
                ),
                definition(
                    ManualJournalTransition.APPROVE,
                    ManualJournalStatus.PENDING_APPROVAL,
                    ManualJournalStatus.POSTED,
                ),
                definition(
                    ManualJournalTransition.REJECT,
                    ManualJournalStatus.PENDING_APPROVAL,
                    ManualJournalStatus.DRAFT,
                ),
                definition(
                    ManualJournalTransition.CANCEL,
                    ManualJournalStatus.DRAFT,
                    ManualJournalStatus.CANCELLED,
                ),
            ),
        )

    private fun definition(
        transition: ManualJournalTransition,
        from: ManualJournalStatus,
        to: ManualJournalStatus,
    ) = TransitionDefinition<ManualJournalStatus, ManualJournalTransition, ManualJournalAggregate>(
        transition = transition,
        from = from,
        to = to,
    )
}

/** A manual journal as the domain sees it. */
data class ManualJournal(
    val id: UUID,
    val organisationId: UUID,
    val branchId: UUID?,
    val title: String,
    val narrative: String,
    val status: ManualJournalStatus,
    val statusReason: String?,
    val transactionDate: LocalDate?,
    val valueDate: LocalDate?,
    val postingDate: LocalDate?,
    val journalEntryId: UUID?,
    val createdBy: UUID?,
    val rowVersion: Long = 0,
)

/** One explicit debit or credit of a manual journal. */
data class ManualJournalLine(
    val lineNumber: Int,
    val accountId: UUID,
    val side: PostingSide,
    val amount: BigDecimal,
    val currencyCode: String,
    val narrative: String?,
)
