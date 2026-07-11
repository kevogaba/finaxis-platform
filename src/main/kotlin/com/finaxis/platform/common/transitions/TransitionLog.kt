package com.finaxis.platform.common.transitions

import java.time.Instant

/**
 * Audit record created for an accepted transition.
 */
data class TransitionLog(
    val aggregateType: String,
    val aggregateId: String,
    val transition: String,
    val fromState: String,
    val toState: String,
    val actorType: String,
    val actorId: String,
    val reason: String?,
    val comment: String?,
    val metadata: Map<String, Any?>,
    val occurredAt: Instant,
    val createdAt: Instant,
    val correlationId: String?,
    val requestId: String?,
)
