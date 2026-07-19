package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
                  AND conname IN (
                      'chk_api_idempotency_record_status',
                      'pk_api_idempotency_record'
                  )
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

    @Test
    fun `idempotency schema declares the response state constraint`() {
        val constraintNames =
            jdbcTemplate.queryForList(
                """
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'api_idempotency_record'::regclass
                  AND contype = 'c'
                ORDER BY conname
                """.trimIndent(),
                String::class.java,
            )
        assertEquals(
            listOf(
                "chk_api_idempotency_record_response",
                "chk_api_idempotency_record_status",
            ),
            constraintNames,
        )
    }

    @Test
    fun `idempotency state rejects partial responses`() {
        assertFailsWith<DataIntegrityViolationException> {
            insertIdempotencyRecord(
                status = "IN_PROGRESS",
                responseStatus = 200,
                responseHeaders = "{}",
                responseBody = "{}",
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            insertIdempotencyRecord(
                status = "COMPLETED",
                responseStatus = 400,
                responseHeaders = "{}",
                responseBody = "{}",
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            insertIdempotencyRecord(
                status = "COMPLETED",
                responseStatus = 200,
                responseHeaders = null,
                responseBody = null,
            )
        }
    }

    @Test
    fun `idempotency state requires 204 to have no stored body`() {
        assertFailsWith<DataIntegrityViolationException> {
            insertIdempotencyRecord(
                status = "COMPLETED",
                responseStatus = 204,
                responseHeaders = "{}",
                responseBody = "{}",
            )
        }

        val noBodyKey =
            insertIdempotencyRecord(
                status = "COMPLETED",
                responseStatus = 204,
                responseHeaders = "{}",
                responseBody = null,
            )
        assertEquals(
            1,
            jdbcTemplate.update(
                """
                DELETE FROM api_idempotency_record
                WHERE scope_organisation_id = ?
                  AND idempotency_key = ?
                """.trimIndent(),
                noBodyKey.first,
                noBodyKey.second,
            ),
        )
    }

    @Test
    fun `idempotency state preserves valid 200 JSON responses`() {
        val validKey =
            insertIdempotencyRecord(
                status = "COMPLETED",
                responseStatus = 200,
                responseHeaders = "{}",
                responseBody = """{"state":"CREATED"}""",
            )

        assertEquals(
            1,
            jdbcTemplate.update(
                """
                DELETE FROM api_idempotency_record
                WHERE scope_organisation_id = ?
                  AND idempotency_key = ?
                """.trimIndent(),
                validKey.first,
                validKey.second,
            ),
        )
    }

    private fun insertIdempotencyRecord(
        status: String,
        responseStatus: Int?,
        responseHeaders: String?,
        responseBody: String?,
    ): Pair<UUID, UUID> {
        val scopeId = UUID.randomUUID()
        val key = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        jdbcTemplate.update(
            """
            INSERT INTO api_idempotency_record (
                scope_organisation_id,
                idempotency_key,
                actor_fingerprint,
                request_method,
                normalized_path,
                request_hash,
                status,
                response_status,
                response_headers,
                response_body,
                created_at,
                expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            """.trimIndent(),
            scopeId,
            key,
            "actor-fingerprint",
            "POST",
            "/api/v1/widgets",
            "request-hash",
            status,
            responseStatus,
            responseHeaders,
            responseBody,
            now,
            now.plusDays(1),
        )
        return scopeId to key
    }

    @Test
    fun `V10 migration seeds all foundation API permission codes`() {
        val expected =
            listOf(
                "auth.select_branch",
                "auth.select_organisation",
                "branch.reactivate",
                "branch.view",
                "branch_assignment.view",
                "membership.reactivate",
                "membership.revoke",
                "membership.suspend",
                "membership.view",
                "permission.view",
                "role.activate",
                "role.deactivate",
                "role.remove_permission",
                "role.view",
                "role_assignment.view",
                "settings.view",
                "tenant.bootstrap_retry",
                "tenant.reactivate",
                "tenant.reject",
                "tenant.update_draft",
                "tenant.view",
                "user.revoke_branch",
                "user.revoke_role",
                "user.view",
            )

        val actual =
            jdbcTemplate.queryForList(
                """
                SELECT permission_code
                FROM permission
                WHERE permission_code IN (
                    'auth.select_organisation', 'auth.select_branch',
                    'tenant.view', 'tenant.update_draft', 'tenant.reject',
                    'tenant.reactivate', 'tenant.bootstrap_retry',
                    'branch.view', 'branch.reactivate',
                    'user.view', 'membership.view', 'membership.suspend',
                    'membership.reactivate', 'membership.revoke',
                    'branch_assignment.view', 'user.revoke_branch',
                    'role.view', 'role.activate', 'role.deactivate',
                    'role.remove_permission', 'role_assignment.view',
                    'user.revoke_role', 'permission.view', 'settings.view'
                )
                ORDER BY permission_code
                """.trimIndent(),
                String::class.java,
            )

        assertEquals(expected.sorted(), actual.map { it as String }.sorted())
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
