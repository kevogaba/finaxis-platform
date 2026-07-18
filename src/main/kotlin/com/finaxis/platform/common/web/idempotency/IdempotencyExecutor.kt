package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.common.application.ConflictException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** Executes a mutation and its completed idempotency record in one database transaction. */
@Service
class IdempotencyExecutor(
    private val store: IdempotencyStore,
    private val responsePolicy: IdempotencyResponsePolicy,
    private val properties: IdempotencyProperties,
    private val clock: Clock,
) {
    /** Acquires [key], executes one successful mutation, or replays its safe response. */
    @Transactional
    fun execute(
        scope: IdempotencyScope,
        key: UUID,
        fingerprint: IdempotencyRequestFingerprint,
        operation: () -> IdempotencyResponse,
    ): IdempotencyResponse {
        val now = clock.instant()
        val acquisition =
            store.acquire(
                IdempotencyAcquireCommand(
                    scope = scope,
                    key = key,
                    fingerprint = fingerprint,
                    now = now,
                    expiresAt = now.plus(properties.retention),
                    inProgressTimeout = properties.inProgressTimeout,
                ),
            )
        return when (acquisition) {
            IdempotencyAcquisition.Acquired -> {
                val response = responsePolicy.sanitize(operation())
                store.complete(scope, key, response)
                response
            }

            is IdempotencyAcquisition.Replay -> {
                acquisition.response
            }

            IdempotencyAcquisition.InProgress -> {
                throw ConflictException(
                    code = "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    safeDetail = "A request with this idempotency key is already in progress.",
                )
            }
        }
    }
}
