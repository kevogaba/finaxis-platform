package com.finaxis.platform.common.transitions

/**
 * Outbound port responsible for storing transition audit records.
 */
fun interface TransitionLogRepository {
    /**
     * Persists a transition audit record.
     */
    fun save(log: TransitionLog)
}
