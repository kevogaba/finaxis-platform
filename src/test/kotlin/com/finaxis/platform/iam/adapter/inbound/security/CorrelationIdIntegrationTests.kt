package com.finaxis.platform.iam.adapter.inbound.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #252 through the real filter chain: a client `X-Correlation-Id` reaches the MDC and the audit
 * row's `correlation_id` only when it is 8 to 64 characters of `[A-Za-z0-9._-]`, the shape
 * `X-Request-Id` already has to take. Any other value is replaced by the request id, exactly as an
 * absent header is, and is never logged, put in the MDC, stored or echoed. A value carrying CR or
 * LF is refused by Spring Security's firewall when the filter reads it, with a problem body that
 * carries only a generated request id.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val maker = seedUser()
        private val organisationId = fixture.createActiveOrganisation("correlation-id", maker)

        private val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        private val appender = ListAppender<ILoggingEvent>()

        @BeforeEach
        fun attachAppender() {
            appender.start()
            rootLogger.addAppender(appender)
        }

        @AfterEach
        fun detachAppender() {
            rootLogger.detachAppender(appender)
            appender.stop()
        }

        @Test
        fun `a good correlation id is kept in the audit row and the MDC`() {
            val response = createBranch(requestId = GOOD_REQUEST_ID, correlationId = GOOD_ID)
            val branchId = branchId(response)

            assertEquals(GOOD_REQUEST_ID, response.getHeader("X-Request-Id"))
            assertEquals(GOOD_ID to GOOD_REQUEST_ID, auditCorrelation(branchId))
            val access =
                accessLogEvents().single { it.mdcPropertyMap["requestId"] == GOOD_REQUEST_ID }
            assertEquals(GOOD_ID, access.mdcPropertyMap["correlationId"])
        }

        @Test
        fun `an absent correlation id still defaults to the request id`() {
            val response = createBranch(requestId = null, correlationId = null)
            val requestId = requireNotNull(response.getHeader("X-Request-Id"))

            assertEquals(requestId to requestId, auditCorrelation(branchId(response)))
            val access = accessLogEvents().single { it.mdcPropertyMap["requestId"] == requestId }
            assertEquals(requestId, access.mdcPropertyMap["correlationId"])
        }

        @ParameterizedTest
        @MethodSource("hostileIds")
        fun `a hostile correlation id is replaced by the request id and never logged or stored`(
            hostile: String,
        ) {
            listOf(GOOD_REQUEST_ID, null).forEach { clientRequestId ->
                val response = createBranch(requestId = clientRequestId, correlationId = hostile)
                val requestId = requireNotNull(response.getHeader("X-Request-Id"))
                if (clientRequestId == null) {
                    assertEquals(UUID_V7, UUID.fromString(requestId).version())
                }

                assertEquals(requestId to requestId, auditCorrelation(branchId(response)))
                val access =
                    accessLogEvents().single { it.mdcPropertyMap["requestId"] == requestId }
                assertEquals(requestId, access.mdcPropertyMap["correlationId"])
                assertNotEchoed(response, hostile)
                appender.list.clear()
            }
            assertNotStored(hostile)
        }

        @Test
        fun `a correlation id carrying CR or LF is refused by the firewall with a problem body`() {
            val before = branchCount()
            CONTROL_CHARACTER_IDS.forEach { hostile ->
                val response =
                    mockMvc
                        .post(ApiPaths.BRANCHES) {
                            branchRequest(correlationId = hostile)
                        }.andExpect {
                            status { isBadRequest() }
                            content {
                                contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                            }
                            jsonPath("$.code") { value("request_rejected") }
                        }.andReturn()
                        .response
                val requestId = requireNotNull(response.getHeader("X-Request-Id"))
                assertEquals(UUID_V7, UUID.fromString(requestId).version())
                assertTrue(response.contentAsString.contains("\"request_id\":\"$requestId\""))
                assertNotEchoed(response, "forged")
            }
            assertEquals(before, branchCount())
            assertNotStored("forged")
        }

        private fun createBranch(
            requestId: String?,
            correlationId: String?,
        ): MockHttpServletResponse =
            mockMvc
                .post(ApiPaths.BRANCHES) {
                    requestId?.let { header("X-Request-Id", it) }
                    branchRequest(correlationId)
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response

        private fun MockHttpServletRequestDsl.branchRequest(correlationId: String?) {
            correlationId?.let { header("X-Correlation-Id", it) }
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
            contentType = MediaType.APPLICATION_JSON
            content =
                apiJsonCodec.mapper.writeValueAsString(
                    mapOf(
                        "branch_code" to "BR-${uuidV7().toString().takeLast(8).uppercase()}",
                        "branch_name" to "Correlation Branch",
                        "branch_type" to "OPERATIONAL",
                        "timezone" to "Africa/Nairobi",
                    ),
                )
            with(authentication(makerToken()))
        }

        private fun branchId(response: MockHttpServletResponse): UUID =
            UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(response.contentAsString)
                    .get("branch_id")
                    .asString(),
            )

        private fun auditCorrelation(branchId: UUID): Pair<String?, String?> {
            val row =
                dsl
                    .fetch(
                        "SELECT correlation_id, request_id FROM audit_event WHERE entity_id = ?",
                        branchId,
                    ).single()
            return row.get(0, String::class.java) to row.get(1, String::class.java)
        }

        private fun assertNotEchoed(
            response: MockHttpServletResponse,
            hostile: String,
        ) {
            val echoed = response.headerNames.flatMap { response.getHeaders(it) }
            assertFalse(echoed.any { hostile in it })
            assertFalse(hostile in response.contentAsString)
            val events = appender.list.toList()
            assertFalse(events.any { it.formattedMessage.contains(hostile) })
            assertFalse(events.any { event -> event.mdcPropertyMap.values.any { hostile in it } })
        }

        private fun assertNotStored(hostile: String) {
            val stored =
                dsl
                    .fetchOne(
                        "SELECT COUNT(*) FROM audit_event WHERE organisation_id = ? " +
                            "AND (position(? in coalesce(correlation_id, '')) > 0 " +
                            "OR position(? in coalesce(request_id, '')) > 0)",
                        organisationId,
                        hostile,
                        hostile,
                    )!!
                    .get(0, Int::class.java)
            assertEquals(0, stored)
        }

        private fun branchCount(): Int =
            dsl
                .fetchOne("SELECT COUNT(*) FROM branch WHERE organisation_id = ?", organisationId)!!
                .get(0, Int::class.java)

        private fun accessLogEvents(): List<ILoggingEvent> =
            appender.list.filter { it.loggerName == "com.finaxis.platform.http.access" }

        private fun makerToken() =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = maker,
                    keycloakSubject = "user-$maker",
                    organisationId = organisationId,
                    membershipId = uuidV7(),
                    branchId = null,
                    email = "maker@correlation-id.test",
                    fullName = "Correlation Maker",
                    permissions = setOf("branch.create"),
                ),
            )

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "maker-$id")
                .set(USER_ACCOUNT.EMAIL, "maker-$id@correlation-id.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "maker")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            const val GOOD_ID = "saga.Step_0042-correlation"
            const val GOOD_REQUEST_ID = "client-request.ID_0252"
            const val UUID_V7 = 7

            // Over the wire the HTTP parser ends or refuses a header line at CR or LF; inside the
            // container Spring Security's StrictHttpFirewall refuses the value when the filter
            // reads the header, before it reaches the MDC, the audit row or the response.
            val CONTROL_CHARACTER_IDS =
                listOf(
                    "forged-correlation\r\nX-Injected: yes",
                    "forged-correlation\nhttp_access requestId=\"spoofed\"",
                    "forged-correlation\rcarriage-return",
                )

            @JvmStatic
            fun hostileIds(): List<String> =
                listOf(
                    "has spaces in it",
                    "a".repeat(500),
                    "sága-ünicode-correlation",
                    "x1y2z3w",
                )
        }
    }
