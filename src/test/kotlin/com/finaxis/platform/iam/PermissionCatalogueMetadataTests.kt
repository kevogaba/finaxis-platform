package com.finaxis.platform.iam

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.lifecycle.adapter.outbound.persistence.OrganisationBootstrapDefaults
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The permission catalogue's own metadata, as `V22__permission_catalogue_metadata.sql` migrated
 * it (ADR 0030, decisions 7 and 8): every code's `kind` and `grant_scope`, and the
 * `permission_view_requirement` pairings of each mutation with the views it implies.
 *
 * A kind constraint cannot be a `CHECK` across two tables and the repository admits no trigger
 * (ADR 0024), so this test is where the kinds are enforced. A new permission migration that adds a
 * code and forgets to classify or pair it fails here. It is also STRICT about the seeded role
 * bundles: any platform role or code-built default bundle that holds a mutation without the views
 * it requires fails it, as does any such violation by the bootstrap `local-admin` role outside its
 * explicit allow-list. (The runtime does not yet refuse such a role; a later change does.)
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PermissionCatalogueMetadataTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val catalogue: Map<String, CatalogueRow> by lazy { loadCatalogue() }

    private val requirements: Map<String, Set<String>> by lazy { loadRequirements() }

    @Test
    fun `every permission row carries a kind and a grant scope`() {
        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permission WHERE kind IS NULL OR grant_scope IS NULL",
                Long::class.java,
            ),
        )
        assertEquals(CATALOGUE_SIZE, catalogue.size, "the live catalogue is 81 codes")
        assertTrue(catalogue.values.all { it.kind in KINDS && it.grantScope in SCOPES })
    }

    @Test
    fun `the catalogue holds eighteen views, three context codes and sixty mutations`() {
        val byKind = catalogue.values.groupingBy { it.kind }.eachCount()

        assertEquals(VIEW_COUNT, byKind["VIEW"])
        assertEquals(CONTEXT_COUNT, byKind["CONTEXT"])
        assertEquals(MUTATION_COUNT, byKind["MUTATION"])
    }

    @Test
    fun `the context codes are the two selections and the platform setting`() {
        assertEquals(
            setOf(
                "auth.select_organisation",
                "auth.select_branch",
                "tenant_setting.manage_platform",
            ),
            codesOfKind("CONTEXT"),
        )
    }

    @Test
    fun `a view is a code that ends in view, or the own profile read, and nothing else is`() {
        val expected =
            catalogue.keys.filter { it.endsWith(".view") || it == "iam.profile.read" }.toSet()

        assertEquals(expected, codesOfKind("VIEW"))
    }

    @Test
    fun `fourteen codes are platform scope and sixty seven are tenant scope`() {
        assertEquals(PLATFORM_CODES, codesOfScope("PLATFORM"))
        assertEquals(TENANT_SCOPE_COUNT, codesOfScope("TENANT").size)
        assertEquals(PLATFORM_SCOPE_COUNT, codesOfScope("PLATFORM").size)
    }

    @Test
    fun `every mutation requires at least one view and a view or context code requires none`() {
        val unpaired = codesOfKind("MUTATION").filter { requirements[it].isNullOrEmpty() }
        val paired = (codesOfKind("VIEW") + codesOfKind("CONTEXT")).filter { it in requirements }

        assertEquals(emptyList(), unpaired.sorted(), "mutations with no required view")
        assertEquals(emptyList(), paired.sorted(), "view or context codes that require something")
    }

    @Test
    fun `every required permission is a view`() {
        val nonViews =
            requirements.flatMap { (code, views) ->
                views.filter { catalogue.getValue(it).kind != "VIEW" }.map { "$code -> $it" }
            }

        assertEquals(emptyList(), nonViews.sorted())
    }

    @Test
    fun `an active mutation requires only active views`() {
        val inactive =
            requirements.flatMap { (code, views) ->
                if (catalogue.getValue(code).status != "ACTIVE") {
                    emptyList()
                } else {
                    views
                        .filter { catalogue.getValue(it).status != "ACTIVE" }
                        .map { "$code -> $it" }
                }
            }

        assertEquals(emptyList(), inactive.sorted())
    }

    @Test
    fun `a deprecated mutation may still be paired with a view`() {
        assertEquals("DEPRECATED", catalogue.getValue("branch.activate").status)
        assertEquals(setOf("branch.view"), requirements["branch.activate"])
    }

    @Test
    fun `the full pairing table is locked, so moving a pairing fails`() {
        assertEquals(EXPECTED_REQUIREMENTS.toSortedMap(), requirements.toSortedMap())
        assertEquals(MUTATION_COUNT, EXPECTED_REQUIREMENTS.size)
        assertEquals(MUTATION_COUNT + 1, EXPECTED_REQUIREMENTS.values.sumOf { it.size })
    }

    @Test
    fun `the owner's accepted pairings are exactly the seeded ones`() {
        assertEquals(setOf("membership.view", "user.view"), requirements["user.invite"])
        assertEquals(setOf("branch_assignment.view"), requirements["user.assign_branch"])
        assertEquals(setOf("branch_assignment.view"), requirements["user.revoke_branch"])
        assertEquals(setOf("role_assignment.view"), requirements["user.assign_role"])
        assertEquals(setOf("role_assignment.view"), requirements["user.revoke_role"])
        assertEquals(setOf("role.view"), requirements["role.assign_permission"])
        assertEquals(setOf("role.view"), requirements["role.remove_permission"])
        assertEquals(setOf("tenant.view"), requirements["tenant.activate"])
        assertEquals(setOf("branch.view"), requirements["branch.approve"])
        assertEquals(setOf("journal.view"), requirements["journal.post_prior_period"])
        assertEquals(setOf("journal.view"), requirements["journal.create_manual"])
        assertEquals(setOf("posting_rule.view"), requirements["posting_rule.create"])
        assertEquals(setOf("posting_rule.view"), requirements["posting_rule.update"])
        assertEquals(setOf("membership.view"), requirements["user.approve"])
        assertEquals(setOf("business_date.view"), requirements["cob.start"])
        assertEquals(
            MUTATION_COUNT + 1,
            requirements.values.sumOf { it.size },
            "one pairing per mutation, plus the second view of user.invite",
        )
    }

    @Test
    fun `platform roles seeded by migration hold no mutation without its views`() {
        assertEquals(
            emptyMap(),
            violations(heldBySeededRole(PLATFORM_SUPER_ADMIN_ID)),
            "PLATFORM_SUPER_ADMIN",
        )
        assertEquals(
            emptyMap(),
            violations(heldBySeededRole(PLATFORM_SUPPORT_ID)),
            "PLATFORM_SUPPORT",
        )
    }

    @Test
    fun `every default role bundle built in code holds a mutation only with its views`() {
        OrganisationBootstrapDefaults.ROLE_PERMISSIONS.forEach { (role, codes) ->
            assertEquals(
                emptyList(),
                codes.filter { it !in catalogue },
                "$role names a code that is not in the catalogue",
            )
        }
        val byRole =
            OrganisationBootstrapDefaults.ROLE_PERMISSIONS.mapValues { (_, codes) ->
                violations(codes.filter { catalogue[it]?.status == "ACTIVE" }.toSet())
            }

        assertEquals(
            OrganisationBootstrapDefaults.ROLE_PERMISSIONS.keys
                .associateWith { emptyMap<String, Set<String>>() },
            byRole,
        )
    }

    @Test
    fun `the bootstrap tenant administrator role is checked against an explicit allow-list`() {
        // STRICT, with an allow-list. The runtime does not yet refuse a mutation without its view
        // (ADR 0030, a later pull request), so a known violation by this one seeded role may be
        // recorded here instead of failing the build. Today local-admin (V3, V4, V5) pairs every
        // mutation it holds with its views, so the allow-list is empty and any violation fails.
        // Its other known gap is the codes it lacks (the audit report, section 3.1), which is a
        // different defect and which a later change corrects. If that change or a new grant
        // leaves a violation, add it here with a comment, so the list shrinks again instead of
        // the test being switched off.
        assertEquals(
            LOCAL_ADMIN_ALLOWED_VIOLATIONS,
            violations(heldBySeededRole(LOCAL_ADMIN_ROLE_ID)),
            "bootstrap tenant local-admin: a violation outside the allow-list, or one fixed " +
                "but still listed",
        )
    }

    private fun violations(held: Set<String>): Map<String, Set<String>> =
        requirements
            .filterKeys { it in held }
            .flatMap { (mutation, views) -> (views - held).map { view -> view to mutation } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
            .toSortedMap()

    private fun heldBySeededRole(roleId: String): Set<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT p.permission_code
                FROM role_permission rp
                JOIN permission p ON p.id = rp.permission_id AND p.status = 'ACTIVE'
                WHERE rp.role_id = ?::uuid
                """.trimIndent(),
                String::class.java,
                roleId,
            ).filterNotNull()
            .toSet()

    private fun codesOfKind(kind: String): Set<String> =
        catalogue.filterValues { it.kind == kind }.keys

    private fun codesOfScope(scope: String): Set<String> =
        catalogue.filterValues { it.grantScope == scope }.keys

    private fun loadCatalogue(): Map<String, CatalogueRow> =
        jdbcTemplate
            .queryForList("SELECT permission_code, kind, grant_scope, status FROM permission")
            .associate {
                it["permission_code"] as String to
                    CatalogueRow(
                        it["kind"] as String?,
                        it["grant_scope"] as String?,
                        it["status"] as String,
                    )
            }

    private fun loadRequirements(): Map<String, Set<String>> =
        jdbcTemplate
            .queryForList(
                """
                SELECT m.permission_code AS mutation, v.permission_code AS view
                FROM permission_view_requirement r
                JOIN permission m ON m.id = r.permission_id
                JOIN permission v ON v.id = r.required_view_permission_id
                """.trimIndent(),
            ).groupBy({ it["mutation"] as String }, { it["view"] as String })
            .mapValues { it.value.toSet() }

    private data class CatalogueRow(
        val kind: String?,
        val grantScope: String?,
        val status: String,
    )

    private companion object {
        const val CATALOGUE_SIZE = 81
        const val VIEW_COUNT = 18
        const val CONTEXT_COUNT = 3
        const val MUTATION_COUNT = 60
        const val PLATFORM_SCOPE_COUNT = 14
        const val TENANT_SCOPE_COUNT = 67

        /** The complete pairing table of `V22`: mutation code to the views it requires. */
        val EXPECTED_REQUIREMENTS: Map<String, Set<String>> =
            mapOf(
                "tenant.create" to setOf("tenant.view"),
                "tenant.update_draft" to setOf("tenant.view"),
                "tenant.submit_for_approval" to setOf("tenant.view"),
                "tenant.approve" to setOf("tenant.view"),
                "tenant.reject" to setOf("tenant.view"),
                "tenant.activate" to setOf("tenant.view"),
                "tenant.suspend" to setOf("tenant.view"),
                "tenant.reactivate" to setOf("tenant.view"),
                "tenant.deprovision" to setOf("tenant.view"),
                "tenant.bootstrap_retry" to setOf("tenant.view"),
                "branch.create" to setOf("branch.view"),
                "branch.update" to setOf("branch.view"),
                "branch.approve" to setOf("branch.view"),
                "branch.activate" to setOf("branch.view"),
                "branch.suspend" to setOf("branch.view"),
                "branch.reactivate" to setOf("branch.view"),
                "branch.close" to setOf("branch.view"),
                "user.invite" to setOf("membership.view", "user.view"),
                "user.approve" to setOf("membership.view"),
                "user.activate" to setOf("user.view"),
                "user.suspend" to setOf("user.view"),
                "user.deactivate" to setOf("user.view"),
                "user.assign_branch" to setOf("branch_assignment.view"),
                "user.revoke_branch" to setOf("branch_assignment.view"),
                "user.assign_role" to setOf("role_assignment.view"),
                "user.revoke_role" to setOf("role_assignment.view"),
                "membership.suspend" to setOf("membership.view"),
                "membership.reactivate" to setOf("membership.view"),
                "membership.revoke" to setOf("membership.view"),
                "role.create" to setOf("role.view"),
                "role.update" to setOf("role.view"),
                "role.activate" to setOf("role.view"),
                "role.deactivate" to setOf("role.view"),
                "role.assign_permission" to setOf("role.view"),
                "role.remove_permission" to setOf("role.view"),
                "settings.update" to setOf("settings.view"),
                "business_date.advance" to setOf("business_date.view"),
                "business_date.reopen" to setOf("business_date.view"),
                "cob.start" to setOf("business_date.view"),
                "cob.complete" to setOf("business_date.view"),
                "gl_account.create" to setOf("gl_account.view"),
                "gl_account.update" to setOf("gl_account.view"),
                "gl_account.submit" to setOf("gl_account.view"),
                "gl_account.approve" to setOf("gl_account.view"),
                "gl_account.deactivate" to setOf("gl_account.view"),
                "fiscal_period.open" to setOf("fiscal_period.view"),
                "fiscal_period.close" to setOf("fiscal_period.view"),
                "fiscal_period.reopen" to setOf("fiscal_period.view"),
                "journal.create_manual" to setOf("journal.view"),
                "journal.submit" to setOf("journal.view"),
                "journal.approve" to setOf("journal.view"),
                "journal.reverse" to setOf("journal.view"),
                "journal.post_prior_period" to setOf("journal.view"),
                "posting_rule.create" to setOf("posting_rule.view"),
                "posting_rule.update" to setOf("posting_rule.view"),
                "posting_rule.submit" to setOf("posting_rule.view"),
                "posting_rule.approve" to setOf("posting_rule.view"),
                "reconciliation.run" to setOf("reconciliation.view"),
                "reconciliation.resolve" to setOf("reconciliation.view"),
                "accounting_report.export" to setOf("accounting_report.view"),
            )

        val KINDS = setOf("VIEW", "MUTATION", "CONTEXT")
        val SCOPES = setOf("TENANT", "PLATFORM")

        const val PLATFORM_SUPER_ADMIN_ID = "50000000-0000-0000-0000-000000000001"
        const val PLATFORM_SUPPORT_ID = "50000000-0000-0000-0000-000000000002"
        const val LOCAL_ADMIN_ROLE_ID = "77777777-7777-7777-7777-777777777777"

        /** The bootstrap `local-admin` role's violations, view to the mutations lacking it. */
        val LOCAL_ADMIN_ALLOWED_VIOLATIONS: Map<String, Set<String>> = emptyMap()

        val PLATFORM_CODES =
            setOf(
                "tenant.create",
                "tenant.update_draft",
                "tenant.submit_for_approval",
                "tenant.approve",
                "tenant.reject",
                "tenant.activate",
                "tenant.suspend",
                "tenant.reactivate",
                "tenant.deprovision",
                "tenant.bootstrap_retry",
                "user.activate",
                "user.suspend",
                "user.deactivate",
                "tenant_setting.manage_platform",
            )
    }
}
