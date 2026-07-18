package com.finaxis.platform.common.web.idempotency

import java.time.Instant
import java.util.UUID

/** Persistence port for transactionally serialized mutation idempotency records. */
interface IdempotencyStore {
    /** Acquires, replays, or reports active work for a tenant-scoped key. */
    fun acquire(command: IdempotencyAcquireCommand): IdempotencyAcquisition

    /** Marks an acquired key complete with its safe successful response. */
    fun complete(
        scope: IdempotencyScope,
        key: UUID,
        response: IdempotencyResponse,
    )

    /** Deletes at most [batchSize] expired completed records. */
    fun deleteExpiredCompleted(
        now: Instant,
        batchSize: Int,
    ): Int
}
