package com.finaxis.platform.common.audit

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Default audit wiring until a module provides a persistent audit adapter.
 */
@Configuration
class AuditConfiguration {
    /**
     * Emits structured audit events to the application log if no durable repository is configured.
     */
    @Bean
    @ConditionalOnMissingBean(AuditEventRepository::class)
    fun loggingAuditEventRepository(): AuditEventRepository =
        AuditEventRepository { event ->
            auditLogger.info(
                "audit_event action={} outcome={} severity={} actorType={} actorId={} " +
                    "tenantId={} branchId={} resourceType={} resourceId={} requestId={} " +
                    "sourceIp={}",
                event.action,
                event.outcome,
                event.severity,
                event.actorType,
                event.actorId,
                event.tenantId,
                event.branchId,
                event.resourceType,
                event.resourceId,
                event.requestId,
                event.sourceIp,
            )
        }

    private companion object {
        private val auditLogger = LoggerFactory.getLogger("com.finaxis.platform.audit")
    }
}
