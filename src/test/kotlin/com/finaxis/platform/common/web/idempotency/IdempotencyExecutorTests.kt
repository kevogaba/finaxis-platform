package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.API_IDEMPOTENCY_RECORD
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import org.jobrunr.jobs.annotations.Recurring
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class IdempotencyExecutorTests(
    private val dsl: DSLContext,
    private val executor: IdempotencyExecutor,
    private val properties: IdempotencyProperties,
) {
    @BeforeEach
    fun clearRecords() {
        dsl.deleteFrom(API_IDEMPOTENCY_RECORD).execute()
    }

    @AfterEach
    fun clearOrganisationFixtures() {
        dsl
            .deleteFrom(ORGANISATION)
            .where(ORGANISATION.TENANT_CODE.like("idempotency-test-%"))
            .execute()
    }

    @Test
    fun `same request executes once and replays exact safe response`() {
        val scope = IdempotencyScope(uuidV7())
        val key = uuidV7()
        val fingerprint = fingerprint()
        val executionCount = AtomicInteger()
        val operation = {
            executionCount.incrementAndGet()
            IdempotencyResponse(
                status = 201,
                headers =
                    mapOf(
                        "Location" to "/api/v1/widgets/42",
                        "ETag" to "\"v1\"",
                        "X-Domain-Reference" to "W-42",
                        "Authorization" to "Bearer secret",
                        "Set-Cookie" to "SESSION=secret",
                        "X-Active-Organisation" to "signed-context-token",
                        "X-Arbitrary" to "must-not-be-replayed",
                    ),
                body = """{"id":42,"state":"CREATED"}""",
            )
        }

        val first = executor.execute(scope, key, fingerprint, operation)
        val replay = executor.execute(scope, key, fingerprint, operation)

        val safeResponse =
            IdempotencyResponse(
                status = 201,
                headers =
                    mapOf(
                        "Location" to "/api/v1/widgets/42",
                        "ETag" to "\"v1\"",
                        "X-Domain-Reference" to "W-42",
                    ),
                body = """{"id":42,"state":"CREATED"}""",
            )
        assertEquals(1, executionCount.get())
        assertEquals(safeResponse, first)
        assertEquals(first, replay)
        val storedHeaders =
            requireNotNull(
                dsl
                    .select(API_IDEMPOTENCY_RECORD.RESPONSE_HEADERS)
                    .from(API_IDEMPOTENCY_RECORD)
                    .fetchOne(API_IDEMPOTENCY_RECORD.RESPONSE_HEADERS),
            ).data()
        assertEquals(false, storedHeaders.contains("secret", ignoreCase = true))
        assertEquals(false, storedHeaders.contains("X-Arbitrary"))
    }

    @Test
    fun `unsafe replay body rejects and rolls back the idempotency record`() {
        val scope = IdempotencyScope(uuidV7())

        assertFailsWith<IllegalArgumentException> {
            executor.execute(scope, uuidV7(), fingerprint()) {
                IdempotencyResponse(
                    200,
                    emptyMap(),
                    """{"result":{"access_token":"must-not-persist"}}""",
                )
            }
        }

        assertEquals(0, dsl.fetchCount(API_IDEMPOTENCY_RECORD))
    }

    @Test
    fun `empty 204 response executes once and replays without a body`() {
        val scope = IdempotencyScope(uuidV7())
        val key = uuidV7()
        val executionCount = AtomicInteger()

        val first =
            executor.execute(scope, key, fingerprint()) {
                executionCount.incrementAndGet()
                IdempotencyResponse(204, emptyMap(), null)
            }
        val replay =
            executor.execute(scope, key, fingerprint()) {
                executionCount.incrementAndGet()
                IdempotencyResponse(204, emptyMap(), null)
            }

        assertEquals(1, executionCount.get())
        assertEquals(204, first.status)
        assertNull(first.body)
        assertEquals(first, replay)
        assertNull(
            dsl
                .select(API_IDEMPOTENCY_RECORD.RESPONSE_BODY)
                .from(API_IDEMPOTENCY_RECORD)
                .fetchOne(API_IDEMPOTENCY_RECORD.RESPONSE_BODY),
        )
    }

    @Test
    fun `same key with actor method path or body mismatch conflicts`() {
        val scope = IdempotencyScope(uuidV7())
        val baseline = fingerprint()
        val mismatches =
            listOf(
                baseline.copy(actorFingerprint = "different-actor"),
                baseline.copy(method = "PUT"),
                baseline.copy(normalizedPath = "/api/v1/widgets/other"),
                baseline.copy(requestHash = "different-body-hash"),
            )

        mismatches.forEach { mismatch ->
            val key = uuidV7()
            executor.execute(scope, key, baseline) { successResponse() }

            val failure =
                assertFailsWith<ConflictException> {
                    executor.execute(scope, key, mismatch) { successResponse() }
                }

            assertEquals("IDEMPOTENCY_KEY_REUSED", failure.code)
        }
    }

    @Test
    fun `failed business transaction rolls back mutation and idempotency record`() {
        val organisationId = insertOrganisation()
        val scope = IdempotencyScope(organisationId)
        val key = uuidV7()

        assertFailsWith<IllegalStateException> {
            executor.execute(scope, key, fingerprint()) {
                dsl
                    .update(ORGANISATION)
                    .set(ORGANISATION.DISPLAY_NAME, "Mutated then failed")
                    .where(ORGANISATION.ID.eq(organisationId))
                    .execute()
                error("business mutation failed")
            }
        }

        assertEquals(
            "Original organisation",
            dsl
                .select(ORGANISATION.DISPLAY_NAME)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchOne(ORGANISATION.DISPLAY_NAME),
        )
        assertEquals(0, dsl.fetchCount(API_IDEMPOTENCY_RECORD))
    }

    @Test
    fun `stale in-progress request is reacquired after configured timeout`() {
        val scope = IdempotencyScope(uuidV7())
        val key = uuidV7()
        val fingerprint = fingerprint()
        val staleAt =
            OffsetDateTime
                .now(
                    ZoneOffset.UTC,
                ).minus(properties.inProgressTimeout)
                .minusSeconds(1)
        dsl
            .insertInto(API_IDEMPOTENCY_RECORD)
            .set(API_IDEMPOTENCY_RECORD.SCOPE_ORGANISATION_ID, scope.organisationId)
            .set(API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY, key)
            .set(API_IDEMPOTENCY_RECORD.ACTOR_FINGERPRINT, fingerprint.actorFingerprint)
            .set(API_IDEMPOTENCY_RECORD.REQUEST_METHOD, fingerprint.method)
            .set(API_IDEMPOTENCY_RECORD.NORMALIZED_PATH, fingerprint.normalizedPath)
            .set(API_IDEMPOTENCY_RECORD.REQUEST_HASH, fingerprint.requestHash)
            .set(API_IDEMPOTENCY_RECORD.STATUS, IdempotencyStatus.IN_PROGRESS.name)
            .set(API_IDEMPOTENCY_RECORD.CREATED_AT, staleAt)
            .set(API_IDEMPOTENCY_RECORD.EXPIRES_AT, staleAt.plus(properties.retention))
            .execute()

        val response = executor.execute(scope, key, fingerprint) { successResponse() }

        assertEquals(successResponse(), response)
        assertEquals(
            IdempotencyStatus.COMPLETED.name,
            dsl
                .select(API_IDEMPOTENCY_RECORD.STATUS)
                .from(API_IDEMPOTENCY_RECORD)
                .fetchOne(API_IDEMPOTENCY_RECORD.STATUS),
        )
    }

    @Test
    fun `cleanup job uses configured bounded batch on its recurring schedule`() {
        val expectedNow = Instant.parse("2026-07-18T18:00:00Z")
        val cleanupCall = mutableListOf<Pair<Instant, Int>>()
        val fakeStore =
            object : IdempotencyStore {
                override fun acquire(command: IdempotencyAcquireCommand): IdempotencyAcquisition =
                    error("Not used by cleanup")

                override fun complete(
                    scope: IdempotencyScope,
                    key: UUID,
                    response: SafeReplayResponse,
                ) = error("Not used by cleanup")

                override fun deleteExpiredCompleted(
                    now: Instant,
                    batchSize: Int,
                ): Int {
                    cleanupCall += now to batchSize
                    return 3
                }
            }
        val cleanupProperties = IdempotencyProperties(cleanupBatchSize = 37)
        val job =
            IdempotencyCleanupJob(
                fakeStore,
                cleanupProperties,
                Clock.fixed(expectedNow, ZoneOffset.UTC),
            )

        job.cleanup()

        assertEquals(listOf(expectedNow to 37), cleanupCall)
        val recurring =
            IdempotencyCleanupJob::class.java
                .getDeclaredMethod("cleanup")
                .getAnnotation(Recurring::class.java)
        assertEquals("api-idempotency-cleanup", recurring.id)
        assertEquals("\${finaxis.api.idempotency.cleanup-schedule}", recurring.cron)
    }

    @Test
    fun `retention must outlast the in-progress recovery timeout`() {
        assertFailsWith<IllegalArgumentException> {
            IdempotencyProperties(
                retention = Duration.ofMinutes(4),
                inProgressTimeout = Duration.ofMinutes(5),
            )
        }
    }

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "idempotency-test-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Original organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun fingerprint(): IdempotencyRequestFingerprint =
        IdempotencyRequestFingerprint(
            actorFingerprint = "actor-a",
            method = "POST",
            normalizedPath = "/api/v1/widgets",
            requestHash = "body-sha-256",
        )

    private fun successResponse(): IdempotencyResponse =
        IdempotencyResponse(200, emptyMap(), """{"ok":true}""")
}
