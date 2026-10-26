package com.finaxis.platform.lifecycle.adapter.inbound.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditEventQueries
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The async request timeout, through a real embedded Tomcat (#167, ADR 0039): a stalled read
 * outlives `spring.mvc.async.request-timeout` (2 seconds here). The timeout dispatch must write
 * nothing (so it cannot interleave bytes with the stream thread), the client must see an incomplete
 * transfer, and the stream thread, which Spring interrupts, must still write its FAILURE audit row.
 * Authentication is [AuditExportAbortEndToEndTests.TestAuthentication].
 */
@Import(
    PostgresTestConfiguration::class,
    AuditExportAbortEndToEndTests.TestAuthentication::class,
)
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "finaxis.rate-limit.policies.tenant-export.capacity=1000",
        "finaxis.rate-limit.policies.tenant-export.refill-tokens=1000",
        "spring.mvc.async.request-timeout=2s",
    ],
)
class AuditExportTimeoutEndToEndTests
    @Autowired
    constructor(
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        @LocalServerPort
        private var port: Int = 0

        @MockitoSpyBean
        private lateinit var queries: AuditEventQueries

        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        private val client: HttpClient =
            HttpClient
                .newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build()

        @Test
        fun `the async timeout aborts the transfer, writes no bytes itself and still audits`() {
            val admin = seedUser()
            val organisationId = fixture.createActiveOrganisation("export-timeout", admin)
            val marker = "e2e${uuidV7().toString().takeLast(10)}"
            seedBulk(organisationId, "$marker.bulk", 1_500)
            val calls = AtomicInteger()
            val interrupted = AtomicBoolean()
            doAnswer {
                if (calls.incrementAndGet() == 2) {
                    // A stalled read: the 2 second async timeout fires while this thread is busy.
                    try {
                        Thread.sleep(STALL_MILLIS)
                    } catch (stopped: InterruptedException) {
                        interrupted.set(true)
                        throw stopped
                    }
                }
                it.callRealMethod()
            }.whenever(queries).exportBatch(any(), anyOrNull(), any())

            val received = ByteArrayOutputStream()
            var failure: Throwable? = null
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val root =
                LoggerFactory.getLogger(
                    Logger.ROOT_LOGGER_NAME,
                ) as ch.qos.logback.classic.Logger
            root.addAppender(appender)
            try {
                val started = System.nanoTime()
                val response = get(admin, organisationId, marker)
                failure = readUntilEnd(response, received)
                val seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds()
                assertTrue(seconds < STALL_MILLIS / 1_000, "the timeout, not the stall, ended it")
                failureRow(organisationId)
            } finally {
                root.detachAppender(appender)
            }

            val text = received.toString(Charsets.UTF_8)
            assertNotNull(failure, "the body ended normally: the client cannot tell it is partial")
            assertTrue(failure is IOException, "an incomplete transfer, not $failure")
            assertTrue(
                !text.contains("\"status\"") && !text.contains("internal_error"),
                text.takeLast(200),
            )
            assertEquals(1_001, text.trimEnd().lines().size, "the header and the first batch only")
            val row = failureRow(organisationId)
            assertEquals(1_000, row.first)
            assertTrue(interrupted.get(), "the timeout interrupts the stream thread")
            assertEquals("InterruptedException", row.second)
            val events = appender.list.toList()
            assertTrue(events.none { it.formattedMessage.contains("Unexpected API exception") })
            assertTrue(events.any { it.formattedMessage.contains("aborting the transfer") })
        }

        private fun get(
            user: UUID,
            organisationId: UUID,
            marker: String,
        ): HttpResponse<java.io.InputStream> {
            val from = Instant.now().minus(Duration.ofDays(1))
            val request =
                HttpRequest
                    .newBuilder(
                        URI.create(
                            "http://127.0.0.1:$port${ApiPaths.AUDIT_EVENTS}/export" +
                                "?occurred_from=$from&action_prefix=$marker.",
                        ),
                    ).header("X-Test-User", user.toString())
                    .header("X-Test-Organisation", organisationId.toString())
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build()
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        }

        /** Reads the body to its end, keeping what arrived; returns the failure, if any. */
        private fun readUntilEnd(
            response: HttpResponse<java.io.InputStream>,
            into: ByteArrayOutputStream,
        ): Throwable? =
            try {
                response.body().use { it.copyTo(into) }
                null
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                failure
            }

        /** Row count and error class of the one FAILURE row, once the stream thread wrote it. */
        private fun failureRow(organisationId: UUID): Pair<Int, String> {
            val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
            while (System.nanoTime() < deadline) {
                dsl
                    .select(AUDIT_EVENT.METADATA_JSONB)
                    .from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
                    .and(AUDIT_EVENT.ACTION.eq("audit.export"))
                    .and(AUDIT_EVENT.OUTCOME.eq("FAILURE"))
                    .fetchOne(AUDIT_EVENT.METADATA_JSONB)
                    ?.data()
                    ?.let { json ->
                        val count = Regex("\"row_count\":\\s*(\\d+)").find(json)!!.groupValues[1]
                        val klass =
                            Regex(
                                "\"error_class\":\\s*\"(\\w+)\"",
                            ).find(json)!!.groupValues[1]
                        return count.toInt() to klass
                    }
                Thread.sleep(POLL_MILLIS)
            }
            error("no FAILURE audit.export row was written")
        }

        private fun seedBulk(
            organisationId: UUID,
            action: String,
            rows: Int,
        ) {
            dsl.execute(
                """
                INSERT INTO audit_event (organisation_id, event_time, actor_type, event_type,
                  entity_type, action, outcome, severity)
                SELECT ?, now() - g * interval '1 second', 'SYSTEM', ?, 'BULK', ?, 'SUCCESS', 'INFO'
                FROM generate_series(1, ?) g
                """.trimIndent(),
                organisationId,
                action,
                action,
                rows,
            )
        }

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "export-e2e-$id")
                .set(USER_ACCOUNT.EMAIL, "export-e2e-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Export E2E User")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            const val STALL_MILLIS = 20_000L
            const val POLL_MILLIS = 100L
        }
    }
