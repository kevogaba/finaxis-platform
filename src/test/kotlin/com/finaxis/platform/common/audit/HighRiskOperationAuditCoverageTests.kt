package com.finaxis.platform.common.audit

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import com.finaxis.platform.lifecycle.domain.UserLifecycleTransition
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage check binding the HIGH/CRITICAL permission catalogue to real audit call sites.
 *
 * Two independent invariants are enforced, and it is worth being precise about what each buys:
 *
 * 1. Every HIGH/CRITICAL permission in the seeded catalogue has an entry in
 *    [auditedActionByPermission]. Adding a high-risk permission without deciding how it is audited
 *    fails here.
 * 2. Every action in that map is reachable from production. Explicit actions must appear as a
 *    literal at an `auditService` call site; FSM actions, which `lifecycleTransitionCommand`
 *    composes at runtime from the aggregate type and transition name, must correspond to a
 *    declared transition. Deleting an audit call, or renaming an action or a transition, fails
 *    here.
 *
 * What this does NOT do is execute each operation and assert a persisted row — that would need
 * ~40 end-to-end flows with full maker-checker setup. Per-operation audit persistence is proven by
 * the focused integration tests instead (`JooqFoundationLifecyclePersistenceTests`,
 * `HeadOfficeBootstrapIntegrationTests`, `UserProvisioningServiceTests`, and the outbox suites).
 * This test's job is to stop the *registry* and the *call sites* drifting apart.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class HighRiskOperationAuditCoverageTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `every high risk permission maps to an audited application action`() {
        val highRiskPermissions =
            jdbcTemplate
                .queryForList(
                    """
                    SELECT permission_code
                    FROM permission
                    WHERE risk_level IN ('HIGH', 'CRITICAL')
                    ORDER BY permission_code
                    """.trimIndent(),
                    String::class.java,
                ).filterNotNull()
                .sorted()

        assertEquals(highRiskPermissions, auditedActionByPermission.keys.sorted())
    }

    @Test
    fun `every mapped audit action is reachable in production`() {
        val reachable = literalAuditActions() + lifecycleDerivedAuditActions()

        val unreachable =
            auditedActionByPermission.values
                .distinct()
                .filterNot { it in reachable }
                .sorted()

        assertTrue(
            unreachable.isEmpty(),
            "mapped audit actions that no production path can emit: $unreachable - " +
                "an operation stopped auditing, or an action or transition was renamed",
        )
    }

    /** Actions written as string literals at an explicit `auditService` call site. */
    private fun literalAuditActions(): Set<String> {
        val source =
            Path
                .of("src/main/kotlin")
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .joinToString("\n") { it.readText() }
        return auditedActionByPermission.values
            .filter { source.contains("\"$it\"") }
            .toSet()
    }

    /**
     * Actions composed at runtime by `AuditService.lifecycleTransitionCommand`, which builds
     * `"${'$'}{aggregateType.lowercase()}.${'$'}{transition.lowercase()}"`. These never appear as
     * literals, so they are validated against the declared transition enums instead: removing or
     * renaming a transition breaks the mapping here.
     */
    private fun lifecycleDerivedAuditActions(): Set<String> =
        buildSet {
            fun add(
                prefix: String,
                transitions: Array<out Enum<*>>,
            ) = transitions.forEach { add("$prefix.${it.name.lowercase()}") }

            add("organisation", OrganisationLifecycleTransition.entries.toTypedArray())
            add("branch", BranchLifecycleTransition.entries.toTypedArray())
            add("user_account", UserLifecycleTransition.entries.toTypedArray())
            add("user", UserLifecycleTransition.entries.toTypedArray())
            add("membership", MembershipLifecycleTransition.entries.toTypedArray())
        }

    private companion object {
        val auditedActionByPermission =
            mapOf(
                "branch.activate" to "branch.activate",
                "branch.approve" to "branch.submit",
                "branch.close" to "branch.close",
                "branch.create" to "branch.create_draft",
                "branch.reactivate" to "branch.reactivate",
                "branch.suspend" to "branch.suspend",
                "business_date.advance" to "business_date.advance",
                "business_date.reopen" to "business_date.reopen",
                "cob.complete" to "cob.complete",
                "cob.start" to "cob.start",
                "membership.reactivate" to "membership.reactivate",
                "membership.revoke" to "membership.revoke",
                "membership.suspend" to "membership.suspend",
                "role.activate" to "role.activate",
                "role.assign_permission" to "role.assign_permission",
                "role.create" to "role.create",
                "role.deactivate" to "role.deactivate",
                "role.remove_permission" to "role.remove_permission",
                "role.update" to "role.update",
                "settings.update" to "settings.update",
                "tenant.activate" to "organisation.activate",
                "tenant.approve" to "organisation.activate",
                "tenant.bootstrap_retry" to "tenant.bootstrap_retry",
                "tenant.create" to "organisation.create_draft",
                "tenant.deprovision" to "organisation.complete_deprovisioning",
                "tenant.reactivate" to "organisation.reactivate",
                "tenant.reject" to "organisation.reject",
                "tenant.submit_for_approval" to "organisation.submit",
                "tenant.suspend" to "organisation.suspend",
                "tenant.update_draft" to "organisation.amend_draft",
                "tenant_setting.manage_platform" to "settings.update",
                "user.activate" to "user.reactivate",
                "user.approve" to "user.approve",
                "user.assign_branch" to "branch.assign_user",
                "user.assign_role" to "user.assign_role",
                "user.deactivate" to "user.deactivate",
                "user.invite" to "user.invite",
                "user.revoke_branch" to "branch.revoke_user",
                "user.revoke_role" to "user.revoke_role",
                "user.suspend" to "user.suspend",
            )
    }
}
