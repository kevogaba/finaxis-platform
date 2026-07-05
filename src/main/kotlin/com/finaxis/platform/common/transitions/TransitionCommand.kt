package com.finaxis.platform.common.transitions

import java.time.Instant

/**
 * Request metadata supplied by an inbound adapter or application service for a transition.
 */
data class TransitionCommand(
    val reason: String? = null,
    val comment: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val occurredAt: Instant? = null,
    val correlationId: String? = null,
    val requestId: String? = null,
)
