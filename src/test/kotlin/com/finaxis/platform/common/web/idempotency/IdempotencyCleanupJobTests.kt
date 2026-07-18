package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.API_IDEMPOTENCY_RECORD
import org.jobrunr.jobs.annotations.Recurring
import org.jobrunr.storage.StorageProvider
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class IdempotencyCleanupJobTests(
    private val dsl: DSLContext,
    private val cleanupJob: IdempotencyCleanupJob,
    private val properties: IdempotencyProperties,
    private val storageProvider: StorageProvider,
) {
    @BeforeEach
    fun clearRecords() {
        dsl.deleteFrom(API_IDEMPOTENCY_RECORD).execute()
    }

    @Test
    fun `proxied cleanup runs transactionally and JobRunr discovers its recurring schedule`() {
        insertExpiredCompletedRecord()

        assertTrue(AopUtils.isAopProxy(cleanupJob))
        cleanupJob.cleanup()

        assertEquals(0, dsl.fetchCount(API_IDEMPOTENCY_RECORD))
        val targetMethod =
            AopUtils
                .getTargetClass(cleanupJob)
                .getDeclaredMethod("cleanup")
        val recurring = assertNotNull(targetMethod.getAnnotation(Recurring::class.java))
        assertEquals("api-idempotency-cleanup", recurring.id)
        assertEquals("\${finaxis.api.idempotency.cleanup-schedule}", recurring.cron)
        val registered =
            assertNotNull(
                storageProvider.recurringJobs.firstOrNull { job ->
                    job.id == "api-idempotency-cleanup"
                },
            )
        assertEquals(properties.cleanupSchedule, registered.scheduleExpression)
    }

    private fun insertExpiredCompletedRecord() {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl
            .insertInto(API_IDEMPOTENCY_RECORD)
            .set(API_IDEMPOTENCY_RECORD.SCOPE_ORGANISATION_ID, uuidV7())
            .set(API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY, uuidV7())
            .set(API_IDEMPOTENCY_RECORD.ACTOR_FINGERPRINT, "cleanup-test-actor")
            .set(API_IDEMPOTENCY_RECORD.REQUEST_METHOD, "POST")
            .set(API_IDEMPOTENCY_RECORD.NORMALIZED_PATH, "/api/v1/widgets")
            .set(API_IDEMPOTENCY_RECORD.REQUEST_HASH, "cleanup-request-hash")
            .set(API_IDEMPOTENCY_RECORD.STATUS, IdempotencyStatus.COMPLETED.name)
            .set(API_IDEMPOTENCY_RECORD.RESPONSE_STATUS, 204)
            .set(API_IDEMPOTENCY_RECORD.RESPONSE_HEADERS, JSONB.jsonb("{}"))
            .set(API_IDEMPOTENCY_RECORD.RESPONSE_BODY, "")
            .set(API_IDEMPOTENCY_RECORD.CREATED_AT, now.minusDays(2))
            .set(API_IDEMPOTENCY_RECORD.EXPIRES_AT, now.minusDays(1))
            .execute()
    }
}
