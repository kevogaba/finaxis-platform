package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.schema.AccountingSchemaFixture.Companion.assertViolates
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What PostgreSQL enforces about the chart of accounts, proved against PostgreSQL.
 *
 * Every rule here is stated in `docs/database/accounting-erd.md` under *"Settled design
 * questions"* and *"Column definitions"*. The document is the authority; these tests are what stop
 * it and the schema drifting apart.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ChartOfAccountsSchemaIntegrationTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val fixture = AccountingSchemaFixture(jdbcTemplate)

    @Test
    fun `a header account and its postable child are accepted`() {
        // The positive control. Every other test in this class asserts a rejection, and all of
        // them would pass against a table that rejected everything.
        val organisationId = fixture.createOrganisation("coa-positive")
        val header = fixture.insertAccount(organisationId, "1000", usage = "HEADER")
        val child =
            fixture.insertAccount(
                organisationId,
                "1010",
                usage = "POSTABLE",
                manualPostingAllowed = true,
                parentId = header,
            )

        assertEquals(
            header,
            jdbcTemplate.queryForObject(
                "SELECT parent_account_id FROM gl_account WHERE id = ?",
                java.util.UUID::class.java,
                child,
            ),
        )
        assertEquals(
            "HEADER",
            jdbcTemplate.queryForObject(
                "SELECT parent_account_usage FROM gl_account WHERE id = ?",
                String::class.java,
                child,
            ),
            "parent_account_usage is generated, so the application never has to populate it",
        )
    }

    @Test
    fun `account codes are unique within a tenant and free across tenants`() {
        val first = fixture.createOrganisation("coa-codes-a")
        val second = fixture.createOrganisation("coa-codes-b")
        fixture.insertAccount(first, "1000", usage = "HEADER")

        assertViolates("uq_gl_account_organisation_code") {
            fixture.insertAccount(first, "1000", usage = "HEADER")
        }
        fixture.insertAccount(second, "1000", usage = "HEADER")
    }

    @Test
    fun `a postable account cannot be a parent`() {
        val organisationId = fixture.createOrganisation("coa-parent-usage")
        val postable = fixture.insertAccount(organisationId, "1010")

        assertViolates("fk_gl_account_parent_same_organisation") {
            fixture.insertAccount(organisationId, "1011", parentId = postable)
        }
    }

    @Test
    fun `a header account with children cannot be turned into a postable one`() {
        // The half that a plain "parent must be HEADER" check would miss: the rule has to keep
        // holding after the parent row is edited, not only when the child is inserted.
        val organisationId = fixture.createOrganisation("coa-demote")
        val header = fixture.insertAccount(organisationId, "1000", usage = "HEADER")
        fixture.insertAccount(organisationId, "1010", parentId = header)

        assertViolates("fk_gl_account_parent_same_organisation") {
            jdbcTemplate.update(
                "UPDATE gl_account SET account_usage = 'POSTABLE' WHERE id = ?",
                header,
            )
        }
    }

    @Test
    fun `a parent account cannot belong to another tenant`() {
        val owner = fixture.createOrganisation("coa-tenant-a")
        val other = fixture.createOrganisation("coa-tenant-b")
        val header = fixture.insertAccount(owner, "1000", usage = "HEADER")

        assertViolates("fk_gl_account_parent_same_organisation") {
            fixture.insertAccount(other, "1010", parentId = header)
        }
    }

    @Test
    fun `an account cannot be its own parent`() {
        val organisationId = fixture.createOrganisation("coa-self-parent")
        val header = fixture.insertAccount(organisationId, "1000", usage = "HEADER")

        assertViolates("chk_gl_account_not_own_parent") {
            jdbcTemplate.update("UPDATE gl_account SET parent_account_id = id WHERE id = ?", header)
        }
    }

    @Test
    fun `the normal balance inverts for a contra account`() {
        val organisationId = fixture.createOrganisation("coa-normal-balance")

        // The four combinations of class side and contra flag. The contra flag must *invert* the
        // class-implied side, not exempt the row from having one: an earlier revision expressed
        // this as `CHECK (is_contra_account OR normal_balance = ...)`, a disjunction that accepted
        // a contra ASSET with a DEBIT balance - exactly the row the rule exists to reject.
        val cases =
            listOf(
                Triple("1000", "ASSET" to false, "DEBIT"),
                Triple("1900", "ASSET" to true, "CREDIT"),
                Triple("2000", "LIABILITY" to false, "CREDIT"),
                Triple("2900", "LIABILITY" to true, "DEBIT"),
                Triple("4000", "INCOME" to false, "CREDIT"),
                Triple("4900", "INCOME" to true, "DEBIT"),
                Triple("5000", "EXPENSE" to false, "DEBIT"),
                Triple("5900", "EXPENSE" to true, "CREDIT"),
                Triple("3000", "EQUITY" to false, "CREDIT"),
                Triple("3900", "EQUITY" to true, "DEBIT"),
            )

        cases.forEach { (code, classAndContra, expected) ->
            val (accountClass, contra) = classAndContra
            val id =
                fixture.insertAccount(
                    organisationId,
                    code,
                    accountClass = accountClass,
                    contra = contra,
                )
            assertEquals(
                expected,
                jdbcTemplate.queryForObject(
                    "SELECT normal_balance FROM gl_account WHERE id = ?",
                    String::class.java,
                    id,
                ),
                "$accountClass with is_contra_account=$contra",
            )
        }
    }

    @Test
    fun `the normal balance cannot be supplied by a caller`() {
        val organisationId = fixture.createOrganisation("coa-normal-balance-forge")

        // 428C9 is PostgreSQL's "cannot insert a non-DEFAULT value into column". This is what makes
        // the inversion structural rather than policed: there is no write path to disagree with.
        val failure =
            assertThrows<DataAccessException> {
                jdbcTemplate.update(
                    """
                    INSERT INTO gl_account (
                        organisation_id, account_code, account_name, account_class,
                        account_usage, normal_balance, status, created_at, updated_at
                    )
                    VALUES (?, '1010', 'Forged', 'ASSET', 'POSTABLE', 'CREDIT', 'ACTIVE',
                            NOW(), NOW())
                    """.trimIndent(),
                    organisationId,
                )
            }
        assertTrue(
            failure.mostSpecificCause.message
                .orEmpty()
                .contains("normal_balance"),
            "expected the generated column to reject a supplied value, got: $failure",
        )
    }

    @Test
    fun `a header account can never allow manual posting`() {
        val organisationId = fixture.createOrganisation("coa-manual")

        assertViolates("chk_gl_account_manual_posting") {
            fixture.insertAccount(
                organisationId,
                "3000",
                accountClass = "EQUITY",
                usage = "HEADER",
                manualPostingAllowed = true,
            )
        }
    }

    @Test
    fun `REJECTED is not a gl account status`() {
        // The settled decision, asserted rather than assumed: rejection returns an account to
        // DRAFT so its code is reusable, and a terminal REJECTED row cannot strand a code under
        // uq_gl_account_organisation_code.
        val organisationId = fixture.createOrganisation("coa-status")

        assertViolates("chk_gl_account_status") {
            fixture.insertAccount(organisationId, "4000", status = "REJECTED")
        }
        listOf("DRAFT", "PENDING_APPROVAL", "ACTIVE", "INACTIVE").forEachIndexed { index, status ->
            fixture.insertAccount(organisationId, "500$index", status = status)
        }
    }

    @Test
    fun `an unknown account class or usage is rejected`() {
        val organisationId = fixture.createOrganisation("coa-domains")

        assertViolates("chk_gl_account_class") {
            fixture.insertAccount(organisationId, "6000", accountClass = "REVENUE")
        }
        assertViolates("chk_gl_account_usage") {
            fixture.insertAccount(organisationId, "6001", usage = "DETAIL")
        }
    }

    @Test
    fun `an account code the value object would refuse is rejected by the database`() {
        // AccountCode caps a code at 32 characters and allows only letters, digits, dots, dashes
        // and underscores. Without the matching CHECK, a row written outside the application - a
        // data migration, a bulk import, an operator fix - could hold a code the value object
        // refuses, and every later read of that tenant's chart would throw while constructing it.
        // The constraint is what makes "anything the database stored can be read back" true.
        val organisationId = fixture.createOrganisation("coa-code-shape")

        assertViolates("chk_gl_account_code") { fixture.insertAccount(organisationId, "") }
        assertViolates("chk_gl_account_code") { fixture.insertAccount(organisationId, "   ") }
        assertViolates("chk_gl_account_code") {
            fixture.insertAccount(organisationId, "1".repeat(33))
        }
        assertViolates("chk_gl_account_code") { fixture.insertAccount(organisationId, "10 10") }
        assertViolates("chk_gl_account_code") { fixture.insertAccount(organisationId, "10/10") }

        // The boundary itself is accepted, so the bound is 32 rather than 31.
        fixture.insertAccount(organisationId, "1".repeat(32))
        fixture.insertAccount(organisationId, "1000.10-A_b")
    }

    @Test
    fun `btree_gist is relocated rather than assumed absent`() {
        // The migration's DO block installs the extension into `extensions` when it is missing and
        // ALTERs it there when it already exists elsewhere. `CREATE EXTENSION IF NOT EXISTS ...
        // WITH SCHEMA extensions` would silently leave a pre-existing `public` installation where
        // it was, and the first EXCLUDE constraint would then fail the whole migration.
        //
        // This asserts the outcome. The relocation branch itself was verified against
        // postgres:18.4 in all three states - pre-installed in public, absent, already correct -
        // which Flyway cannot reproduce from inside a suite that applies the migration once.
        assertEquals("extensions", extensionSchemaOfBtreeGist())
    }

    @Test
    fun `a transition log row cannot reference another tenants account`() {
        val owner = fixture.createOrganisation("coa-log-a")
        val other = fixture.createOrganisation("coa-log-b")
        val account = fixture.insertAccount(owner, "1000", usage = "HEADER")

        assertViolates("fk_gl_account_transition_log_account") {
            jdbcTemplate.update(
                """
                INSERT INTO gl_account_transition_log (
                    organisation_id, entity_id, transition_name, status_from, status_to,
                    created_at, updated_at
                )
                VALUES (?, ?, 'approve', 'PENDING_APPROVAL', 'ACTIVE', NOW(), NOW())
                """.trimIndent(),
                other,
                account,
            )
        }
    }

    private fun extensionSchemaOfBtreeGist(): String? =
        jdbcTemplate.queryForObject(
            "SELECT n.nspname FROM pg_extension e " +
                "JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'btree_gist'",
            String::class.java,
        )
}
