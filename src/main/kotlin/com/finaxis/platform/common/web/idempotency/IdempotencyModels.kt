package com.finaxis.platform.common.web.idempotency

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Organisation boundary used to isolate idempotency keys. */
data class IdempotencyScope(
    val organisationId: UUID,
)

/** Stable identity of the actor and mutation request bound to an idempotency key. */
data class IdempotencyRequestFingerprint(
    val actorFingerprint: String,
    val method: String,
    val normalizedPath: String,
    val requestHash: String,
) {
    init {
        require(actorFingerprint.isNotBlank()) { "Actor fingerprint must not be blank" }
        require(method.isNotBlank()) { "Request method must not be blank" }
        require(normalizedPath.startsWith('/')) { "Normalized path must be absolute" }
        require(requestHash.isNotBlank()) { "Request hash must not be blank" }
    }
}

/** Successful mutation response that is safe to return and durably replay. */
data class IdempotencyResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** Enforces the successful-status and response-header persistence allowlist. */
@Component
class IdempotencyResponsePolicy(
    properties: IdempotencyProperties,
) {
    private val allowedHeaders =
        (STANDARD_SAFE_HEADERS + properties.safeDomainHeaders)
            .associateBy { it.lowercase(Locale.ROOT) }

    /** Returns the response shape safe for both the first response and durable replay. */
    fun sanitize(response: IdempotencyResponse): IdempotencyResponse {
        require(response.status in SUCCESS_STATUS_MINIMUM..SUCCESS_STATUS_MAXIMUM) {
            "Only successful responses can be replayed"
        }
        val safeHeaders =
            response.headers
                .mapNotNull { (name, value) ->
                    allowedHeaders[name.lowercase(Locale.ROOT)]?.let { canonicalName ->
                        canonicalName to value
                    }
                }.toMap()
        return response.copy(headers = safeHeaders)
    }

    private companion object {
        const val SUCCESS_STATUS_MINIMUM: Int = 200
        const val SUCCESS_STATUS_MAXIMUM: Int = 299
        val STANDARD_SAFE_HEADERS: Set<String> = setOf("Location", "ETag")
    }
}

/** Complete input needed for one atomic store acquisition attempt. */
data class IdempotencyAcquireCommand(
    val scope: IdempotencyScope,
    val key: UUID,
    val fingerprint: IdempotencyRequestFingerprint,
    val now: Instant,
    val expiresAt: Instant,
    val inProgressTimeout: Duration,
)

/** Durable processing state of an idempotency record. */
enum class IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
}

/** Outcome after serializing contenders for an organisation-scoped idempotency key. */
sealed interface IdempotencyAcquisition {
    /** The caller owns the mutation attempt in the current transaction. */
    data object Acquired : IdempotencyAcquisition

    /** A successful prior response is available for exact replay. */
    data class Replay(
        val response: IdempotencyResponse,
    ) : IdempotencyAcquisition

    /** A non-stale mutation attempt still owns this key. */
    data object InProgress : IdempotencyAcquisition
}
