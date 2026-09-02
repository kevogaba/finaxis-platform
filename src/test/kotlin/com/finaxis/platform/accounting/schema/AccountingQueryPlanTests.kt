package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Index choices are asserted by a test, not by a paragraph.
 *
 * `docs/architecture/accounting-foundation.md` fixes the fixture — a couple of hundred thousand
 * lines spread across two years, forty branches and five hundred accounts — and a shared-block
 * budget per query pattern. This class seeds that fixture once, runs
 * `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for the patterns whose indexes issue #40 creates, and
 * asserts what the foundation says to assert: no sequential scan on the ledger tables, the expected
 * index in the plan, no sort over a bitmap heap scan, and the block budget honoured.
 *
 * It deliberately does **not** assert `Heap Fetches = 0`. That depends on the visibility map,
 * which depends on when autovacuum last ran, and a flaky assertion about vacuum timing tells a
 * reviewer nothing about the change under review. Index-only-ness is bounded by the block budget.
 *
 * Budgets live in the foundation document as well as here, and change in the same commit.
 *
 * `EXPLAIN` and `VACUUM` are PostgreSQL statements jOOQ has no typed form for, so those two - and
 * the queries under test, which are quoted so the plan is of the SQL a reader sees - run as plain
 * SQL through the jOOQ context.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountingQueryPlanTests(
    private val dsl: DSLContext,
) {
    private val mapper = ObjectMapper()
    private lateinit var organisationId: UUID
    private lateinit var accountId: UUID

    @BeforeAll
    fun seed() {
        organisationId = LedgerVolumeFixture(dsl).seed()
        accountId =
            dsl
                .select(GL_ACCOUNT.ID)
                .from(GL_ACCOUNT)
                .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
                .and(GL_ACCOUNT.ACCOUNT_CODE.eq("0008"))
                .fetchOne(GL_ACCOUNT.ID)!!
    }

    @Test
    fun `Q1 account ledger between two dates is one backward index range scan`() {
        val plan =
            explain(
                """
                SELECT id, posting_date, direction, functional_amount, branch_id, journal_entry_id
                FROM journal_line
                WHERE organisation_id = ? AND gl_account_id = ?
                  AND posting_date BETWEEN ? AND ?
                ORDER BY posting_date DESC, id DESC
                LIMIT 100
                """.trimIndent(),
                organisationId,
                accountId,
                LedgerVolumeFixture.START.plusMonths(6),
                LedgerVolumeFixture.START.plusMonths(7).minusDays(1),
            )

        assertNoSeqScan(plan)
        assertUsesIndex(plan, "idx_journal_line_account_date")
        assertNoNode(plan, "Sort")
        assertNoNode(plan, "Bitmap Heap Scan")
        assertBlocksUnder(plan, Q1_BUDGET)
    }

    @Test
    fun `Q7 a keyset page deep in history costs the same as the first page`() {
        // One page deep, by the row comparison the foundation prescribes - never OFFSET in
        // production. OFFSET is used once here, to *find* a cursor to test with. The fixture
        // spreads 200,000 lines over 500 accounts, so one account holds a few hundred lines; the
        // foundation's "fifty pages deep" shape is the same index walk at a depth this fixture
        // cannot reach, and keyset cost is independent of depth by construction.
        val cursor =
            dsl
                .select(JOURNAL_LINE.POSTING_DATE, JOURNAL_LINE.ID)
                .from(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
                .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(accountId))
                .orderBy(JOURNAL_LINE.POSTING_DATE.desc(), JOURNAL_LINE.ID.desc())
                .offset(DEEP_PAGE_OFFSET)
                .limit(1)
                .fetchOne()!!
        val plan =
            explain(
                """
                SELECT id, posting_date, direction, functional_amount, branch_id, journal_entry_id
                FROM journal_line
                WHERE organisation_id = ? AND gl_account_id = ?
                  AND posting_date BETWEEN ? AND ?
                  AND (posting_date, id) < (?, ?)
                ORDER BY posting_date DESC, id DESC
                LIMIT 100
                """.trimIndent(),
                organisationId,
                accountId,
                LedgerVolumeFixture.START,
                LedgerVolumeFixture.END,
                cursor.value1(),
                cursor.value2(),
            )

        assertNoSeqScan(plan)
        assertUsesIndex(plan, "idx_journal_line_account_date")
        assertNoNode(plan, "Sort")
        assertBlocksUnder(plan, Q7_BUDGET)
    }

    @Test
    fun `Q5 drill-down by entry number reads the header and its lines through their keys`() {
        val plan =
            explain(
                """
                SELECT je.id, je.entry_number, jl.line_number, jl.gl_account_id, jl.direction,
                       jl.functional_amount
                FROM journal_entry je
                JOIN journal_line jl
                  ON jl.organisation_id = je.organisation_id AND jl.journal_entry_id = je.id
                WHERE je.organisation_id = ? AND je.entry_number = ?
                ORDER BY jl.line_number
                """.trimIndent(),
                organisationId,
                LedgerVolumeFixture.JOURNALS / 2L,
            )

        assertNoSeqScan(plan)
        assertUsesIndex(plan, "uq_journal_entry_number")
        assertUsesIndex(plan, "uq_journal_line_entry_number")
        assertBlocksUnder(plan, Q5_BUDGET)
    }

    @Test
    fun `the header-versus-lines proof stays inside its period bound`() {
        // Not a foundation budget line of its own - it is the operational check, bounded by
        // tenant and date so it can run per period on a very large table - but it must not
        // degrade into a sequential scan of journal_line, or it stops being runnable at all.
        val plan =
            explain(
                """
                SELECT je.id
                FROM journal_entry je
                JOIN LATERAL (
                    SELECT COALESCE(SUM(jl.functional_amount)
                                    FILTER (WHERE jl.direction = 'DEBIT'), 0)  AS debit_total,
                           COALESCE(SUM(jl.functional_amount)
                                    FILTER (WHERE jl.direction = 'CREDIT'), 0) AS credit_total,
                           COUNT(*) AS lines
                    FROM journal_line jl
                    WHERE jl.organisation_id = je.organisation_id AND jl.journal_entry_id = je.id
                ) agg ON TRUE
                WHERE je.organisation_id = ? AND je.posting_date BETWEEN ? AND ?
                  AND (je.total_debit_functional <> agg.debit_total
                    OR je.total_credit_functional <> agg.credit_total
                    OR je.line_count <> agg.lines
                    OR agg.debit_total <> agg.credit_total)
                """.trimIndent(),
                organisationId,
                LedgerVolumeFixture.START,
                LedgerVolumeFixture.START.plusMonths(1).minusDays(1),
            )

        assertNoSeqScan(plan)
        assertUsesIndex(plan, "idx_journal_entry_posting_date")
        assertUsesIndex(plan, "uq_journal_line_entry_number")
        assertEquals(0, plan["Actual Rows"].asInt(), "the seeded ledger is balanced")
    }

    private fun explain(
        sql: String,
        vararg args: Any?,
    ): JsonNode {
        val json =
            dsl
                .fetchValue("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql", *args)
                .toString()
        return mapper.readTree(json)[0]["Plan"]
    }

    private fun nodes(plan: JsonNode): Sequence<JsonNode> =
        sequenceOf(plan) + (plan["Plans"]?.asSequence() ?: emptySequence()).flatMap(::nodes)

    private fun assertNoSeqScan(plan: JsonNode) {
        val seqScans =
            nodes(plan)
                .filter { it["Node Type"].asString() == "Seq Scan" }
                .map { it["Relation Name"].asString() }
                .filter { it == "journal_line" || it == "journal_entry" }
                .toList()
        assertTrue(seqScans.isEmpty(), "sequential scan on $seqScans in\n${plan.toPrettyString()}")
    }

    private fun assertUsesIndex(
        plan: JsonNode,
        indexName: String,
    ) {
        val used = nodes(plan).mapNotNull { it["Index Name"]?.asString() }.toSet()
        assertTrue(
            indexName in used,
            "expected $indexName, plan used $used:\n${plan.toPrettyString()}",
        )
    }

    private fun assertNoNode(
        plan: JsonNode,
        nodeType: String,
    ) {
        assertTrue(
            nodes(plan).none { it["Node Type"].asString() == nodeType },
            "a $nodeType node defeats the index:\n${plan.toPrettyString()}",
        )
    }

    private fun assertBlocksUnder(
        plan: JsonNode,
        budget: Int,
    ) {
        val blocks = plan["Shared Hit Blocks"].asInt() + plan["Shared Read Blocks"].asInt()
        assertTrue(
            blocks <= budget,
            "shared blocks $blocks exceed the documented budget $budget:\n${plan.toPrettyString()}",
        )
    }

    private companion object {
        /** Budgets, as `docs/architecture/accounting-foundation.md` states them. */
        const val Q1_BUDGET = 200
        const val Q5_BUDGET = 50
        const val Q7_BUDGET = 200

        /** One page of a hundred rows into an account that holds a few hundred lines. */
        const val DEEP_PAGE_OFFSET = 100
    }
}

/**
 * Seeds the ledger volume the foundation's EXPLAIN validation plan calls for.
 *
 * One tenant, forty branches, five hundred postable accounts, twenty-four monthly periods, and
 * [JOURNALS] balanced four-line journals spread over the two years - 200,000 lines, which is small
 * enough for CI and large enough that the planner prefers an index over a sequential scan when the
 * index is right. Followed by `VACUUM ANALYZE`, because a planner without statistics would be
 * guessing and the assertions would be about the guess.
 *
 * The reference rows go through the generated tables. The journals are one set-based statement
 * over `generate_series`, which jOOQ cannot express typed, so it runs as plain SQL - inserting
 * 200,000 rows one statement at a time would take longer than the rest of the suite.
 *
 * Seeded once, keyed on a fixed tenant code, so a second test class in the same context does not
 * pay for it again.
 */
class LedgerVolumeFixture(
    private val dsl: DSLContext,
) {
    /** Seeds if absent and returns the fixture tenant's organisation id. */
    fun seed(): UUID {
        dsl
            .select(ORGANISATION.ID)
            .from(ORGANISATION)
            .where(ORGANISATION.TENANT_CODE.eq(TENANT_CODE))
            .fetchOne(ORGANISATION.ID)
            ?.let { return it }

        val organisationId = createOrganisation()
        seedCalendar(organisationId)
        seedBranches(organisationId)
        seedAccounts(organisationId)
        seedJournals(organisationId)
        dsl.execute("VACUUM ANALYZE posting_request, journal_entry, journal_line")
        return organisationId
    }

    private fun createOrganisation(): UUID =
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.TENANT_CODE, TENANT_CODE)
            .set(ORGANISATION.DISPLAY_NAME, "Ledger volume fixture")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now())
            .set(ORGANISATION.UPDATED_AT, now())
            .returning(ORGANISATION.ID)
            .fetchOne()!!
            .id!!

    private fun seedCalendar(organisationId: UUID) {
        listOf(START.year, START.year + 1).forEach { year ->
            val yearId =
                dsl
                    .insertInto(ACCOUNTING_FISCAL_YEAR)
                    .set(ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID, organisationId)
                    .set(ACCOUNTING_FISCAL_YEAR.YEAR_CODE, "FY$year")
                    .set(ACCOUNTING_FISCAL_YEAR.YEAR_NAME, "Financial year $year")
                    .set(ACCOUNTING_FISCAL_YEAR.START_DATE, LocalDate.of(year, 1, 1))
                    .set(ACCOUNTING_FISCAL_YEAR.END_DATE, LocalDate.of(year, 12, 31))
                    .set(ACCOUNTING_FISCAL_YEAR.CREATED_AT, now())
                    .set(ACCOUNTING_FISCAL_YEAR.UPDATED_AT, now())
                    .returning(ACCOUNTING_FISCAL_YEAR.ID)
                    .fetchOne()!!
                    .id!!
            (1..MONTHS_PER_YEAR).forEach { month ->
                val first = LocalDate.of(year, month, 1)
                dsl
                    .insertInto(ACCOUNTING_FISCAL_PERIOD)
                    .set(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID, organisationId)
                    .set(ACCOUNTING_FISCAL_PERIOD.FISCAL_YEAR_ID, yearId)
                    .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NUMBER, month)
                    .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NAME, "Period $month")
                    .set(ACCOUNTING_FISCAL_PERIOD.START_DATE, first)
                    .set(
                        ACCOUNTING_FISCAL_PERIOD.END_DATE,
                        first.withDayOfMonth(first.lengthOfMonth()),
                    ).set(ACCOUNTING_FISCAL_PERIOD.STATUS, "OPEN")
                    .set(ACCOUNTING_FISCAL_PERIOD.CREATED_AT, now())
                    .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_AT, now())
                    .execute()
            }
        }
    }

    private fun seedBranches(organisationId: UUID) {
        val insert =
            dsl.insertInto(
                BRANCH,
                BRANCH.ORGANISATION_ID,
                BRANCH.BRANCH_CODE,
                BRANCH.BRANCH_NAME,
                BRANCH.BRANCH_TYPE,
                BRANCH.STATUS,
                BRANCH.TIMEZONE,
                BRANCH.CREATED_AT,
                BRANCH.UPDATED_AT,
            )
        (1..BRANCHES).forEach { b ->
            insert.values(
                organisationId,
                branchCode(b),
                "Branch $b",
                "BRANCH",
                "ACTIVE",
                "Africa/Nairobi",
                now(),
                now(),
            )
        }
        insert.execute()
    }

    private fun seedAccounts(organisationId: UUID) {
        val insert =
            dsl.insertInto(
                GL_ACCOUNT,
                GL_ACCOUNT.ORGANISATION_ID,
                GL_ACCOUNT.ACCOUNT_CODE,
                GL_ACCOUNT.ACCOUNT_NAME,
                GL_ACCOUNT.ACCOUNT_CLASS,
                GL_ACCOUNT.ACCOUNT_USAGE,
                GL_ACCOUNT.STATUS,
                GL_ACCOUNT.CREATED_AT,
                GL_ACCOUNT.UPDATED_AT,
            )
        (1..ACCOUNTS).forEach { a ->
            insert.values(
                organisationId,
                accountCode(a),
                "Account $a",
                if (a % 2 == 0) "ASSET" else "LIABILITY",
                "POSTABLE",
                "ACTIVE",
                now(),
                now(),
            )
        }
        insert.execute()
    }

    /**
     * Journal n posts on day `n mod 730` of the two years, at branch `n mod 40`, with two debit
     * lines and two credit lines of 50.00 each against four accounts chosen by `n`, so every
     * journal balances at 100.00 and every account receives a spread of lines.
     */
    private fun seedJournals(organisationId: UUID) {
        dsl.execute(
            SEED_JOURNALS_SQL,
            START,
            DAYS,
            JOURNALS,
            organisationId,
            organisationId,
            BRANCHES,
            organisationId,
            organisationId,
            organisationId,
            organisationId,
            ACCOUNTS,
        )
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now()

    private fun branchCode(b: Int) = "B" + b.toString().padStart(BRANCH_CODE_WIDTH, '0')

    private fun accountCode(a: Int) = a.toString().padStart(ACCOUNT_CODE_WIDTH, '0')

    companion object {
        const val TENANT_CODE = "ledger-volume-fixture"
        const val JOURNALS = 50_000
        const val BRANCHES = 40
        const val ACCOUNTS = 500
        const val DAYS = 730
        const val MONTHS_PER_YEAR = 12
        const val BRANCH_CODE_WIDTH = 3
        const val ACCOUNT_CODE_WIDTH = 4
        val START: LocalDate = LocalDate.of(2025, 1, 1)
        val END: LocalDate = LocalDate.of(2026, 12, 31)

        /** The set-based seed; see [seedJournals] for the shape it produces. */
        val SEED_JOURNALS_SQL =
            """
            WITH seq AS (
                SELECT n,
                       (?::date + (n % ?))::date AS posting_date,
                       uuidv7() AS request_id,
                       uuidv7() AS journal_id
                FROM generate_series(1, ?) AS n
            ),
            resolved AS (
                SELECT s.*, p.id AS period_id, b.id AS branch_id
                FROM seq s
                JOIN accounting_fiscal_period p
                  ON p.organisation_id = ?
                 AND s.posting_date BETWEEN p.start_date AND p.end_date
                JOIN branch b
                  ON b.organisation_id = ?
                 AND b.branch_code = 'B' || lpad(((s.n % ?) + 1)::text, 3, '0')
            ),
            requests AS (
                INSERT INTO posting_request (
                    id, organisation_id, branch_id, source_module, source_entity_type,
                    source_entity_id, source_reference, event_code, request_fingerprint,
                    business_date, transaction_date, value_date, posting_date, currency_code,
                    status, posted_at, created_at, updated_at
                )
                SELECT request_id, ?, branch_id, 'savings', 'SAVINGS_DEPOSIT', uuidv7(),
                       'seed-' || n, 'SAVINGS_DEPOSIT', repeat('0', 64),
                       posting_date, posting_date, posting_date, posting_date, 'KES',
                       'POSTED', NOW(), NOW(), NOW()
                FROM resolved
                RETURNING id
            ),
            entries AS (
                INSERT INTO journal_entry (
                    id, organisation_id, branch_id, posting_request_id, fiscal_period_id,
                    entry_number, entry_type, business_date, transaction_date, value_date,
                    posting_date, currency_code, functional_currency_code,
                    total_debit_functional, total_credit_functional, line_count, posted_at,
                    created_at
                )
                SELECT journal_id, ?, branch_id, request_id, period_id, n, 'STANDARD',
                       posting_date, posting_date, posting_date, posting_date, 'KES', 'KES',
                       100, 100, 4, NOW(), NOW()
                FROM resolved
                RETURNING id
            )
            INSERT INTO journal_line (
                organisation_id, journal_entry_id, line_number, gl_account_id, branch_id,
                fiscal_period_id, posting_date, direction, currency_code, amount,
                functional_currency_code, functional_amount, exchange_rate, source_module,
                subledger_reference, created_at
            )
            SELECT ?, r.journal_id, leg, a.id, r.branch_id, r.period_id, r.posting_date,
                   CASE WHEN leg <= 2 THEN 'DEBIT' ELSE 'CREDIT' END,
                   'KES', 50, 'KES', 50, 1, 'savings', 'SAV-' || (r.n % 25000), NOW()
            FROM resolved r
            CROSS JOIN generate_series(1, 4) AS leg
            JOIN gl_account a
              ON a.organisation_id = ?
             AND a.account_code = lpad((((r.n * leg) % ?) + 1)::text, 4, '0')
            WHERE EXISTS (SELECT 1 FROM requests) AND EXISTS (SELECT 1 FROM entries)
            """.trimIndent()
    }
}
