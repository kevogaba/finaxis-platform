package com.finaxis.platform.accounting.application.posting

/**
 * The single public accounting entry point for every balance-affecting business transaction.
 *
 * Product modules express posting intent and financial facts here; they never insert, update or
 * delete journals, journal lines, or any accounting table, and they never choose a general-ledger
 * account. That restriction is enforced by `AccountingBoundaryRuleTests`, not left to review.
 *
 * BIAN: Financial Accounting (adapted). Implementations run inside the caller's Spring transaction,
 * so the source-domain mutation, the product subsidiary-ledger effect and the general-ledger
 * journal commit or roll back together - see
 * `docs/adr/0018-financial-transaction-atomicity-invariant.md`. The implementation is delivered by
 * issue #41; this module defines only the contract.
 */
interface PostingService {
    /**
     * Records the supplied posting intent as one posting request, one balanced journal entry and
     * its journal lines. Persists nothing when the intent is unbalanced, the fiscal period is
     * closed, an account is not postable, or the caller is not authorized.
     */
    fun post(command: PostFinancialFactsCommand): PostingReceipt

    /**
     * Reverses a posted journal entry by writing a compensating entry. Posted financial history is
     * never updated or deleted; reversal is the only legal correction path, per
     * `docs/adr/0020-immutable-ledger-and-reversal-only-correction.md`.
     */
    fun reverse(command: ReversePostingCommand): PostingReceipt
}
