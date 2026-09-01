package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.schema.AccountingSchemaFixture.Companion.assertViolates
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What PostgreSQL enforces about the fiscal calendar, proved against PostgreSQL.
 *
 * The overlap rule is the one issue #36 asks for explicitly — *"invalid fiscal-period
 * ranges/overlap are rejected by PostgreSQL"* — and it is the reason this schema installs
 * `btree_gist`. The placement of that extension is asserted here too, because it is what keeps
 * jOOQ code generation from being gated by an extension's ~160 functions.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FiscalCalendarSchemaIntegrationTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val fixture = AccountingSchemaFixture(jdbcTemplate)

    @Test
    fun `a year with two adjacent periods is accepted`() {
        // Positive control for a class whose other assertions are all rejections.
        val organisationId = fixture.createOrganisation("cal-positive")
        val year = fixture.insertYear(organisationId)
        fixture.insertPeriod(organisationId, year, 1, JAN_START, JAN_END)
        fixture.insertPeriod(organisationId, year, 2, FEB_START, FEB_END, status = "FUTURE")

        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounting_fiscal_period WHERE organisation_id = ?",
                Int::class.java,
                organisationId,
            ),
        )
    }

    @Test
    fun `overlapping periods are rejected within a tenant and accepted across tenants`() {
        val first = fixture.createOrganisation("cal-overlap-a")
        val second = fixture.createOrganisation("cal-overlap-b")
        val firstYear = fixture.insertYear(first)
        val secondYear = fixture.insertYear(second)
        fixture.insertPeriod(first, firstYear, 1, JAN_START, JAN_END)

        assertViolates("ex_accounting_fiscal_period_no_overlap") {
            fixture.insertPeriod(first, firstYear, 2, LocalDate.of(2026, 1, 15), FEB_END)
        }
        fixture.insertPeriod(second, secondYear, 1, JAN_START, JAN_END)
    }

    @Test
    fun `periods that share a boundary day overlap`() {
        // The range is built with inclusive bounds on both ends, so 31 January belongs to January.
        // A calendar generator that ends one period and starts the next on the same day is a
        // classic off-by-one, and this is where it is caught.
        val organisationId = fixture.createOrganisation("cal-boundary")
        val year = fixture.insertYear(organisationId)
        fixture.insertPeriod(organisationId, year, 1, JAN_START, JAN_END)

        assertViolates("ex_accounting_fiscal_period_no_overlap") {
            fixture.insertPeriod(organisationId, year, 2, JAN_END, FEB_END)
        }
    }

    @Test
    fun `two fiscal years of one tenant cannot cover the same date`() {
        val organisationId = fixture.createOrganisation("cal-year-overlap")
        fixture.insertYear(organisationId)

        assertViolates("ex_accounting_fiscal_year_no_overlap") {
            fixture.insertYear(
                organisationId,
                code = "FY2026H2",
                start = LocalDate.of(2026, 7, 1),
                end = LocalDate.of(2027, 6, 30),
            )
        }
    }

    @Test
    fun `a period cannot belong to another tenants fiscal year`() {
        val owner = fixture.createOrganisation("cal-tenant-a")
        val other = fixture.createOrganisation("cal-tenant-b")
        val year = fixture.insertYear(owner)

        assertViolates("fk_accounting_fiscal_period_year") {
            fixture.insertPeriod(other, year, 1, JAN_START, JAN_END)
        }
    }

    @Test
    fun `a period cannot end before it starts and its number must be positive`() {
        val organisationId = fixture.createOrganisation("cal-bounds")
        val year = fixture.insertYear(organisationId)

        assertViolates("chk_accounting_fiscal_period_dates") {
            fixture.insertPeriod(organisationId, year, 1, JAN_END, JAN_START)
        }
        assertViolates("chk_accounting_fiscal_period_number") {
            fixture.insertPeriod(organisationId, year, 0, JAN_START, JAN_END)
        }
    }

    @Test
    fun `period numbers do not repeat within a year`() {
        val organisationId = fixture.createOrganisation("cal-numbers")
        val year = fixture.insertYear(organisationId)
        fixture.insertPeriod(organisationId, year, 1, JAN_START, JAN_END)

        assertViolates("uq_accounting_fiscal_period_year_number") {
            fixture.insertPeriod(organisationId, year, 1, FEB_START, FEB_END)
        }
    }

    @Test
    fun `the four adopted period statuses are the only ones accepted`() {
        val organisationId = fixture.createOrganisation("cal-status")
        val year = fixture.insertYear(organisationId)

        listOf("FUTURE", "OPEN", "CLOSED", "LOCKED").forEachIndexed { index, status ->
            fixture.insertPeriod(
                organisationId,
                year,
                index + 1,
                JAN_START.plusMonths(index.toLong()),
                JAN_START.plusMonths(index.toLong()).plusDays(20),
                status = status,
            )
        }
        assertViolates("chk_accounting_fiscal_period_status") {
            fixture.insertPeriod(
                organisationId,
                year,
                9,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30),
                status = "SOFT_CLOSED",
            )
        }
    }

    @Test
    fun `a posting date resolves to exactly one period`() {
        // The predicate the whole locking protocol rests on. It is asserted against real rows
        // because the Phase A stand-in ignored its posting date entirely and returned hardcoded
        // bounds, so this predicate had never executed against a database.
        val organisationId = fixture.createOrganisation("cal-covering")
        val year = fixture.insertYear(organisationId)
        fixture.insertPeriod(organisationId, year, 1, JAN_START, JAN_END)
        val february = fixture.insertPeriod(organisationId, year, 2, FEB_START, FEB_END)

        val covering =
            jdbcTemplate.queryForList(
                """
                SELECT id FROM accounting_fiscal_period
                WHERE organisation_id = ? AND ? BETWEEN start_date AND end_date
                """.trimIndent(),
                java.util.UUID::class.java,
                organisationId,
                LocalDate.of(2026, 2, 10),
            )

        assertEquals(listOf(february), covering)
        assertTrue(
            jdbcTemplate
                .queryForList(
                    """
                    SELECT id FROM accounting_fiscal_period
                    WHERE organisation_id = ? AND ? BETWEEN start_date AND end_date
                    """.trimIndent(),
                    java.util.UUID::class.java,
                    organisationId,
                    LocalDate.of(2026, 3, 10),
                ).isEmpty(),
            "a date in the calendar's gap resolves to nothing, which is what makes " +
                "accounting.fiscal_period_not_found reachable",
        )
    }

    @Test
    fun `a transition log row cannot reference another tenants period`() {
        val owner = fixture.createOrganisation("cal-log-a")
        val other = fixture.createOrganisation("cal-log-b")
        val year = fixture.insertYear(owner)
        val period = fixture.insertPeriod(owner, year, 1, JAN_START, JAN_END)

        assertViolates("fk_fiscal_period_transition_log_period") {
            jdbcTemplate.update(
                """
                INSERT INTO fiscal_period_transition_log (
                    organisation_id, entity_id, transition_name, status_from, status_to,
                    created_at, updated_at
                )
                VALUES (?, ?, 'close', 'OPEN', 'CLOSED', NOW(), NOW())
                """.trimIndent(),
                other,
                period,
            )
        }
    }

    @Test
    fun `btree_gist is installed outside public so code generation never sees it`() {
        // Mutation-checked: changing V6 to `CREATE EXTENSION btree_gist` with no schema fails this
        // test, and regenerating then emits 213 routine classes into a new
        // `com.finaxis.platform.jooq.routines` package - which is the cost this placement avoids.
        assertEquals(
            "extensions",
            jdbcTemplate.queryForObject(
                """
                SELECT n.nspname FROM pg_extension e
                JOIN pg_namespace n ON n.oid = e.extnamespace
                WHERE e.extname = 'btree_gist'
                """.trimIndent(),
                String::class.java,
            ),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM pg_depend d
                JOIN pg_extension e ON e.oid = d.refobjid AND e.extname = 'btree_gist'
                JOIN pg_proc p ON p.oid = d.objid
                JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = 'public'
                """.trimIndent(),
                Int::class.java,
            ),
            "an extension function in public would be generated into com.finaxis.platform.jooq " +
                "on every build, because codegen reads inputSchema = \"public\" with no excludes",
        )
    }

    @Test
    fun `the exclusion constraint is what rejects an overlap, not an ordinary index`() {
        // Guards the assertion above against passing for a trivial reason. If btree_gist were
        // absent the migration could not have created the constraint at all, so this asserts the
        // constraint exists and is of the exclusion kind.
        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM pg_constraint
                WHERE contype = 'x'
                  AND conname IN (
                      'ex_accounting_fiscal_year_no_overlap',
                      'ex_accounting_fiscal_period_no_overlap'
                  )
                """.trimIndent(),
                Int::class.java,
            ),
        )
    }

    private companion object {
        val JAN_START: LocalDate = LocalDate.of(2026, 1, 1)
        val JAN_END: LocalDate = LocalDate.of(2026, 1, 31)
        val FEB_START: LocalDate = LocalDate.of(2026, 2, 1)
        val FEB_END: LocalDate = LocalDate.of(2026, 2, 28)
    }
}
