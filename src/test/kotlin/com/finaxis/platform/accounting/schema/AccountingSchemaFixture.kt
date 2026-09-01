package com.finaxis.platform.accounting.schema

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Direct-SQL fixture for the issue #36 schema tests.
 *
 * These tests assert what PostgreSQL rejects, so they insert with `JdbcTemplate` rather than
 * through an application service: a service that refuses a row first would prove the service, not
 * the constraint, and the point of every assertion here is that the database is the last line.
 *
 * Every test creates its own organisations, so nothing depends on the seeded `PLATFORM` or
 * bootstrap tenants and no two tests can collide on `uq_gl_account_organisation_code`.
 */
class AccountingSchemaFixture(
    private val jdbcTemplate: JdbcTemplate,
) {
    /** Creates a committed organisation and returns its generated identifier. */
    fun createOrganisation(label: String): UUID =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO organisation (
                tenant_code, display_name, country_code, base_currency_code, timezone, status,
                created_at, updated_at
            )
            VALUES (?, ?, 'KE', 'KES', 'Africa/Nairobi', 'ACTIVE', NOW(), NOW())
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            "$label-${UUID.randomUUID()}",
            label,
        )!!

    /** Inserts a GL account and returns its generated identifier. */
    @Suppress("LongParameterList")
    fun insertAccount(
        organisationId: UUID,
        code: String,
        accountClass: String = "ASSET",
        usage: String = "POSTABLE",
        contra: Boolean = false,
        manualPostingAllowed: Boolean = false,
        parentId: UUID? = null,
        status: String = "ACTIVE",
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO gl_account (
                organisation_id, account_code, account_name, account_class, account_usage,
                is_contra_account, manual_posting_allowed, parent_account_id,
                status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            organisationId,
            code,
            "Account $code",
            accountClass,
            usage,
            contra,
            manualPostingAllowed,
            parentId,
            status,
        )!!

    /** Inserts a fiscal year and returns its generated identifier. */
    fun insertYear(
        organisationId: UUID,
        code: String = "FY2026",
        start: LocalDate = LocalDate.of(2026, 1, 1),
        end: LocalDate = LocalDate.of(2026, 12, 31),
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO accounting_fiscal_year (
                organisation_id, year_code, year_name, start_date, end_date, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, NOW(), NOW())
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            organisationId,
            code,
            "Year $code",
            start,
            end,
        )!!

    /** Inserts a fiscal period and returns its generated identifier. */
    @Suppress("LongParameterList")
    fun insertPeriod(
        organisationId: UUID,
        yearId: UUID,
        number: Int,
        start: LocalDate,
        end: LocalDate,
        status: String = "OPEN",
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO accounting_fiscal_period (
                organisation_id, fiscal_year_id, period_number, period_name, start_date, end_date,
                status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            organisationId,
            yearId,
            number,
            "Period $number",
            start,
            end,
            status,
        )!!

    companion object {
        /**
         * Asserts that [block] is rejected by the named database constraint.
         *
         * The constraint name is asserted, not merely the exception type. Without it a test passes
         * when a *different* constraint fires — a typo in the fixture, or a unique key catching a
         * row the check under test would have let through — which is how a schema test comes to
         * report success for the wrong reason.
         */
        fun assertViolates(
            constraint: String,
            block: () -> Unit,
        ) {
            val failure =
                try {
                    block()
                    fail("expected $constraint to reject this row, but the insert succeeded")
                } catch (ex: DataIntegrityViolationException) {
                    ex
                }

            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }
                    .any { it.message?.contains(constraint) == true },
                "expected $constraint to be the violated constraint, but the failure was: " +
                    failure.mostSpecificCause.message,
            )
        }
    }
}
