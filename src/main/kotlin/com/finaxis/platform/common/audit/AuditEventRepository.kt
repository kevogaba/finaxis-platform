package com.finaxis.platform.common.audit

/**
 * Output port for durable audit-event storage.
 */
fun interface AuditEventRepository {
    /**
     * Persists or emits an audit event.
     */
    fun save(event: AuditEvent)
}
