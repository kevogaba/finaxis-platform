package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditActorType
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full-stack proof of the #183 audit filters on the tenant route and on both platform routes:
 * every filter narrows to the rows it names, filters combine with AND, a matching row of another
 * tenant never appears, and bad input is a 400 naming the parameter, never a 500.
 *
 * Rows are seeded straight into `audit_event` so each filter has a known match and near-miss; the
 * tenant fixture's own rows (provisioning, activation) may also be on a page, so assertions name
 * the seeded rows they expect or refuse and check the predicate on every returned item.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AuditEventFilterIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val apiJsonCodec: ApiJsonCodec,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        /** Two tenants seeded with the same rows, so a leak of tenant B into A is visible. */
        private inner class Scenario {
            val marker = "f183x${uuidV7().toString().takeLast(8)}"
            val platformAdmin = seedUser().also { fixture.grantPlatformSuperAdmin(it) }
            val tenantAAdmin = seedUser()
            val tenantA = fixture.createActiveOrganisation("filter-a", tenantAAdmin)
            val tenantB = fixture.createActiveOrganisation("filter-b", seedUser())
            val branchA = seedBranch(tenantA)
            val otherBranchA = seedBranch(tenantA)
            val branchB = seedBranch(tenantB)
            val rowsA = seedRows(tenantA, branchA, otherBranchA)
            val rowsB = seedRows(tenantB, branchB, null)
            val platformRows = seedRows(PlatformOrganisation.ID, null, null)

            fun seedRows(
                organisationId: UUID,
                branchId: UUID?,
                otherBranchId: UUID?,
            ): Rows =
                Rows(
                    deniedHigh =
                        seedEvent(
                            organisationId,
                            "$marker.approve",
                            outcome = "DENIED",
                            severity = "HIGH",
                            branchId = branchId,
                            reason = "Maker cannot approve 100%",
                            minutesAgo = 50,
                        ),
                    failureCritical =
                        seedEvent(
                            organisationId,
                            "$marker.post",
                            outcome = "FAILURE",
                            severity = "CRITICAL",
                            minutesAgo = 40,
                        ),
                    successLowOnOtherBranch =
                        seedEvent(
                            organisationId,
                            "${marker}x.update",
                            severity = "LOW",
                            branchId = otherBranchId,
                            minutesAgo = 30,
                        ),
                    userWithSubject =
                        seedEvent(
                            organisationId,
                            "$marker.update",
                            actorUserId = tenantAAdmin,
                            actorSubject = "kc-$marker",
                            entityType = "POLICY_$marker",
                            minutesAgo = 20,
                        ),
                    oldSystem =
                        seedEvent(
                            organisationId,
                            "$marker.archive",
                            minutesAgo = Duration.ofDays(40).toMinutes(),
                        ),
                )
        }

        private data class Rows(
            val deniedHigh: UUID,
            val failureCritical: UUID,
            val successLowOnOtherBranch: UUID,
            val userWithSubject: UUID,
            val oldSystem: UUID,
        ) {
            val all = listOf(deniedHigh, failureCritical, successLowOnOtherBranch, userWithSubject)
        }

        @Test
        fun `each filter narrows the tenant route and never shows another tenant's row`() {
            val s = Scenario()
            val tenant = Route(ApiPaths.AUDIT_EVENTS, token(s.tenantAAdmin, s.tenantA))

            assertFilter(s, tenant, s.rowsA, mapOf("outcome" to "DENIED"), s.rowsA.deniedHigh) {
                it.text("outcome") == AuditOutcome.DENIED.name
            }
            assertFilter(
                s,
                tenant,
                s.rowsA,
                mapOf("severity" to "critical"),
                s.rowsA.failureCritical,
            ) { it.text("severity") == AuditSeverity.CRITICAL.name }
            assertFilter(
                s,
                tenant,
                s.rowsA,
                mapOf("min_severity" to "HIGH"),
                s.rowsA.deniedHigh,
                s.rowsA.failureCritical,
            ) { it.text("severity") in setOf("HIGH", "CRITICAL") }
            assertFilter(
                s,
                tenant,
                s.rowsA,
                mapOf("branch_id" to s.branchA.toString()),
                s.rowsA.deniedHigh,
            ) { it.text("branch_id") == s.branchA.toString() }
            assertFilter(
                s,
                tenant,
                s.rowsA,
                mapOf("actor_subject" to "kc-${s.marker}"),
                s.rowsA.userWithSubject,
            ) { it.text("actor_external_subject") == "kc-${s.marker}" }
            assertFilter(
                s,
                tenant,
                s.rowsA,
                mapOf("actor_type" to "USER"),
                s.rowsA.userWithSubject,
            ) { it.text("actor_type") == AuditActorType.USER.name }
        }

        @Test
        fun `prefix, text and the old-row window work on the tenant route`() {
            val s = Scenario()
            val tenant = Route(ApiPaths.AUDIT_EVENTS, token(s.tenantAAdmin, s.tenantA))
            val dayAgo =
                OffsetDateTime
                    .now(ZoneOffset.UTC)
                    .minusDays(1)
                    .toInstant()
                    .toString()

            // "<marker>." is a literal prefix: "<marker>x.update" and the old row differ.
            assertExactly(
                tenant,
                mapOf("action_prefix" to "${s.marker}."),
                s.rowsA.deniedHigh,
                s.rowsA.failureCritical,
                s.rowsA.userWithSubject,
                s.rowsA.oldSystem,
            )
            // Reason text, case-insensitive, with a literal % ...
            assertExactly(
                tenant,
                mapOf("q" to "CANNOT APPROVE 100%", "occurred_from" to dayAgo),
                s.rowsA.deniedHigh,
            )
            // ... and in the entity type ("POLICY_<marker>", matched case-insensitively).
            assertExactly(
                tenant,
                mapOf("q" to "policy_${s.marker}", "occurred_from" to dayAgo),
                s.rowsA.userWithSubject,
            )
            assertExactly(
                tenant,
                mapOf(
                    "action_prefix" to "${s.marker}.",
                    "outcome" to "SUCCESS",
                    "actor_type" to "SYSTEM",
                ),
                s.rowsA.oldSystem,
            )
        }

        @Test
        fun `combined filters are AND-ed and sort_dir orders by event time`() {
            val s = Scenario()
            val tenant = Route(ApiPaths.AUDIT_EVENTS, token(s.tenantAAdmin, s.tenantA))
            val dayAgo =
                OffsetDateTime
                    .now(ZoneOffset.UTC)
                    .minusDays(1)
                    .toInstant()
                    .toString()

            assertExactly(
                tenant,
                mapOf(
                    "outcome" to "DENIED",
                    "min_severity" to "HIGH",
                    "branch_id" to s.branchA.toString(),
                    "action_prefix" to "${s.marker}.",
                    "q" to "maker",
                    "occurred_from" to dayAgo,
                    "actor_type" to "SYSTEM",
                ),
                s.rowsA.deniedHigh,
            )
            assertExactly(
                tenant,
                mapOf("outcome" to "DENIED", "branch_id" to s.otherBranchA.toString()),
            )

            val prefix = mapOf("action_prefix" to "${s.marker}.")
            val newestFirst =
                listOf(
                    s.rowsA.userWithSubject,
                    s.rowsA.failureCritical,
                    s.rowsA.deniedHigh,
                    s.rowsA.oldSystem,
                )
            assertEquals(newestFirst, ids(tenant, prefix))
            assertEquals(newestFirst, ids(tenant, prefix + ("sort_dir" to "desc")))
            assertEquals(newestFirst.reversed(), ids(tenant, prefix + ("sort_dir" to "ASC")))
        }

        @Test
        fun `the platform tenant route filters one tenant's log and never another's`() {
            val s = Scenario()
            val route =
                Route(
                    "${ApiPaths.PLATFORM_TENANTS}/${s.tenantA}/audit-events",
                    token(s.platformAdmin, PlatformOrganisation.ID),
                )

            assertFilter(s, route, s.rowsA, mapOf("outcome" to "DENIED"), s.rowsA.deniedHigh) {
                it.text("outcome") == "DENIED"
            }
            assertFilter(
                s,
                route,
                s.rowsA,
                mapOf("min_severity" to "high"),
                s.rowsA.deniedHigh,
                s.rowsA.failureCritical,
            ) { it.text("severity") in setOf("HIGH", "CRITICAL") }
            assertFilter(
                s,
                route,
                s.rowsA,
                mapOf("branch_id" to s.branchA.toString()),
                s.rowsA.deniedHigh,
            ) { it.text("branch_id") == s.branchA.toString() }
            assertFilter(
                s,
                route,
                s.rowsA,
                mapOf("actor_type" to "SYSTEM"),
                s.rowsA.deniedHigh,
                s.rowsA.failureCritical,
            ) { it.text("actor_type") == "SYSTEM" }
            assertExactly(
                route,
                mapOf(
                    "q" to "maker",
                    "occurred_from" to
                        OffsetDateTime
                            .now()
                            .minusDays(1)
                            .toInstant()
                            .toString(),
                    "action_prefix" to "${s.marker}.",
                    "sort_dir" to "asc",
                ),
                s.rowsA.deniedHigh,
            )
            // Tenant B's identical rows exist but are not in tenant A's log.
            assertExactly(route, mapOf("branch_id" to s.branchB.toString()))
        }

        @Test
        fun `the platform log route filters the platform organisation's own rows`() {
            val s = Scenario()
            val route =
                Route(
                    ApiPaths.PLATFORM_AUDIT_EVENTS,
                    token(s.platformAdmin, PlatformOrganisation.ID),
                )

            assertExactly(
                route,
                mapOf("action_prefix" to "${s.marker}.", "outcome" to "FAILURE"),
                s.platformRows.failureCritical,
            )
            assertExactly(
                route,
                mapOf("action_prefix" to "${s.marker}.", "min_severity" to "HIGH"),
                s.platformRows.deniedHigh,
                s.platformRows.failureCritical,
            )
            assertExactly(
                route,
                mapOf(
                    "action_prefix" to "${s.marker}",
                    "severity" to "LOW",
                    "actor_type" to "SYSTEM",
                ),
                s.platformRows.successLowOnOtherBranch,
            )
            assertExactly(
                route,
                mapOf(
                    "q" to s.marker,
                    "occurred_from" to
                        OffsetDateTime
                            .now()
                            .minusDays(1)
                            .toInstant()
                            .toString(),
                    "actor_type" to "USER",
                ),
                s.platformRows.userWithSubject,
            )
        }

        @Test
        fun `bad filters are a 400 naming the parameter on every route`() {
            val s = Scenario()
            val dayAgo =
                OffsetDateTime
                    .now()
                    .minusDays(1)
                    .toInstant()
                    .toString()
            val routes =
                listOf(
                    Route(ApiPaths.AUDIT_EVENTS, token(s.tenantAAdmin, s.tenantA)),
                    Route(
                        ApiPaths.PLATFORM_AUDIT_EVENTS,
                        token(s.platformAdmin, PlatformOrganisation.ID),
                    ),
                    Route(
                        "${ApiPaths.PLATFORM_TENANTS}/${s.tenantA}/audit-events",
                        token(s.platformAdmin, PlatformOrganisation.ID),
                    ),
                )
            val failures =
                listOf(
                    mapOf("outcome" to "MAYBE") to "outcome",
                    mapOf("severity" to "LOUD") to "severity",
                    mapOf("min_severity" to "1") to "min_severity",
                    mapOf("severity" to "HIGH", "min_severity" to "LOW") to "min_severity",
                    mapOf("actor_type" to "SERVICE_ACCOUNT") to "actor_type",
                    mapOf("sort_dir" to "sideways") to "sort_dir",
                    mapOf("branch_id" to "not-a-uuid") to "branch_id",
                    mapOf("action_prefix" to "b") to "action_prefix",
                    mapOf("action_prefix" to "b".repeat(65)) to "action_prefix",
                    mapOf("q" to "ab", "occurred_from" to dayAgo) to "q",
                    mapOf("q" to "a".repeat(65), "occurred_from" to dayAgo) to "q",
                    mapOf("q" to "abc") to "occurred_from",
                    mapOf("q" to "abc", "occurred_from" to "2000-01-01T00:00:00Z") to
                        "occurred_from",
                    mapOf("q" to "abc", "occurred_from" to "2999-01-01T00:00:00Z") to
                        "occurred_from",
                    mapOf("actor_subject" to "s".repeat(256)) to "actor_subject",
                )
            routes.forEach { route ->
                failures.forEach { (parameters, field) ->
                    assertBadRequest(route, parameters, field)
                }
            }
        }

        @Test
        fun `the platform routes refuse the withheld actor subject as a filter`() {
            val s = Scenario()
            listOf(
                ApiPaths.PLATFORM_AUDIT_EVENTS,
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantA}/audit-events",
            ).forEach { path ->
                assertBadRequest(
                    Route(path, token(s.platformAdmin, PlatformOrganisation.ID)),
                    mapOf("actor_subject" to "kc-${s.marker}"),
                    "actor_subject",
                )
            }
        }

        @Test
        fun `the OpenAPI document publishes the closed sets of every audit search`() {
            val document =
                apiJsonCodec.mapper.readTree(
                    mockMvc
                        .get("/v3/api-docs")
                        .andReturn()
                        .response.contentAsString,
                )
            val expected =
                mapOf(
                    "outcome" to AuditOutcome.entries.map { it.name },
                    "severity" to AuditSeverity.entries.map { it.name },
                    "min_severity" to AuditSeverity.entries.map { it.name },
                    "actor_type" to AuditActorType.entries.map { it.name },
                    "sort_dir" to listOf("ASC", "DESC"),
                )
            listOf(
                ApiPaths.AUDIT_EVENTS,
                ApiPaths.PLATFORM_AUDIT_EVENTS,
                "${ApiPaths.PLATFORM_TENANTS}/{tenant_id}/audit-events",
            ).forEach { path ->
                val parameters =
                    document
                        .path("paths")
                        .path(path)
                        .path("get")
                        .path("parameters")
                expected.forEach { (name, values) ->
                    val parameter =
                        parameters.toList().single { it.path("name").asString() == name }
                    assertEquals(
                        values,
                        parameter
                            .path("schema")
                            .path("enum")
                            .toList()
                            .map { it.asString() },
                        "$path $name",
                    )
                    assertTrue(parameter.path("description").asString().isNotBlank())
                }
                // The declared description survives the global sort_dir customizer.
                assertEquals(
                    AUDIT_SORT_DIR_DOC,
                    parameters
                        .toList()
                        .single { it.path("name").asString() == "sort_dir" }
                        .path("description")
                        .asString(),
                    "$path sort_dir",
                )
            }
        }

        private data class Route(
            val path: String,
            val token: AppPrincipalAuthenticationToken,
        )

        /**
         * [expected] are on the page, every other seeded row of [rows] that fails [predicate] is
         * not, every item satisfies [predicate] and belongs to the route's organisation, and no
         * row of tenant B ever appears.
         */
        private fun assertFilter(
            s: Scenario,
            route: Route,
            rows: Rows,
            parameters: Map<String, String>,
            vararg expected: UUID,
            predicate: (JsonNode) -> Boolean,
        ) {
            val items = items(route, parameters)
            val returned = items.map { UUID.fromString(it.text("id")) }
            expected.forEach { assertTrue(it in returned, "$parameters must return $it") }
            items.forEach { assertTrue(predicate(it), "$parameters returned ${it.text("id")}") }
            val organisations = items.map { it.text("organisation_id") }.toSet()
            assertEquals(setOf(rows.organisation(s)), organisations, "$parameters organisations")
            (s.rowsB.all + s.rowsB.oldSystem).forEach {
                assertTrue(it !in returned, "$parameters leaked tenant B row $it")
            }
        }

        private fun Rows.organisation(s: Scenario): String =
            if (this == s.rowsA) s.tenantA.toString() else PlatformOrganisation.ID.toString()

        private fun assertExactly(
            route: Route,
            parameters: Map<String, String>,
            vararg expected: UUID,
        ) {
            assertEquals(expected.toSet(), ids(route, parameters).toSet(), "$parameters")
        }

        private fun assertBadRequest(
            route: Route,
            parameters: Map<String, String>,
            field: String,
        ) {
            mockMvc
                .get(route.path) {
                    parameters.forEach { (name, value) -> param(name, value) }
                    with(authentication(route.token))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("invalid_parameter") }
                    jsonPath("$.violations[0].field") { value(field) }
                }
        }

        private fun ids(
            route: Route,
            parameters: Map<String, String>,
        ): List<UUID> = items(route, parameters).map { UUID.fromString(it.text("id")) }

        private fun items(
            route: Route,
            parameters: Map<String, String>,
        ): List<JsonNode> {
            val body =
                mockMvc
                    .get(route.path) {
                        param("size", "100")
                        parameters.forEach { (name, value) -> param(name, value) }
                        with(authentication(route.token))
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
            return apiJsonCodec.mapper
                .readTree(body)
                .path("items")
                .toList()
        }

        private fun JsonNode.text(field: String): String? =
            path(field).takeUnless { it.isNull || it.isMissingNode }?.asString()

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "filter-$id")
                .set(USER_ACCOUNT.EMAIL, "filter-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Filter User")
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
                .set(BRANCH.BRANCH_CODE, "F183-$id")
                .set(BRANCH.BRANCH_NAME, "Filter Branch")
                .set(BRANCH.BRANCH_TYPE, "MAIN")
                .set(BRANCH.STATUS, "ACTIVE")
                .set(BRANCH.TIMEZONE, "Africa/Nairobi")
                .set(BRANCH.CREATED_AT, now)
                .set(BRANCH.UPDATED_AT, now)
                .execute()
            return id
        }

        private fun seedEvent(
            organisationId: UUID,
            action: String,
            outcome: String = "SUCCESS",
            severity: String = "INFO",
            branchId: UUID? = null,
            actorUserId: UUID? = null,
            actorSubject: String? = null,
            entityType: String = "ORGANISATION",
            reason: String? = null,
            minutesAgo: Long,
        ): UUID {
            val id = uuidV7()
            dsl
                .insertInto(AUDIT_EVENT)
                .set(AUDIT_EVENT.ID, id)
                .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
                .set(AUDIT_EVENT.EVENT_TIME, OffsetDateTime.now().minusMinutes(minutesAgo))
                .set(AUDIT_EVENT.ACTOR_TYPE, if (actorUserId == null) "SYSTEM" else "USER")
                .set(AUDIT_EVENT.ACTOR_USER_ID, actorUserId)
                .set(AUDIT_EVENT.ACTOR_EXTERNAL_SUBJECT, actorSubject)
                .set(AUDIT_EVENT.BRANCH_ID, branchId)
                .set(AUDIT_EVENT.EVENT_TYPE, action)
                .set(AUDIT_EVENT.ENTITY_TYPE, entityType)
                .set(AUDIT_EVENT.ACTION, action)
                .set(AUDIT_EVENT.OUTCOME, outcome)
                .set(AUDIT_EVENT.SEVERITY, severity)
                .set(AUDIT_EVENT.REASON, reason)
                .execute()
            return id
        }

        private fun token(
            userId: UUID,
            organisationId: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "filter-user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "filter@example.test",
                fullName = "Filter User",
                permissions = setOf("audit.view"),
            ),
        )
    }
