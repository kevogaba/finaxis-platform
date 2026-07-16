package com.finaxis.platform.common.audit.adapter.outbound.persistence

import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.audit.AuditEventPage
import com.finaxis.platform.common.audit.AuditEventQueries
import com.finaxis.platform.common.audit.AuditEventSummary
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import org.jooq.Condition
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.ZoneOffset

/** jOOQ adapter for paginated, tenant-scoped audit event administration reads. */
@Component
class JooqAuditEventQueries(
    private val dsl: DSLContext,
) : AuditEventQueries {
    override fun search(filter: AuditEventFilter): AuditEventPage {
        val condition = buildCondition(filter)
        val total = dsl.fetchCount(AUDIT_EVENT, condition).toLong()
        val items =
            dsl
                .select(
                    AUDIT_EVENT.ID,
                    AUDIT_EVENT.EVENT_TIME,
                    AUDIT_EVENT.ACTOR_TYPE,
                    AUDIT_EVENT.ACTOR_USER_ID,
                    AUDIT_EVENT.BRANCH_ID,
                    AUDIT_EVENT.ACTION,
                    AUDIT_EVENT.ENTITY_TYPE,
                    AUDIT_EVENT.ENTITY_ID,
                    AUDIT_EVENT.OUTCOME,
                    AUDIT_EVENT.SEVERITY,
                    AUDIT_EVENT.REASON,
                ).from(AUDIT_EVENT)
                .where(condition)
                .orderBy(AUDIT_EVENT.EVENT_TIME.desc())
                .limit(filter.size)
                .offset(filter.page * filter.size)
                .fetch(::toSummary)
        return AuditEventPage(items, total)
    }

    private fun buildCondition(filter: AuditEventFilter): Condition {
        var condition: Condition = AUDIT_EVENT.ORGANISATION_ID.eq(filter.organisationId)
        filter.entityType?.let { condition = condition.and(AUDIT_EVENT.ENTITY_TYPE.eq(it)) }
        filter.entityId?.let { condition = condition.and(AUDIT_EVENT.ENTITY_ID.eq(it)) }
        filter.actorId?.let { condition = condition.and(AUDIT_EVENT.ACTOR_USER_ID.eq(it)) }
        filter.action?.let { condition = condition.and(AUDIT_EVENT.ACTION.eq(it)) }
        filter.occurredFrom?.let {
            condition = condition.and(AUDIT_EVENT.EVENT_TIME.ge(it.atOffset(ZoneOffset.UTC)))
        }
        filter.occurredTo?.let {
            condition = condition.and(AUDIT_EVENT.EVENT_TIME.le(it.atOffset(ZoneOffset.UTC)))
        }
        return condition
    }

    private companion object {
        fun toSummary(record: org.jooq.Record): AuditEventSummary =
            AuditEventSummary(
                id = requireNotNull(record.get(AUDIT_EVENT.ID)),
                occurredAt = requireNotNull(record.get(AUDIT_EVENT.EVENT_TIME)).toInstant(),
                actorType = requireNotNull(record.get(AUDIT_EVENT.ACTOR_TYPE)),
                actorId = record.get(AUDIT_EVENT.ACTOR_USER_ID),
                branchId = record.get(AUDIT_EVENT.BRANCH_ID),
                action = requireNotNull(record.get(AUDIT_EVENT.ACTION)),
                resourceType = requireNotNull(record.get(AUDIT_EVENT.ENTITY_TYPE)),
                resourceId = record.get(AUDIT_EVENT.ENTITY_ID)?.toString(),
                outcome = AuditOutcome.valueOf(requireNotNull(record.get(AUDIT_EVENT.OUTCOME))),
                severity = AuditSeverity.valueOf(requireNotNull(record.get(AUDIT_EVENT.SEVERITY))),
                reason = record.get(AUDIT_EVENT.REASON),
            )
    }
}
