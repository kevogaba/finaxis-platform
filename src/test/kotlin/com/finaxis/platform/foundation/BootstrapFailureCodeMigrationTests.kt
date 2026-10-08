package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `V24`'s one-way rewrite of `organisation_initial_administrator_bootstrap.last_failure_code` and
 * its `chk_bootstrap_failure_code`, proved on PostgreSQL. Every test rebuilds the pre-`V24` shape
 * (the constraint dropped, raw messages stored) inside a transaction that is rolled back, so the
 * shared container is never left holding crafted rows.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BootstrapFailureCodeMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @Test
    fun `the Kotlin enum and the constraint name the same closed set`() {
        val definition =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                        "WHERE conname = 'chk_bootstrap_failure_code'",
                    String::class.java,
                ),
            )

        InitialAdministratorBootstrapFailureCode.entries.forEach {
            assertTrue(definition.contains("'${it.name}'"), "${it.name} missing from $definition")
        }
        assertEquals(
            InitialAdministratorBootstrapFailureCode.entries.size,
            Regex("'[A-Z_]+'").findAll(definition).count(),
            definition,
        )
    }

    @Test
    fun `the constraint accepts every set member and NULL`() {
        inRolledBackTransaction {
            InitialAdministratorBootstrapFailureCode.entries.forEach {
                assertEquals(it.name, storedAfterInsert(it.name))
            }
            assertNull(storedAfterInsert(null))
        }
    }

    @Test
    fun `the constraint rejects free text with its name`() {
        val failure =
            assertFailsWith<DataIntegrityViolationException> {
                inRolledBackTransaction {
                    insertBootstrap("Keycloak admin failed to create user jane.doe@acme.test")
                }
            }

        assertTrue(
            failure.message.orEmpty().contains("chk_bootstrap_failure_code"),
            failure.message,
        )
    }

    @Test
    fun `the migration turns every stored raw message into UNEXPECTED and keeps set members`() {
        inRolledBackTransaction {
            dropConstraint()
            val rawEmail = insertBootstrap("duplicate key (email)=(jane.doe@acme.test)")
            val rawSql = insertBootstrap("insert into user_account (email) values ('x')")
            val rawLowercase = insertBootstrap("keycloak_unavailable")
            val member = insertBootstrap("CONFLICT")
            val identity = insertBootstrap("IDENTITY_PROVIDER_FAILED")
            val none = insertBootstrap(null)
            val before = rowVersions(listOf(rawEmail, member))

            runMigration()

            assertEquals("UNEXPECTED", codeOf(rawEmail))
            assertEquals("UNEXPECTED", codeOf(rawSql))
            assertEquals("UNEXPECTED", codeOf(rawLowercase))
            assertEquals("CONFLICT", codeOf(member))
            assertEquals("IDENTITY_PROVIDER_FAILED", codeOf(identity))
            assertNull(codeOf(none))
            // Only the one column is written: the row's version and timestamp stand.
            assertEquals(before, rowVersions(listOf(rawEmail, member)))
            assertEquals(1, constraintCount())
        }
    }

    @Test
    fun `the migration is idempotent`() {
        inRolledBackTransaction {
            dropConstraint()
            val raw = insertBootstrap("some raw failure")

            runMigration()
            runMigration()

            assertEquals("UNEXPECTED", codeOf(raw))
            assertEquals(1, constraintCount())
        }
    }

    @Test
    fun `after the migration free text cannot be written again`() {
        assertFailsWith<DataIntegrityViolationException> {
            inRolledBackTransaction {
                dropConstraint()
                runMigration()

                insertBootstrap("a message again")
            }
        }
    }

    private fun storedAfterInsert(code: String?): String? = codeOf(insertBootstrap(code))

    private fun insertBootstrap(code: String?): UUID {
        val organisationId = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, created_at, updated_at
            ) VALUES (?, ?, 'Failure Code Test', 'KE', 'KES', 'UTC', 'DRAFT', NOW(), NOW())
            """.trimIndent(),
            organisationId,
            "FC-" + organisationId.toString().takeLast(TENANT_CODE_SUFFIX),
        )
        jdbcTemplate.update(
            """
            INSERT INTO organisation_initial_administrator_bootstrap (
                organisation_id, admin_email, admin_username, admin_display_name, status,
                attempts, requested_by, last_failure_code, created_at, updated_at, row_version
            ) VALUES (?, ?, ?, 'Admin', 'FAILED', 1, ?, ?, NOW(), NOW(), 3)
            """.trimIndent(),
            organisationId,
            "admin-$organisationId@tenant.test",
            "admin-$organisationId",
            uuidV7(),
            code,
        )
        return organisationId
    }

    private fun codeOf(organisationId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT last_failure_code FROM organisation_initial_administrator_bootstrap " +
                "WHERE organisation_id = ?",
            String::class.java,
            organisationId,
        )

    private fun rowVersions(ids: List<UUID>): List<Pair<Long, Any?>> =
        ids.map { id ->
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT row_version, updated_at FROM " +
                        "organisation_initial_administrator_bootstrap WHERE organisation_id = ?",
                    { rs, _ -> rs.getLong(1) to rs.getTimestamp(2) },
                    id,
                ),
            )
        }

    private fun dropConstraint() {
        jdbcTemplate.execute(
            "ALTER TABLE organisation_initial_administrator_bootstrap " +
                "DROP CONSTRAINT chk_bootstrap_failure_code",
        )
    }

    private fun runMigration() {
        val sql =
            ClassPathResource("db/migration/V24__bootstrap_failure_code_closed_set.sql")
                .inputStream
                .use { String(it.readAllBytes()) }
        jdbcTemplate.execute(sql)
    }

    private fun constraintCount(): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'chk_bootstrap_failure_code'",
                Int::class.java,
            ),
        )

    /** Runs [block] and always rolls back, whether it returns or throws. */
    private fun <T> inRolledBackTransaction(block: () -> T): T =
        requireNotNull(
            transactionTemplate.execute { transaction ->
                transaction.setRollbackOnly()
                block()
            },
        )

    private companion object {
        const val TENANT_CODE_SUFFIX = 12
    }
}
