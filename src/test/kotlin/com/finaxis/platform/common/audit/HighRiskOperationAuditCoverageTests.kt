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

    @Test
    fun `every action not pending enforcement has a real call site`() {
        // The assertion the ratchet was missing, and the reason two CRITICAL fiscal-period
        // operations shipped unaudited. `actions pending enforcement have no production call site
        // yet` only looks at entries still IN pendingEnforcement, and
        // `every mapped audit action is reachable in production` uses the deliberately loose text
        // match, which any permission literal satisfies. So an entry could be DELETED from
        // pendingEnforcement — the act that claims "this is now wired" — and nothing checked that
        // it was. Discharging an entry is exactly the moment the claim needs verifying.
        val callSites = callSiteAuditActions()
        assertTrue(
            CALL_SITE_CANARY in callSites,
            "the call-site scan found nothing it should have found, so this rule would pass " +
                "vacuously - check the working directory and the scan itself",
        )

        val discharged =
            auditedActionByPermission.values
                .distinct()
                .filterNot { it in pendingEnforcement.keys }
                .filterNot { it in callSites }
                .sorted()

        assertTrue(
            discharged.isEmpty(),
            "these actions are neither pending enforcement nor emitted by any audit call site: " +
                "$discharged - either wire them, or put them back in pendingEnforcement against " +
                "the issue that will",
        )
    }

    @Test
    fun `actions pending enforcement have no production call site yet`() {
        val unmapped = pendingEnforcement.keys.filterNot { it in auditedActionByPermission.values }
        assertTrue(
            unmapped.isEmpty(),
            "pendingEnforcement names actions that are not in the registry at all: $unmapped",
        )

        // Equality, not `<=`, and the difference is the whole ratchet. With `<=`, discharging an
        // entry without lowering the constant leaves slack behind, and the next unwired high-risk
        // action can be parked in this map without failing anything - which is the opposite of what
        // the map is for. Equality makes the ceiling follow the map down and never back up.
        assertEquals(
            MAXIMUM_PENDING_ENFORCEMENT,
            pendingEnforcement.size,
            "pendingEnforcement is ${pendingEnforcement.size} and the ceiling is " +
                "$MAXIMUM_PENDING_ENFORCEMENT. If you discharged an entry, lower the ceiling to " +
                "match. If you added one, do not: it is a ratchet, not a parking space - a new " +
                "high-risk permission needs an audit call site, not another entry here.",
        )

        val callSites = callSiteAuditActions()
        assertTrue(
            CALL_SITE_CANARY in callSites,
            "the call-site scan found nothing it should have found, so this rule would pass " +
                "vacuously - check the working directory and the scan itself, not the registry",
        )

        val nowWired = pendingEnforcement.keys intersect callSites
        assertTrue(
            nowWired.isEmpty(),
            "these actions now have a real production call site - delete them from " +
                "pendingEnforcement so the registry stops claiming they are unwired: $nowWired",
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
        // Matches BOTH a raw literal and a reference through a constants object. Matching only the
        // literal is what made an earlier revision of this ratchet unable to close:
        // AccountingAuditActions exists precisely so call sites write
        // AccountingAuditActions.FISCAL_PERIOD_CLOSE rather than "fiscal_period.close", so a
        // correctly wired call site contained no literal and was never detected.
        //
        // FSM transitions compose their action at runtime from aggregate and transition names and
        // can never appear as either, so lifecycleDerivedAuditActions() is folded in as well.
        val constantNames = auditActionConstantNames()
        return auditedActionByPermission.values
            .filter { action ->
                source.contains("\"$action\"") ||
                    constantNames[action].orEmpty().any { source.contains(it) }
            }.toSet() + lifecycleDerivedAuditActions()
    }

    /**
     * Actions with a real call site.
     *
     * [literalAuditActions] is a plain text match over `src/main/kotlin`, so any file that merely
     * *names* an action satisfies it - a constants object, or a role bundle listing permission
     * codes that happen to share an action's name. That is acceptable for the reachability rule
     * while behaviour is still being built, but it cannot back a ratchet.
     *
     * So this variant asks a semantic question rather than maintaining a list of files to ignore:
     * only a file that actually calls the audit service can emit an audit action. A file that
     * never mentions `auditService` is not a call site, whatever strings it contains.
     */
    private fun callSiteAuditActions(): Set<String> {
        val source =
            Path
                .of("src/main/kotlin")
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.readText() }
                .filter { it.contains(AUDIT_SERVICE_MARKER) }
                .joinToString("\n")
        // Matches BOTH a raw literal and a reference through a constants object. Matching only the
        // literal is what made an earlier revision of this ratchet unable to close:
        // AccountingAuditActions exists precisely so call sites write
        // AccountingAuditActions.FISCAL_PERIOD_CLOSE rather than "fiscal_period.close", so a
        // correctly wired call site contained no literal and was never detected.
        //
        // FSM transitions compose their action at runtime from aggregate and transition names and
        // can never appear as either, so lifecycleDerivedAuditActions() is folded in as well.
        val constantNames = auditActionConstantNames()
        return auditedActionByPermission.values
            .filter { action ->
                source.contains("\"$action\"") ||
                    constantNames[action].orEmpty().any { source.contains(it) }
            }.toSet() + lifecycleDerivedAuditActions()
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

    @Test
    fun `the constant-reference index actually resolves declarations`() {
        // This guard exists because the regex behind it was, at first, written with doubled
        // backslashes inside a Kotlin raw string - which matches literal backslashes and therefore
        // nothing at all. The index silently returned an empty map, so the ratchet it feeds could
        // not detect a constant-referencing call site: the exact defect it had been changed to fix,
        // reintroduced invisibly. A guard whose failure mode is "matches nothing" needs a test that
        // fails when it matches nothing.
        val index = auditActionConstantNames()

        assertTrue(
            index.isNotEmpty(),
            "no `const val NAME = \"value\"` declaration was resolved, so the ratchet cannot see " +
                "a call site that references an audit action through a constants object",
        )
        assertTrue(
            "AccountingAuditActions.FISCAL_PERIOD_CLOSE" in index["fiscal_period.close"].orEmpty(),
            "expected the audit registry's declaration among the candidates, but got " +
                "${index["fiscal_period.close"]}",
        )
        assertTrue(
            index.values.none { candidates ->
                candidates.any { it.startsWith("AccountingPermissions.") }
            },
            "a permission constant must never enter this index. AccountingPermissions spells " +
                "most codes identically to their audit action, so indexing it let a file that " +
                "merely *checked* fiscal_period.close count as a call site that *audits* it - " +
                "which is how two CRITICAL operations were signed off as wired while auditing " +
                "nothing",
        )
    }

    /**
     * Maps each audit action to the `SomeAuditActions.CONSTANT` reference a call site would use for
     * it, read from source rather than hard-coded so a renamed constant stops being matched instead
     * of silently continuing to match.
     *
     * Scoped to files named `*AuditActions` on purpose, and this is the correction that matters
     * most in this suite. An earlier revision indexed **every** `const val` in production source
     * by value, and `AccountingPermissions.FISCAL_PERIOD_OPEN` holds the identical string as
     * `AccountingAuditActions.FISCAL_PERIOD_OPEN` — so a file that merely checked the *permission*
     * and happened to mention `auditService` somewhere counted as an audit call site for the
     * *action*. `FiscalPeriodLifecycleService` did exactly that: it audited only `reopen`, checked
     * `AccountingPermissions.FISCAL_PERIOD_OPEN`/`FISCAL_PERIOD_CLOSE`, and the ratchet reported
     * all three as wired. Two genuinely unaudited CRITICAL operations were signed off by a name
     * collision.
     *
     * An audit action is referenced through an `*AuditActions` registry by convention, so that is
     * what the index accepts.
     */
    private fun auditActionConstantNames(): Map<String, Set<String>> =
        Path
            .of("src/main/kotlin")
            .toFile()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.nameWithoutExtension.endsWith(AUDIT_ACTION_REGISTRY_SUFFIX) }
            .flatMap { file ->
                val objectName = file.nameWithoutExtension
                CONSTANT_DECLARATION
                    .findAll(file.readText())
                    .map { it.groupValues[2] to "$objectName.${it.groupValues[1]}" }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, references) -> references.toSet() }

    private companion object {
        /** Only a file that calls the audit service can emit an audit action. */
        const val AUDIT_SERVICE_MARKER = "auditService"

        /** Audit actions are referenced through a `*AuditActions` registry, never a permission. */
        const val AUDIT_ACTION_REGISTRY_SUFFIX = "AuditActions"

        /** `const val NAME = "value"`, capturing the constant name and the action it holds. */
        val CONSTANT_DECLARATION = Regex("""const val (\w+)\s*=\s*"([\w.]+)"""")

        /**
         * Accounting actions whose permission is seeded but whose emitting behaviour is not built
         * yet, each against the issue that will wire it. Issue #34 seeds the catalogue ahead of the
         * accounting schema so Phase B does not add codes piecemeal, which necessarily creates this
         * gap.
         *
         * The set can only shrink, and that is enforced two ways: [MAXIMUM_PENDING_ENFORCEMENT]
         * stops it being widened by hand, and the moment an action gains a real call site -
         * whether written as a literal, through a constants object, or composed by an FSM
         * transition - `actions pending enforcement have no production call site yet` fails until
         * its entry is deleted. It must reach empty before the accounting readiness gate (#54).
         */
        val pendingEnforcement =
            mapOf(
                "journal.create_manual" to "#48",
                "journal.approve" to "#48",
            )

        /**
         * The ratchet may only shrink. Without this, a new HIGH/CRITICAL permission shipped with
         * no audit at all could be waved through by *adding* an entry here, which is the opposite
         * of what this map is for. Lower it as entries are discharged; never raise it.
         *
         * Asserted as an **equality** against the map size, so a discharge that forgets to lower
         * this constant fails rather than quietly banking slack for the next unwired action.
         */
        const val MAXIMUM_PENDING_ENFORCEMENT = 2

        /** A wired action the scan must always find; its absence means the scan is broken. */
        const val CALL_SITE_CANARY = "settings.update"

        val auditedActionByPermission =
            mapOf(
                "branch.activate" to "branch.activate",
                "fiscal_period.close" to "fiscal_period.close",
                "fiscal_period.open" to "fiscal_period.open",
                "fiscal_period.reopen" to "fiscal_period.reopen",
                "gl_account.approve" to "gl_account.approve",
                "gl_account.deactivate" to "gl_account.deactivate",
                "journal.approve" to "journal.approve",
                "journal.create_manual" to "journal.create_manual",
                "journal.post_prior_period" to "journal.post_prior_period",
                "journal.reverse" to "journal.reverse",
                "posting_rule.approve" to "posting_rule.approve",
                "posting_rule.create" to "posting_rule.create",
                "posting_rule.update" to "posting_rule.create_version",
                "reconciliation.resolve" to "reconciliation.resolve",
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
