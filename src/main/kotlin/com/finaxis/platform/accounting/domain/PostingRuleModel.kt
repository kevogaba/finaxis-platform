package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.transitions.TransitionDefinition
import com.finaxis.platform.common.transitions.TransitionGraph
import com.finaxis.platform.common.transitions.Transitionable
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The posting-rule-version lifecycle, matching `chk_posting_rule_version_status` exactly.
 *
 * Rejection returns a version to [DRAFT] with a reason; there is no terminal rejected state, for
 * the same reason the chart of accounts has none. Three statuses are *approved* - [ACTIVE],
 * [SUPERSEDED] and [RETIRED] - and all three resolve a posting whose date falls inside the
 * version's effective range; the status records how the window came to be closed, not whether the
 * version may govern the dates it covers.
 */
enum class PostingRuleVersionStatus {
    /** Being prepared. The only state in which legs and the effective-from date may change. */
    DRAFT,

    /** Submitted and awaiting a checker who is not the maker. */
    PENDING_APPROVAL,

    /** Approved and the rule's current head. */
    ACTIVE,

    /** Closed by a successor version's activation. */
    SUPERSEDED,

    /** Closed deliberately with no successor. */
    RETIRED,

    ;

    /** Whether the version has been approved and may resolve postings inside its window. */
    val isApproved: Boolean
        get() = this == ACTIVE || this == SUPERSEDED || this == RETIRED
}

/** The named state changes a posting-rule version can undergo. */
enum class PostingRuleVersionTransition {
    /** Draft to awaiting a checker. The maker's act. */
    SUBMIT,

    /** Awaiting a checker to in force. The checker's act, and never the maker's. */
    APPROVE,

    /** Awaiting a checker back to draft, with a reason. */
    REJECT,

    /** In force to closed by a successor. Performed by the successor's activation, not by hand. */
    SUPERSEDE,

    /** In force to closed with no successor. */
    RETIRE,
}

/** A posting-rule version as the transition executor sees it. */
class PostingRuleVersionAggregate(
    val versionId: UUID,
    initialStatus: PostingRuleVersionStatus,
) : Transitionable<PostingRuleVersionStatus> {
    override val aggregateId: String = versionId.toString()

    override val aggregateType: String = AGGREGATE_TYPE

    override var state: PostingRuleVersionStatus = initialStatus
        private set

    override fun transitionTo(state: PostingRuleVersionStatus) {
        this.state = state
    }

    /** Identifies posting-rule-version rows in the dispatching transition-log repository. */
    companion object {
        const val AGGREGATE_TYPE = "POSTING_RULE_VERSION"
    }
}

/**
 * The posting-rule-version state machine.
 *
 * `SUPERSEDE` and `RETIRE` both leave `ACTIVE`, and both are one-way: a closed version's window is
 * history, and re-opening it would let a rule govern dates it did not govern when postings were
 * made on them. A correction to a closed version is a new version. No `eventFactories`, for the
 * reason the other accounting graphs give: nothing outside accounting consumes a rule change.
 */
object PostingRuleVersionLifecycle {
    /** The declared transitions, as the executor consumes them. */
    val GRAPH: TransitionGraph<
        PostingRuleVersionStatus,
        PostingRuleVersionTransition,
        PostingRuleVersionAggregate,
    > =
        TransitionGraph(
            listOf(
                definition(
                    PostingRuleVersionTransition.SUBMIT,
                    PostingRuleVersionStatus.DRAFT,
                    PostingRuleVersionStatus.PENDING_APPROVAL,
                ),
                definition(
                    PostingRuleVersionTransition.APPROVE,
                    PostingRuleVersionStatus.PENDING_APPROVAL,
                    PostingRuleVersionStatus.ACTIVE,
                ),
                definition(
                    PostingRuleVersionTransition.REJECT,
                    PostingRuleVersionStatus.PENDING_APPROVAL,
                    PostingRuleVersionStatus.DRAFT,
                ),
                definition(
                    PostingRuleVersionTransition.SUPERSEDE,
                    PostingRuleVersionStatus.ACTIVE,
                    PostingRuleVersionStatus.SUPERSEDED,
                ),
                definition(
                    PostingRuleVersionTransition.RETIRE,
                    PostingRuleVersionStatus.ACTIVE,
                    PostingRuleVersionStatus.RETIRED,
                ),
            ),
        )

    private fun definition(
        transition: PostingRuleVersionTransition,
        from: PostingRuleVersionStatus,
        to: PostingRuleVersionStatus,
    ) = TransitionDefinition<
        PostingRuleVersionStatus,
        PostingRuleVersionTransition,
        PostingRuleVersionAggregate,
    >(transition = transition, from = from, to = to)
}

/**
 * A posting rule: the tenant's answer to one financial event, selected by the event and two
 * optional dimensions. Carries no status; its versions do.
 */
data class PostingRule(
    val id: UUID,
    val organisationId: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val selector: PostingRuleSelector,
    val rowVersion: Long = 0,
)

/**
 * What a rule answers. [productClass] and [currencyCode] are optional dimensions; null means
 * *any*. [specificity] orders candidate rules when more than one matches an intent.
 */
data class PostingRuleSelector(
    val eventCode: String,
    val productClass: String? = null,
    val currencyCode: String? = null,
) {
    /** How many optional dimensions the selector pins: 0, 1 or 2. */
    val specificity: Int
        get() = listOfNotNull(productClass, currencyCode).size

    /** Whether this selector applies to an intent with the given dimensions. */
    fun matches(
        eventCode: String,
        productClass: String?,
        currencyCode: String,
    ): Boolean =
        this.eventCode == eventCode &&
            (this.productClass == null || this.productClass == productClass) &&
            (this.currencyCode == null || this.currencyCode == currencyCode)
}

/** One approved-or-proposed set of legs for a rule, effective from a date. */
data class PostingRuleVersion(
    val id: UUID,
    val organisationId: UUID,
    val ruleId: UUID,
    val versionNumber: Int,
    val status: PostingRuleVersionStatus,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val description: String?,
    val statusReason: String? = null,
    val rowVersion: Long = 0,
) {
    /** Whether the version governs [postingDate]: approved, and the date inside its window. */
    fun governs(postingDate: LocalDate): Boolean =
        status.isApproved &&
            !postingDate.isBefore(effectiveFrom) &&
            (effectiveTo == null || !postingDate.isAfter(effectiveTo))
}

/** How a leg finds its account. `FIXED_ACCOUNT` is the only strategy this schema admits. */
enum class AccountResolution {
    /** The leg names its account directly. */
    FIXED_ACCOUNT,
}

/**
 * One ordered debit or credit of a version: the account it lands in and the financial fact - by
 * [amountSource] - it takes its amount from, as [amountPercentage] of that fact.
 */
data class PostingRuleLeg(
    val legNumber: Int,
    val side: PostingSide,
    val accountResolution: AccountResolution,
    val accountId: UUID,
    val amountSource: String,
    val amountPercentage: BigDecimal,
    val isResidual: Boolean,
    val narrative: String?,
)
