package com.finaxis.platform.common.web.idempotency

import jakarta.servlet.http.HttpServletRequest

/** Safe controller result whose body is persisted instead of a sensitive live response. */
data class IdempotencyReplayResponse(
    val durableBody: Any?,
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
)

/** Rebuilds a sensitive live response from safe durable replay JSON. */
interface IdempotencyReplayHandler {
    /** Replay mode handled by this adapter. */
    val mode: IdempotencyReplayMode

    /** Restores request-local state and returns a newly issued live response. */
    fun restore(
        durableJson: String,
        request: HttpServletRequest,
    ): Any
}
