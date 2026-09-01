package com.finaxis.platform.common.persistence

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.relational.core.mapping.Table
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps [FoundationJdbcEntities] honest as a schema reference.
 *
 * The entities double as the readable, typed description of the foundation tables. That is only
 * worth anything if it cannot drift from `V1__foundation_schema.sql`, so this reflects over every
 * `@Table` class and asserts the table exists and every mapped property resolves to a real column.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FoundationJdbcEntitySchemaTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `every mapped entity names a table that exists`() {
        val missing = MAPPED_ENTITIES.map { it.table }.filterNot(::tableExists).sorted()

        assertEquals(emptyList(), missing, "entities reference tables absent from the schema")
    }

    @Test
    fun `every mapped property resolves to a real column`() {
        val problems =
            MAPPED_ENTITIES
                .flatMap { entity ->
                    val columns = columnsOf(entity.table)
                    entity.columns
                        .filterNot { it in columns }
                        .map { "${entity.table}.$it" }
                }.sorted()

        assertEquals(
            emptyList(),
            problems,
            "entity properties without a matching column - add the column to V1 or fix the entity",
        )
    }

    @Test
    fun `entities cover every mutable foundation table`() {
        val mapped = MAPPED_ENTITIES.map { it.table }.toSet()
        val unmapped = (MUTABLE_FOUNDATION_TABLES - mapped).sorted()

        assertTrue(
            unmapped.isEmpty(),
            "mutable foundation tables with no readable entity reference: $unmapped",
        )
    }

    private fun tableExists(table: String): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            table,
        ) == true

    private fun columnsOf(table: String): Set<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """.trimIndent(),
                String::class.java,
                table,
            ).filterNotNull()
            .toSet()

    private data class MappedEntity(
        val table: String,
        val columns: List<String>,
    )

    private companion object {
        /**
         * Foundation tables that hold mutable state and therefore deserve a typed reference.
         * Append-only logs and framework-owned tables are intentionally excluded.
         *
         * Scoped to the **foundation** rather than to every application table, and the accounting
         * tables `V6` adds are deliberately absent. Per
         * `docs/adr/0014-spring-data-jdbc-auditing.md` these entities are convention scaffolding
         * rather than live write paths — every production write goes through jOOQ — and accounting
         * ships no Spring Data JDBC entity at all. Listing its tables here would demand
         * scaffolding for a module that has deliberately declined it.
         */
        val MUTABLE_FOUNDATION_TABLES =
            setOf(
                "organisation",
                "branch",
                "user_account",
                "keycloak_identity_link",
                "user_organisation_membership",
                "user_branch_assignment",
                "permission",
                "role",
                "role_permission",
                "user_role_assignment",
                "organisation_setting",
                "business_date",
            )

        val MAPPED_ENTITIES: List<MappedEntity> =
            listOf(
                OrganisationJdbcEntity::class,
                BranchJdbcEntity::class,
                UserAccountJdbcEntity::class,
                KeycloakIdentityLinkJdbcEntity::class,
                UserOrganisationMembershipJdbcEntity::class,
                UserBranchAssignmentJdbcEntity::class,
                PermissionJdbcEntity::class,
                RoleJdbcEntity::class,
                RolePermissionJdbcEntity::class,
                UserRoleAssignmentJdbcEntity::class,
                OrganisationSettingJdbcEntity::class,
                BusinessDateJdbcEntity::class,
                OrganisationTransitionLogJdbcEntity::class,
                BranchTransitionLogJdbcEntity::class,
                UserAccountTransitionLogJdbcEntity::class,
                UserOrganisationMembershipTransitionLogJdbcEntity::class,
                AuditEventJdbcEntity::class,
            ).map { type ->
                val annotation = type.findAnnotation<Table>()
                val table =
                    requireNotNull(annotation) { "${type.simpleName} is missing @Table" }.value
                val constructorParameters =
                    type.primaryConstructor
                        ?.parameters
                        ?.mapNotNull { it.name }
                        .orEmpty()
                        .toSet()
                val columns =
                    type.declaredMemberProperties
                        .filter { it.name in constructorParameters }
                        .map { snakeCase(it.name) }
                MappedEntity(table, columns)
            }

        /** Mirrors Spring Data JDBC's default camelCase-to-snake_case naming strategy. */
        fun snakeCase(name: String): String =
            name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }
}
