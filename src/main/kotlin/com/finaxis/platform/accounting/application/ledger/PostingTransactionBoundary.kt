package com.finaxis.platform.accounting.application.ledger
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * The transaction boundary every accounting write commits in, and the only place a serialization
 * failure is retried.
 *
 * **Module-internal, and that placement is load-bearing.** A product module does not reach this
 * class; it reaches
 * [com.finaxis.platform.accounting.application.posting.PostingTransactions], the generics-free
 * contract in accounting's `@NamedInterface` package, which delegates here. The split is not
 * ceremony. Spring Modulith advises every type a module *exposes* with `ModuleEntryInterceptor`,
 * which renders the invoked method's signature for its observation name - and
 * `DefaultObservedModule.render` calls `FormattableType.of(resolvableType.resolve())`, where
 * `resolve()` returns `null` for an unresolvable type variable. A generic method on an exposed
 * bean therefore dies with `NullPointerException: Cannot invoke "java.lang.Class.getTypeName()"
 * because "type" is null` on the first call, before any application code runs. That is a defect in
 * Spring Modulith 2.1.0 rather than in this method, but the way to not ship it is for the generic
 * signature to live where nothing renders it. `AccountingBoundaryRuleTests` fails the build if a
 * type variable reappears on an exposed accounting type.
 *
 * The retry is not on this branch. This one raises the posting path to `SERIALIZABLE` and
 * establishes the seam; the `@Retryable`/`@Recover` pair that makes a serialization failure
 * survivable, and the `accounting.posting_retries_exhausted` code it translates an exhausted budget
 * into, arrive in the branch that follows. Until they do, a `40001` propagates to the caller. The
 * two branches are meant to merge as a unit.
 *
 * Accounting's own write paths enter here directly. A product module enters through
 * [com.finaxis.platform.accounting.application.posting.PostingTransactions], wrapping its own
 * mutation and the posting in one lambda:
 *
 * ```
 * postingTransactions.execute("Disbursing a loan") {
 *     loans.disburse(command)
 *     postingService.post(command.toPostingCommand())
 * }
 * ```
 *
 * Both run in the single transaction this boundary opened, so the source mutation, the product
 * sub-ledger effect and the journal commit or roll back together (`INV-12`), and a retry re-runs
 * *both* - which is what PostgreSQL prescribes for a serialization failure, and the reason the
 * boundary is lambda-shaped rather than a facade the posting hides behind. A facade that retried
 * only the posting would re-run one third of a unit of work whose other two thirds had already
 * rolled back.
 *
 * **Everything inside the lambda must be replayable.** No `REQUIRES_NEW`, no JobRunr enqueue, no
 * broker publish, no `registerSynchronization`: a retry runs the lambda again from the top, and any
 * effect that escaped the rolled-back transaction then happens twice. Externalized domain events
 * are safe because they go to the outbox, which rolls back with everything else.
 *
 * **This class carries no transaction advice, deliberately.** The transaction is opened one call
 * later, on a different bean ([SerializablePostingTransaction]), so that when the retry annotation
 * lands here the retry interceptor is outside the transaction interceptor *structurally* rather
 * than by advisor precedence. That matters because a serialization failure can be raised by
 * `COMMIT` itself - PostgreSQL cancels a transaction identified as an SSI pivot during the commit
 * attempt - and a commit-time exception is thrown *by* the transaction interceptor, so only
 * strictly outer advice can see it. It never reaches jOOQ's `ExecuteListener` either, so no
 * statement-level translator will ever see it. Co-locating the two annotations and pinning their
 * order numerically would be correct today and one `@Order` attribute away from silently never
 * retrying the commit-time case. The separation also means the transaction has committed or rolled
 * back, and its pooled connection has been returned, before any backoff sleeps.
 */
@Service
class PostingTransactionBoundary(
    private val transaction: SerializablePostingTransaction,
) {
    /**
     * Runs [work] in one `SERIALIZABLE` transaction. [operation] names it in refusals and logs.
     *
     * Refuses to run at all when a transaction is already open. Joining one would drop the declared
     * isolation silently - Spring does not validate an existing transaction against the joiner's
     * attributes - and would leave the retry sitting *inside* the transaction it is supposed to
     * retry, where re-running the work only produces `25P02` against a connection the failure has
     * already doomed. Failing loudly on a wiring defect beats degrading quietly into one.
     */
    fun <T : Any> execute(
        operation: String,
        work: () -> T,
    ): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "$operation must own its transaction so a serialization failure can be retried by " +
                "re-running it; entering it from inside one makes the retry impossible."
        }
        return transaction.run(work)
    }
}
