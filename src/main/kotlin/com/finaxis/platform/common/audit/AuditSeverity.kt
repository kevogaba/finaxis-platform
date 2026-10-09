package com.finaxis.platform.common.audit

/**
 * Severity classification for an audit event, mirroring the `audit_event.severity` check
 * constraint. The declaration order is the severity order, lowest first: the audit search's
 * `min_severity` filter compares by it, so do not reorder the constants.
 */
enum class AuditSeverity {
    /** Routine successful activity. */
    INFO,

    /** Notable but low-risk activity. */
    LOW,

    /** Activity that warrants operator attention. */
    MEDIUM,

    /** Security-sensitive activity such as a denied access attempt. */
    HIGH,

    /** Activity that requires immediate investigation. */
    CRITICAL,
}
