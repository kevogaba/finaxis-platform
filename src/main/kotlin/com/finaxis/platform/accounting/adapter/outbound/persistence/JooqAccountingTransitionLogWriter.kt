package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.accounting.application.FiscalPeriodMakerResolver
import com.finaxis.platform.accounting.application.GlAccountMakerResolver
import com.finaxis.platform.accounting.application.manual.ManualJournalMakerResolver
import com.finaxis.platform.accounting.application.rules.PostingRuleVersionMakerResolver
import com.finaxis.platform.accounting.domain.FiscalPeriodAggregate
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodTransition
import com.finaxis.platform.accounting.domain.GlAccountAggregate
import com.finaxis.platform.accounting.domain.GlAccountTransition
import com.finaxis.platform.accounting.domain.ManualJournalAggregate
import com.finaxis.platform.accounting.domain.ManualJournalTransition
import com.finaxis.platform.accounting.domain.PostingRuleVersionAggregate
import com.finaxis.platform.accounting.domain.PostingRuleVersionTransition
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogWriter
import com.finaxis.platform.jooq.tables.references.FISCAL_PERIOD_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_VERSION_TRANSITION_LOG
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
    GlAccountMakerResolver,
    PostingRuleVersionMakerResolver,
    ManualJournalMakerResolver {
    override fun supports(aggregateType: String): Boolean = aggregateType in OWNED_TYPES

    override fun save(log: TransitionLog) {
        val organisationId =
            UUID.fromString(
                requireNotNull(log.metadata[ORGANISATION_ID] as String?) {
                    "An accounting transition log needs its organisation in metadata; without " +
                        "it the row cannot be written tenant-safely."
                },
            )
        val row =
            LogRow(
                organisationId = organisationId,
                entityId = UUID.fromString(log.aggregateId),
                actorId = log.actorId.toActorId(),
                createdAt = log.createdAt.atOffset(ZoneOffset.UTC),
                now = OffsetDateTime.now(clock),
                metadata = JSONB.jsonb(objectMapper.writeValueAsString(log.metadata)),
            )
        when (log.aggregateType) {
            FiscalPeriodAggregate.AGGREGATE_TYPE -> saveFiscalPeriod(log, row)

            GlAccountAggregate.AGGREGATE_TYPE -> saveGlAccount(log, row)

            PostingRuleVersionAggregate.AGGREGATE_TYPE -> savePostingRuleVersion(log, row)

            ManualJournalAggregate.AGGREGATE_TYPE -> saveManualJournal(log, row)

            // Unreachable while `supports` gates this, and kept so it stays unreachable: a type
            // added there without a branch here would otherwise write nothing and report success.
            else -> error("Accounting owns no transition log for ${log.aggregateType}")
        }
    }

    /** The tenant-safe, timestamped, attributed part of a log row, the same for every table. */
    private data class LogRow(
        val organisationId: UUID,
        val entityId: UUID,
        val actorId: UUID,
        val createdAt: OffsetDateTime,
        val now: OffsetDateTime,
        val metadata: JSONB,
    )

    private fun saveFiscalPeriod(
        log: TransitionLog,
        row: LogRow,
    ) {
        dsl
            .insertInto(FISCAL_PERIOD_TRANSITION_LOG)
            .set(FISCAL_PERIOD_TRANSITION_LOG.ORGANISATION_ID, row.organisationId)
            .set(FISCAL_PERIOD_TRANSITION_LOG.ENTITY_ID, row.entityId)
            .set(FISCAL_PERIOD_TRANSITION_LOG.TRANSITION_NAME, log.transition)
            .set(FISCAL_PERIOD_TRANSITION_LOG.STATUS_FROM, log.fromState)
            .set(FISCAL_PERIOD_TRANSITION_LOG.STATUS_TO, log.toState)
            .set(FISCAL_PERIOD_TRANSITION_LOG.REASON, log.reason)
            .set(FISCAL_PERIOD_TRANSITION_LOG.CREATED_AT, row.createdAt)
            .set(FISCAL_PERIOD_TRANSITION_LOG.CREATED_BY, row.actorId)
            .set(FISCAL_PERIOD_TRANSITION_LOG.UPDATED_AT, row.now)
            .set(FISCAL_PERIOD_TRANSITION_LOG.UPDATED_BY, row.actorId)
            .set(FISCAL_PERIOD_TRANSITION_LOG.METADATA_JSONB, row.metadata)
            .execute()
    }

    private fun saveGlAccount(
        log: TransitionLog,
        row: LogRow,
    ) {
        dsl
            .insertInto(GL_ACCOUNT_TRANSITION_LOG)
            .set(GL_ACCOUNT_TRANSITION_LOG.ORGANISATION_ID, row.organisationId)
            .set(GL_ACCOUNT_TRANSITION_LOG.ENTITY_ID, row.entityId)
            .set(GL_ACCOUNT_TRANSITION_LOG.TRANSITION_NAME, log.transition)
            .set(GL_ACCOUNT_TRANSITION_LOG.STATUS_FROM, log.fromState)
            .set(GL_ACCOUNT_TRANSITION_LOG.STATUS_TO, log.toState)
            .set(GL_ACCOUNT_TRANSITION_LOG.REASON, log.reason)
            .set(GL_ACCOUNT_TRANSITION_LOG.CREATED_AT, row.createdAt)
            .set(GL_ACCOUNT_TRANSITION_LOG.CREATED_BY, row.actorId)
            .set(GL_ACCOUNT_TRANSITION_LOG.UPDATED_AT, row.now)
            .set(GL_ACCOUNT_TRANSITION_LOG.UPDATED_BY, row.actorId)
            .set(GL_ACCOUNT_TRANSITION_LOG.METADATA_JSONB, row.metadata)
            .execute()
    }

    private fun saveManualJournal(
        log: TransitionLog,
        row: LogRow,
    ) {
        dsl
            .insertInto(MANUAL_JOURNAL_TRANSITION_LOG)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.ORGANISATION_ID, row.organisationId)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.ENTITY_ID, row.entityId)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.TRANSITION_NAME, log.transition)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.STATUS_FROM, log.fromState)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.STATUS_TO, log.toState)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.REASON, log.reason)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.CREATED_AT, row.createdAt)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.CREATED_BY, row.actorId)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.UPDATED_AT, row.now)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.UPDATED_BY, row.actorId)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.METADATA_JSONB, row.metadata)
            .execute()
    }

    private fun savePostingRuleVersion(
        log: TransitionLog,
        row: LogRow,
    ) {
        dsl
            .insertInto(POSTING_RULE_VERSION_TRANSITION_LOG)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.ORGANISATION_ID, row.organisationId)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.ENTITY_ID, row.entityId)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.TRANSITION_NAME, log.transition)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.STATUS_FROM, log.fromState)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.STATUS_TO, log.toState)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.REASON, log.reason)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.CREATED_AT, row.createdAt)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.CREATED_BY, row.actorId)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.UPDATED_AT, row.now)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.UPDATED_BY, row.actorId)
            .set(POSTING_RULE_VERSION_TRANSITION_LOG.METADATA_JSONB, row.metadata)
            .execute()
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

    override fun lastActorFor(
        organisationId: UUID,
        journalId: UUID,
        transition: ManualJournalTransition,
    ): UUID? =
        dsl
            .select(MANUAL_JOURNAL_TRANSITION_LOG.CREATED_BY)
            .from(MANUAL_JOURNAL_TRANSITION_LOG)
            .where(MANUAL_JOURNAL_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId))
            .and(MANUAL_JOURNAL_TRANSITION_LOG.ENTITY_ID.eq(journalId))
            .and(MANUAL_JOURNAL_TRANSITION_LOG.TRANSITION_NAME.eq(transition.name))
            .orderBy(
                MANUAL_JOURNAL_TRANSITION_LOG.CREATED_AT.desc(),
                MANUAL_JOURNAL_TRANSITION_LOG.ID.desc(),
            ).limit(1)
            .fetchOne(MANUAL_JOURNAL_TRANSITION_LOG.CREATED_BY)

    override fun lastActorFor(
        organisationId: UUID,
        versionId: UUID,
        transition: PostingRuleVersionTransition,
    ): UUID? =
        dsl
            .select(POSTING_RULE_VERSION_TRANSITION_LOG.CREATED_BY)
            .from(POSTING_RULE_VERSION_TRANSITION_LOG)
            .where(POSTING_RULE_VERSION_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE_VERSION_TRANSITION_LOG.ENTITY_ID.eq(versionId))
            .and(POSTING_RULE_VERSION_TRANSITION_LOG.TRANSITION_NAME.eq(transition.name))
            .orderBy(
                POSTING_RULE_VERSION_TRANSITION_LOG.CREATED_AT.desc(),
                POSTING_RULE_VERSION_TRANSITION_LOG.ID.desc(),
            ).limit(1)
            .fetchOne(POSTING_RULE_VERSION_TRANSITION_LOG.CREATED_BY)

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
            setOf(
                FiscalPeriodAggregate.AGGREGATE_TYPE,
                GlAccountAggregate.AGGREGATE_TYPE,
                PostingRuleVersionAggregate.AGGREGATE_TYPE,
                ManualJournalAggregate.AGGREGATE_TYPE,
            )
    }
}
