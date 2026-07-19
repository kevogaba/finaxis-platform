package com.finaxis.platform.common.web.idempotency

/** Durable replay strategy for one mutation endpoint. */
enum class IdempotencyReplayMode {
    EXACT_RESPONSE,
    REISSUE_CONTEXT_TOKEN,
}

/** Explicit server-owned namespace used when reserving an idempotency key. */
enum class IdempotencyScopeKind {
    TENANT,
    PLATFORM,
    ORGANISATION_SELECTION,
}

/** Marks an HTTP mutation as governed by the durable idempotency interceptor. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class IdempotentMutation(
    val scope: IdempotencyScopeKind,
    val replayMode: IdempotencyReplayMode = IdempotencyReplayMode.EXACT_RESPONSE,
)
