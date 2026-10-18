package com.finaxis.platform.common.audit

/**
 * The severity an audit row carries when its writer names none (#184). A writer that knows better
 * passes [AuditCommand.severity] and the policy is not consulted; the table is documented in
 * `docs/architecture/audit-logging.md` ("Severity policy"), so change both together.
 *
 * The outcome decides first: a failure is [AuditSeverity.HIGH] (something the caller asked for
 * broke), a refusal by a rule [AuditSeverity.MEDIUM]. A success is judged by the action's verb,
 * the part after the last `.`: removing or ending access and granting authority is HIGH, deciding
 * or granting access is MEDIUM, everything else is INFO.
 */
object AuditSeverityPolicy {
    private val HIGH_VERBS =
        setOf(
            "suspend",
            "suspend_draft",
            "suspend_pending_approval",
            "confirm_suspended",
            "close",
            "close_suspended",
            "lock",
            "deactivate",
            "start_deactivation",
            "complete_deactivation",
            "deprovision",
            "start_deprovisioning",
            "start_suspended_deprovisioning",
            "complete_deprovisioning",
            "revoke",
            "revoke_pending",
            "revoke_suspended",
            "revoke_role",
            "revoke_branch",
            "revoke_user",
            "assign_permission",
            "remove_permission",
            "assignments_revoked",
            "self_approve",
            "archive",
        )
    private val MEDIUM_VERBS =
        setOf(
            "approve",
            "activate",
            "reactivate",
            "unlock",
            "reject",
            "return_for_changes",
            "invite",
            "assign_role",
            "assign_branch",
            "assign_user",
            "update_permission",
        )

    /** The severity of an event with this [action] and [outcome]. */
    fun resolve(
        action: String,
        outcome: AuditOutcome,
    ): AuditSeverity =
        when (outcome) {
            AuditOutcome.FAILURE -> AuditSeverity.HIGH
            AuditOutcome.DENIED -> AuditSeverity.MEDIUM
            AuditOutcome.SUCCESS -> forSuccess(action.substringAfterLast('.').lowercase())
        }

    private fun forSuccess(verb: String): AuditSeverity =
        when {
            verb in HIGH_VERBS || verb.endsWith("_revoked") -> AuditSeverity.HIGH
            verb in MEDIUM_VERBS -> AuditSeverity.MEDIUM
            else -> AuditSeverity.INFO
        }
}
