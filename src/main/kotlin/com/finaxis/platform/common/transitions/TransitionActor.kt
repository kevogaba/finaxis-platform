package com.finaxis.platform.common.transitions

/**
 * Identifies the user, service, device, or system process that requested a transition.
 */
data class TransitionActor(
    val type: String,
    val id: String,
    val displayName: String? = null,
)
