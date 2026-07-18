package com.finaxis.platform.common.web.idempotency

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.jooq.tables.records.ApiIdempotencyRecordRecord
import com.finaxis.platform.jooq.tables.references.API_IDEMPOTENCY_RECORD
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** jOOQ/PostgreSQL adapter for durable tenant-scoped mutation idempotency. */
@Repository
class JooqIdempotencyStore(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val replayResponseFactory: SafeReplayResponseFactory,
) : IdempotencyStore {
    override fun acquire(command: IdempotencyAcquireCommand): IdempotencyAcquisition {
        requireActiveTransaction()
        acquireTransactionLock(command.scope, command.key)
        val record = find(command.scope, command.key)
        if (record == null) {
            insert(command)
            return IdempotencyAcquisition.Acquired
        }
        if (record.expiresAt!!.toInstant() <= command.now) {
            delete(command.scope, command.key)
            insert(command)
            return IdempotencyAcquisition.Acquired
        }
        ensureSameRequest(record, command.fingerprint)
        return when (record.status) {
            IdempotencyStatus.COMPLETED.name -> IdempotencyAcquisition.Replay(toResponse(record))
            IdempotencyStatus.IN_PROGRESS.name -> reacquireIfStale(record, command)
            else -> error("Unsupported idempotency status")
        }
    }

    override fun complete(
        scope: IdempotencyScope,
        key: UUID,
        response: SafeReplayResponse,
    ) {
        requireActiveTransaction()
        check(
            dsl
                .update(API_IDEMPOTENCY_RECORD)
                .set(API_IDEMPOTENCY_RECORD.STATUS, IdempotencyStatus.COMPLETED.name)
                .set(API_IDEMPOTENCY_RECORD.RESPONSE_STATUS, response.status)
                .set(
                    API_IDEMPOTENCY_RECORD.RESPONSE_HEADERS,
                    JSONB.jsonb(objectMapper.writeValueAsString(response.headers)),
                ).set(API_IDEMPOTENCY_RECORD.RESPONSE_BODY, response.storedBody)
                .where(API_IDEMPOTENCY_RECORD.SCOPE_ORGANISATION_ID.eq(scope.organisationId))
                .and(API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
                .and(API_IDEMPOTENCY_RECORD.STATUS.eq(IdempotencyStatus.IN_PROGRESS.name))
                .execute() == 1,
        ) { "An acquired in-progress idempotency record is required" }
    }

    override fun deleteExpiredCompleted(
        now: Instant,
        batchSize: Int,
    ): Int {
        requireActiveTransaction()
        require(batchSize > 0) { "Cleanup batch size must be positive" }
        val expiredKeys =
            dsl
                .select(
                    API_IDEMPOTENCY_RECORD.SCOPE_ORGANISATION_ID,
                    API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY,
                ).from(API_IDEMPOTENCY_RECORD)
                .where(API_IDEMPOTENCY_RECORD.STATUS.eq(IdempotencyStatus.COMPLETED.name))
                .and(API_IDEMPOTENCY_RECORD.EXPIRES_AT.le(now.toOffsetDateTime()))
                .orderBy(API_IDEMPOTENCY_RECORD.EXPIRES_AT)
                .limit(batchSize)
        return dsl
            .deleteFrom(API_IDEMPOTENCY_RECORD)
            .where(
                DSL
                    .row(
                        API_IDEMPOTENCY_RECORD.SCOPE_ORGANISATION_ID,
                        API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY,
                    ).`in`(expiredKeys),
            ).execute()
    }

    private fun acquireTransactionLock(
        scope: IdempotencyScope,
        key: UUID,
    ) {
        dsl.fetch(
            """
            SELECT pg_advisory_xact_lock(
                hashtextextended(CAST(? AS text) || ':' || CAST(? AS text), 0)
            )
            """.trimIndent(),
            scope.organisationId,
            key,
        )
    }

    private fun find(
        scope: IdempotencyScope,
        key: UUID,
    ): ApiIdempotencyRecordRecord? =
        dsl
            .selectFrom(API_IDEMPOTENCY_RECORD)
            .where(API_IDEMPOTENCY_RECORD.SCOPE_ORGANISATION_ID.eq(scope.organisationId))
            .and(API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
            .fetchOne()

    private fun insert(command: IdempotencyAcquireCommand) {
        dsl
            .insertInto(API_IDEMPOTENCY_RECORD)
            .set(API_IDEMPOTENCY_RECORD.SCOPE_ORGANISATION_ID, command.scope.organisationId)
            .set(API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY, command.key)
            .set(API_IDEMPOTENCY_RECORD.ACTOR_FINGERPRINT, command.fingerprint.actorFingerprint)
            .set(API_IDEMPOTENCY_RECORD.REQUEST_METHOD, command.fingerprint.method)
            .set(API_IDEMPOTENCY_RECORD.NORMALIZED_PATH, command.fingerprint.normalizedPath)
            .set(API_IDEMPOTENCY_RECORD.REQUEST_HASH, command.fingerprint.requestHash)
            .set(API_IDEMPOTENCY_RECORD.STATUS, IdempotencyStatus.IN_PROGRESS.name)
            .set(API_IDEMPOTENCY_RECORD.CREATED_AT, command.now.toOffsetDateTime())
            .set(API_IDEMPOTENCY_RECORD.EXPIRES_AT, command.expiresAt.toOffsetDateTime())
            .execute()
    }

    private fun delete(
        scope: IdempotencyScope,
        key: UUID,
    ) {
        dsl
            .deleteFrom(API_IDEMPOTENCY_RECORD)
            .where(API_IDEMPOTENCY_RECORD.SCOPE_ORGANISATION_ID.eq(scope.organisationId))
            .and(API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
            .execute()
    }

    private fun ensureSameRequest(
        record: ApiIdempotencyRecordRecord,
        fingerprint: IdempotencyRequestFingerprint,
    ) {
        val matches =
            record.actorFingerprint == fingerprint.actorFingerprint &&
                record.requestMethod == fingerprint.method &&
                record.normalizedPath == fingerprint.normalizedPath &&
                record.requestHash == fingerprint.requestHash
        if (!matches) {
            throw ConflictException(
                code = "IDEMPOTENCY_KEY_REUSED",
                safeDetail = "The idempotency key was already used for a different request.",
            )
        }
    }

    private fun reacquireIfStale(
        record: ApiIdempotencyRecordRecord,
        command: IdempotencyAcquireCommand,
    ): IdempotencyAcquisition {
        val staleBefore = command.now.minus(command.inProgressTimeout)
        if (record.createdAt!!.toInstant() > staleBefore) {
            return IdempotencyAcquisition.InProgress
        }
        dsl
            .update(API_IDEMPOTENCY_RECORD)
            .set(API_IDEMPOTENCY_RECORD.CREATED_AT, command.now.toOffsetDateTime())
            .set(API_IDEMPOTENCY_RECORD.EXPIRES_AT, command.expiresAt.toOffsetDateTime())
            .where(
                API_IDEMPOTENCY_RECORD.SCOPE_ORGANISATION_ID
                    .eq(command.scope.organisationId),
            ).and(API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(command.key))
            .execute()
        return IdempotencyAcquisition.Acquired
    }

    private fun toResponse(record: ApiIdempotencyRecordRecord): SafeReplayResponse =
        replayResponseFactory.fromStored(
            status = requireNotNull(record.responseStatus),
            headers =
                objectMapper.readValue(
                    requireNotNull(record.responseHeaders).data(),
                    object : TypeReference<Map<String, String>>() {},
                ),
            storedBody = requireNotNull(record.responseBody),
        )

    private fun requireActiveTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Idempotency store operations require an active transaction"
        }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
