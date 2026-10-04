package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `V20`'s `chk_organisation_platform_always_active` (issue #205), proved on PostgreSQL.
 *
 * The constraint pins one row: the reserved `PLATFORM` organisation must be `ACTIVE`. Every
 * statement that could break that is attempted inside a transaction that is rolled back, so that a
 * test run against a database that lacks the constraint cannot leave the shared container with a
 * suspended platform organisation for the suites that authenticate against it.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PlatformOrganisationStaysActiveMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private val tenants = mutableListOf<UUID>()

    @AfterEach
    fun removeSeededTenants() {
        tenants.forEach { jdbcTemplate.update("DELETE FROM organisation WHERE id = ?", it) }
        tenants.clear()
    }

    @Test
    fun `the constraint exists on the organisation table and is validated`() {
        val validated =
            jdbcTemplate.queryForObject(
                """
                SELECT convalidated FROM pg_constraint
                WHERE conname = 'chk_organisation_platform_always_active'
                  AND conrelid = 'organisation'::regclass
                  AND contype = 'c'
                """.trimIndent(),
                Boolean::class.java,
            )

        assertEquals(true, validated)
    }

    @Test
    fun `the platform organisation cannot be set to any status but active`() {
        STATUSES
            .filter { it != "ACTIVE" }
            .forEach { status ->
                val failure =
                    assertFailsWith<DataIntegrityViolationException>(status) {
                        inRolledBackTransaction {
                            jdbcTemplate.update(
                                "UPDATE organisation SET status = ? WHERE id = ?",
                                status,
                                PLATFORM_ID,
                            )
                        }
                    }

                assertTrue(
                    failure.message.orEmpty().contains("chk_organisation_platform_always_active"),
                    "$status: ${failure.message}",
                )
            }
        assertEquals("ACTIVE", statusOf(PLATFORM_ID))
    }

    @Test
    fun `the platform organisation can still be updated while it stays active`() {
        val updated =
            inRolledBackTransaction {
                jdbcTemplate.update(
                    "UPDATE organisation SET status = 'ACTIVE', updated_at = NOW() WHERE id = ?",
                    PLATFORM_ID,
                )
            }

        assertEquals(1, updated)
    }

    @Test
    fun `an ordinary tenant can move through every status`() {
        val tenantId = seedTenant()

        STATUSES.forEach { status ->
            val updated =
                jdbcTemplate.update(
                    "UPDATE organisation SET status = ? WHERE id = ?",
                    status,
                    tenantId,
                )

            assertEquals(1, updated, status)
            assertEquals(status, statusOf(tenantId))
        }
    }

    @Test
    fun `a tenant can be inserted in a non active status`() {
        val tenantId = seedTenant(status = "DRAFT")

        assertEquals("DRAFT", statusOf(tenantId))
    }

    @Test
    fun `the platform organisation row cannot be deleted because its dependants restrict it`() {
        // Not the constraint's job: this documents that physical deletion is not reachable either,
        // because rows V2 seeds (roles, grants) and the audit trail reference it with no cascade.
        assertFailsWith<DataIntegrityViolationException> {
            inRolledBackTransaction {
                jdbcTemplate.update("DELETE FROM organisation WHERE id = ?", PLATFORM_ID)
            }
        }
        assertEquals("ACTIVE", statusOf(PLATFORM_ID))
    }

    @Test
    fun `the migration file installs the constraint on an active platform organisation`() {
        inRolledBackTransaction {
            jdbcTemplate.execute(
                "ALTER TABLE organisation DROP CONSTRAINT chk_organisation_platform_always_active",
            )

            runMigration()

            assertEquals(1, constraintCount())
        }
    }

    @Test
    fun `the migration file refuses to run when the platform organisation is not active`() {
        val failure =
            assertFailsWith<Exception> {
                inRolledBackTransaction {
                    jdbcTemplate.execute(
                        "ALTER TABLE organisation " +
                            "DROP CONSTRAINT chk_organisation_platform_always_active",
                    )
                    jdbcTemplate.update(
                        "UPDATE organisation SET status = 'SUSPENDED' WHERE id = ?",
                        PLATFORM_ID,
                    )

                    runMigration()
                }
            }

        assertTrue(
            generateSequence<Throwable>(failure) { it.cause }.any {
                it.message.orEmpty().contains("PLATFORM organisation")
            },
            failure.toString(),
        )
        assertEquals("ACTIVE", statusOf(PLATFORM_ID))
        assertEquals(1, constraintCount())
    }

    private fun runMigration() {
        val sql =
            ClassPathResource("db/migration/V20__platform_organisation_stays_active.sql")
                .inputStream
                .use { String(it.readAllBytes()) }
        jdbcTemplate.execute(sql)
    }

    private fun constraintCount(): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint " +
                    "WHERE conname = 'chk_organisation_platform_always_active'",
                Int::class.java,
            ),
        )

    private fun statusOf(organisationId: UUID): String =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT status FROM organisation WHERE id = ?",
                String::class.java,
                organisationId,
            ),
        )

    private fun seedTenant(status: String = "ACTIVE"): UUID {
        val id = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, created_at, updated_at
            ) VALUES (?, ?, 'Platform Guard Test', 'KE', 'KES', 'UTC', ?, NOW(), NOW())
            """.trimIndent(),
            id,
            "PG-" + id.toString().take(TENANT_CODE_SUFFIX),
            status,
        )
        tenants += id
        return id
    }

    /** Runs [block] and always rolls back, whether it returns or throws. */
    private fun <T> inRolledBackTransaction(block: () -> T): T =
        requireNotNull(
            transactionTemplate.execute { transaction ->
                transaction.setRollbackOnly()
                block()
            },
        )

    private companion object {
        val PLATFORM_ID: UUID = UUID(0L, 0L)
        const val TENANT_CODE_SUFFIX = 12
        val STATUSES =
            listOf(
                "DRAFT",
                "PENDING_APPROVAL",
                "PROVISIONING",
                "ACTIVE",
                "SUSPENDED",
                "DEPROVISIONING",
                "DEPROVISIONED",
                "REJECTED",
                "ARCHIVED",
            )
    }
}
