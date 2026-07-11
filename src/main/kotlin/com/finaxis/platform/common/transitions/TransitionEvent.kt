package com.finaxis.platform.common.transitions

import org.springframework.modulith.events.Externalized
import java.time.Instant

/**
 * Common shape for transition-related events published through Spring application events.
 */
interface TransitionEvent {
    /**
     * Business aggregate type that changed state.
     */
    val aggregateType: String

    /**
     * Stable aggregate identifier.
     */
    val aggregateId: String

    /**
     * Transition name.
     */
    val transition: String

    /**
     * Previous state name.
     */
    val fromState: String

    /**
     * New state name.
     */
    val toState: String

    /**
     * Actor that requested the transition.
     */
    val actor: TransitionActor

    /**
     * Time the transition occurred.
     */
    val occurredAt: Instant

    /**
     * Event metadata safe for application listeners and integration messages.
     */
    val metadata: Map<String, Any?>
}

/**
 * Generic internal event useful for infrastructure tests and low-value lifecycle notifications.
 */
data class InternalTransitionEvent(
    override val aggregateType: String,
    override val aggregateId: String,
    override val transition: String,
    override val fromState: String,
    override val toState: String,
    override val actor: TransitionActor,
    override val occurredAt: Instant,
    override val metadata: Map<String, Any?> = emptyMap(),
) : TransitionEvent

/**
 * Generic integration event selected for Spring Modulith externalization through Namastack.
 */
@Externalized("#{target}")
data class ExternalizedTransitionEvent(
    val target: String,
    override val aggregateType: String,
    override val aggregateId: String,
    override val transition: String,
    override val fromState: String,
    override val toState: String,
    override val actor: TransitionActor,
    override val occurredAt: Instant,
    override val metadata: Map<String, Any?> = emptyMap(),
) : TransitionEvent
