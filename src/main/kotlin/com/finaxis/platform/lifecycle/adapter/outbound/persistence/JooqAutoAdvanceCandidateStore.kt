package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.lifecycle.application.AutoAdvanceCandidate
import com.finaxis.platform.lifecycle.application.AutoAdvanceCandidateStore
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SelectConditionStep
import org.jooq.SelectSelectStep
import org.springframework.stereotype.Component

/**
 * The organisations the scheduled business-date advance may move (#277): bounded reads, joined on
 * the current setting row so a tenant that never stored `true` is never read, in organisation-id
 * order so a run can take its window of them ([autoAdvanceCandidates]'s offset). The local date is
 * compared in Kotlin, not with SQL `AT TIME ZONE`, which reads an offset id with the POSIX sign.
 */
@Component
class JooqAutoAdvanceCandidateStore(
    private val dsl: DSLContext,
) : AutoAdvanceCandidateStore {
    override fun countAutoAdvanceCandidates(): Int = dsl.fetchCount(candidates(dsl.selectOne()))

    override fun autoAdvanceCandidates(
        limit: Int,
        offset: Int,
    ): List<AutoAdvanceCandidate> =
        candidates(
            dsl.select(
                ORGANISATION.ID,
                ORGANISATION.TIMEZONE,
                BUSINESS_DATE.CURRENT_BUSINESS_DATE,
            ),
        ).orderBy(ORGANISATION.ID)
            .limit(limit)
            .offset(offset)
            .fetch { record ->
                AutoAdvanceCandidate(
                    organisationId = requireNotNull(record.value1()),
                    timezone = requireNotNull(record.value2()),
                    currentBusinessDate = requireNotNull(record.value3()),
                )
            }

    /** [select] over the candidates: opted in, `ACTIVE`, not the platform, the date `OPEN`. */
    private fun <R : Record> candidates(select: SelectSelectStep<R>): SelectConditionStep<R> =
        select
            .from(ORGANISATION)
            .join(BUSINESS_DATE)
            .on(BUSINESS_DATE.ORGANISATION_ID.eq(ORGANISATION.ID))
            .join(ORGANISATION_SETTING)
            .on(ORGANISATION_SETTING.ORGANISATION_ID.eq(ORGANISATION.ID))
            .and(
                ORGANISATION_SETTING.SETTING_KEY.eq(
                    TenantSettingCatalog.BUSINESS_DATE_AUTO_ADVANCE_KEY,
                ),
            ).and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .and(settingText().eq(ENABLED))
            .where(ORGANISATION.STATUS.eq(OrganisationLifecycleState.ACTIVE.name))
            .and(ORGANISATION.ID.ne(PlatformOrganisation.ID))
            .and(BUSINESS_DATE.STATUS.eq(OPEN))

    private companion object {
        const val ENABLED = "true"
        const val OPEN = "OPEN"
    }
}
