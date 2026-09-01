package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.accounting.application.FiscalPeriodMakerResolver
import com.finaxis.platform.accounting.application.GlAccountMakerResolver
import com.finaxis.platform.accounting.domain.FiscalPeriodAggregate
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodTransition
import com.finaxis.platform.accounting.domain.GlAccountAggregate
import com.finaxis.platform.accounting.domain.GlAccountTransition
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogWriter
import com.finaxis.platform.jooq.tables.references.FISCAL_PERIOD_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT_TRANSITION_LOG
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Accounting's slice of transition-log persistence, and the read the reopen control depends on.
 *
 * Writes `fiscal_period_transition_log`. Accounting owns that table and nothing outside
 * `accounting.adapter.outbound.persistence` may write it, which is why the shared executor had to
 * learn to dispatch rather than lifecycle's writer learning about accounting.
 *
 * It also answers both maker resolvers, because the actor who performed a given transition is
 * recorded here and nowhere else. The entity row's `updated_by` says who moved it *last*, which is
 * a different question once a period has been closed, reopened and closed again, or an account
 * submitted, rejected and resubmitted.
 */
@Component
class JooqAccountingTransitionLogWriter(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : TransitionLogWriter,
    FiscalPeriodMakerResolver,
    GlAccountMakerResolver {
    override fun supports(aggregateType: String): Boolean = aggregateType in OWNED_TYPES

    override fun save(log: TransitionLog) {
        val organisationId =
            UUID.fromString(
                requireNotNull(log.metadata[ORGANISATION_ID] as String?) {
                    "An accounting transition log needs its organisation in metadata; without " +
                        "it the row cannot be written tenant-safely."
                },
            )
        val now = OffsetDateTime.now(clock)
        val metadata = JSONB.jsonb(objectMapper.writeValueAsString(log.metadata))
        val actorId = log.actorId.toActorId()
        val createdAt = log.createdAt.atOffset(ZoneOffset.UTC)

        when (log.aggregateType) {
            FiscalPeriodAggregate.AGGREGATE_TYPE -> {
                dsl
                    .insertInto(FISCAL_PERIOD_TRANSITION_LOG)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.ORGANISATION_ID, organisationId)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.ENTITY_ID, UUID.fromString(log.aggregateId))
                    .set(FISCAL_PERIOD_TRANSITION_LOG.TRANSITION_NAME, log.transition)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.STATUS_FROM, log.fromState)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.STATUS_TO, log.toState)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.REASON, log.reason)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.CREATED_AT, createdAt)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.CREATED_BY, actorId)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.UPDATED_AT, now)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.UPDATED_BY, actorId)
                    .set(FISCAL_PERIOD_TRANSITION_LOG.METADATA_JSONB, metadata)
                    .execute()
            }

            GlAccountAggregate.AGGREGATE_TYPE -> {
                dsl
                    .insertInto(GL_ACCOUNT_TRANSITION_LOG)
                    .set(GL_ACCOUNT_TRANSITION_LOG.ORGANISATION_ID, organisationId)
                    .set(GL_ACCOUNT_TRANSITION_LOG.ENTITY_ID, UUID.fromString(log.aggregateId))
                    .set(GL_ACCOUNT_TRANSITION_LOG.TRANSITION_NAME, log.transition)
                    .set(GL_ACCOUNT_TRANSITION_LOG.STATUS_FROM, log.fromState)
                    .set(GL_ACCOUNT_TRANSITION_LOG.STATUS_TO, log.toState)
                    .set(GL_ACCOUNT_TRANSITION_LOG.REASON, log.reason)
                    .set(GL_ACCOUNT_TRANSITION_LOG.CREATED_AT, createdAt)
                    .set(GL_ACCOUNT_TRANSITION_LOG.CREATED_BY, actorId)
                    .set(GL_ACCOUNT_TRANSITION_LOG.UPDATED_AT, now)
                    .set(GL_ACCOUNT_TRANSITION_LOG.UPDATED_BY, actorId)
                    .set(GL_ACCOUNT_TRANSITION_LOG.METADATA_JSONB, metadata)
                    .execute()
            }

            // Unreachable while `supports` gates this, and kept so it stays unreachable: a type
            // added there without a branch here would otherwise write nothing and report success.
            else -> {
                error("Accounting owns no transition log for ${log.aggregateType}")
            }
        }
    }

    override fun lastActorFor(
        organisationId: UUID,
        accountId: UUID,
        transition: GlAccountTransition,
    ): UUID? =
        dsl
            .select(GL_ACCOUNT_TRANSITION_LOG.CREATED_BY)
            .from(GL_ACCOUNT_TRANSITION_LOG)
            .where(GL_ACCOUNT_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT_TRANSITION_LOG.ENTITY_ID.eq(accountId))
            .and(GL_ACCOUNT_TRANSITION_LOG.TRANSITION_NAME.eq(transition.name))
            .orderBy(
                GL_ACCOUNT_TRANSITION_LOG.CREATED_AT.desc(),
                GL_ACCOUNT_TRANSITION_LOG.ID.desc(),
            ).limit(1)
            .fetchOne(GL_ACCOUNT_TRANSITION_LOG.CREATED_BY)

    /**
     * The actor of the most recent [transition] on a period, or null when it never happened.
     *
     * Ordered by `created_at` and then `id`, not by `created_at` alone: two transitions on one
     * period inside a single transaction share a timestamp, and `id` is `uuidv7()`, so it breaks
     * the tie in insertion order rather than arbitrarily.
     */
    override fun lastActorFor(
        key: FiscalPeriodKey,
        transition: FiscalPeriodTransition,
    ): UUID? =
        dsl
            .select(FISCAL_PERIOD_TRANSITION_LOG.CREATED_BY)
            .from(FISCAL_PERIOD_TRANSITION_LOG)
            .where(FISCAL_PERIOD_TRANSITION_LOG.ORGANISATION_ID.eq(key.organisationId))
            .and(FISCAL_PERIOD_TRANSITION_LOG.ENTITY_ID.eq(key.fiscalPeriodId))
            .and(FISCAL_PERIOD_TRANSITION_LOG.TRANSITION_NAME.eq(transition.name))
            .orderBy(
                FISCAL_PERIOD_TRANSITION_LOG.CREATED_AT.desc(),
                FISCAL_PERIOD_TRANSITION_LOG.ID.desc(),
            ).limit(1)
            .fetchOne(FISCAL_PERIOD_TRANSITION_LOG.CREATED_BY)

    /**
     * Parses the actor strictly, because a null `created_by` disables a security control.
     *
     * An earlier revision used a `runCatching { … }.getOrNull()` here, so a non-UUID actor id
     * silently became `NULL`. Both maker resolvers then returned null for that row, and both
     * services read null as *"the transition never happened"* and skipped the different-actor
     * check entirely — so the same person could submit and approve one account, or close and
     * reopen one period, and separation of duties failed **open** because of a formatting problem.
     * The organisation id on the same row already fails loudly for exactly this reason.
     */
    private fun String.toActorId(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse {
            error(
                "An accounting transition needs a UUID actor id; '$this' is not one, and storing " +
                    "null here would disable the different-actor control that reads it.",
            )
        }

    private companion object {
        const val ORGANISATION_ID = "organisationId"

        /**
         * The aggregate types accounting owns a transition-log table for.
         *
         * Kept beside the `when` in [save] rather than derived from it, for the same reason
         * lifecycle's writer keeps its own list: a type added to one and not the other would
         * otherwise reach the `error` branch at runtime instead of failing a test.
         */
        val OWNED_TYPES =
            setOf(FiscalPeriodAggregate.AGGREGATE_TYPE, GlAccountAggregate.AGGREGATE_TYPE)
    }
}
