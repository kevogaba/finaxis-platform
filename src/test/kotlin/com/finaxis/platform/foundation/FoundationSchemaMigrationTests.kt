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
                      'membership_permission'
                  )
                ORDER BY tablename
                """.trimIndent(),
                String::class.java,
            )

        assertEquals(
            listOf(
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
}
