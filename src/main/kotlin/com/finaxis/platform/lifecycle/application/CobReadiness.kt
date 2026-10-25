package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.accounting.CloseReadinessSnapshot
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import java.time.LocalDate

/** Whether a failed check stops [CobReadinessView.ready] or only advises (ADR 0038). */
enum class CobCheckSeverity {
    /** A failed blocking check makes the tenant not ready. */
    BLOCKING,

    /** An advisory check can only warn; it never changes `ready`. */
    ADVISORY,
}

/** The outcome of one close-of-business readiness check. */
enum class CobCheckStatus {
    /** Nothing open for this check. */
    PASS,

    /** A blocking check found open work. */
    FAIL,

    /** An advisory check found open work; the tenant can still close. */
    WARN,
}

/**
 * One close-of-business readiness check.
 *
 * @property code stable machine-readable check name.
 * @property detail human-readable result; never carries ids or amounts.
 * @property count how many items the check found, for the checks that count items.
 */
data class CobReadinessCheck(
    val code: String,
    val severity: CobCheckSeverity,
    val status: CobCheckStatus,
    val detail: String,
    val count: Long? = null,
)

/**
 * A point-in-time answer to *"is this tenant ready to start close-of-business?"* (#168). It is
 * advice: nothing is locked, and `cob/start` and `cob/complete` do not consult it.
 *
 * @property ready true when no [CobCheckSeverity.BLOCKING] check failed.
 */
data class CobReadinessView(
    val businessDate: LocalDate,
    val status: String,
    val ready: Boolean,
    val checks: List<CobReadinessCheck>,
)

/** Builds the fixed list of readiness checks from the facts the service has read. */
internal object CobReadinessChecks {
    const val BUSINESS_DATE_OPEN = "business_date_open"
    const val ORGANISATION_ACTIVE = "organisation_active"
    const val MANUAL_JOURNALS_PENDING_APPROVAL = "manual_journals_pending_approval"
    const val MANUAL_JOURNALS_DRAFT = "manual_journals_draft"
    const val RECONCILIATION_BREAKS = "reconciliation_breaks"

    fun evaluate(
        businessDate: LocalDate,
        businessDateStatus: String,
        organisationState: OrganisationLifecycleState?,
        accounting: CloseReadinessSnapshot,
    ): CobReadinessView {
        val checks =
            listOf(
                businessDateOpen(businessDateStatus),
                organisationActive(organisationState),
                pendingApproval(accounting.pendingApprovalManualJournals),
                drafts(accounting.draftManualJournals),
                breaks(accounting.latestBreakRuns),
            )
        val ready =
            checks.none {
                it.severity == CobCheckSeverity.BLOCKING && it.status == CobCheckStatus.FAIL
            }
        return CobReadinessView(businessDate, businessDateStatus, ready, checks)
    }

    private fun businessDateOpen(status: String) =
        if (status == OPEN) {
            blocking(BUSINESS_DATE_OPEN, true, "The business date is OPEN.")
        } else {
            blocking(
                BUSINESS_DATE_OPEN,
                false,
                "The business date is $status; close-of-business starts only from OPEN.",
            )
        }

    private fun organisationActive(state: OrganisationLifecycleState?) =
        if (state == OrganisationLifecycleState.ACTIVE) {
            blocking(ORGANISATION_ACTIVE, true, "The organisation is ACTIVE.")
        } else {
            blocking(ORGANISATION_ACTIVE, false, "The organisation is ${state ?: "UNKNOWN"}.")
        }

    private fun pendingApproval(count: Long) =
        blocking(
            MANUAL_JOURNALS_PENDING_APPROVAL,
            count == 0L,
            if (count == 0L) {
                "No manual journals are awaiting approval."
            } else {
                "$count manual journal(s) await approval; decide them before closing."
            },
            count,
        )

    private fun drafts(count: Long) =
        advisory(
            MANUAL_JOURNALS_DRAFT,
            count,
            "No draft manual journals.",
            "$count draft manual journal(s) are not yet submitted.",
        )

    private fun breaks(count: Long) =
        advisory(
            RECONCILIATION_BREAKS,
            count,
            "No control account's latest reconciliation run is a break.",
            "$count control account(s) have an unresolved reconciliation break.",
        )

    private fun blocking(
        code: String,
        passed: Boolean,
        detail: String,
        count: Long? = null,
    ) = CobReadinessCheck(
        code,
        CobCheckSeverity.BLOCKING,
        if (passed) CobCheckStatus.PASS else CobCheckStatus.FAIL,
        detail,
        count,
    )

    private fun advisory(
        code: String,
        count: Long,
        passDetail: String,
        warnDetail: String,
    ) = CobReadinessCheck(
        code,
        CobCheckSeverity.ADVISORY,
        if (count == 0L) CobCheckStatus.PASS else CobCheckStatus.WARN,
        if (count == 0L) passDetail else warnDetail,
        count,
    )

    private const val OPEN = "OPEN"
}
