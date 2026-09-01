package com.finaxis.platform.common.transitions

/**
 * One module's slice of transition-log persistence.
 *
 * The reusable FSM has a single [TransitionExecutor], but a transition log row belongs to the table
 * the owning module created, and no module may write another's. Each module therefore contributes a
 * writer for the aggregate types it owns, and [DispatchingTransitionLogRepository] routes to it.
 *
 * Before this existed, `TransitionLogRepository` was a single unqualified bean whose only
 * implementation was lifecycle's, and its `save` ended in
 * `error("Unsupported lifecycle aggregate type: …")`. That made the shared executor unusable by any
 * second module in two different ways at once: an accounting FSM routed through it would hit that
 * `error`, and adding a second `TransitionLogRepository` bean to avoid it would make the executor's
 * own dependency ambiguous and fail application-context startup platform-wide.
 */
interface TransitionLogWriter {
    /** True when this module owns [aggregateType] and can persist its transition log. */
    fun supports(aggregateType: String): Boolean

    /** Persists a transition audit record for an aggregate type this writer [supports]. */
    fun save(log: TransitionLog)
}

/**
 * Routes a transition log to the module that owns its aggregate type.
 *
 * Fails loudly on an unowned type rather than dropping the row. A transition that mutated state and
 * left no history is exactly the audit gap the FSM exists to close, so silence here would be worse
 * than a failed transaction: the state change would stand with nothing recording who made it.
 *
 * It fails just as loudly when **two** writers claim the same aggregate type. Ownership is meant to
 * be exclusive, so an overlap is a wiring defect, and picking the first match would resolve it by
 * bean-registration order - writing the row to whichever module happened to be constructed first,
 * differently between the application and a test slice, and silently. Two claimants means nobody
 * knows where a period's history lives, which is a worse failure than not starting.
 */
class DispatchingTransitionLogRepository(
    private val writers: List<TransitionLogWriter>,
) : TransitionLogRepository {
    override fun save(log: TransitionLog) {
        val owners = writers.filter { it.supports(log.aggregateType) }
        check(owners.isNotEmpty()) {
            "No module owns transition logs for aggregate type ${log.aggregateType}; " +
                "the module that declared the FSM must contribute a TransitionLogWriter."
        }
        check(owners.size == 1) {
            val names = owners.joinToString { it::class.simpleName.orEmpty() }
            "Aggregate type ${log.aggregateType} is claimed by ${owners.size} " +
                "TransitionLogWriters ($names); transition-log ownership must be exclusive, and " +
                "resolving the overlap by registration order would write history to an arbitrary " +
                "module's table."
        }
        owners.single().save(log)
    }
}
