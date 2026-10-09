package com.finaxis.platform.common.audit.adapter.outbound.persistence

import com.finaxis.platform.common.audit.AuditEventDetail
import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.audit.AuditEventPage
import com.finaxis.platform.common.audit.AuditEventQueries
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.web.api.boundedPageOffset
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.SortField
import org.jooq.impl.DSL.inline
import org.jooq.impl.DSL.noCondition
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.util.UUID

/**
 * jOOQ adapter for paginated, tenant-scoped audit event administration reads.
 *
 * Index support for the #183 filters (V25-V28): `branch_id` and `actor_external_subject` lead a
 * partial index after the organisation, and `outcome` and `severity` have partial indexes over
 * the rare values (not `SUCCESS`, not `INFO`). Those two are compared with inlined literals, never
 * bind values: they come from closed enums, and a literal lets the planner prove a partial
 * index's predicate in a cached generic plan too. `q` is an unindexed substring match, bounded by
 * the 31-day window [com.finaxis.platform.common.audit.requireValid] enforces.
 */
@Component
class JooqAuditEventQueries(
    private val dsl: DSLContext,
) : AuditEventQueries {
    override fun search(filter: AuditEventFilter): AuditEventPage {
        val condition = searchCondition(filter)
        val total = dsl.fetchCount(AUDIT_EVENT, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return AuditEventPage(emptyList(), total)
        val items =
            dsl
                .select(FIELDS)
                .from(AUDIT_EVENT)
                .where(condition)
                .orderBy(order(filter.ascending))
                .limit(filter.size)
                .offset(offset)
                .fetch(::toDetail)
        return AuditEventPage(items, total)
    }

    override fun findById(
        id: UUID,
        organisationId: UUID,
    ): AuditEventDetail? =
        dsl
            .select(FIELDS)
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ID.eq(id))
            .and(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
            .fetchOne(::toDetail)

    /**
     * The WHERE clause of [search] and its count. Internal so `AuditQueryPlanTests` can plan the
     * exact SQL this adapter renders.
     */
    internal fun searchCondition(filter: AuditEventFilter): Condition {
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
        return condition.and(classificationCondition(filter)).and(textCondition(filter))
    }

    private fun classificationCondition(filter: AuditEventFilter): Condition {
        var condition = noCondition()
        filter.outcome?.let { condition = condition.and(AUDIT_EVENT.OUTCOME.eq(inline(it.name))) }
        filter.severity?.let {
            condition = condition.and(AUDIT_EVENT.SEVERITY.eq(inline(it.name)))
        }
        filter.minSeverity?.let { minimum ->
            val atLeast = AuditSeverity.entries.filter { it >= minimum }.map { inline(it.name) }
            condition = condition.and(AUDIT_EVENT.SEVERITY.`in`(atLeast))
        }
        filter.branchId?.let { condition = condition.and(AUDIT_EVENT.BRANCH_ID.eq(it)) }
        filter.actorType?.let { condition = condition.and(AUDIT_EVENT.ACTOR_TYPE.eq(it.name)) }
        filter.actorSubject?.let {
            condition = condition.and(AUDIT_EVENT.ACTOR_EXTERNAL_SUBJECT.eq(it))
        }
        return condition
    }

    /** jOOQ's `startsWith` and `containsIgnoreCase` escape `%`, `_` and the escape character. */
    private fun textCondition(filter: AuditEventFilter): Condition {
        var condition = noCondition()
        filter.actionPrefix?.let { condition = condition.and(AUDIT_EVENT.ACTION.startsWith(it)) }
        filter.q?.let { text ->
            condition =
                condition.and(
                    AUDIT_EVENT.ACTION
                        .containsIgnoreCase(text)
                        .or(AUDIT_EVENT.ENTITY_TYPE.containsIgnoreCase(text))
                        .or(AUDIT_EVENT.REASON.containsIgnoreCase(text)),
                )
        }
        return condition
    }

    private fun order(ascending: Boolean): List<SortField<*>> =
        if (ascending) {
            listOf(AUDIT_EVENT.EVENT_TIME.asc(), AUDIT_EVENT.ID.asc())
        } else {
            listOf(AUDIT_EVENT.EVENT_TIME.desc(), AUDIT_EVENT.ID.desc())
        }

    private companion object {
        /** The columns both the search and the by-id read return (#187). */
        val FIELDS =
            listOf(
                AUDIT_EVENT.ID,
                AUDIT_EVENT.ORGANISATION_ID,
                AUDIT_EVENT.EVENT_TIME,
                AUDIT_EVENT.ACTOR_USER_ID,
                AUDIT_EVENT.ACTOR_EXTERNAL_SUBJECT,
                AUDIT_EVENT.ACTOR_TYPE,
                AUDIT_EVENT.BRANCH_ID,
                AUDIT_EVENT.EVENT_TYPE,
                AUDIT_EVENT.ENTITY_TYPE,
                AUDIT_EVENT.ENTITY_ID,
                AUDIT_EVENT.ACTION,
                AUDIT_EVENT.OUTCOME,
                AUDIT_EVENT.SEVERITY,
                AUDIT_EVENT.IP_ADDRESS,
                AUDIT_EVENT.USER_AGENT,
                AUDIT_EVENT.CORRELATION_ID,
                AUDIT_EVENT.REQUEST_ID,
                AUDIT_EVENT.BEFORE_JSONB,
                AUDIT_EVENT.AFTER_JSONB,
                AUDIT_EVENT.METADATA_JSONB,
                AUDIT_EVENT.REASON,
            )

        fun toDetail(record: org.jooq.Record): AuditEventDetail =
            AuditEventDetail(
                id = requireNotNull(record.get(AUDIT_EVENT.ID)),
                organisationId = requireNotNull(record.get(AUDIT_EVENT.ORGANISATION_ID)),
                occurredAt = requireNotNull(record.get(AUDIT_EVENT.EVENT_TIME)).toInstant(),
                actorUserId = record.get(AUDIT_EVENT.ACTOR_USER_ID),
                actorExternalSubject = record.get(AUDIT_EVENT.ACTOR_EXTERNAL_SUBJECT),
                actorType = requireNotNull(record.get(AUDIT_EVENT.ACTOR_TYPE)),
                branchId = record.get(AUDIT_EVENT.BRANCH_ID),
                eventType = requireNotNull(record.get(AUDIT_EVENT.EVENT_TYPE)),
                entityType = requireNotNull(record.get(AUDIT_EVENT.ENTITY_TYPE)),
                entityId = record.get(AUDIT_EVENT.ENTITY_ID),
                action = requireNotNull(record.get(AUDIT_EVENT.ACTION)),
                outcome = AuditOutcome.valueOf(requireNotNull(record.get(AUDIT_EVENT.OUTCOME))),
                severity = AuditSeverity.valueOf(requireNotNull(record.get(AUDIT_EVENT.SEVERITY))),
                ipAddress = record.get(AUDIT_EVENT.IP_ADDRESS),
                userAgent = record.get(AUDIT_EVENT.USER_AGENT),
                correlationId = record.get(AUDIT_EVENT.CORRELATION_ID),
                requestId = record.get(AUDIT_EVENT.REQUEST_ID),
                beforeJson = record.get(AUDIT_EVENT.BEFORE_JSONB)?.data(),
                afterJson = record.get(AUDIT_EVENT.AFTER_JSONB)?.data(),
                metadataJson = requireNotNull(record.get(AUDIT_EVENT.METADATA_JSONB)).data(),
                reason = record.get(AUDIT_EVENT.REASON),
            )
    }
}
