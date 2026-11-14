package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.lifecycle.application.AuditRetentionStore
import com.finaxis.platform.lifecycle.application.AuditRetentionTarget
import com.finaxis.platform.lifecycle.application.BRANCH_AUDIT_ENTITY_TYPE
import com.finaxis.platform.lifecycle.application.BRANCH_UPDATE_AUDIT_ACTION
import com.finaxis.platform.lifecycle.application.ORGANISATION_AMEND_AUDIT_ACTION
import com.finaxis.platform.lifecycle.application.ORGANISATION_AUDIT_ENTITY_TYPE
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The one adapter that deletes from `audit_event` (#278, ADR 0042, which amends ADR 0007's
 * "no code path issues DELETE"). `AuditRetentionDeletePathTests` holds every other class to that.
 *
 * Each batch is one `DELETE ... WHERE id IN (SELECT ... ORDER BY event_time LIMIT n)` that leads
 * `idx_audit_event_organisation_time` (organisation, then the time range). It runs in the caller's
 * transaction (`AuditRetentionBatch`), which holds the organisation's retention advisory lock and
 * writes the batch's record, so two runs never delete side by side and a deletion never commits
 * without its record.
 */
@Component
class JooqAuditRetentionStore(
    private val dsl: DSLContext,
) : AuditRetentionStore {
    override fun countRetentionTargets(): Int = dsl.fetchCount(ORGANISATION_SETTING, hasPeriod())

    override fun retentionTargets(
        limit: Int,
        offset: Int,
    ): List<AuditRetentionTarget> =
        dsl
            .select(ORGANISATION_SETTING.ORGANISATION_ID, settingText())
            .from(ORGANISATION_SETTING)
            .where(hasPeriod())
            .orderBy(ORGANISATION_SETTING.ORGANISATION_ID)
            .limit(limit)
            .offset(offset)
            .fetch()
            .mapNotNull { record ->
                // A value the catalogue never wrote (hand-written SQL) keeps everything.
                record.value2()?.toIntOrNull()?.let { days ->
                    AuditRetentionTarget(requireNotNull(record.value1()), days)
                }
            }

    override fun countDeletable(
        organisationId: UUID,
        cutoff: Instant,
    ): Long = dsl.fetchCount(AUDIT_EVENT, deletable(organisationId, cutoff)).toLong()

    override fun tryLockOrganisation(organisationId: UUID): Boolean {
        requireTransaction()
        return dsl
            .select(
                DSL.field(
                    "pg_try_advisory_xact_lock(hashtextextended({0}, 0))",
                    Boolean::class.java,
                    DSL.value("$ADVISORY_LOCK_PREFIX$organisationId"),
                ),
            ).fetchOne()
            ?.value1() == true
    }

    override fun deleteBatch(
        organisationId: UUID,
        cutoff: Instant,
        batchSize: Int,
    ): Int {
        requireTransaction()
        return dsl
            .deleteFrom(AUDIT_EVENT)
            .where(
                AUDIT_EVENT.ID.`in`(
                    DSL
                        .select(AUDIT_EVENT.ID)
                        .from(AUDIT_EVENT)
                        .where(deletable(organisationId, cutoff))
                        .orderBy(AUDIT_EVENT.EVENT_TIME)
                        .limit(batchSize),
                ),
            ).execute()
    }

    /** The advisory lock and the batch need the caller's transaction to outlast one statement. */
    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "An audit retention batch requires an active transaction."
        }
    }

    /** A current `audit_retention_days` row. */
    private fun hasPeriod(): Condition =
        ORGANISATION_SETTING.SETTING_KEY
            .eq(TenantSettingCatalog.AUDIT_RETENTION_DAYS_KEY)
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)

    /** The rows ADR 0042 lets the job delete; see [AuditRetentionStore]. */
    private fun deletable(
        organisationId: UUID,
        cutoff: Instant,
    ): Condition =
        AUDIT_EVENT.ORGANISATION_ID
            .eq(organisationId)
            .and(AUDIT_EVENT.EVENT_TIME.lt(cutoff.atOffset(ZoneOffset.UTC)))
            .and(AUDIT_EVENT.SEVERITY.eq(AuditSeverity.INFO.name))
            .and(AUDIT_EVENT.OUTCOME.eq(AuditOutcome.SUCCESS.name))
            .andNot(amendmentStillRead(organisationId))

    /**
     * An amendment the "approver is never an amender" rules still read (#255): a `branch.update`
     * of a branch that has not reached `ACTIVE`, or was suspended, and an
     * `organisation.amend_draft` of an organisation not yet approved. Deleting one would let its
     * amender approve.
     */
    private fun amendmentStillRead(organisationId: UUID): Condition =
        AUDIT_EVENT.ACTION
            .eq(BRANCH_UPDATE_AUDIT_ACTION)
            .and(AUDIT_EVENT.ENTITY_TYPE.eq(BRANCH_AUDIT_ENTITY_TYPE))
            .and(
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(BRANCH)
                        .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                        .and(BRANCH.ID.eq(AUDIT_EVENT.ENTITY_ID))
                        .and(BRANCH.STATUS.`in`(BRANCH_STATES_BEFORE_APPROVAL)),
                ),
            ).or(
                AUDIT_EVENT.ACTION
                    .eq(ORGANISATION_AMEND_AUDIT_ACTION)
                    .and(AUDIT_EVENT.ENTITY_TYPE.eq(ORGANISATION_AUDIT_ENTITY_TYPE))
                    .and(
                        DSL.exists(
                            DSL
                                .selectOne()
                                .from(ORGANISATION)
                                .where(ORGANISATION.ID.eq(organisationId))
                                .and(ORGANISATION.STATUS.`in`(ORGANISATION_STATES_BEFORE_APPROVAL)),
                        ),
                    ),
            )

    private companion object {
        const val ADVISORY_LOCK_PREFIX = "audit-retention:"

        /** Branch states from which an approval, and so the amender rule, can still come. */
        val BRANCH_STATES_BEFORE_APPROVAL =
            listOf(
                BranchLifecycleState.DRAFT,
                BranchLifecycleState.PENDING_APPROVAL,
                BranchLifecycleState.SUSPENDED,
            ).map { it.name }

        /** Organisation states before approval, in which the tenant amender rule applies. */
        val ORGANISATION_STATES_BEFORE_APPROVAL =
            listOf(
                OrganisationLifecycleState.DRAFT,
                OrganisationLifecycleState.PENDING_APPROVAL,
                OrganisationLifecycleState.PROVISIONING,
            ).map { it.name }
    }
}
