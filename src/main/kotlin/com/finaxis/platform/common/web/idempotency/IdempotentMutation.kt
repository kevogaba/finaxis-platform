package com.finaxis.platform.common.web.idempotency

/** Durable replay strategy for one mutation endpoint. */
enum class IdempotencyReplayMode {
    EXACT_RESPONSE,
    REISSUE_CONTEXT_TOKEN,
}

/** Marks an HTTP mutation as governed by the durable idempotency interceptor. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class IdempotentMutation(
    val replayMode: IdempotencyReplayMode = IdempotencyReplayMode.EXACT_RESPONSE,
)
