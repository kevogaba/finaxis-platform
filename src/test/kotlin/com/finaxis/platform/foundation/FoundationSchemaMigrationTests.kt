package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FoundationSchemaMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `Flyway creates the organisation foundation on an empty PostgreSQL database`() {
        val tables =
            jdbcTemplate.queryForList(
                """
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename IN (
                      'organisation',
                      'branch',
                      'user_account',
                      'keycloak_identity_link',
                      'user_organisation_membership',
                      'user_branch_assignment',
                      'user_role_assignment',
                      'organisation_transition_log',
                      'audit_event',
                      'organisation_setting',
                      'business_date',
                      'permission',
                      'role',
                      'role_permission',
                      'membership_permission',
                      'api_idempotency_record'
                  )
                ORDER BY tablename
                """.trimIndent(),
                String::class.java,
            )

        assertEquals(
            listOf(
                "api_idempotency_record",
                "audit_event",
                "branch",
                "business_date",
                "keycloak_identity_link",
                "membership_permission",
                "organisation",
                "organisation_setting",
                "organisation_transition_log",
                "permission",
                "role",
                "role_permission",
                "user_account",
                "user_branch_assignment",
                "user_organisation_membership",
                "user_role_assignment",
            ),
            tables,
        )
    }

    @Test
    fun `Flyway creates durable API idempotency records with tenant scoped uniqueness`() {
        val columns =
            jdbcTemplate.queryForList(
                """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'api_idempotency_record'
                ORDER BY ordinal_position
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                column("scope_organisation_id", "uuid"),
                column("idempotency_key", "uuid"),
                column("actor_fingerprint", "character varying"),
                column("request_method", "character varying"),
                column("normalized_path", "character varying"),
                column("request_hash", "character varying"),
                column("status", "character varying"),
                column("response_status", "integer"),
                column("response_headers", "jsonb"),
                column("response_body", "text"),
                column("created_at", "timestamp with time zone"),
                column("expires_at", "timestamp with time zone"),
            ),
            columns,
        )

        val constraints =
            jdbcTemplate.queryForList(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'api_idempotency_record'::regclass
                  AND contype IN ('c', 'p')
                ORDER BY contype, conname
                """.trimIndent(),
                String::class.java,
            )

        assertEquals(
            listOf(
                "CHECK (((status)::text = ANY " +
                    "((ARRAY['IN_PROGRESS'::character varying, " +
                    "'COMPLETED'::character varying])::text[])))",
                "PRIMARY KEY (scope_organisation_id, idempotency_key)",
            ),
            constraints,
        )
    }

    private fun column(
        name: String,
        type: String,
    ): Map<String, Any> =
        mapOf(
            "column_name" to name,
            "data_type" to type,
        )
}
