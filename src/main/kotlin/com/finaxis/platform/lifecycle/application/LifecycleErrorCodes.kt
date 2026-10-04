package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.domain.FoundationLifecycleDefinitions

/**
 * Stable public error codes the lifecycle module supplies to the shared
 * [com.finaxis.platform.common.application.ApplicationException] family, so lifecycle failures
 * surface through the existing RFC 9457 problem contract without a parallel exception hierarchy.
 *
 * Deliberately sparse. Most lifecycle conflicts are raised with the family's default `conflict`
 * code, which is adequate while the only thing a caller can do about them is read the message. A
 * code is added here when a caller has to **tell one failure from another to act on it** - which is
 * what a lock-wait expiry is: retryable, and distinguishable from the optimistic-lock conflict the
 * same method raises when someone else got there first.
 */
object LifecycleErrorCodes {
    /**
     * A business-date mutation could not take its lock within the configured bound.
     *
     * Retryable, and deliberately bounded: a queued exclusive request also makes every new posting
     * for the tenant queue behind it, so a mutation that cannot get in must give up rather than
     * stall the ledger. See [BusinessDateProperties].
     */
    const val BUSINESS_DATE_LOCK_TIMEOUT = "lifecycle.business_date_lock_timeout"

    /**
     * A platform checker step was refused because the tenant already has its own active members or
     * branches beyond the bootstrap ones, so its own approvers must approve (ADR 0028).
     */
    const val PLATFORM_CHECKER_CLOSED = "lifecycle.platform_checker_closed"

    /** The safe detail that accompanies [PLATFORM_CHECKER_CLOSED]. */
    const val PLATFORM_CHECKER_CLOSED_DETAIL =
        "The tenant already has its own active members or branches, so its own approvers must " +
            "approve this."

    /**
     * A tenant approval was refused because the approving platform user is the account the draft
     * names as the tenant's initial administrator, which the bootstrap would then have to approve.
     */
    const val APPROVER_IS_INITIAL_ADMINISTRATOR = "lifecycle.approver_is_initial_administrator"

    /** The safe detail that accompanies [APPROVER_IS_INITIAL_ADMINISTRATOR]. */
    const val APPROVER_IS_INITIAL_ADMINISTRATOR_DETAIL =
        "The approver cannot be the tenant's initial administrator."

    /**
     * The reserved `PLATFORM` organisation was named as the target of a tenant lifecycle action
     * (amend, submit, approve, reject, return, suspend, reactivate, deprovision, bootstrap retry).
     * It is the identity every platform principal authenticates against, so it is never a tenant
     * to be moved through the tenant lifecycle: a refusal, not a 404, because the organisation
     * does exist and the caller is entitled to know why the action is not available.
     */
    const val PLATFORM_ORGANISATION_PROTECTED = "lifecycle.platform_organisation_protected"

    /** The safe detail that accompanies [PLATFORM_ORGANISATION_PROTECTED]. */
    const val PLATFORM_ORGANISATION_PROTECTED_DETAIL =
        FoundationLifecycleDefinitions.PLATFORM_ORGANISATION_PROTECTED_DETAIL
}
