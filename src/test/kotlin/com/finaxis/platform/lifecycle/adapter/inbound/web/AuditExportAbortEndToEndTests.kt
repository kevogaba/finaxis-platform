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
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a real client sees when an audit export fails after the response started (#167, ADR 0039,
 * point 12: "a file that looks complete and is not" is worse than a refusal). The server is a real
 * embedded Tomcat on a random port and the client is `java.net.http.HttpClient` reading the chunked
 * body, because MockMvc cannot show a truncated transfer: the second batch read fails after the
 * first batch was flushed, and the client must see an incomplete transfer (an `IOException` while
 * reading), never a normal end of body, and no problem document appended to the CSV.
 *
 * Authentication is a test filter ahead of the security chain that places an application
 * principal in the request-attribute security context; the filter chain, rate limiting, the
 * controller, the executor and the exception handling are the production ones.
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
    ],
)
class AuditExportAbortEndToEndTests
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
        fun `a read failure after the first batch is an incomplete transfer, not a clean end`() {
            assertAbortedTransfer("IllegalStateException") {
                IllegalStateException("boom-secret-message-$it")
            }
        }

        @Test
        fun `a lost database connection is an incomplete transfer, not a quiet client abort`() {
            // PgJDBC reports a dropped server connection as an EOFException under a SQLException
            // under a Spring DataAccessException: it must never look like a client that left.
            assertAbortedTransfer("DataAccessResourceFailureException") {
                DataAccessResourceFailureException(
                    "boom-secret-message-$it",
                    SQLException(
                        "An I/O error occurred while sending to the backend",
                        EOFException(),
                    ),
                )
            }
        }

        @Test
        fun `a reset database connection is an incomplete transfer, not a quiet client abort`() {
            assertAbortedTransfer("DataAccessResourceFailureException") {
                DataAccessResourceFailureException(
                    "boom-secret-message-$it",
                    SQLException("I/O error", SocketException("Connection reset")),
                )
            }
        }

        @Test
        fun `a complete export still ends cleanly for the same client`() {
            val admin = seedUser()
            val organisationId = fixture.createActiveOrganisation("export-clean", admin)
            val marker = "e2e${uuidV7().toString().takeLast(10)}"
            seedBulk(organisationId, "$marker.bulk", 1_500)

            val response = get(admin, organisationId, marker)
            val received = ByteArrayOutputStream()
            val failure = readUntilEnd(response, received)

            assertEquals(200, response.statusCode())
            assertEquals(null, failure)
            assertEquals(
                1_501,
                received
                    .toString(Charsets.UTF_8)
                    .trimEnd()
                    .lines()
                    .size,
            )
        }

        /**
         * The second batch read throws what [failureOf] builds, after the first batch was flushed:
         * the real client must see an incomplete transfer whatever the failure looks like.
         */
        private fun assertAbortedTransfer(
            expectedErrorClass: String,
            failureOf: (String) -> Exception,
        ) {
            val admin = seedUser()
            val organisationId = fixture.createActiveOrganisation("export-abort", admin)
            val marker = "e2e${uuidV7().toString().takeLast(10)}"
            seedBulk(organisationId, "$marker.bulk", 1_500)
            val calls = AtomicInteger()
            doAnswer {
                if (calls.incrementAndGet() == 2) {
                    throw failureOf(marker)
                }
                it.callRealMethod()
            }.whenever(queries).exportBatch(any(), anyOrNull(), any())

            val received = ByteArrayOutputStream()
            var failure: Throwable? = null
            var status = 0
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val root =
                LoggerFactory.getLogger(
                    Logger.ROOT_LOGGER_NAME,
                ) as ch.qos.logback.classic.Logger
            root.addAppender(appender)
            try {
                val response = get(admin, organisationId, marker)
                status = response.statusCode()
                failure = readUntilEnd(response, received)
                // The stream thread writes the audit row last: wait for it, then for the logs.
                failureRow(organisationId)
            } finally {
                root.detachAppender(appender)
            }

            assertEquals(200, status, "the status was sent before the failure")
            val text = received.toString(Charsets.UTF_8)
            assertTrue(text.startsWith("occurred_at,action,"), "the CSV had started")
            assertTrue(
                !text.contains("\"status\"") && !text.contains("internal_error"),
                "no problem document may follow the partial CSV",
            )
            assertNotNull(failure, "the body ended normally: the client cannot tell it is partial")
            assertTrue(failure is IOException, "an incomplete transfer, not $failure")
            val row = failureRow(organisationId)
            assertEquals(1_000, row.first, "the audit row counts the batch that was written")
            assertEquals(expectedErrorClass, row.second)
            // The failure is reported once, by class, as a warning: not as an unexpected API
            // exception (twice, at ERROR) and never with the message.
            val events = appender.list.toList()
            assertTrue(
                events.none { it.formattedMessage.contains("Unexpected API exception") },
                events.filter { it.level == Level.ERROR }.map { it.formattedMessage }.toString(),
            )
            val aborts = events.filter { it.formattedMessage.contains("aborting the transfer") }
            assertEquals(
                1,
                aborts.size,
                aborts.map { it.loggerName + ": " + it.formattedMessage }.toString(),
            )
            assertTrue(events.none { it.formattedMessage.contains("boom-secret-message") })
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

        /** Authenticates a request from two headers, ahead of Spring Security's filter chain. */
        @TestConfiguration(proxyBeanMethods = false)
        class TestAuthentication {
            @Bean
            fun testAuthenticationFilter(): FilterRegistrationBean<OncePerRequestFilter> =
                FilterRegistrationBean<OncePerRequestFilter>(
                    object : OncePerRequestFilter() {
                        override fun doFilterInternal(
                            request: HttpServletRequest,
                            response: HttpServletResponse,
                            filterChain: FilterChain,
                        ) {
                            val user = request.getHeader("X-Test-User")
                            val organisation = request.getHeader("X-Test-Organisation")
                            if (user != null && organisation != null) {
                                val principal =
                                    AppPrincipal(
                                        userId = UUID.fromString(user),
                                        keycloakSubject = "kc-$user",
                                        organisationId = UUID.fromString(organisation),
                                        membershipId = uuidV7(),
                                        email = "export-e2e@example.test",
                                        fullName = "Export E2E User",
                                        permissions = emptySet(),
                                    )
                                request.setAttribute(
                                    RequestAttributeSecurityContextRepository
                                        .DEFAULT_REQUEST_ATTR_NAME,
                                    SecurityContextImpl(AppPrincipalAuthenticationToken(principal)),
                                )
                            }
                            filterChain.doFilter(request, response)
                        }
                    },
                ).apply { order = TEST_FILTER_ORDER }
        }

        private companion object {
            const val POLL_MILLIS = 100L
            const val TEST_FILTER_ORDER = -200
        }
    }
