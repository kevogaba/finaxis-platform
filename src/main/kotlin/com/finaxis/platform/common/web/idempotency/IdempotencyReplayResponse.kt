package com.finaxis.platform.common.web.idempotency

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.servlet.http.HttpServletRequest

/** Safe controller result whose body is persisted instead of a sensitive live response. */
interface IdempotencyReplayResponse<out T : Any> {
    /** Safe state persisted in place of the public live response. */
    @get:JsonIgnore
    val durableBody: T?

    /** HTTP status persisted with the safe state. */
    @get:JsonIgnore
    val durableStatus: Int
        get() = 200

    /** Safe response headers persisted with the safe state. */
    @get:JsonIgnore
    val durableHeaders: Map<String, String>
        get() = emptyMap()
}

/** Rebuilds a sensitive live response from safe durable replay JSON. */
interface IdempotencyReplayHandler {
    /** Replay mode handled by this adapter. */
    val mode: IdempotencyReplayMode

    /** Restores request-local state and returns a newly issued live response. */
    fun restore(
        durableJson: String,
        request: HttpServletRequest,
    ): IdempotencyReplayResponse<*>
}

/** Principal contract whose stable external subject participates in request fingerprints. */
interface IdempotencyActorPrincipal {
    /** Stable identity from the authenticating identity provider. */
    val idempotencySubject: String
}
