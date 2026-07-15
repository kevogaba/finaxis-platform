package com.finaxis.platform.common.audit

import com.finaxis.platform.common.context.RequestContexts
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.BeanFactory
import org.springframework.context.expression.BeanFactoryResolver
import org.springframework.context.expression.MethodBasedEvaluationContext
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.expression.EvaluationContext
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Records an audit event around methods annotated with [AuditedAction]. This aspect is reserved
 * for simple, non-FSM administrative mutations; lifecycle transitions keep recording audit
 * events explicitly through [AuditService] because a transition needs an explicit reason and
 * from/to state that a generic method wrapper cannot infer.
 */
@Aspect
@Component
class AuditedActionAspect(
    private val auditService: AuditService,
    private val beanFactory: BeanFactory,
) {
    private val parser = SpelExpressionParser()
    private val parameterNameDiscoverer = DefaultParameterNameDiscoverer()

    /**
     * Wraps the annotated method call, recording success or failure as a single audit event.
     *
     * Catches broadly (rather than a narrower exception type) because it must audit whatever the
     * wrapped method throws before rethrowing it unchanged; it cannot know that type in advance.
     */
    @Around("@annotation(auditedAction)")
    @Suppress("TooGenericExceptionCaught")
    fun around(
        joinPoint: ProceedingJoinPoint,
        auditedAction: AuditedAction,
    ): Any? {
        val context = evaluationContext(joinPoint)
        val tenantId =
            requireNotNull(evaluateUuid(auditedAction.tenantId, context)) {
                "@AuditedAction.tenantId must resolve to a non-null organisation id."
            }
        val actorId =
            evaluateUuid(auditedAction.actorId, context) ?: RequestContexts.actor()?.userId
        val resourceId = evaluateString(auditedAction.resourceId, context)
        val reason = evaluateString(auditedAction.reason, context)
        val before = evaluateSummary(auditedAction.before, context)

        return try {
            val result = joinPoint.proceed()
            context.setVariable(RESULT_VARIABLE, result)
            auditService.recordSuccess(
                actorId = actorId,
                tenantId = tenantId,
                action = auditedAction.action,
                resourceType = auditedAction.resourceType,
                resourceId = resourceId,
                reason = reason,
                before = before,
                after = evaluateSummary(auditedAction.after, context),
            )
            result
        } catch (ex: RuntimeException) {
            auditService.recordFailure(
                actorId = actorId,
                tenantId = tenantId,
                action = auditedAction.action,
                resourceType = auditedAction.resourceType,
                resourceId = resourceId,
                reason = ex.message ?: ex.javaClass.name,
                before = before,
            )
            throw ex
        }
    }

    private fun evaluationContext(joinPoint: ProceedingJoinPoint): EvaluationContext {
        val method = (joinPoint.signature as MethodSignature).method
        val context =
            MethodBasedEvaluationContext(
                joinPoint.target,
                method,
                joinPoint.args,
                parameterNameDiscoverer,
            )
        context.setBeanResolver(BeanFactoryResolver(beanFactory))
        return context
    }

    private fun evaluateUuid(
        expression: String,
        context: EvaluationContext,
    ): UUID? {
        if (expression.isBlank()) return null
        return when (val value = parser.parseExpression(expression).getValue(context)) {
            null -> null
            is UUID -> value
            else -> value.toString().takeIf(String::isNotBlank)?.let(UUID::fromString)
        }
    }

    private fun evaluateString(
        expression: String,
        context: EvaluationContext,
    ): String? {
        if (expression.isBlank()) return null
        return parser.parseExpression(expression).getValue(context)?.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun evaluateSummary(
        expression: String,
        context: EvaluationContext,
    ): Map<String, Any?>? {
        if (expression.isBlank()) return null
        val value = parser.parseExpression(expression).getValue(context) ?: return null
        return if (value is Map<*, *>) value as Map<String, Any?> else mapOf(VALUE_KEY to value)
    }

    private companion object {
        const val RESULT_VARIABLE = "result"
        const val VALUE_KEY = "value"
    }
}
