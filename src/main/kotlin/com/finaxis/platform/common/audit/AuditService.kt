package com.finaxis.platform.common.audit

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Application service for recording audit events without coupling callers to storage details.
 * [record] is the single choke point: every convenience method funnels through it so redaction
 * always applies before an event reaches its repository.
 *
 * Every helper takes an optional [subjectUserId]: the user the event is ABOUT, which is never
 * defaulted to the actor (ADR 0031; #186). Pass it on every event about one user; leave it null
 * for an event about no user.
 *
 * Excluded from Modulith proxying via [Role]: in 2.1.0, rendering the default-parameter bridge
 * signatures generated for methods like [recordLifecycleTransition] recurses indefinitely, the same
 * observability-proxy issue already worked around for `TransitionExecutor`.
 */
@Service
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
class AuditService(
    private val repository: AuditEventRepository,
    private val clock: Clock,
) {
    /** Records the supplied audit command as an immutable, redacted audit event. */
    fun record(command: AuditCommand): AuditEvent = persist(command)

    /**
     * Records the supplied audit command in a new, independent transaction, so the row survives
     * even when the caller's own transaction is later rolled back. Required for FSM
     * rejection/failure audits: [FoundationLifecycleService][com.finaxis.platform.lifecycle
     * .application.FoundationLifecycleService] records these from inside the same `@Transactional`
     * `transition(...)` call that then rethrows, which would otherwise roll the audit row back too.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordIndependently(command: AuditCommand): AuditEvent = persist(command)

    private fun persist(command: AuditCommand): AuditEvent {
        val event =
            AuditEvent(
                id = uuidV7(),
                actorType = command.actorType,
                actorId = command.actorId,
                tenantId = command.tenantId,
                branchId = command.branchId,
                subjectUserId = command.subjectUserId,
                action = command.action,
                resourceType = command.resourceType,
                resourceId = command.resourceId,
                outcome = command.outcome,
                severity = command.severity,
                reason = command.reason,
                requestId = command.requestId,
                sourceIp = command.sourceIp,
                userAgent = command.userAgent,
                before = command.before?.let(SensitiveDataRedactor::redact),
                after = command.after?.let(SensitiveDataRedactor::redact),
                metadata = SensitiveDataRedactor.redact(command.metadata),
                occurredAt = clock.instant(),
            )
        repository.save(event)
        return event
    }

    /** Records a successful security-sensitive or state-changing action. */
    fun recordSuccess(
        actorId: UUID?,
        tenantId: UUID?,
        action: String,
        resourceType: String,
        resourceId: String?,
        actorType: String? = null,
        branchId: UUID? = null,
        reason: String? = null,
        requestId: String? = null,
        before: Map<String, Any?>? = null,
        after: Map<String, Any?>? = null,
        metadata: Map<String, Any?> = emptyMap(),
        subjectUserId: UUID? = null,
    ): AuditEvent =
        record(
            baseCommand(
                actorId = actorId,
                actorType = actorType,
                tenantId = tenantId,
                branchId = branchId,
                action = action,
                resourceType = resourceType,
                resourceId = resourceId,
                outcome = AuditOutcome.SUCCESS,
                reason = reason,
                requestId = requestId,
                metadata = metadata,
                subjectUserId = subjectUserId,
            ).copy(before = before, after = after),
        )

    /** Records a failed or denied security-sensitive or state-changing action. */
    fun recordFailure(
        actorId: UUID?,
        tenantId: UUID?,
        action: String,
        resourceType: String,
        resourceId: String?,
        outcome: AuditOutcome = AuditOutcome.FAILURE,
        actorType: String? = null,
        branchId: UUID? = null,
        reason: String? = null,
        requestId: String? = null,
        before: Map<String, Any?>? = null,
        metadata: Map<String, Any?> = emptyMap(),
        subjectUserId: UUID? = null,
    ): AuditEvent =
        record(
            baseCommand(
                actorId = actorId,
                actorType = actorType,
                tenantId = tenantId,
                branchId = branchId,
                action = action,
                resourceType = resourceType,
                resourceId = resourceId,
                outcome = outcome,
                reason = reason,
                requestId = requestId,
                metadata = metadata,
                subjectUserId = subjectUserId,
            ).copy(before = before),
        )

    /**
     * Records an FSM lifecycle transition outcome, successful or rejected. [fromState] and
     * [toState] land in metadata; for a rejected transition, pass `"N/A"` for [toState] since the
     * aggregate never reached it.
     */
    fun recordLifecycleTransition(
        actorId: UUID?,
        tenantId: UUID,
        aggregateType: String,
        aggregateId: String,
        transition: String,
        fromState: String,
        toState: String,
        outcome: AuditOutcome,
        actorType: String? = null,
        branchId: UUID? = null,
        reason: String? = null,
        requestId: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
        subjectUserId: UUID? = null,
    ): AuditEvent =
        record(
            lifecycleTransitionCommand(
                actorId = actorId,
                tenantId = tenantId,
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                transition = transition,
                fromState = fromState,
                toState = toState,
                outcome = outcome,
                actorType = actorType,
                branchId = branchId,
                reason = reason,
                requestId = requestId,
                metadata = metadata,
                subjectUserId = subjectUserId,
            ),
        )

    /**
     * Builds the [AuditCommand] for an FSM lifecycle-transition outcome without persisting it, so a
     * caller that needs [recordIndependently] (a rejection/failure recorded outside the enclosing
     * transaction) can reuse the same field mapping as [recordLifecycleTransition].
     */
    fun lifecycleTransitionCommand(
        actorId: UUID?,
        tenantId: UUID,
        aggregateType: String,
        aggregateId: String,
        transition: String,
        fromState: String,
        toState: String,
        outcome: AuditOutcome,
        actorType: String? = null,
        branchId: UUID? = null,
        reason: String? = null,
        requestId: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
        subjectUserId: UUID? = null,
    ): AuditCommand =
        baseCommand(
            actorId = actorId,
            actorType = actorType,
            tenantId = tenantId,
            branchId = branchId,
            action = "${aggregateType.lowercase()}.${transition.lowercase()}",
            resourceType = aggregateType,
            resourceId = aggregateId,
            outcome = outcome,
            reason = reason,
            requestId = requestId,
            metadata = metadata + mapOf(FROM_STATE to fromState, TO_STATE to toState),
            subjectUserId = subjectUserId,
        )

    /**
     * Records a security-sensitive event such as a denied access attempt or a rejected
     * authentication step. Defaults to [AuditSeverity.HIGH].
     */
    fun recordSecurityEvent(
        actorId: UUID?,
        tenantId: UUID?,
        action: String,
        resourceType: String,
        resourceId: String?,
        outcome: AuditOutcome,
        severity: AuditSeverity = AuditSeverity.HIGH,
        actorType: String? = null,
        branchId: UUID? = null,
        reason: String? = null,
        requestId: String? = null,
        sourceIp: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
        subjectUserId: UUID? = null,
    ): AuditEvent =
        record(
            baseCommand(
                actorId = actorId,
                actorType = actorType,
                tenantId = tenantId,
                branchId = branchId,
                action = action,
                resourceType = resourceType,
                resourceId = resourceId,
                outcome = outcome,
                reason = reason,
                requestId = requestId,
                metadata = metadata,
                subjectUserId = subjectUserId,
            ).copy(severity = severity, sourceIp = sourceIp),
        )

    /**
     * Records an IAM administration change: role, permission, membership, or assignment. [branchId]
     * is the branch the change is about (a branch-scoped assignment's), or null for a tenant-wide
     * one. It is never the caller's selected branch. [subjectUserId] is the user whose assignment
     * or membership changed (the target, not the actor), or null for a change about no user.
     */
    fun recordIamChange(
        actorId: UUID?,
        tenantId: UUID,
        action: String,
        resourceType: String,
        resourceId: String?,
        actorType: String? = null,
        branchId: UUID? = null,
        before: Map<String, Any?>? = null,
        after: Map<String, Any?>? = null,
        reason: String? = null,
        requestId: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
        subjectUserId: UUID? = null,
    ): AuditEvent =
        record(
            baseCommand(
                actorId = actorId,
                actorType = actorType,
                tenantId = tenantId,
                branchId = branchId,
                action = action,
                resourceType = resourceType,
                resourceId = resourceId,
                outcome = AuditOutcome.SUCCESS,
                reason = reason,
                requestId = requestId,
                metadata = metadata,
                subjectUserId = subjectUserId,
            ).copy(before = before, after = after),
        )

    /**
     * Records the outcome of dispatching work to an external system such as Keycloak or an
     * invite/email provider. [externalSystemRef] identifies the system, e.g. `"KEYCLOAK"`.
     */
    fun recordExternalDispatch(
        actorId: UUID?,
        tenantId: UUID,
        action: String,
        outcome: AuditOutcome,
        externalSystemRef: String,
        resourceType: String,
        resourceId: String?,
        actorType: String? = null,
        reason: String? = null,
        requestId: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
        subjectUserId: UUID? = null,
    ): AuditEvent =
        record(
            baseCommand(
                actorId = actorId,
                actorType = actorType,
                tenantId = tenantId,
                branchId = null,
                action = action,
                resourceType = resourceType,
                resourceId = resourceId,
                outcome = outcome,
                reason = reason,
                requestId = requestId,
                metadata =
                    metadata +
                        mapOf(AuditMetadataKeys.EXTERNAL_SYSTEM_REFERENCE to externalSystemRef),
                subjectUserId = subjectUserId,
            ),
        )

    /** Records an organisation settings change together with its before and after values. */
    fun recordSettingsChange(
        actorId: UUID?,
        tenantId: UUID,
        resourceId: String?,
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        actorType: String? = null,
        reason: String? = null,
        requestId: String? = null,
        subjectUserId: UUID? = null,
    ): AuditEvent =
        record(
            baseCommand(
                actorId = actorId,
                actorType = actorType,
                tenantId = tenantId,
                branchId = null,
                action = SETTINGS_UPDATE_ACTION,
                resourceType = ORGANISATION_SETTING_RESOURCE,
                resourceId = resourceId,
                outcome = AuditOutcome.SUCCESS,
                reason = reason,
                requestId = requestId,
                metadata = emptyMap(),
                subjectUserId = subjectUserId,
            ).copy(before = before, after = after),
        )

    private fun baseCommand(
        actorId: UUID?,
        actorType: String?,
        tenantId: UUID?,
        branchId: UUID?,
        action: String,
        resourceType: String,
        resourceId: String?,
        outcome: AuditOutcome,
        reason: String?,
        requestId: String?,
        metadata: Map<String, Any?>,
        subjectUserId: UUID?,
    ): AuditCommand =
        AuditCommand(
            actorType = actorType ?: resolveActorType(actorId),
            actorId = actorId?.toString(),
            tenantId = tenantId?.toString(),
            branchId = branchId?.toString(),
            subjectUserId = subjectUserId?.toString(),
            action = action,
            resourceType = resourceType,
            resourceId = resourceId,
            outcome = outcome,
            reason = reason,
            requestId = requestId,
            metadata = metadata,
        )

    private fun resolveActorType(actorId: UUID?): String =
        if (SystemActor.isSystemActor(actorId)) SYSTEM_ACTOR_TYPE else USER_ACTOR_TYPE

    private companion object {
        const val SYSTEM_ACTOR_TYPE = "SYSTEM"
        const val USER_ACTOR_TYPE = "USER"
        const val FROM_STATE = "from"
        const val TO_STATE = "to"
        const val SETTINGS_UPDATE_ACTION = "settings.update"
        const val ORGANISATION_SETTING_RESOURCE = "ORGANISATION_SETTING"
    }
}
