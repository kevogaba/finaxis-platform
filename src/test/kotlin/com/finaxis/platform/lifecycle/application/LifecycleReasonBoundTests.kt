package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.web.scanPlatformTypes
import org.junit.jupiter.api.Test
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Defence in depth for #206: a lifecycle command carries its reason as a value object whose
 * constructors enforce the bound, and the TYPE says whether it is required, so an adapter that
 * skips the REST DTO (a job, a listener, another module) can neither pass an over-long text nor
 * leave out, blank or shorten a reason the transition cannot go without.
 */
class LifecycleReasonBoundTests {
    @Test
    fun `a command that requires a reason takes a non-null Reason`() {
        val offenders =
            commandsWithReason()
                .filter { it.simpleName in REQUIRED_REASON_COMMANDS }
                .filterNot { type ->
                    reasonField(type).type == Reason::class.java && !isNullable(type)
                }.map { it.simpleName }

        assertEquals(emptyList(), offenders, "These commands must take a non-null Reason")
    }

    @Test
    fun `a command whose remark is optional takes a nullable DecisionRemark`() {
        val offenders =
            commandsWithReason()
                .filterNot { it.simpleName in REQUIRED_REASON_COMMANDS }
                .filterNot { it.simpleName in UNBOUNDED_REASON_COMMANDS }
                .filterNot { type ->
                    reasonField(type).type == DecisionRemark::class.java && isNullable(type)
                }.map { it.simpleName }

        assertEquals(emptyList(), offenders, "These commands must take a DecisionRemark?")
    }

    @Test
    fun `the allowlists name real commands`() {
        val existing = commandsWithReason().map { it.simpleName }.toSet()

        assertTrue(
            existing.containsAll(UNBOUNDED_REASON_COMMANDS + REQUIRED_REASON_COMMANDS),
            "Stale entries: ${(UNBOUNDED_REASON_COMMANDS + REQUIRED_REASON_COMMANDS) - existing}",
        )
    }

    private fun reasonField(type: Class<*>) = type.declaredFields.first { it.name == "reason" }

    private fun isNullable(type: Class<*>): Boolean =
        type.kotlin.primaryConstructor!!
            .parameters
            .first { it.name == "reason" }
            .type.isMarkedNullable

    private fun commandsWithReason(): List<Class<*>> =
        scanPlatformTypes()
            .filter { it.packageName == "com.finaxis.platform.lifecycle.application" }
            .filter { it.simpleName.endsWith("Command") }
            .filter { type -> type.declaredFields.any { it.name == "reason" } }

    private companion object {
        /** Transitions that cannot go without a justification. */
        val REQUIRED_REASON_COMMANDS =
            setOf(
                "RejectOrganisationProvisioningCommand",
                "ReturnOrganisationForChangesCommand",
                "SuspendOrganisationCommand",
                "DeprovisionOrganisationCommand",
                "ReturnBranchCommand",
                "SuspendBranchCommand",
                "CloseBranchCommand",
                "SuspendUserCommand",
                "DeactivateUserCommand",
                "RevokeTenantMembershipCommand",
                "SuspendMembershipCommand",
            )

        /** Business date and tenant setting reasons are out of scope for #206. */
        val UNBOUNDED_REASON_COMMANDS =
            setOf(
                "AdvanceBusinessDateCommand",
                "InitializeBusinessDateCommand",
                "StartCobCommand",
                "CompleteCobCommand",
                "ReopenBusinessDateCommand",
                "CreateOrUpdateTenantSettingCommand",
                "DeactivateTenantSettingCommand",
            )
    }
}
