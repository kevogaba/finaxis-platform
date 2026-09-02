package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.rules.NewPostingRule
import com.finaxis.platform.accounting.application.rules.NewPostingRuleVersion
import com.finaxis.platform.accounting.application.rules.PostingRuleStore
import com.finaxis.platform.accounting.domain.AccountResolution
import com.finaxis.platform.accounting.domain.PostingRule
import com.finaxis.platform.accounting.domain.PostingRuleLeg
import com.finaxis.platform.accounting.domain.PostingRuleSelector
import com.finaxis.platform.accounting.domain.PostingRuleVersion
import com.finaxis.platform.accounting.domain.PostingRuleVersionStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.jooq.tables.records.PostingRuleLegRecord
import com.finaxis.platform.jooq.tables.records.PostingRuleRecord
import com.finaxis.platform.jooq.tables.records.PostingRuleVersionRecord
import com.finaxis.platform.jooq.tables.references.POSTING_RULE
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_LEG
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_VERSION
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The posting-rule adapter behind [PostingRuleStore]. Every statement carries the tenant predicate;
 * the rule lock is `FOR UPDATE` with that predicate in the same statement, as the GL-account store
 * does, so a caller naming another tenant's rule locks nothing.
 */
@Component
@Suppress("TooManyFunctions")
class JooqPostingRuleStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : PostingRuleStore {
    override fun findRule(
        organisationId: UUID,
        ruleId: UUID,
    ): PostingRule? =
        dsl
            .selectFrom(POSTING_RULE)
            .where(POSTING_RULE.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE.ID.eq(ruleId))
            .fetchOne()
            ?.let(::toRule)

    override fun findRulesForEvent(
        organisationId: UUID,
        eventCode: String,
    ): List<PostingRule> =
        dsl
            .selectFrom(POSTING_RULE)
            .where(POSTING_RULE.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE.EVENT_CODE.eq(eventCode))
            .orderBy(POSTING_RULE.RULE_CODE)
            .fetch(::toRule)

    override fun createRule(rule: NewPostingRule): PostingRule {
        val now = OffsetDateTime.now(clock)
        return dsl
            .insertInto(POSTING_RULE)
            .set(POSTING_RULE.ORGANISATION_ID, rule.organisationId)
            .set(POSTING_RULE.RULE_CODE, rule.code)
            .set(POSTING_RULE.RULE_NAME, rule.name)
            .set(POSTING_RULE.DESCRIPTION, rule.description)
            .set(POSTING_RULE.EVENT_CODE, rule.selector.eventCode)
            .set(POSTING_RULE.PRODUCT_CLASS, rule.selector.productClass)
            .set(POSTING_RULE.CURRENCY_CODE, rule.selector.currencyCode)
            .set(POSTING_RULE.CREATED_AT, now)
            .set(POSTING_RULE.CREATED_BY, rule.actorId)
            .set(POSTING_RULE.UPDATED_AT, now)
            .set(POSTING_RULE.UPDATED_BY, rule.actorId)
            .returning()
            .fetchOne()!!
            .let(::toRule)
    }

    override fun lockRule(
        organisationId: UUID,
        ruleId: UUID,
    ): PostingRule? {
        requireActiveTransaction("Locking a posting rule")
        return dsl
            .selectFrom(POSTING_RULE)
            .where(POSTING_RULE.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE.ID.eq(ruleId))
            .forUpdate()
            .fetchOne()
            ?.let(::toRule)
    }

    override fun findVersion(
        organisationId: UUID,
        versionId: UUID,
    ): PostingRuleVersion? =
        dsl
            .selectFrom(POSTING_RULE_VERSION)
            .where(POSTING_RULE_VERSION.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE_VERSION.ID.eq(versionId))
            .fetchOne()
            ?.let(::toVersion)

    override fun findVersions(
        organisationId: UUID,
        ruleId: UUID,
    ): List<PostingRuleVersion> =
        dsl
            .selectFrom(POSTING_RULE_VERSION)
            .where(POSTING_RULE_VERSION.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE_VERSION.POSTING_RULE_ID.eq(ruleId))
            .orderBy(POSTING_RULE_VERSION.VERSION_NUMBER)
            .fetch(::toVersion)

    override fun createVersion(version: NewPostingRuleVersion): PostingRuleVersion {
        val now = OffsetDateTime.now(clock)
        return dsl
            .insertInto(POSTING_RULE_VERSION)
            .set(POSTING_RULE_VERSION.ORGANISATION_ID, version.organisationId)
            .set(POSTING_RULE_VERSION.POSTING_RULE_ID, version.ruleId)
            .set(POSTING_RULE_VERSION.VERSION_NUMBER, version.versionNumber)
            .set(POSTING_RULE_VERSION.STATUS, PostingRuleVersionStatus.DRAFT.name)
            .set(POSTING_RULE_VERSION.EFFECTIVE_FROM, version.effectiveFrom)
            .set(POSTING_RULE_VERSION.DESCRIPTION, version.description)
            .set(POSTING_RULE_VERSION.CREATED_AT, now)
            .set(POSTING_RULE_VERSION.CREATED_BY, version.actorId)
            .set(POSTING_RULE_VERSION.UPDATED_AT, now)
            .set(POSTING_RULE_VERSION.UPDATED_BY, version.actorId)
            .returning()
            .fetchOne()!!
            .let(::toVersion)
    }

    override fun updateDraftVersion(
        organisationId: UUID,
        versionId: UUID,
        effectiveFrom: LocalDate,
        description: String?,
        actorId: UUID,
    ): Boolean =
        dsl
            .update(POSTING_RULE_VERSION)
            .set(POSTING_RULE_VERSION.EFFECTIVE_FROM, effectiveFrom)
            .set(POSTING_RULE_VERSION.DESCRIPTION, description)
            .set(POSTING_RULE_VERSION.UPDATED_AT, OffsetDateTime.now(clock))
            .set(POSTING_RULE_VERSION.UPDATED_BY, actorId)
            .set(POSTING_RULE_VERSION.ROW_VERSION, POSTING_RULE_VERSION.ROW_VERSION.plus(1))
            .where(POSTING_RULE_VERSION.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE_VERSION.ID.eq(versionId))
            .and(POSTING_RULE_VERSION.STATUS.eq(PostingRuleVersionStatus.DRAFT.name))
            .execute() == 1

    @Suppress("LongParameterList")
    override fun updateStatus(
        organisationId: UUID,
        versionId: UUID,
        from: PostingRuleVersionStatus,
        to: PostingRuleVersionStatus,
        effectiveTo: LocalDate?,
        reason: String?,
        actorId: UUID,
    ): Boolean {
        requireActiveTransaction("Moving a posting-rule version")
        val update =
            dsl
                .update(POSTING_RULE_VERSION)
                .set(POSTING_RULE_VERSION.STATUS, to.name)
                .set(POSTING_RULE_VERSION.STATUS_REASON, reason)
                .set(POSTING_RULE_VERSION.UPDATED_AT, OffsetDateTime.now(clock))
                .set(POSTING_RULE_VERSION.UPDATED_BY, actorId)
                .set(POSTING_RULE_VERSION.ROW_VERSION, POSTING_RULE_VERSION.ROW_VERSION.plus(1))
        val withWindow =
            if (effectiveTo != null) {
                update.set(POSTING_RULE_VERSION.EFFECTIVE_TO, effectiveTo)
            } else {
                update
            }
        return withWindow
            .where(POSTING_RULE_VERSION.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE_VERSION.ID.eq(versionId))
            .and(POSTING_RULE_VERSION.STATUS.eq(from.name))
            .execute() == 1
    }

    override fun findLegs(
        organisationId: UUID,
        versionId: UUID,
    ): List<PostingRuleLeg> =
        dsl
            .selectFrom(POSTING_RULE_LEG)
            .where(POSTING_RULE_LEG.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE_LEG.POSTING_RULE_VERSION_ID.eq(versionId))
            .orderBy(POSTING_RULE_LEG.LEG_NUMBER)
            .fetch(::toLeg)

    override fun replaceLegs(
        organisationId: UUID,
        versionId: UUID,
        legs: List<PostingRuleLeg>,
        actorId: UUID,
    ) {
        requireActiveTransaction("Replacing posting-rule legs")
        dsl
            .deleteFrom(POSTING_RULE_LEG)
            .where(POSTING_RULE_LEG.ORGANISATION_ID.eq(organisationId))
            .and(POSTING_RULE_LEG.POSTING_RULE_VERSION_ID.eq(versionId))
            .execute()
        if (legs.isEmpty()) {
            return
        }
        val now = OffsetDateTime.now(clock)
        val insert =
            dsl.insertInto(
                POSTING_RULE_LEG,
                POSTING_RULE_LEG.ORGANISATION_ID,
                POSTING_RULE_LEG.POSTING_RULE_VERSION_ID,
                POSTING_RULE_LEG.LEG_NUMBER,
                POSTING_RULE_LEG.DIRECTION,
                POSTING_RULE_LEG.ACCOUNT_RESOLUTION,
                POSTING_RULE_LEG.GL_ACCOUNT_ID,
                POSTING_RULE_LEG.AMOUNT_SOURCE,
                POSTING_RULE_LEG.AMOUNT_PERCENTAGE,
                POSTING_RULE_LEG.IS_RESIDUAL,
                POSTING_RULE_LEG.NARRATIVE,
                POSTING_RULE_LEG.CREATED_AT,
                POSTING_RULE_LEG.CREATED_BY,
                POSTING_RULE_LEG.UPDATED_AT,
                POSTING_RULE_LEG.UPDATED_BY,
            )
        legs.forEach { leg ->
            insert.values(
                organisationId,
                versionId,
                leg.legNumber,
                leg.side.name,
                leg.accountResolution.name,
                leg.accountId,
                leg.amountSource,
                leg.amountPercentage,
                leg.isResidual,
                leg.narrative,
                now,
                actorId,
                now,
                actorId,
            )
        }
        insert.execute()
    }

    private fun toRule(record: PostingRuleRecord) =
        PostingRule(
            id = record.id!!,
            organisationId = record.organisationId!!,
            code = record.ruleCode!!,
            name = record.ruleName!!,
            description = record.description,
            selector =
                PostingRuleSelector(
                    eventCode = record.eventCode!!,
                    productClass = record.productClass,
                    currencyCode = record.currencyCode,
                ),
            rowVersion = record.rowVersion!!,
        )

    private fun toVersion(record: PostingRuleVersionRecord) =
        PostingRuleVersion(
            id = record.id!!,
            organisationId = record.organisationId!!,
            ruleId = record.postingRuleId!!,
            versionNumber = record.versionNumber!!,
            status = PostingRuleVersionStatus.valueOf(record.status!!),
            effectiveFrom = record.effectiveFrom!!,
            effectiveTo = record.effectiveTo,
            description = record.description,
            statusReason = record.statusReason,
            rowVersion = record.rowVersion!!,
        )

    private fun toLeg(record: PostingRuleLegRecord) =
        PostingRuleLeg(
            legNumber = record.legNumber!!,
            side = PostingSide.valueOf(record.direction!!),
            accountResolution = AccountResolution.valueOf(record.accountResolution!!),
            accountId = record.glAccountId!!,
            amountSource = record.amountSource!!,
            amountPercentage = record.amountPercentage!!,
            isResidual = record.isResidual!!,
            narrative = record.narrative,
        )

    private fun requireActiveTransaction(operation: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "$operation takes a row lock or writes rows that must commit with their transaction."
        }
    }
}
