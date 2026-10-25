package com.finaxis.platform.iam.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogWriter
import com.finaxis.platform.iam.domain.RoleAggregate
import com.finaxis.platform.jooq.tables.references.ROLE_TRANSITION_LOG
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * IAM's slice of transition-log persistence: writes `role_transition_log` for [RoleAggregate].
 *
 * IAM owns that table and nothing outside `iam.adapter.outbound.persistence` writes it. The row is
 * inserted by the shared `TransitionExecutor` in the caller's transaction, so it commits or rolls
 * back with the role's status. The organisation comes from the command metadata, as for every
 * other module's log, so a row is never written without its tenant.
 */
@Component
class JooqRoleTransitionLogWriter(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : TransitionLogWriter {
    override fun supports(aggregateType: String): Boolean =
        aggregateType == RoleAggregate.AGGREGATE_TYPE

    override fun save(log: TransitionLog) {
        val organisationId =
            UUID.fromString(
                requireNotNull(log.metadata[ORGANISATION_ID] as String?) {
                    "A role transition log needs its organisation in metadata; without it the " +
                        "row cannot be written tenant-safely."
                },
            )
        val actorId = log.actorId.toUuidOrNull()
        dsl
            .insertInto(ROLE_TRANSITION_LOG)
            .set(ROLE_TRANSITION_LOG.ORGANISATION_ID, organisationId)
            .set(ROLE_TRANSITION_LOG.ENTITY_ID, UUID.fromString(log.aggregateId))
            .set(ROLE_TRANSITION_LOG.TRANSITION_NAME, log.transition)
            .set(ROLE_TRANSITION_LOG.STATUS_FROM, log.fromState)
            .set(ROLE_TRANSITION_LOG.STATUS_TO, log.toState)
            .set(ROLE_TRANSITION_LOG.REASON, log.reason)
            .set(ROLE_TRANSITION_LOG.CREATED_AT, log.createdAt.atOffset(ZoneOffset.UTC))
            .set(ROLE_TRANSITION_LOG.CREATED_BY, actorId)
            .set(ROLE_TRANSITION_LOG.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(ROLE_TRANSITION_LOG.UPDATED_BY, actorId)
            .set(
                ROLE_TRANSITION_LOG.METADATA_JSONB,
                JSONB.jsonb(objectMapper.writeValueAsString(log.metadata)),
            ).execute()
    }

    private companion object {
        const val ORGANISATION_ID = "organisationId"
    }
}

private fun String.toUuidOrNull(): UUID? = runCatching(UUID::fromString).getOrNull()
