package com.finaxis.platform.common.audit.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.audit.AuditExportCursor
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import org.jooq.DSLContext
import org.jooq.Select
import org.jooq.impl.DSL
import org.jooq.impl.DSL.inline
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract V25-V28, V30 and [JooqAuditEventQueries] make (#183, #186), asserted by a plan
 * rather than a paragraph: the count behind a selective audit filter uses that filter's partial
 * index even in a **generic** plan, the plan a cached prepared statement falls back to.
 *
 * The SQL planned is the adapter's own (`searchCondition`), rendered by jOOQ, so replacing the
 * inlined outcome or severity literal with a bind value fails here: a partial index's
 * predicate (`outcome <> 'SUCCESS'`) cannot be proved from `outcome = $2`.
 * The statement is `PREPARE`d under `plan_cache_mode = force_generic_plan`, so `EXECUTE` is
 * planned with the parameters unknown, which is exactly that fallback. Only the index name is
 * asserted, not index-only-ness or a block budget, which depend on vacuum timing.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditQueryPlanTests(
    private val dsl: DSLContext,
    private val queries: JooqAuditEventQueries,
) {
    private val mapper = ObjectMapper()
    private val organisationId = uuidV7()
    private val branchId = uuidV7()

    @BeforeAll
    fun seed() {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, organisationId)
            .set(ORGANISATION.TENANT_CODE, "plan-$organisationId")
            .set(ORGANISATION.DISPLAY_NAME, "Audit Plan Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, branchId)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "PLAN-$branchId")
            .set(BRANCH.BRANCH_NAME, "Plan Branch")
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        // Mostly SUCCESS / INFO / no branch, as a real log is: the filtered values are rare.
        dsl.execute(
            """
            INSERT INTO audit_event (organisation_id, event_time, actor_external_subject,
              actor_type, branch_id, subject_user_id, event_type, entity_type, action, outcome,
              severity)
            SELECT ?, now() - g * interval '1 minute',
              CASE WHEN g % 5 = 0 THEN 'kc-plan-' || (g % 400) END,
              'USER',
              CASE WHEN g % 50 = 0 THEN ?::uuid END,
              CASE WHEN g % 200 = 0 THEN
                ('00000000-0000-7000-8000-' || lpad(to_hex((g / 200) % 20), 12, '0'))::uuid
              END,
              'plan.update', 'PLAN', 'plan.update',
              CASE WHEN g % 100 = 0 THEN 'DENIED' WHEN g % 100 = 1 THEN 'FAILURE'
                   ELSE 'SUCCESS' END,
              CASE WHEN g % 100 = 2 THEN 'HIGH' WHEN g % 100 = 3 THEN 'CRITICAL'
                   ELSE 'INFO' END
            FROM generate_series(1, $SEEDED_ROWS) g
            """.trimIndent(),
            organisationId,
            branchId,
        )
        dsl.execute("VACUUM ANALYZE audit_event")
    }

    @Test
    fun `an outcome filter uses the outcome partial index in a generic plan`() {
        assertGenericPlanUses(
            AuditEventFilter(organisationId, outcome = AuditOutcome.DENIED),
            "idx_audit_event_organisation_outcome_time",
        )
    }

    @Test
    fun `exact and minimum severity filters use the severity partial index in a generic plan`() {
        assertGenericPlanUses(
            AuditEventFilter(organisationId, severity = AuditSeverity.CRITICAL),
            "idx_audit_event_organisation_severity_time",
        )
        assertGenericPlanUses(
            AuditEventFilter(organisationId, minSeverity = AuditSeverity.HIGH),
            "idx_audit_event_organisation_severity_time",
        )
    }

    @Test
    fun `a subject user filter uses the subject user partial index in a generic plan`() {
        assertGenericPlanUses(
            AuditEventFilter(organisationId, subjectUserId = SEEDED_SUBJECT_USER),
            "idx_audit_event_organisation_subject_user_time",
        )
    }

    @Test
    fun `branch and actor subject filters use their partial indexes in a generic plan`() {
        assertGenericPlanUses(
            AuditEventFilter(organisationId, branchId = branchId),
            "idx_audit_event_organisation_branch_time",
        )
        assertGenericPlanUses(
            AuditEventFilter(organisationId, actorSubject = "kc-plan-7"),
            "idx_audit_event_organisation_subject_time",
        )
    }

    @Test
    fun `an export batch is one bounded index range read in either direction`() {
        val window =
            AuditEventFilter(
                organisationId,
                occurredFrom = Instant.now().minus(Duration.ofDays(30)),
                occurredTo = Instant.now(),
            )
        val cursor = AuditExportCursor(Instant.now().minus(Duration.ofHours(2)), uuidV7())
        listOf(window, window.copy(ascending = true)).forEach { filter ->
            listOf<AuditExportCursor?>(null, cursor).forEach { after ->
                val query = queries.exportQuery(filter, after, EXPORT_BATCH)
                val plan = genericPlan(query)
                val label = "ascending=${filter.ascending} after=${after != null}"

                val used = nodes(plan).mapNotNull { it["Index Name"]?.asString() }.toSet()
                assertTrue("idx_audit_event_organisation_time" in used, "$label used $used")
                val types = nodes(plan).map { it["Node Type"].asString() }
                assertTrue("Limit" in types, "$label has a Limit node: $types")
                assertTrue("Seq Scan" !in types, "$label scans the table: $types")
                assertTrue(
                    "Sort" !in types,
                    "$label sorts the whole range instead of reading in index order: $types",
                )
            }
        }
    }

    @Test
    fun `the export count stops at its limit`() {
        val filter =
            AuditEventFilter(
                organisationId,
                occurredFrom = Instant.now().minus(Duration.ofDays(30)),
                occurredTo = Instant.now(),
            )

        val plan = genericPlan(queries.countQuery(filter, EXPORT_COUNT_LIMIT))
        val types = nodes(plan).map { it["Node Type"].asString() }

        assertTrue("Limit" in types, "the count is bounded by a Limit: $types")
        assertTrue("Seq Scan" !in types, "$types")
        val everything = filter.copy(occurredFrom = null, occurredTo = null)
        assertEquals(
            EXPORT_COUNT_LIMIT,
            queries.countBounded(everything, EXPORT_COUNT_LIMIT),
            "a log of $SEEDED_ROWS rows is counted only up to the limit",
        )
        assertEquals(7, queries.countBounded(everything, 7))
    }

    private fun assertGenericPlanUses(
        filter: AuditEventFilter,
        indexName: String,
    ) {
        val query = dsl.selectCount().from(AUDIT_EVENT).where(queries.searchCondition(filter))
        val plan = genericPlan(query)
        val used = nodes(plan).mapNotNull { it["Index Name"]?.asString() }.toSet()
        assertTrue(indexName in used, "expected $indexName, used $used for\n$plan")
    }

    /** The root plan node of [query] prepared and planned with its parameters unknown. */
    private fun genericPlan(query: Select<*>): JsonNode {
        val sql = dsl.render(query)
        val arguments = query.bindValues.joinToString(", ") { dsl.renderInlined(inline(it)) }
        // One connection: the prepared statement and the plan-cache mode are session state.
        val json =
            dsl.connectionResult { connection ->
                val session = DSL.using(connection, dsl.dialect())
                session.execute("SET plan_cache_mode = force_generic_plan")
                try {
                    session.execute("PREPARE audit_plan AS ${numbered(sql)}")
                    session
                        .fetchValue("EXPLAIN (FORMAT JSON) EXECUTE audit_plan($arguments)")
                        .toString()
                } finally {
                    session.execute("DEALLOCATE ALL")
                    session.execute("RESET plan_cache_mode")
                }
            }
        return mapper.readTree(json)[0]["Plan"]
    }

    /** Turns jOOQ's `?` placeholders into `$1..$n`, leaving quoted literals alone. */
    private fun numbered(sql: String): String {
        val out = StringBuilder()
        var inLiteral = false
        var next = 1
        sql.forEach { char ->
            when {
                char == '\'' -> {
                    inLiteral = !inLiteral
                    out.append(char)
                }

                char == '?' && !inLiteral -> {
                    out.append('$').append(next++)
                }

                else -> {
                    out.append(char)
                }
            }
        }
        return out.toString()
    }

    private fun nodes(plan: JsonNode): Sequence<JsonNode> =
        sequenceOf(plan) + (plan["Plans"]?.asSequence() ?: emptySequence()).flatMap(::nodes)

    private companion object {
        const val SEEDED_ROWS = 20_000
        const val EXPORT_BATCH = 1_000
        const val EXPORT_COUNT_LIMIT = 10_001

        /** One of the 20 subjects the seed spreads over about 100 rows (`g / 200 mod 20 = 7`). */
        val SEEDED_SUBJECT_USER: UUID = UUID.fromString("00000000-0000-7000-8000-000000000007")
    }
}
