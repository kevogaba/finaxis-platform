package com.finaxis.platform.common.audit

/**
 * Outcome values recorded for security-sensitive or critical audit events.
 */
enum class AuditOutcome {
    /** The audited action completed successfully. */
    SUCCESS,

    /** The audited action failed after being attempted. */
    FAILURE,

    /** The audited action was denied before completion. */
    DENIED,
}
