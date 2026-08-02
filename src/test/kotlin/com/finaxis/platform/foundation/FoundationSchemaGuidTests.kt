package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FoundationSchemaGuidTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val applicationTables =
        listOf(
            "api_idempotency_record",
            "audit_event",
            "branch",
            "branch_transition_log",
            "business_date",
            "business_date_history",
            "identity_dispatch_log",
            "keycloak_identity_link",
            "membership_permission",
            "organisation",
            "organisation_initial_administrator_bootstrap",
            "organisation_setting",
            "organisation_transition_log",
            "permission",
            "reference_sequence",
            "role",
            "role_permission",
            "user_account",
            "user_account_transition_log",
            "user_branch_assignment",
            "user_organisation_membership",
            "user_organisation_membership_transition_log",
            "user_role_assignment",
        )

    @Test
    fun `every application table declares a non-null uuidv7 guid column`() {
        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT table_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'guid'
                ORDER BY table_name
                """.trimIndent(),
            )

        assertEquals(applicationTables, rows.map { it["table_name"] as String })
        rows.forEach { row ->
            assertEquals("uuid", row["data_type"], "guid type on ${row["table_name"]}")
            assertEquals("NO", row["is_nullable"], "guid nullability on ${row["table_name"]}")
            assertTrue(
                (row["column_default"] as String).contains("uuidv7()"),
                "guid default on ${row["table_name"]} was ${row["column_default"]}",
            )
        }
    }

    @Test
    fun `every guid column is backed by a unique index`() {
        val indexed =
            jdbcTemplate.queryForList(
                """
                SELECT DISTINCT t.relname AS table_name
                FROM pg_index i
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (i.indkey)
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public'
                  AND a.attname = 'guid'
                  AND i.indisunique
                  AND i.indnatts = 1
                ORDER BY t.relname
                """.trimIndent(),
                String::class.java,
            )

        assertEquals(applicationTables, indexed)
    }

    @Test
    fun `guid defaults are distinct and time ordered across inserts`() {
        val guids =
            jdbcTemplate.queryForList(
                "SELECT uuidv7() FROM generate_series(1, 50)",
                java.util.UUID::class.java,
            )

        assertEquals(50, guids.toSet().size)
        assertEquals(guids.sortedBy { it.toString() }, guids)
    }
}
