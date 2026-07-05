package com.finaxis.platform.common.transitions

/**
 * Minimal contract implemented by aggregates whose lifecycle is driven by a finite state machine.
 */
interface Transitionable<S>
    where S : Enum<S> {
    /**
     * Stable aggregate identifier used in logs, events, and external messages.
     */
    val aggregateId: String

    /**
     * Business aggregate type, for example `SHIPMENT`, `TRIP`, or `INVENTORY_TRANSFER`.
     */
    val aggregateType: String

    /**
     * Current lifecycle state.
     */
    val state: S

    /**
     * Applies the already validated target state to the aggregate.
     */
    fun transitionTo(state: S)
}
