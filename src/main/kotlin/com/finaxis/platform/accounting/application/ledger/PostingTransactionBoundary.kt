package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.common.application.ConflictException
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Recover
import org.springframework.retry.annotation.Retryable
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
 * **A serialization failure re-runs the whole unit of work, up to [PostingRetryPolicy.MAX_ATTEMPTS]
 * times.** That is not a safety net bolted onto an unlikely event: at `SERIALIZABLE` every posting
 * in a tenant updates that tenant's single gapless `reference_sequence` row, so any two overlapping
 * same-tenant postings produce one winner and one `40001`, measured. Retrying is how the loser
 * makes progress, because a retry opens a *new* transaction and therefore takes a fresh snapshot
 * that includes the winner's commit - and no amount of in-transaction lock ordering substitutes for
 * that, since any lock that makes the loser wait is acquired after its snapshot is already fixed.
 * Once the budget is spent the request is given back as
 * [PostingErrorCodes.POSTING_RETRIES_EXHAUSTED] rather than as a raw data-access exception.
 *
 * One consequence worth stating because an auditor will meet it: a backdated posting records its
 * `journal.post_prior_period` authority independently of the transaction, so that the record
 * survives a rollback that happens after the gate. A retried backdated posting therefore leaves up
 * to [PostingRetryPolicy.MAX_ATTEMPTS] such rows, each of them true - every attempt did locate,
 * lock and validate the period, and authority was exercised on each. A query counting exercises of
 * that authority must group rather than count rows, and the key is the source reference the
 * metadata carries: actor, tenant, `sourceModule` and `sourceReference`. Not the fiscal period with
 * the posting date - one operator backdating a batch of corrections into one period on one date
 * wears a single such tuple, so grouping there would merge authorities that are genuinely separate.
 * See [com.finaxis.platform.accounting.application.PostingPeriodResolver], which writes the row and
 * carries the full argument.
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
     *
     * **[ConcurrencyFailureException], not `CannotSerializeTransactionException`.** One SQLSTATE
     * arrives here as two unrelated Spring classes. A statement-level `40001` is translated by
     * jOOQ's execute listener through `SQLErrorCodeSQLExceptionTranslator("PostgreSQL")` into
     * `CannotSerializeTransactionException`; a `40001` raised by `COMMIT` never reaches jOOQ at
     * all and is translated by `JdbcTransactionManager`, which in Spring 7 defaults to
     * `SQLExceptionSubclassTranslator` absent a user `sql-error-codes.xml` - this repository has
     * none - whose `instanceof` chain misses pgjdbc's `PSQLException` and falls through to
     * `SQLStateSQLExceptionTranslator`, yielding `CannotAcquireLockException`. Naming the jOOQ
     * class alone would silently never retry an SSI pivot, the case the design exists for.
     * [ConcurrencyFailureException] is their only common supertype, and it also covers `40P01`
     * deadlocks. [OptimisticLockingFailureException] extends it too and is excluded: retrying a
     * row-version conflict is a different decision, taken by whoever owns that row. Accounting's
     * own [ConflictException] is outside this hierarchy, so `accounting.fiscal_period_closed`
     * propagates un-retried on the first attempt, as it should.
     *
     * The backoff is deliberately uniform-random rather than exponential; [PostingRetryPolicy]
     * carries the reasoning and the numbers.
     *
     * **The annotation below is `org.springframework.retry.annotation.Retryable`, and the package
     * matters.** Spring Framework 7.0.9 ships `org.springframework.resilience.annotation.Retryable`
     * too - a same-named annotation differing only by package, which an IDE will happily auto-
     * import. It has no `@Recover`, so the wrong symbol still compiles, still retries, and silently
     * drops [exhausted] along with the whole of the named error code this class exists to publish.
     * Check the import before changing anything here.
     */
    @Retryable(
        retryFor = [ConcurrencyFailureException::class],
        noRetryFor = [OptimisticLockingFailureException::class],
        maxAttempts = PostingRetryPolicy.MAX_ATTEMPTS,
        backoff =
            Backoff(
                delay = PostingRetryPolicy.MIN_BACKOFF_MILLIS,
                maxDelay = PostingRetryPolicy.MAX_BACKOFF_MILLIS,
            ),
    )
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

    /**
     * Gives the request back with a named code once the retry budget is spent.
     *
     * [ConflictException] is the only member of the sealed `ApplicationException` family that
     * accepts a cause, so [failure] is preserved for the logs without any of it reaching the RFC
     * 9457 body. The work lambda is deliberately not invoked and not inspected; it is declared only
     * because Spring Retry matches a `@Recover` method reflectively, against the retried method's
     * erased parameter list following the exception, and a signature that does not mirror
     * [execute]'s is not a startup error - it silently rethrows the original and this code never
     * reaches a caller. Hence the narrow suppression rather than dropping the parameter.
     */
    @Recover
    fun <T : Any> exhausted(
        failure: ConcurrencyFailureException,
        operation: String,
        @Suppress("UNUSED_PARAMETER") work: () -> T,
    ): T =
        throw ConflictException(
            code = PostingErrorCodes.POSTING_RETRIES_EXHAUSTED,
            safeDetail = "$operation lost a serialization race on every attempt; retry it.",
            cause = failure,
        )

    /**
     * Hands a row-version conflict back untouched.
     *
     * [OptimisticLockingFailureException] is named in `noRetryFor`, so it is never retried - but
     * "not retried" and "propagated" are *not* the same thing in Spring Retry, which is the trap
     * this method exists to close. `RetryTemplate` routes **every** terminal outcome through the
     * recovery handler, an exhausted budget and an exception the policy refused to retry alike. A
     * row-version conflict is an [OptimisticLockingFailureException] and therefore also a
     * [ConcurrencyFailureException], so without this more specific overload it would match
     * [exhausted] and come back as `accounting.posting_retries_exhausted` after a single attempt -
     * a named code asserting a race that never happened, over a failure whose real owner is
     * whoever holds that row.
     */
    @Recover
    fun <T : Any> notRetryable(
        failure: OptimisticLockingFailureException,
        @Suppress("UNUSED_PARAMETER") operation: String,
        @Suppress("UNUSED_PARAMETER") work: () -> T,
    ): T = throw failure

    /**
     * Hands anything else back exactly as it was thrown.
     *
     * The catch-all, and load-bearing rather than defensive. Because `RetryTemplate` sends every
     * terminal outcome to the recovery handler, a failure the policy never even considered
     * retryable still arrives here - and `RecoverAnnotationRecoveryHandler` answers a recovery it
     * cannot match by throwing `ExhaustedRetryException("Cannot locate recovery method")`, which
     * discards the original. Without this overload every ordinary accounting refusal on the
     * posting path - `accounting.fiscal_period_closed`, `accounting.unbalanced_posting`, a context
     * mismatch, the boundary's own nested-transaction `check` - would reach the caller as an
     * opaque Spring Retry exception and be answered `500 internal_error` instead of the 409 and
     * the documented code it earned. A test proves it; this is not theoretical.
     */
    @Recover
    fun <T : Any> propagate(
        failure: Throwable,
        @Suppress("UNUSED_PARAMETER") operation: String,
        @Suppress("UNUSED_PARAMETER") work: () -> T,
    ): T = throw failure
}
