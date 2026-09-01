package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Separation-of-duties policy for the default accounting role bundles.
 *
 * A point worth being precise about, because it is easy to get backwards: separation of duties is
 * enforced by **actor identity at the transition** — the approver must differ from the submitter,
 * as `UserProvisioningService.approveUser` already enforces and as the accounting FSM guards will
 * — **not** by splitting permissions across roles. A role holding both `submit` and `approve` is
 * therefore legitimate: it means a holder may perform either act on *different* records, not both
 * acts on the same one. `TENANT_ADMIN` deliberately holds both, exactly as the bootstrap
 * `local-admin` role does, so a two-actor flow works out of the box.
 *
 * What these tests protect is narrower and still worth protecting: the two *dedicated* maker and
 * checker roles must genuinely model the split, and no default bundle may carry a break-glass code.
 * Runtime authorization never evaluates a role name, so these bundles are conveniences rather than
 * a security boundary.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AccountingSeparationOfDutiesPolicyTests(
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `the dedicated maker and checker roles hold opposite sides of every approval`() {
        val organisationId = fixture.createActiveOrganisation("sod", ACTOR_ID)
        val bundles = defaultRoleBundles(organisationId)
        val maker = bundles.getValue("ACCOUNTING_OPERATOR")
        val checker = bundles.getValue("ACCOUNTING_APPROVER")

        val violations =
            MAKER_CHECKER_PAIRS.flatMap { (submitCode, approveCode) ->
                buildList {
                    if (approveCode in maker) add("ACCOUNTING_OPERATOR holds $approveCode")
                    if (submitCode in checker) add("ACCOUNTING_APPROVER holds $submitCode")
                }
            }

        assertTrue(
            violations.isEmpty(),
            "the dedicated maker and checker roles must not overlap on an approval: $violations",
        )
    }

    @Test
    fun `no default role carries a break-glass accounting permission`() {
        val organisationId = fixture.createActiveOrganisation("sod-breakglass", ACTOR_ID)
        val bundles = defaultRoleBundles(organisationId)

        val violations =
            bundles
                .mapValues { (_, codes) -> codes intersect AccountingPermissions.BREAK_GLASS }
                .filterValues { it.isNotEmpty() }

        assertEquals(
            emptyMap(),
            violations,
            "reopening a closed period and posting into a prior one are granted deliberately per " +
                "tenant, never inherited from a default bundle",
        )
    }

    @Test
    fun `the maker and checker accounting roles are provisioned`() {
        val organisationId = fixture.createActiveOrganisation("sod-roles", ACTOR_ID)
        val bundles = defaultRoleBundles(organisationId)

        assertTrue("ACCOUNTING_OPERATOR" in bundles, "expected the accounting maker role")
        assertTrue("ACCOUNTING_APPROVER" in bundles, "expected the accounting checker role")
        assertTrue(
            AccountingPermissions.JOURNAL_APPROVE in bundles.getValue("ACCOUNTING_APPROVER"),
            "the checker role must be able to approve a journal",
        )
        assertTrue(
            AccountingPermissions.JOURNAL_APPROVE !in bundles.getValue("ACCOUNTING_OPERATOR"),
            "the maker role must not be able to approve a journal",
        )
    }

    @Test
    fun `every break-glass enforcement site also records an audit event`() {
        // A break-glass control whose exercise leaves no record is the finding a bank auditor
        // leads with. Enforcement and audit were split across issues once already - #35 shipped
        // the prior-period permission check while the audit was deferred to the issue that
        // shipped it - so this binds them: any production file that enforces a break-glass code
        // must also call the audit service.
        //
        // Deliberately a source scan rather than a runtime assertion, because the classes
        // involved have no beans until issue #36 wires them.
        val offenders =
            Path
                .of(PRODUCTION_SOURCE_ROOT)
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val text = file.readText()
                    AccountingPermissions.BREAK_GLASS.any { code ->
                        ENFORCEMENT_MARKER.containsMatchIn(text) &&
                            (text.contains("\"$code\"") || text.contains(constantName(code)))
                    } &&
                        !AUDIT_MARKER.containsMatchIn(text)
                }.map { it.name }
                .toList()

        assertEquals(
            emptyList(),
            offenders,
            "these files enforce a break-glass permission without recording its use: $offenders",
        )
    }

    @Test
    fun `the maker role can actually make and the checker cannot`() {
        // Every other assertion in this class is negative - no role holds both sides, no bundle
        // holds break-glass. All of them pass for an ACCOUNTING_OPERATOR that holds nothing at
        // all, which would ship a maker role that can make nothing. This is the positive control.
        val organisationId = fixture.createActiveOrganisation("sod-positive", ACTOR_ID)
        val bundles = defaultRoleBundles(organisationId)
        val maker = bundles.getValue("ACCOUNTING_OPERATOR")
        val checker = bundles.getValue("ACCOUNTING_APPROVER")

        listOf(
            AccountingPermissions.GL_ACCOUNT_SUBMIT,
            AccountingPermissions.JOURNAL_SUBMIT,
            AccountingPermissions.POSTING_RULE_SUBMIT,
            AccountingPermissions.JOURNAL_CREATE_MANUAL,
        ).forEach {
            assertTrue(it in maker, "the maker role must be able to prepare work: missing $it")
        }

        assertTrue(
            AccountingPermissions.JOURNAL_CREATE_MANUAL !in checker,
            "the checker must not also be able to originate the journal it approves",
        )
    }

    @Test
    fun `every accounting code named by a default bundle exists and is active`() {
        // The two sides must come from independent sources. Reading `granted` back out of
        // role_permission and diffing it against the catalogue cannot fail: grantPermissions only
        // ever inserts rows for codes that already exist and are ACTIVE, so a typo in a bundle
        // produces no row and silently leaves the diff empty - exactly the ADR 0010 failure this
        // test claims to catch. The expected side is therefore the constants the bundles are
        // built from, and the actual side is the seeded catalogue.
        val organisationId = fixture.createActiveOrganisation("sod-codes", ACTOR_ID)
        val granted = defaultRoleBundles(organisationId).values.flatten().toSet()

        val active =
            dsl
                .select(PERMISSION.PERMISSION_CODE)
                .from(PERMISSION)
                .where(PERMISSION.STATUS.eq("ACTIVE"))
                .fetchSet(PERMISSION.PERMISSION_CODE)
                .filterNotNull()
                .toSet()

        assertEquals(
            emptySet(),
            AccountingPermissions.ALL - active,
            "an accounting code the bundles are built from is missing or inactive in the " +
                "catalogue, so any bundle naming it grants nothing - the ADR 0010 failure mode",
        )
        assertEquals(
            emptySet(),
            granted.filter { it.startsWith("gl_account.") || it.startsWith("journal.") }.toSet() -
                AccountingPermissions.ALL,
            "a bundle granted an accounting-shaped code that is not in AccountingPermissions",
        )
    }

    private fun defaultRoleBundles(organisationId: UUID): Map<String, Set<String>> =
        dsl
            .select(ROLE.ROLE_CODE, PERMISSION.PERMISSION_CODE)
            .from(ROLE_PERMISSION)
            .join(ROLE)
            .on(ROLE.ID.eq(ROLE_PERMISSION.ROLE_ID))
            .join(PERMISSION)
            .on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
            .where(ROLE.ORGANISATION_ID.eq(organisationId))
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })
            .mapValues { (_, codes) -> codes.toSet() }

    private fun constantName(code: String): String =
        "AccountingPermissions." + code.uppercase().replace('.', '_')

    private companion object {
        const val PRODUCTION_SOURCE_ROOT = "src/main/kotlin"

        /** A call that actually enforces a permission, as opposed to merely naming a code. */
        val ENFORCEMENT_MARKER = Regex("""require(BreakGlass|Tenant|Branch)Permission\s*\(""")

        /**
         * An actual audit **call**, not merely an injected dependency.
         *
         * Matching the bare identifier was the first attempt, and mutation testing showed it
         * passes for a class that injects `AuditService` and never calls it - the constructor
         * property satisfies the match. That is the same false-negative this suite exists to
         * catch elsewhere.
         */
        val AUDIT_MARKER = Regex("""auditService\.record\w*\s*\(""")
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        /**
         * Submit code to approve code, for each accounting approval flow.
         *
         * Fiscal-period open and close are deliberately absent: they are two operations in a
         * lifecycle, not an approval of the same act, so one role legitimately holds both.
         */
        val MAKER_CHECKER_PAIRS =
            listOf(
                AccountingPermissions.GL_ACCOUNT_SUBMIT to AccountingPermissions.GL_ACCOUNT_APPROVE,
                AccountingPermissions.JOURNAL_SUBMIT to AccountingPermissions.JOURNAL_APPROVE,
                AccountingPermissions.POSTING_RULE_SUBMIT to
                    AccountingPermissions.POSTING_RULE_APPROVE,
            )
    }
}
