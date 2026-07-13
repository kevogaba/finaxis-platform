package com.finaxis.platform.common.context

import org.slf4j.MDC
import java.util.UUID

/** Current organisation selection for a request, worker invocation, or test. */
data class TenantContext(
    val organisationId: UUID,
    val organisationCode: String? = null,
)

/** Current branch selection for branch-scoped work. */
data class BranchContext(
    val branchId: UUID,
    val scope: String? = null,
)

/** Authenticated application actor, kept separate from Keycloak authentication details. */
data class ActorContext(
    val userId: UUID,
    val externalSubject: String?,
    val username: String?,
    val email: String?,
)

/** Correlation identifiers propagated across HTTP, jobs, and integration messages. */
data class CorrelationContext(
    val requestId: String?,
    val correlationId: String?,
)

/** Immutable context snapshot used to install the complete request context. */
data class RequestContext(
    val tenant: TenantContext? = null,
    val branch: BranchContext? = null,
    val actor: ActorContext? = null,
    val correlation: CorrelationContext? = null,
)

/** Thread-bound context and matching MDC lifecycle for synchronous Spring MVC execution. */
object RequestContexts {
    private val current = ThreadLocal<RequestContext?>()

    /** Returns the context installed for the current synchronous execution, if any. */
    fun current(): RequestContext? = current.get()

    /** Returns the authenticated application actor from the current context, if available. */
    fun actor(): ActorContext? = current()?.actor

    /**
     * Runs [block] with only an actor context installed, then restores the previous context.
     */
    fun <T> withActor(
        actor: ActorContext,
        block: () -> T,
    ): T = with(RequestContext(actor = actor), block)

    /** Runs [block] with [context] and matching MDC values, then restores the previous context. */
    fun <T> with(
        context: RequestContext,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(context)
        applyMdc(context)
        return try {
            block()
        } finally {
            current.set(previous)
            applyMdc(previous)
        }
    }

    /** Removes the current context and all context-derived MDC entries. */
    fun clear() {
        current.remove()
        clearMdc()
    }

    private fun clearMdc() {
        MDC.remove(ORGANISATION_ID)
        MDC.remove(ORGANISATION_CODE)
        MDC.remove(BRANCH_ID)
        MDC.remove(ACTOR_ID)
        MDC.remove(ACTOR_SUBJECT)
        MDC.remove(REQUEST_ID)
        MDC.remove(CORRELATION_ID)
    }

    private fun applyMdc(context: RequestContext?) {
        clearMdc()
        if (context == null) {
            return
        }
        context.tenant?.let {
            MDC.put(ORGANISATION_ID, it.organisationId.toString())
            it.organisationCode?.let { code -> MDC.put(ORGANISATION_CODE, code) }
        }
        context.branch?.let { MDC.put(BRANCH_ID, it.branchId.toString()) }
        context.actor?.let {
            MDC.put(ACTOR_ID, it.userId.toString())
            it.externalSubject?.let { subject -> MDC.put(ACTOR_SUBJECT, subject) }
        }
        context.correlation?.let {
            it.requestId?.let { requestId -> MDC.put(REQUEST_ID, requestId) }
            it.correlationId?.let { correlationId -> MDC.put(CORRELATION_ID, correlationId) }
        }
    }

    private const val ORGANISATION_ID = "organisationId"
    private const val ORGANISATION_CODE = "organisationCode"
    private const val BRANCH_ID = "branchId"
    private const val ACTOR_ID = "actorId"
    private const val ACTOR_SUBJECT = "actorSubject"
    private const val REQUEST_ID = "requestId"
    private const val CORRELATION_ID = "correlationId"
}
