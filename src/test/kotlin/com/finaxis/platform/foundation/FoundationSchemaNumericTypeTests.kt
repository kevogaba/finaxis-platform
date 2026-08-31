package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals

/**
 * The schema half of the binary-floating-point ban.
 *
 * ADR 0019 bans `double precision` and `real` from accounting, and the code half is
 * `AccountingBoundaryRuleTests.accounting code never uses binary floating point`. Both
 * `docs/adr/0019-accounting-money-representation-and-rounding.md` and
 * `docs/architecture/accounting-foundation.md` state that these two tests exist; until this file
 * was written, neither did.
 *
 * The rule is asserted over **every** application table rather than only accounting's, for two
 * reasons. It is meaningful today, before any accounting table exists — a test scoped to
 * accounting tables would pass vacuously until issue #36 and prove nothing in the meantime. And
 * the reasoning is not accounting-specific: a float column anywhere the ledger later reads from,
 * or reconciles against, reintroduces the same representation error.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FoundationSchemaNumericTypeTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `no application table declares a binary floating point column`() {
        val offending =
            jdbcTemplate.queryForList(
                """
                SELECT table_name, column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND data_type IN ('double precision', 'real')
                ORDER BY table_name, column_name
                """.trimIndent(),
            )

        assertEquals(
            emptyList(),
            offending.map { "${it["table_name"]}.${it["column_name"]} is ${it["data_type"]}" },
            "money and every quantity the ledger derives from it are NUMERIC end to end; " +
                "binary floating point cannot represent decimal fractions exactly (ADR 0019)",
        )
    }

    @Test
    fun `the check is looking at a schema that actually has columns`() {
        // Guards the test above against passing because the query matched nothing for a trivial
        // reason - a renamed schema, a migration that did not run, an empty database. Without
        // this, "no float columns" and "no columns at all" are indistinguishable.
        val columns =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                """.trimIndent(),
                Int::class.java,
            )

        assertEquals(
            true,
            (columns ?: 0) > MINIMUM_EXPECTED_COLUMNS,
            "expected the migrated foundation schema, but found $columns columns in public",
        )
    }

    private companion object {
        /** `V1` alone creates 23 tables, so a healthy schema is far above this floor. */
        const val MINIMUM_EXPECTED_COLUMNS = 100
    }
}
