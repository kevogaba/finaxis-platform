package com.finaxis.platform.common.audit

/**
 * Command used by application services or listeners to record an audit event.
 */
data class AuditCommand(
    val actorType: String,
    val actorId: String?,
    val tenantId: String?,
    val action: String,
    val resourceType: String,
    val resourceId: String?,
    val outcome: AuditOutcome,
    val branchId: String? = null,
    /** The user the event is ABOUT, never the actor by default; null when it is about no user. */
    val subjectUserId: String? = null,
    /** Null lets [AuditSeverityPolicy] choose from the action and outcome (#184). */
    val severity: AuditSeverity? = null,
    val reason: String? = null,
    val requestId: String? = null,
    val sourceIp: String? = null,
    val userAgent: String? = null,
    val before: Map<String, Any?>? = null,
    val after: Map<String, Any?>? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)
