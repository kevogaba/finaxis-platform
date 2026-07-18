package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.API_IDEMPOTENCY_RECORD
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JooqIdempotencyStoreTests(
    private val dsl: DSLContext,
    private val store: IdempotencyStore,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @BeforeEach
    fun clearRecords() {
        dsl.deleteFrom(API_IDEMPOTENCY_RECORD).execute()
    }

    @Test
    fun `first acquisition inserts an in-progress record`() {
        val command = acquireCommand()

        val acquired = inTransaction { store.acquire(command) }

        assertIs<IdempotencyAcquisition.Acquired>(acquired)
        assertEquals(1, dsl.fetchCount(API_IDEMPOTENCY_RECORD))
        assertEquals(
            IdempotencyStatus.IN_PROGRESS.name,
            dsl
                .select(API_IDEMPOTENCY_RECORD.STATUS)
                .from(API_IDEMPOTENCY_RECORD)
                .fetchOne(API_IDEMPOTENCY_RECORD.STATUS),
        )
    }

    @Test
    fun `transaction advisory lock selects one concurrent acquisition winner`() {
        val command = acquireCommand()
        val firstAcquired = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { executorService ->
            val first =
                executorService.submit<IdempotencyAcquisition> {
                    inTransaction {
                        store.acquire(command).also {
                            firstAcquired.countDown()
                            assertTrue(releaseFirst.await(10, TimeUnit.SECONDS))
                        }
                    }
                }
            assertTrue(firstAcquired.await(10, TimeUnit.SECONDS))
            val second =
                executorService.submit<IdempotencyAcquisition> {
                    inTransaction { store.acquire(command) }
                }

            assertFalse(second.isDone)
            releaseFirst.countDown()

            assertIs<IdempotencyAcquisition.Acquired>(first.get(10, TimeUnit.SECONDS))
            assertIs<IdempotencyAcquisition.InProgress>(second.get(10, TimeUnit.SECONDS))
        }
        assertEquals(1, dsl.fetchCount(API_IDEMPOTENCY_RECORD))
    }

    @Test
    fun `completed acquisition replays the exact stored response`() {
        val command = acquireCommand()
        val response =
            IdempotencyResponse(
                status = 201,
                headers = mapOf("Location" to "/api/v1/widgets/42", "ETag" to "\"v1\""),
                body = """{"id":42,"state":"CREATED"}""",
            )
        inTransaction {
            assertIs<IdempotencyAcquisition.Acquired>(store.acquire(command))
            store.complete(command.scope, command.key, response)
        }

        val replay = inTransaction { store.acquire(command.copy(now = command.now.plusSeconds(1))) }

        assertEquals(response, assertIs<IdempotencyAcquisition.Replay>(replay).response)
    }

    @Test
    fun `request hash mismatch conflicts instead of replaying`() {
        val command = acquireCommand()
        complete(command)

        val failure =
            expectConflict {
                inTransaction {
                    store.acquire(
                        command.copy(
                            fingerprint = command.fingerprint.copy(requestHash = "different-body"),
                        ),
                    )
                }
            }

        assertEquals("IDEMPOTENCY_KEY_REUSED", failure.code)
    }

    @Test
    fun `actor mismatch conflicts instead of replaying`() {
        val command = acquireCommand()
        complete(command)

        val failure =
            expectConflict {
                inTransaction {
                    store.acquire(
                        command.copy(
                            fingerprint = command.fingerprint.copy(actorFingerprint = "actor-b"),
                        ),
                    )
                }
            }

        assertEquals("IDEMPOTENCY_KEY_REUSED", failure.code)
    }

    @Test
    fun `rolled back acquisition leaves no durable record`() {
        val command = acquireCommand()

        runCatching {
            inTransaction<Unit> {
                store.acquire(command)
                error("business mutation failed")
            }
        }

        assertEquals(0, dsl.fetchCount(API_IDEMPOTENCY_RECORD))
    }

    @Test
    fun `expired completed record can be acquired as a new request`() {
        val command = acquireCommand(expiresAt = NOW.plusSeconds(5))
        complete(command)
        val replacement =
            command.copy(
                fingerprint = command.fingerprint.copy(actorFingerprint = "actor-after-expiry"),
                now = NOW.plusSeconds(6),
                expiresAt = NOW.plusSeconds(66),
            )

        val result = inTransaction { store.acquire(replacement) }

        assertIs<IdempotencyAcquisition.Acquired>(result)
        assertEquals(
            "actor-after-expiry",
            dsl
                .select(API_IDEMPOTENCY_RECORD.ACTOR_FINGERPRINT)
                .from(API_IDEMPOTENCY_RECORD)
                .fetchOne(API_IDEMPOTENCY_RECORD.ACTOR_FINGERPRINT),
        )
    }

    @Test
    fun `cleanup removes expired completed records in bounded batches`() {
        complete(acquireCommand(key = uuidV7(), expiresAt = NOW.minusSeconds(2)))
        complete(acquireCommand(key = uuidV7(), expiresAt = NOW.minusSeconds(1)))
        inTransaction {
            store.acquire(acquireCommand(key = uuidV7(), expiresAt = NOW.minusSeconds(1)))
        }
        complete(acquireCommand(key = uuidV7(), expiresAt = NOW.plusSeconds(60)))

        assertEquals(1, inTransaction { store.deleteExpiredCompleted(NOW, 1) })
        assertEquals(1, inTransaction { store.deleteExpiredCompleted(NOW, 1) })
        assertEquals(0, inTransaction { store.deleteExpiredCompleted(NOW, 1) })
        assertEquals(2, dsl.fetchCount(API_IDEMPOTENCY_RECORD))
    }

    private fun complete(command: IdempotencyAcquireCommand) {
        inTransaction {
            assertIs<IdempotencyAcquisition.Acquired>(store.acquire(command))
            store.complete(
                command.scope,
                command.key,
                IdempotencyResponse(200, emptyMap(), """{"ok":true}"""),
            )
        }
    }

    private fun acquireCommand(
        key: UUID = uuidV7(),
        expiresAt: Instant = NOW.plus(Duration.ofDays(1)),
    ): IdempotencyAcquireCommand =
        IdempotencyAcquireCommand(
            scope = IdempotencyScope(uuidV7()),
            key = key,
            fingerprint =
                IdempotencyRequestFingerprint(
                    actorFingerprint = "actor-a",
                    method = "POST",
                    normalizedPath = "/api/v1/widgets",
                    requestHash = "body-sha-256",
                ),
            now = NOW,
            expiresAt = expiresAt,
            inProgressTimeout = Duration.ofMinutes(5),
        )

    private fun expectConflict(block: () -> Unit): ConflictException =
        try {
            block()
            fail("Expected an idempotency key conflict")
        } catch (failure: ConflictException) {
            failure
        }

    private fun <T> inTransaction(block: () -> T): T =
        requireNotNull(transaction.execute { block() })

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-18T12:00:00Z")
    }
}
