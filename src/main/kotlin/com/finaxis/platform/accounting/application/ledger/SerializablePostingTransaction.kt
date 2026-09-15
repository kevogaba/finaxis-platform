package com.finaxis.platform.accounting.application.ledger

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * Opens the one transaction every accounting write path commits in, at `SERIALIZABLE`.
 *
 * This is the **only** declaration of a non-default isolation level on any accounting write path,
 * and an ArchUnit rule keeps it that way rather than leaving it to review. The obvious
 * alternative - annotating each method that today opens a transaction, reversal and manual-journal
 * approval and whatever a product module writes next - is three places to drift, and all three
 * become *joiners* the moment an outer boundary such as the idempotency executor opens the
 * transaction first. Spring's transaction managers ship with `validateExistingTransaction = false`,
 * so a declared isolation on a joined transaction is dropped with no log line and no exception.
 * One opener is the only shape that survives that.
 *
 * The escalation is scoped to this path on purpose, rather than set as the transaction manager's
 * default: a global default would drag iam, lifecycle and notifications into the predicate-lock
 * bookkeeping `SERIALIZABLE` costs and into `40001` risk, with nothing anywhere to retry them.
 *
 * **Declaring the level here does not prove it holds.** A caller that reaches a write path from
 * inside a transaction it opened itself joins that one and this annotation never applies, which is
 * why [PostingEngine.post] asks the database what is actually in force and refuses anything below
 * `SERIALIZABLE` with `accounting.snapshot_isolation_unavailable`. Entering through
 * [com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary] is what makes
 * that check pass; nothing else should call this bean.
 *
 * Wired by `@Service` rather than by a `@Bean` method on the module configuration, because the
 * Kotlin Spring plugin opens annotated classes for subclass proxying. A `@Bean`-constructed class
 * with no stereotype stays `final`, and `@Transactional` on a final class is advice that silently
 * never runs - the failure mode this whole change exists to remove.
 */
@Service
class SerializablePostingTransaction {
    /** Runs [work] inside a transaction this method opened at `SERIALIZABLE`. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun <T : Any> run(work: () -> T): T = work()
}
