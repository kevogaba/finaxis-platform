package com.finaxis.platform.accounting.application.posting

import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import org.springframework.stereotype.Service

/**
 * One unit of work that ends in a posting: a product module's own mutation and the journal it
 * produces, as a single replayable block.
 *
 * Deliberately not `() -> PostingReceipt`. Kotlin compiles a function type to
 * `kotlin.jvm.functions.Function0<? extends PostingReceipt>`, and every generic argument on an
 * exposed accounting type is one more thing Spring Modulith's observability interceptor has to
 * resolve when it renders the method signature. A named interface with no type parameters at all
 * cannot be resolved wrongly. Kotlin's SAM conversion means a caller still writes a trailing
 * lambda and never names this type.
 */
fun interface PostingUnitOfWork {
    /** Performs the mutation and the posting, returning the journal's receipt. */
    fun execute(): PostingReceipt
}

/**
 * The contract a product module enters accounting's write path through.
 *
 * ```
 * postingTransactions.execute("Disbursing a loan") {
 *     loans.disburse(command)
 *     postingService.post(command.toPostingCommand())
 * }
 * ```
 *
 * Both the product's own mutation and the journal run in the one `SERIALIZABLE` transaction this
 * opens, so they commit or roll back together (`INV-12`), and - once the retry lands in the branch
 * that follows - a serialization failure re-runs *both*, which is what PostgreSQL prescribes.
 * A facade that retried only the posting would re-run one third of a unit of work whose other two
 * thirds had already rolled back.
 *
 * **Everything inside the lambda must be replayable.** No `REQUIRES_NEW`, no JobRunr enqueue, no
 * broker publish, no `registerSynchronization`: a retry runs the lambda again from the top, and any
 * effect that escaped the rolled-back transaction then happens twice. Externalized domain events
 * are safe, because they go to the outbox and roll back with everything else.
 *
 * This type exists separately from [PostingTransactionBoundary], which holds the actual logic,
 * for one concrete reason. Accounting's write paths return several different things - a
 * [PostingReceipt] here, a manual-journal approval elsewhere - so the internal boundary is generic.
 * A generic method may not appear on a type this module exposes: Spring Modulith advises every
 * exposed type and renders the invoked signature, and rendering an unresolvable type variable
 * throws `NullPointerException` inside the interceptor before any application code runs. So the
 * generic seam stays internal and this, the only shape a product module needs, names its types.
 * `AccountingBoundaryRuleTests` fails the build if a type variable reappears on an exposed type.
 */
@Service
class PostingTransactions(
    private val boundary: PostingTransactionBoundary,
) {
    /**
     * Runs [work] in one `SERIALIZABLE` transaction. [operation] names it in refusals and logs.
     *
     * Refuses to run at all when a transaction is already open, because joining one would drop the
     * declared isolation silently and would leave the retry sitting inside the transaction it is
     * meant to retry. See [PostingTransactionBoundary.execute].
     */
    fun execute(
        operation: String,
        work: PostingUnitOfWork,
    ): PostingReceipt = boundary.execute(operation) { work.execute() }
}
