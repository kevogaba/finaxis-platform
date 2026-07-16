package com.finaxis.platform.common.audit

/**
 * Marks a simple, non-FSM application-service method whose outcome should be audited
 * automatically. Attribute values are Spring SpEL expressions evaluated against the method's
 * arguments (by parameter name); [after] additionally binds `#result` to the method's return
 * value once it has completed.
 *
 * Reserved for simple administrative mutations with no meaningful FSM transition. Lifecycle
 * transitions must keep recording audit events explicitly through [AuditService], since a
 * transition needs an explicit reason and from/to state that this generic wrapper cannot infer.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuditedAction(
    /** Audit action name, e.g. `"settings.update"`. */
    val action: String,
    /** Audit resource type, e.g. `"ORGANISATION_SETTING"`. */
    val resourceType: String,
    /** SpEL expression resolving the tenant organisation id. Required. */
    val tenantId: String,
    /** SpEL expression resolving the resource id; blank when not applicable. */
    val resourceId: String = "",
    /** SpEL expression resolving the actor id; falls back to the ambient request actor. */
    val actorId: String = "",
    /** SpEL expression evaluated before the method runs, capturing a before-state summary. */
    val before: String = "",
    /** SpEL expression evaluated after the method returns, capturing an after-state summary. */
    val after: String = "",
    /** SpEL expression resolving a human-readable reason for the audit entry. */
    val reason: String = "",
)
