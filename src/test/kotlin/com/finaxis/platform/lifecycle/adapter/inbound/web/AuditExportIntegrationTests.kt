package com.finaxis.platform.lifecycle.adapter.inbound.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AUDIT_EXPORT_BATCH_SIZE
import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.audit.AuditEventQueries
import com.finaxis.platform.common.audit.AuditExportBatch
import com.finaxis.platform.common.audit.AuditExportCursor
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.foundation.ViewCoupledGrants
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full-stack proof of the audit CSV export (`GET /api/v1/tenant/audit-events/export`, #167,
 * ADR 0039): the file's shape and headers, that the same filters select the same events as the
 * JSON search, the bounds that make it refuse rather than truncate, formula-injection and RFC 4180
 * encoding, the permission rules, tenant isolation and the one audit row it writes.
 *
 * Rows are seeded straight into `audit_event`, so each assertion names a known set. A tenant's own
 * provisioning rows may also be in the log, so every export narrows by an action prefix or an
 * entity type that only the seeded rows carry.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.rate-limit.policies.tenant-export.capacity=1000",
        "finaxis.rate-limit.policies.tenant-export.refill-tokens=1000",
    ],
)
@AutoConfigureMockMvc
@Suppress("LargeClass", "TooManyFunctions")
class AuditExportIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val apiJsonCodec: ApiJsonCodec,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        @MockitoSpyBean
        private lateinit var queries: AuditEventQueries

        @MockitoSpyBean
        private lateinit var auditService: AuditService

        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        private inner class Tenant(
            label: String = "export",
        ) {
            val marker = "x167${uuidV7().toString().takeLast(10)}"
            val admin = seedUser()
            val id: UUID = fixture.createActiveOrganisation(label, admin)
            val token = token(admin, id)
            val from: String = Instant.now().minus(Duration.ofDays(2)).toString()

            fun export(
                vararg parameters: Pair<String, String>,
                actor: AppPrincipalAuthenticationToken = token,
                customise: (MockHttpServletRequestDsl) -> Unit = {},
            ): MockHttpServletResponse = exportAs(actor, mapOf(*parameters), customise)

            /** The default scope: the seeded rows only, in the last two days. */
            fun scoped(vararg more: Pair<String, String>): Array<Pair<String, String>> =
                arrayOf("occurred_from" to from, "action_prefix" to "$marker.", *more)
        }

        // ---- shape, headers, columns -----------------------------------------------------------

        @Test
        fun `the file has the headers, the header row and the eleven columns in order`() {
            val t = Tenant()
            val a = seedEvent(t.id, "${t.marker}.one", minutesAgo = 10)
            seedEvent(
                t.id,
                "${t.marker}.two",
                outcome = "DENIED",
                severity = "HIGH",
                minutesAgo = 5,
            )

            val before = LocalDate.now(ZoneOffset.UTC)
            val response = t.export(*t.scoped())
            val after = LocalDate.now(ZoneOffset.UTC)

            assertEquals(200, response.status)
            assertEquals("text/csv;charset=UTF-8", response.contentType)
            val disposition = response.getHeader("Content-Disposition")
            assertTrue(
                setOf(before, after).any {
                    disposition == "attachment; filename=\"audit-events-${ddMMyyyy(it)}.csv\""
                },
                "Content-Disposition was $disposition",
            )
            assertTrue(response.getHeader("Cache-Control").orEmpty().contains("no-store"))
            val bytes = response.contentAsByteArray
            assertTrue(
                !(bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte()),
                "no byte-order mark",
            )
            val text = String(bytes, StandardCharsets.UTF_8)
            assertTrue(text.endsWith("\r\n"), "records end with CRLF")
            assertTrue(!text.replace("\r\n", "").contains('\n'), "no bare LF")
            val records = CsvTestParser.parse(text)
            assertEquals(COLUMNS, records.first())
            assertEquals(3, records.size)
            assertTrue(records.all { it.size == COLUMNS.size })
            // Newest first by default, as the search is.
            assertEquals(
                listOf("${t.marker}.two", "${t.marker}.one"),
                records.drop(1).map { it[1] },
            )
            assertNotNull(a)
        }

        @Test
        fun `no personal data, free text or json is selected into the file`() {
            val t = Tenant()
            val branch = seedBranch(t.id)
            seedEvent(
                t.id,
                "${t.marker}.secret",
                actorUserId = t.admin,
                actorSubject = "kc-secret-subject-${t.marker}",
                branchId = branch,
                reason = "reason-secret-text",
                ipAddress = "198.51.100.77",
                userAgent = "SeedAgent/9.9-secret",
                correlationId = "corr-secret-${t.marker}",
                json = """{"before":"json-secret-value"}""",
                minutesAgo = 3,
            )

            val text = bodyOf(t.export(*t.scoped()))

            listOf(
                "kc-secret-subject",
                "reason-secret-text",
                "198.51.100.77",
                "SeedAgent",
                "corr-secret",
                "json-secret-value",
            ).forEach { assertTrue(!text.contains(it), "the file must not hold $it") }
            val row = CsvTestParser.parse(text)[1]
            assertEquals(t.admin.toString(), row[COLUMNS.indexOf("actor_user_id")])
            assertEquals(branch.toString(), row[COLUMNS.indexOf("branch_id")])
        }

        @Test
        fun `an empty result is just the header row`() {
            val t = Tenant()

            val response = t.export(*t.scoped())

            assertEquals(200, response.status)
            assertEquals(listOf(COLUMNS), CsvTestParser.parse(bodyOf(response)))
        }

        // ---- the same filters select the same events as the search -----------------------------

        @Test
        fun `every filter selects the rows the json search selects, in the same order`() {
            val t = Tenant()
            val rows = seedFilterRows(t, Tenant("export-other"))

            filterSets(t, rows).forEach { parameters ->
                val exported = exportedTuples(t.export(*parameters))
                val searched = searchedTuples(t.token, parameters.toMap())
                assertEquals(searched, exported, "filters ${parameters.toMap()}")
            }
            // And the unfiltered scope really has the four rows and none of the other tenant's.
            assertEquals(4, exportedTuples(t.export(*t.scoped())).size)
        }

        private data class FilterRows(
            val branch: UUID,
            val subject: UUID,
            val entity: UUID,
        )

        /** Four rows in [t], and look-alikes in [other] that must never leak into an export. */
        private fun seedFilterRows(
            t: Tenant,
            other: Tenant,
        ): FilterRows {
            val rows = FilterRows(seedBranch(t.id), uuidV7(), uuidV7())
            val m = t.marker
            seedEvent(t.id, "$m.a", outcome = "DENIED", severity = "HIGH", minutesAgo = 50)
            seedEvent(t.id, "$m.b", outcome = "FAILURE", severity = "CRITICAL", minutesAgo = 40)
            seedEvent(
                t.id,
                "$m.c",
                branchId = rows.branch,
                subjectUserId = rows.subject,
                entityType = "POLICY_$m",
                entityId = rows.entity,
                requestId = "req-$m",
                minutesAgo = 30,
            )
            seedEvent(
                t.id,
                "$m.d",
                actorUserId = t.admin,
                actorSubject = "kc-$m",
                reason = "needle-$m",
                minutesAgo = 20,
            )
            seedEvent(other.id, "$m.a", outcome = "DENIED", minutesAgo = 50)
            seedEvent(other.id, "$m.c", minutesAgo = 30)
            return rows
        }

        private fun filterSets(
            t: Tenant,
            rows: FilterRows,
        ): List<Array<Pair<String, String>>> =
            listOf(
                t.scoped(),
                t.scoped("outcome" to "denied"),
                t.scoped("severity" to "CRITICAL"),
                t.scoped("min_severity" to "HIGH"),
                t.scoped("branch_id" to rows.branch.toString()),
                t.scoped("subject_user_id" to rows.subject.toString()),
                t.scoped("entity_type" to "POLICY_${t.marker}"),
                t.scoped("entity_id" to rows.entity.toString()),
                t.scoped("actor_id" to t.admin.toString()),
                t.scoped("actor_type" to "USER"),
                t.scoped("actor_type" to "SYSTEM"),
                t.scoped("actor_subject" to "kc-${t.marker}"),
                t.scoped("action" to "${t.marker}.b"),
                t.scoped("q" to "needle-${t.marker}"),
                t.scoped("sort_dir" to "ASC"),
                t.scoped("occurred_to" to minutesAgoText(25)),
            )

        // ---- required and bounded parameters ----------------------------------------------------

        @Test
        fun `occurred_from is required`() {
            val t = Tenant()

            t.export("action_prefix" to "${t.marker}.").assertBadRequestOn("occurred_from")
            t.export().assertBadRequestOn("occurred_from")
        }

        @Test
        fun `a window over thirty one days is refused, thirty one is allowed`() {
            val t = Tenant()
            val to = Instant.now()

            t
                .export(
                    "occurred_from" to to.minus(Duration.ofDays(31)).minusSeconds(1).toString(),
                    "occurred_to" to to.toString(),
                ).assertBadRequestOn("occurred_from")
            t
                .export("occurred_from" to to.minus(Duration.ofDays(32)).toString())
                .assertBadRequestOn("occurred_from")
            assertEquals(
                200,
                t
                    .export(
                        "occurred_from" to to.minus(Duration.ofDays(31)).toString(),
                        "occurred_to" to to.toString(),
                        "action_prefix" to "${t.marker}.",
                    ).status,
            )
        }

        @Test
        fun `occurred_from after the end of the window is refused`() {
            val t = Tenant()
            val now = Instant.now()

            t
                .export(
                    "occurred_from" to now.minus(Duration.ofDays(1)).toString(),
                    "occurred_to" to now.minus(Duration.ofDays(2)).toString(),
                ).assertBadRequestOn("occurred_from")
            t
                .export("occurred_from" to now.plus(Duration.ofDays(1)).toString())
                .assertBadRequestOn("occurred_from")
        }

        @Test
        fun `a bad filter value is a 400 naming the parameter and writes no export row`() {
            val t = Tenant()

            t.export(*t.scoped("outcome" to "BOGUS")).assertBadRequestOn("outcome")
            t
                .export(*t.scoped("severity" to "HIGH", "min_severity" to "LOW"))
                .assertBadRequestOn("min_severity")
            t.export(*t.scoped("q" to "ab")).assertBadRequestOn("q")
            t.export(*t.scoped("actor_subject" to " ")).assertBadRequestOn("actor_subject")

            assertEquals(0, exportRows(t.id).size)
        }

        // ---- the cap ---------------------------------------------------------------------------

        @Test
        fun `exactly 10000 matching rows export in full`() {
            val t = Tenant()
            seedBulk(t.id, "${t.marker}.bulk", ROW_CAP)

            val response = t.export(*t.scoped())

            assertEquals(200, response.status)
            val records = CsvTestParser.parse(bodyOf(response))
            assertEquals(ROW_CAP + 1, records.size)
            val row = exportRows(t.id).single()
            assertEquals("SUCCESS", row.outcome)
            assertEquals(ROW_CAP, row.metadata.path("row_count").asInt())
        }

        @Test
        fun `10001 matching rows are refused with no csv and a failure row of zero rows`() {
            val t = Tenant()
            seedBulk(t.id, "${t.marker}.bulk", ROW_CAP + 1)

            val response = t.export(*t.scoped())

            assertEquals(400, response.status)
            assertTrue(response.contentType.orEmpty().startsWith("application/problem+json"))
            val body = apiJsonCodec.mapper.readTree(bodyOf(response))
            assertEquals("audit.export_too_large", body.path("code").asString())
            val detail = body.path("detail").asString()
            assertTrue(detail.contains("Narrow", ignoreCase = true), detail)
            assertTrue(!detail.contains(Regex("\\d{3,}")), "the message includes no count: $detail")
            assertNull(response.getHeader("Content-Disposition"))
            assertTrue(!bodyOf(response).contains("occurred_at,action"), "no CSV header was sent")
            val row = exportRows(t.id).single()
            assertEquals("FAILURE", row.outcome)
            assertEquals("HIGH", row.severity)
            assertEquals(0, row.metadata.path("row_count").asInt())
            assertTrue(row.metadata.path("error_class").isMissingNode)
            val prefix =
                row.metadata
                    .path("filters")
                    .path("action_prefix")
                    .asString()
            assertEquals("${t.marker}.", prefix)
        }

        // ---- encoding --------------------------------------------------------------------------

        @Test
        fun `cells that could run as formulas get a leading quote, in every cell`() {
            val t = Tenant()
            val payloads =
                listOf("=1+1", "+1", "-1", "@SUM(A1)", "\tTab", "\rCarriage", "-", "=\"x\",y")
            payloads.forEachIndexed { index, payload ->
                seedEvent(
                    t.id,
                    "${t.marker}.f$index",
                    entityType = "INJ_${t.marker}",
                    requestId = payload,
                    minutesAgo = 100L - index,
                )
            }
            // The action column is a cell too.
            seedEvent(t.id, "=cmd|' /C calc'!A0", entityType = "INJ_${t.marker}", minutesAgo = 1)

            val rows =
                CsvTestParser
                    .parse(
                        bodyOf(
                            t.export(
                                "occurred_from" to t.from,
                                "entity_type" to "INJ_${t.marker}",
                                "sort_dir" to "ASC",
                            ),
                        ),
                    ).drop(1)

            assertEquals(payloads.size + 1, rows.size)
            val requestIds = rows.take(payloads.size).map { it[COLUMNS.indexOf("request_id")] }
            assertEquals(payloads.map { "'$it" }, requestIds)
            assertEquals("'=cmd|' /C calc'!A0", rows.last()[COLUMNS.indexOf("action")])
            // The cell that is only a minus is prefixed too.
            assertEquals("'-", requestIds[payloads.indexOf("-")])
        }

        @Test
        fun `commas, quotes and line breaks are quoted per RFC 4180 and round-trip`() {
            val t = Tenant()
            val payloads =
                listOf(
                    "a,b",
                    "say \"hi\"",
                    "line1\nline2",
                    "x\r\ny",
                    "\"",
                    ",",
                    "plain",
                    "trailing,",
                )
            payloads.forEachIndexed { index, payload ->
                seedEvent(
                    t.id,
                    "${t.marker}.q$index",
                    entityType = "CSV_${t.marker}",
                    requestId = payload,
                    minutesAgo = 100L - index,
                )
            }

            val response =
                t.export(
                    "occurred_from" to t.from,
                    "entity_type" to "CSV_${t.marker}",
                    "sort_dir" to "ASC",
                )
            val rows = CsvTestParser.parse(bodyOf(response)).drop(1)

            assertEquals(
                payloads,
                rows.map { it[COLUMNS.indexOf("request_id")] },
            )
            assertTrue(rows.all { it.size == COLUMNS.size })
        }

        @Test
        fun `occurred_at is written exactly as the json search writes it`() {
            val t = Tenant()
            seedEvent(t.id, "${t.marker}.whole", minutesAgo = 9, atMicros = 0)
            seedEvent(t.id, "${t.marker}.micro", minutesAgo = 8, atMicros = 123_456)
            seedEvent(t.id, "${t.marker}.milli", minutesAgo = 7, atMicros = 120_000)

            val exported =
                CsvTestParser
                    .parse(bodyOf(t.export(*t.scoped("sort_dir" to "ASC"))))
                    .drop(1)
                    .map { it[0] }
            val searched =
                searchItems(t.token, t.scoped("sort_dir" to "ASC").toMap())
                    .map { it.path("occurred_at").asString() }

            assertEquals(searched, exported)
            assertEquals(3, exported.size)
            assertTrue(exported.all { it.endsWith("Z") }, "$exported")
        }

        // ---- permission ------------------------------------------------------------------------

        @Test
        fun `a caller with audit view but not audit export gets the named 403 first`() {
            val t = Tenant()
            val viewer = seedUser()
            fixture.grantTenantPermissionsExactly(t.id, viewer, "audit.view")

            // A bad request from an unauthorised caller is a 403, not a 400.
            val response = t.export(actor = token(viewer, t.id))

            assertForbiddenNaming(response, "audit.export")
            assertForbiddenNaming(
                t.export(*t.scoped(), actor = token(viewer, t.id)),
                "audit.export",
            )
            assertEquals(0, exportRows(t.id).size)
            assertEquals(2, deniedRows(t.id, viewer, "audit.export"))
        }

        @Test
        fun `a caller with audit export but not audit view gets the 403 naming audit view`() {
            val t = Tenant()
            val exporter = seedUser()
            fixture.grantTenantPermissionsExactly(t.id, exporter, "audit.export")

            val response = t.export(*t.scoped(), actor = token(exporter, t.id))

            assertForbiddenNaming(response, "audit.view")
            assertEquals(0, exportRows(t.id).size)
            assertEquals(1, deniedRows(t.id, exporter, "audit.view"))
        }

        @Test
        fun `the seeded auditor, who holds audit view, cannot export`() {
            val t = Tenant()
            val auditor = seedUser()
            fixture.grantSeededTenantRole(t.id, auditor, "TENANT_AUDITOR")

            // It can read the log ...
            mockMvc
                .get(ApiPaths.AUDIT_EVENTS) {
                    with(authentication(searchToken(token(auditor, t.id))))
                }.andExpect { status { isOk() } }
            // ... and cannot take it out.
            val response = t.export(*t.scoped(), actor = token(auditor, t.id))

            assertForbiddenNaming(response, "audit.export")
            assertEquals(0, exportRows(t.id).size)
        }

        @Test
        fun `audit export with its view, and the tenant administrator, can export`() {
            val t = Tenant()
            val exporter = seedUser()
            fixture.grantTenantPermissionsWithViews(t.id, exporter, "audit.export")
            seedEvent(t.id, "${t.marker}.ok", minutesAgo = 4)

            assertEquals(
                setOf("audit.export", "audit.view"),
                ViewCoupledGrants.withRequiredViews(dsl, "audit.export"),
            )
            assertEquals(200, t.export(*t.scoped(), actor = token(exporter, t.id)).status)
            assertEquals(200, t.export(*t.scoped()).status)
            assertEquals(2, exportRows(t.id).size)
        }

        // ---- tenant isolation ------------------------------------------------------------------

        @Test
        fun `another tenant's events are never exported`() {
            val a = Tenant("export-iso-a")
            val b = Tenant("export-iso-b")
            seedEvent(a.id, "${a.marker}.mine", minutesAgo = 4)
            seedEvent(b.id, "${a.marker}.theirs", minutesAgo = 4)

            val rows = CsvTestParser.parse(bodyOf(a.export(*a.scoped()))).drop(1)

            assertEquals(listOf("${a.marker}.mine"), rows.map { it[1] })
        }

        // ---- the audit row ---------------------------------------------------------------------

        @Test
        fun `one audit row records the filters and the row count, never the contents`() {
            val t = Tenant()
            val requestId = "exp-${t.marker}"
            val a =
                seedEvent(
                    t.id,
                    "${t.marker}.one",
                    requestId = "cell-secret-${t.marker}",
                    minutesAgo = 6,
                )
            seedEvent(
                t.id,
                "${t.marker}.two",
                reason = "needle-${t.marker}",
                actorUserId = t.admin,
                actorSubject = "kc-subject-${t.marker}",
                minutesAgo = 5,
            )

            val response =
                t.export(
                    *t.scoped(
                        "q" to "needle-${t.marker}",
                        "actor_subject" to "kc-subject-${t.marker}",
                        "severity" to "info",
                    ),
                ) { it.header("X-Request-Id", requestId) }
            assertEquals(200, response.status)

            val row = exportRows(t.id).single()
            assertEquals("SUCCESS", row.outcome)
            assertEquals("MEDIUM", row.severity)
            assertEquals("AUDIT_EVENT", row.entityType)
            assertNull(row.entityId)
            assertEquals(t.admin, row.actorUserId)
            assertEquals(setOf("filters", "row_count"), row.metadata.keys())
            assertEquals(1, row.metadata.path("row_count").asInt())
            val filters = row.metadata.path("filters")
            assertEquals("[redacted]", filters.path("q").asString())
            assertEquals("[redacted]", filters.path("actor_subject").asString())
            assertEquals("info", filters.path("severity").asString(), "values as supplied")
            assertEquals(t.from, filters.path("occurred_from").asString(), "timestamps as sent")
            assertEquals("${t.marker}.", filters.path("action_prefix").asString())
            assertEquals(
                setOf("q", "actor_subject", "severity", "occurred_from", "action_prefix"),
                filters.keys(),
                "only what the caller sent; no occurred_to, page or size",
            )
            val stored = row.metadata.toString()
            listOf(a.toString(), "cell-secret", "needle-${t.marker}", "kc-subject").forEach {
                assertTrue(!stored.contains(it), "the audit row must not hold $it: $stored")
            }
            assertNull(row.before)
            assertNull(row.after)
        }

        @Test
        fun `a repeated parameter is recorded with all its values, redacted where it must be`() {
            val t = Tenant()

            val response =
                t.export("occurred_from" to t.from) {
                    it.param("action", "a.one", "a.two")
                    it.param("q", "mail@example.test", "other@example.test")
                }

            assertEquals(200, response.status, bodyOf(response))
            val filters = exportRows(t.id).single().metadata.path("filters")
            assertEquals("a.one,a.two", filters.path("action").asString())
            assertEquals("[redacted]", filters.path("q").asString())
            assertTrue(!filters.toString().contains("example.test"))
        }

        @Test
        fun `the row keeps the request thread's actor, request id, address and agent`() {
            val t = Tenant()
            seedEvent(t.id, "${t.marker}.one", minutesAgo = 6)
            val requestId = "exp-${t.marker}"

            val response =
                t.export(*t.scoped()) { request ->
                    request.header("X-Request-Id", requestId)
                    request.header("User-Agent", "ExportAgent/1.0")
                    request.with(
                        RequestPostProcessor { it.apply { remoteAddr = "198.51.100.44" } },
                    )
                }
            assertEquals(200, response.status)

            val row = exportRows(t.id).single()
            assertEquals(requestId, row.requestId)
            assertEquals("198.51.100.44", row.ipAddress)
            assertEquals("ExportAgent/1.0", row.userAgent)
            assertEquals(t.admin, row.actorUserId)
            assertEquals("kc-${t.admin}", row.actorSubject)
            assertNotNull(row.correlationId)
        }

        @Test
        fun `the export row is not in its own file but a later export finds it`() {
            val t = Tenant()
            seedEvent(t.id, "${t.marker}.one", minutesAgo = 6)

            val first = bodyOf(t.export(*t.scoped()))
            assertTrue(!first.contains("audit.export"))

            val later =
                CsvTestParser
                    .parse(bodyOf(t.export("occurred_from" to t.from, "action" to "audit.export")))
                    .drop(1)
            assertEquals(1, later.size)
            assertEquals("SUCCESS", later.single()[COLUMNS.indexOf("outcome")])
            assertEquals("MEDIUM", later.single()[COLUMNS.indexOf("severity")])
        }

        // ---- the stream: thread, batches, aborts, failed audit writes ------------------------

        @Test
        fun `the body streams on a virtual thread that reads no request state`() {
            val t = Tenant()
            seedEvent(t.id, "${t.marker}.one", minutesAgo = 6)
            val streamThreads = CopyOnWriteArrayList<Thread>()
            val seenContexts = CopyOnWriteArrayList<Any?>()
            doAnswer {
                streamThreads += Thread.currentThread()
                seenContexts += RequestContexts.current()
                it.callRealMethod()
            }.whenever(queries).exportBatch(any(), anyOrNull(), any())

            val response = t.export(*t.scoped()) { it.header("X-Request-Id", "thr-${t.marker}") }

            assertEquals(200, response.status)
            val thread = streamThreads.first()
            assertTrue(thread != Thread.currentThread(), "the body is not on the request thread")
            assertTrue(thread.isVirtual, "the body runs on Boot's virtual-thread executor")
            assertEquals(listOf<Any?>(null), seenContexts.distinct())
            assertEquals("thr-${t.marker}", exportRows(t.id).single().requestId)
        }

        @Test
        fun `rows are read in keyset batches of at most one thousand`() {
            val t = Tenant()
            seedBulk(t.id, "${t.marker}.bulk", 2_500)
            val limits = CopyOnWriteArrayList<Int>()
            val sizes = CopyOnWriteArrayList<Int>()
            val cursors = CopyOnWriteArrayList<AuditExportCursor?>()
            doAnswer {
                limits += it.getArgument<Int>(2)
                cursors += it.getArgument<AuditExportCursor?>(1)
                (it.callRealMethod() as AuditExportBatch).also { batch -> sizes += batch.rows.size }
            }.whenever(queries).exportBatch(any(), anyOrNull(), any())

            val response = t.export(*t.scoped())

            assertEquals(200, response.status)
            assertEquals(2_501, CsvTestParser.parse(bodyOf(response)).size)
            assertEquals(listOf(1_000, 1_000, 500), sizes)
            assertTrue(limits.all { it <= AUDIT_EXPORT_BATCH_SIZE }, "$limits")
            assertEquals(null, cursors.first())
            assertTrue(cursors.drop(1).all { it != null }, "later batches continue from a cursor")
            assertEquals(
                2_500,
                exportRows(t.id)
                    .single()
                    .metadata
                    .path("row_count")
                    .asInt(),
            )
        }

        @Test
        fun `an aborted stream writes one failure row with the class and the rows so far`() {
            val t = Tenant()
            seedBulk(t.id, "${t.marker}.bulk", 1_500)
            val calls = AtomicInteger()
            doAnswer {
                if (calls.incrementAndGet() == 2) {
                    throw IllegalStateException("boom-secret-message-${t.marker}")
                }
                it.callRealMethod()
            }.whenever(queries).exportBatch(any(), anyOrNull(), any())

            var dispatchFailure: Throwable? = null
            var body = ""
            val events =
                capturingLogs {
                    val result =
                        mockMvc
                            .get(EXPORT_PATH) {
                                t.scoped().forEach { (name, value) -> param(name, value) }
                                with(authentication(t.token))
                            }.andReturn()
                    assertTrue(result.request.isAsyncStarted)
                    result.asyncResult
                    // The container's async dispatch runs the exception resolvers on the failure.
                    dispatchFailure =
                        runCatching { mockMvc.perform(asyncDispatch(result)) }.exceptionOrNull()
                    body = bodyOf(result.response)
                }

            // The response had already started: no problem document may be appended to the CSV
            // (a file that looks complete and is not), and the dispatch fails so the container
            // aborts the transfer instead of finishing it.
            assertTrue(!body.contains("\"status\""), "a problem document was appended: $body")
            assertTrue(!body.contains("internal_error"), "a problem document was appended")
            assertEquals(
                1_001,
                CsvTestParser.parse(body).size,
                "the header and the first batch only",
            )
            assertNotNull(dispatchFailure, "the failure is not swallowed into a clean completion")
            assertTrue(
                events.none {
                    it.level == Level.ERROR &&
                        it.formattedMessage.contains("Unexpected API")
                },
                "a committed response is not reported as an unexpected API exception",
            )

            val row = exportRows(t.id).single()
            assertEquals("FAILURE", row.outcome)
            assertEquals("HIGH", row.severity)
            assertEquals(1_000, row.metadata.path("row_count").asInt())
            assertEquals("IllegalStateException", row.metadata.path("error_class").asString())
            assertEquals(setOf("filters", "row_count", "error_class"), row.metadata.keys())
            assertTrue(!row.metadata.toString().contains("boom-secret-message"))
            assertTrue(
                events.none { it.mentions("boom-secret-message-${t.marker}") },
                "no log line may carry the exception message",
            )
        }

        @Test
        fun `a failing audit write after the bytes were sent only logs a quiet warning`() {
            val t = Tenant()
            seedEvent(t.id, "${t.marker}.one", minutesAgo = 6)
            doThrow(IllegalStateException("db-secret-message-${t.marker}"))
                .whenever(auditService)
                .record(argThat { action == "audit.export" })

            val events = capturingLogs { assertEquals(200, t.export(*t.scoped()).status) }

            val response = t.export(*t.scoped())
            assertEquals(200, response.status)
            assertEquals(2, CsvTestParser.parse(bodyOf(response)).size)
            assertEquals(0, exportRows(t.id).size, "no row was written")
            val warnings =
                events.filter {
                    it.level == Level.WARN && it.formattedMessage.contains("audit export row")
                }
            assertTrue(warnings.isNotEmpty(), "the failed write is logged")
            warnings.forEach {
                val message = it.formattedMessage
                assertTrue(message.contains("IllegalStateException"), message)
                assertNull(it.throwableProxy, "the throwable is never handed to the logger")
            }
            assertTrue(events.none { it.mentions("db-secret-message-${t.marker}") })
        }

        @Test
        fun `a failing audit write for the too-large refusal still answers 400`() {
            val t = Tenant()
            seedBulk(t.id, "${t.marker}.bulk", ROW_CAP + 1)
            doThrow(IllegalStateException("db-secret-message"))
                .whenever(auditService)
                .recordIndependently(argThat { action == "audit.export" })

            val response = t.export(*t.scoped())

            assertEquals(400, response.status)
            assertEquals(
                "audit.export_too_large",
                apiJsonCodec.mapper
                    .readTree(bodyOf(response))
                    .path("code")
                    .asString(),
            )
            assertEquals(0, exportRows(t.id).size)
        }

        @Test
        fun `a refused caller causes no count and no read`() {
            val t = Tenant()
            val viewer = seedUser()
            fixture.grantTenantPermissionsExactly(t.id, viewer, "audit.view")

            val refused = t.export(*t.scoped(), actor = token(viewer, t.id))
            assertForbiddenNaming(refused, "audit.export")
            assertForbiddenNaming(t.export(actor = token(viewer, t.id)), "audit.export")

            verify(queries, never()).countBounded(any<AuditEventFilter>(), any())
            verify(queries, never()).exportBatch(any(), anyOrNull(), any())
        }

        // ---- the published contract ------------------------------------------------------------

        @Test
        fun `the openapi document names audit export and its view in the hand-written 403 text`() {
            val operation =
                apiJsonCodec.mapper
                    .readTree(
                        mockMvc
                            .get("/v3/api-docs")
                            .andReturn()
                            .response.contentAsString,
                    ).path("paths")
                    .path(EXPORT_PATH)
                    .path("get")
            assertTrue(!operation.isMissingNode, "the export route is documented")

            // No @PreAuthorize derives the text for this GET, so it is written by hand: the code
            // and every view the catalogue pairs with it, exactly as the mutation routes' are.
            val forbidden =
                operation
                    .path("responses")
                    .path("403")
                    .path("description")
                    .asString()
            val views = ViewCoupledGrants.requiredViews(dsl, "audit.export")
            assertEquals(listOf("audit.view"), views)
            assertTrue(forbidden.contains("`audit.export`"), forbidden)
            views.forEach { assertTrue(forbidden.contains("`$it`"), forbidden) }
            assertTrue(operation.path("description").asString().contains("`audit.export`"))
            assertTrue(operation.path("description").asString().contains("`audit.view`"))
            assertTrue(
                operation
                    .path("responses")
                    .path("200")
                    .path("content")
                    .has("text/csv"),
            )
            assertTrue(operation.path("responses").has("400"))
            assertTrue(operation.path("responses").has("429"))

            // The shared active-organisation header is a $ref, not one of the route's own filters.
            val parameters = operation.path("parameters").toList().filter { it.has("name") }
            val names = parameters.map { it.path("name").asString() }
            assertEquals(AUDIT_FILTERS.sorted(), names.sorted())
            assertTrue("page" !in names && "size" !in names)
            assertTrue(
                parameters
                    .single { it.path("name").asString() == "occurred_from" }
                    .path("required")
                    .asBoolean(),
                "occurred_from is documented as required",
            )
        }

        // ---- plumbing --------------------------------------------------------------------------

        /** Runs [block] and returns every log event the root logger saw meanwhile. */
        private fun capturingLogs(block: () -> Unit): List<ILoggingEvent> {
            val root =
                LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                    as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            try {
                block()
            } finally {
                root.detachAppender(appender)
            }
            return appender.list.toList()
        }

        private fun ILoggingEvent.mentions(text: String): Boolean =
            formattedMessage.contains(text) || throwableProxy.chainMentions(text)

        private fun IThrowableProxy?.chainMentions(text: String): Boolean =
            this != null && ((message?.contains(text) == true) || cause.chainMentions(text))

        private fun exportAs(
            actor: AppPrincipalAuthenticationToken,
            parameters: Map<String, String>,
            customise: (MockHttpServletRequestDsl) -> Unit,
        ): MockHttpServletResponse {
            val result =
                mockMvc
                    .get(EXPORT_PATH) {
                        parameters.forEach { (name, value) -> param(name, value) }
                        with(authentication(actor))
                        customise(this)
                    }.andReturn()
            // A streamed body runs on another thread: wait for it to finish.
            if (result.request.isAsyncStarted) {
                result.asyncResult
            }
            return result.response
        }

        private fun JsonNode.keys(): Set<String> = properties().map { it.key }.toSet()

        private fun bodyOf(response: MockHttpServletResponse): String =
            String(response.contentAsByteArray, StandardCharsets.UTF_8)

        private fun MockHttpServletResponse.assertBadRequestOn(field: String) {
            assertEquals(400, status, bodyOf(this))
            val body = apiJsonCodec.mapper.readTree(bodyOf(this))
            assertEquals("invalid_parameter", body.path("code").asString())
            assertEquals(
                field,
                body
                    .path("violations")
                    .path(0)
                    .path("field")
                    .asString(),
            )
        }

        private fun assertForbiddenNaming(
            response: MockHttpServletResponse,
            code: String,
        ) {
            assertEquals(403, response.status, bodyOf(response))
            val body = apiJsonCodec.mapper.readTree(bodyOf(response))
            assertEquals("forbidden", body.path("code").asString())
            assertEquals("Missing permission: $code.", body.path("detail").asString())
        }

        private fun exportedTuples(response: MockHttpServletResponse): List<List<String>> {
            assertEquals(200, response.status, bodyOf(response))
            return CsvTestParser.parse(bodyOf(response)).drop(1)
        }

        private fun searchedTuples(
            token: AppPrincipalAuthenticationToken,
            parameters: Map<String, String>,
        ): List<List<String>> =
            searchItems(token, parameters).map { item ->
                COLUMNS.map { column ->
                    item
                        .path(column)
                        .takeUnless { it.isNull || it.isMissingNode }
                        ?.asString()
                        .orEmpty()
                }
            }

        private fun searchItems(
            token: AppPrincipalAuthenticationToken,
            parameters: Map<String, String>,
        ): List<JsonNode> {
            val body =
                mockMvc
                    .get(ApiPaths.AUDIT_EVENTS) {
                        param("size", "100")
                        parameters.forEach { (name, value) -> param(name, value) }
                        with(authentication(searchToken(token)))
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
            return apiJsonCodec.mapper
                .readTree(body)
                .path("items")
                .toList()
        }

        private data class ExportRow(
            val outcome: String,
            val severity: String,
            val entityType: String,
            val entityId: UUID?,
            val actorUserId: UUID?,
            val requestId: String?,
            val ipAddress: String?,
            val userAgent: String?,
            val actorSubject: String?,
            val correlationId: String?,
            val metadata: JsonNode,
            val before: String?,
            val after: String?,
        )

        private fun exportRows(organisationId: UUID): List<ExportRow> =
            dsl
                .selectFrom(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
                .and(AUDIT_EVENT.ACTION.eq("audit.export"))
                .orderBy(AUDIT_EVENT.EVENT_TIME.asc())
                .fetch {
                    ExportRow(
                        outcome = it.outcome!!,
                        severity = it.severity!!,
                        entityType = it.entityType!!,
                        entityId = it.entityId,
                        actorUserId = it.actorUserId,
                        requestId = it.requestId,
                        ipAddress = it.ipAddress,
                        userAgent = it.userAgent,
                        actorSubject = it.actorExternalSubject,
                        correlationId = it.correlationId,
                        metadata = apiJsonCodec.mapper.readTree(it.metadataJsonb!!.data()),
                        before = it.beforeJsonb?.data(),
                        after = it.afterJsonb?.data(),
                    )
                }

        private fun deniedRows(
            organisationId: UUID,
            actorId: UUID,
            permission: String,
        ): Int =
            dsl
                .selectCount()
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
                .and(AUDIT_EVENT.ACTION.eq("api.access_denied"))
                .and(AUDIT_EVENT.ACTOR_USER_ID.eq(actorId))
                .and(AUDIT_EVENT.METADATA_JSONB.cast(String::class.java).contains(permission))
                .fetchOne(0, Int::class.java)!!

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

        @Suppress("LongParameterList")
        private fun seedEvent(
            organisationId: UUID,
            action: String,
            outcome: String = "SUCCESS",
            severity: String = "INFO",
            branchId: UUID? = null,
            actorUserId: UUID? = null,
            actorSubject: String? = null,
            subjectUserId: UUID? = null,
            entityType: String = "ORGANISATION",
            entityId: UUID? = null,
            requestId: String? = null,
            reason: String? = null,
            ipAddress: String? = null,
            userAgent: String? = null,
            correlationId: String? = null,
            json: String? = null,
            atMicros: Int? = null,
            minutesAgo: Long,
        ): UUID {
            val id = uuidV7()
            val time =
                OffsetDateTime
                    .now(ZoneOffset.UTC)
                    .minusMinutes(minutesAgo)
                    .let { if (atMicros == null) it else it.withNano(atMicros * NANOS_PER_MICRO) }
            dsl
                .insertInto(AUDIT_EVENT)
                .set(AUDIT_EVENT.ID, id)
                .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
                .set(AUDIT_EVENT.EVENT_TIME, time)
                .set(AUDIT_EVENT.ACTOR_TYPE, if (actorUserId == null) "SYSTEM" else "USER")
                .set(AUDIT_EVENT.ACTOR_USER_ID, actorUserId)
                .set(AUDIT_EVENT.ACTOR_EXTERNAL_SUBJECT, actorSubject)
                .set(AUDIT_EVENT.BRANCH_ID, branchId)
                .set(AUDIT_EVENT.SUBJECT_USER_ID, subjectUserId)
                .set(AUDIT_EVENT.EVENT_TYPE, action)
                .set(AUDIT_EVENT.ENTITY_TYPE, entityType)
                .set(AUDIT_EVENT.ENTITY_ID, entityId)
                .set(AUDIT_EVENT.ACTION, action)
                .set(AUDIT_EVENT.OUTCOME, outcome)
                .set(AUDIT_EVENT.SEVERITY, severity)
                .set(AUDIT_EVENT.REASON, reason)
                .set(AUDIT_EVENT.REQUEST_ID, requestId)
                .set(AUDIT_EVENT.IP_ADDRESS, ipAddress)
                .set(AUDIT_EVENT.USER_AGENT, userAgent)
                .set(AUDIT_EVENT.CORRELATION_ID, correlationId)
                .apply {
                    if (json != null) {
                        set(AUDIT_EVENT.BEFORE_JSONB, JSONB.jsonb(json))
                        set(AUDIT_EVENT.AFTER_JSONB, JSONB.jsonb(json))
                        set(AUDIT_EVENT.METADATA_JSONB, JSONB.jsonb(json))
                    }
                }.execute()
            return id
        }

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "export-$id")
                .set(USER_ACCOUNT.EMAIL, "export-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Export User")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun seedBranch(organisationId: UUID): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(BRANCH)
                .set(BRANCH.ID, id)
                .set(BRANCH.ORGANISATION_ID, organisationId)
                .set(BRANCH.BRANCH_CODE, "X167-$id")
                .set(BRANCH.BRANCH_NAME, "Export Branch")
                .set(BRANCH.BRANCH_TYPE, "MAIN")
                .set(BRANCH.STATUS, "ACTIVE")
                .set(BRANCH.TIMEZONE, "Africa/Nairobi")
                .set(BRANCH.CREATED_AT, now)
                .set(BRANCH.UPDATED_AT, now)
                .execute()
            return id
        }

        private fun token(
            userId: UUID,
            organisationId: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "kc-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "export@example.test",
                fullName = "Export User",
                // The route has no @PreAuthorize: the service check is the only authorisation, so
                // the token's own authorities are deliberately empty.
                permissions = emptySet(),
            ),
        )

        private fun minutesAgoText(minutes: Long): String =
            Instant.now().minus(Duration.ofMinutes(minutes)).toString()

        /** The JSON search keeps its coarse `audit.view` gate; the export has none. */
        private fun searchToken(token: AppPrincipalAuthenticationToken) =
            AppPrincipalAuthenticationToken(token.principal.copy(permissions = setOf("audit.view")))

        private fun ddMMyyyy(date: LocalDate): String =
            "%02d-%02d-%04d".format(date.dayOfMonth, date.monthValue, date.year)

        private companion object {
            const val EXPORT_PATH = "${ApiPaths.AUDIT_EVENTS}/export"
            const val ROW_CAP = 10_000
            const val NANOS_PER_MICRO = 1_000
            val AUDIT_FILTERS =
                listOf(
                    "entity_type",
                    "entity_id",
                    "actor_id",
                    "subject_user_id",
                    "action",
                    "action_prefix",
                    "occurred_from",
                    "occurred_to",
                    "outcome",
                    "severity",
                    "min_severity",
                    "branch_id",
                    "q",
                    "actor_type",
                    "actor_subject",
                    "sort_dir",
                )
            val COLUMNS =
                listOf(
                    "occurred_at",
                    "action",
                    "outcome",
                    "severity",
                    "actor_type",
                    "actor_user_id",
                    "branch_id",
                    "subject_user_id",
                    "entity_type",
                    "entity_id",
                    "request_id",
                )
        }
    }
