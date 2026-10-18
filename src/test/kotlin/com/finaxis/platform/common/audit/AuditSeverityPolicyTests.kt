package com.finaxis.platform.common.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AuditSeverityPolicyTests {
    @Test
    fun `a failure is high and a refusal by a rule is medium whatever the action`() {
        assertEquals(
            AuditSeverity.HIGH,
            AuditSeverityPolicy.resolve("tenant.bootstrap_retry", AuditOutcome.FAILURE),
        )
        assertEquals(
            AuditSeverity.HIGH,
            AuditSeverityPolicy.resolve("user.view", AuditOutcome.FAILURE),
        )
        assertEquals(
            AuditSeverity.MEDIUM,
            AuditSeverityPolicy.resolve("organisation.activate", AuditOutcome.DENIED),
        )
    }

    @Test
    fun `removing access or granting authority is high on success`() {
        listOf(
            "organisation.suspend",
            "organisation.start_deprovisioning",
            "organisation.deprovision_assignment_revoked",
            "branch.close",
            "membership.revoke",
            "user.deactivate",
            "user.start_deactivation",
            "user.revoke_role",
            "role.assign_permission",
            "role.remove_permission",
            "membership.assignments_revoked",
            "branch.self_approve",
        ).forEach { action ->
            assertEquals(
                AuditSeverity.HIGH,
                AuditSeverityPolicy.resolve(action, AuditOutcome.SUCCESS),
                action,
            )
        }
    }

    @Test
    fun `deciding or granting access is medium on success`() {
        listOf(
            "organisation.activate",
            "branch.approve",
            "membership.reactivate",
            "organisation.reject",
            "organisation.return_for_changes",
            "user.assign_role",
            "user.assign_branch",
            "user.invite",
        ).forEach { action ->
            assertEquals(
                AuditSeverity.MEDIUM,
                AuditSeverityPolicy.resolve(action, AuditOutcome.SUCCESS),
                action,
            )
        }
    }

    @Test
    fun `routine reads, edits and submissions stay info`() {
        listOf(
            "user.update",
            "membership.update",
            "organisation.submit",
            "branch.create_draft",
            "role.create",
            "auth.select_branch",
            "user.invitation_resend",
        ).forEach { action ->
            assertEquals(
                AuditSeverity.INFO,
                AuditSeverityPolicy.resolve(action, AuditOutcome.SUCCESS),
                action,
            )
        }
    }
}
