package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.api.boundedPageOffset
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE_HISTORY
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.lifecycle.application.query.BranchDetail
import com.finaxis.platform.lifecycle.application.query.BranchFilter
import com.finaxis.platform.lifecycle.application.query.BranchSummary
import com.finaxis.platform.lifecycle.application.query.BusinessDateHistoryDetail
import com.finaxis.platform.lifecycle.application.query.BusinessDateHistoryFilter
import com.finaxis.platform.lifecycle.application.query.BusinessDateHistorySummary
import com.finaxis.platform.lifecycle.application.query.FoundationQueryStore
import com.finaxis.platform.lifecycle.application.query.TenantDetail
import com.finaxis.platform.lifecycle.application.query.TenantFilter
import com.finaxis.platform.lifecycle.application.query.TenantSummary
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.util.UUID

/**
 * jOOQ implementation of the [FoundationQueryStore] port.
 * Executes database reads for tenants, branches, and business date histories.
 */
@Component
class JooqFoundationQueryStore(
    private val dsl: DSLContext,
) : FoundationQueryStore {
    override fun searchTenants(
        filter: TenantFilter,
        organisationId: UUID?,
    ): ApiPage<TenantSummary> {
        var condition: Condition = DSL.noCondition()
        if (organisationId != null) {
            condition = condition.and(ORGANISATION.ID.eq(organisationId))
        }
        filter.status?.let { condition = condition.and(ORGANISATION.STATUS.eq(it)) }
        filter.country?.let { condition = condition.and(ORGANISATION.COUNTRY_CODE.eq(it)) }
        filter.createdFrom?.let {
            condition = condition.and(ORGANISATION.CREATED_AT.ge(it.atOffset(ZoneOffset.UTC)))
        }
        filter.createdTo?.let {
            condition = condition.and(ORGANISATION.CREATED_AT.le(it.atOffset(ZoneOffset.UTC)))
        }
        filter.q?.let { q ->
            val query = "%$q%"
            condition =
                condition.and(
                    ORGANISATION.TENANT_CODE
                        .likeIgnoreCase(query)
                        .or(ORGANISATION.DISPLAY_NAME.likeIgnoreCase(query)),
                )
        }

        val total = dsl.fetchCount(ORGANISATION, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(
                    ORGANISATION.ID,
                    ORGANISATION.TENANT_CODE,
                    ORGANISATION.DISPLAY_NAME,
                    ORGANISATION.COUNTRY_CODE,
                    ORGANISATION.STATUS,
                    ORGANISATION.CREATED_AT,
                ).from(ORGANISATION)
                .where(condition)
                .orderBy(
                    tenantSortOrder(tenantSortField(filter.sortBy), filter.sortDir),
                    ORGANISATION.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch { record ->
                    TenantSummary(
                        id = requireNotNull(record.get(ORGANISATION.ID)),
                        tenantCode = requireNotNull(record.get(ORGANISATION.TENANT_CODE)),
                        displayName = requireNotNull(record.get(ORGANISATION.DISPLAY_NAME)),
                        countryCode = requireNotNull(record.get(ORGANISATION.COUNTRY_CODE)),
                        status = requireNotNull(record.get(ORGANISATION.STATUS)),
                        createdAt = requireNotNull(record.get(ORGANISATION.CREATED_AT)).toInstant(),
                    )
                }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findTenantById(id: UUID): TenantDetail? =
        dsl
            .select(
                ORGANISATION.ID,
                ORGANISATION.TENANT_CODE,
                ORGANISATION.DISPLAY_NAME,
                ORGANISATION.COUNTRY_CODE,
                ORGANISATION.BASE_CURRENCY_CODE,
                ORGANISATION.TIMEZONE,
                ORGANISATION.STATUS,
                ORGANISATION.CREATED_AT,
                ORGANISATION.UPDATED_AT,
            ).from(ORGANISATION)
            .where(ORGANISATION.ID.eq(id))
            .fetchOne { record ->
                TenantDetail(
                    id = requireNotNull(record.get(ORGANISATION.ID)),
                    tenantCode = requireNotNull(record.get(ORGANISATION.TENANT_CODE)),
                    displayName = requireNotNull(record.get(ORGANISATION.DISPLAY_NAME)),
                    countryCode = requireNotNull(record.get(ORGANISATION.COUNTRY_CODE)),
                    baseCurrencyCode = requireNotNull(record.get(ORGANISATION.BASE_CURRENCY_CODE)),
                    timezone = requireNotNull(record.get(ORGANISATION.TIMEZONE)),
                    status = requireNotNull(record.get(ORGANISATION.STATUS)),
                    createdAt = requireNotNull(record.get(ORGANISATION.CREATED_AT)).toInstant(),
                    updatedAt = requireNotNull(record.get(ORGANISATION.UPDATED_AT)).toInstant(),
                )
            }

    override fun searchBranches(
        organisationId: UUID,
        filter: BranchFilter,
    ): ApiPage<BranchSummary> {
        var condition: Condition = BRANCH.ORGANISATION_ID.eq(organisationId)
        filter.status?.let { condition = condition.and(BRANCH.STATUS.eq(it)) }
        filter.type?.let { condition = condition.and(BRANCH.BRANCH_TYPE.eq(it)) }
        filter.q?.let { q ->
            val query = "%$q%"
            condition =
                condition.and(
                    BRANCH.BRANCH_CODE
                        .likeIgnoreCase(query)
                        .or(BRANCH.BRANCH_NAME.likeIgnoreCase(query)),
                )
        }

        val total = dsl.fetchCount(BRANCH, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(
                    BRANCH.ID,
                    BRANCH.ORGANISATION_ID,
                    BRANCH.BRANCH_CODE,
                    BRANCH.BRANCH_NAME,
                    BRANCH.BRANCH_TYPE,
                    BRANCH.STATUS,
                    BRANCH.CREATED_AT,
                ).from(BRANCH)
                .where(condition)
                .orderBy(
                    branchSortOrder(branchSortField(filter.sortBy), filter.sortDir),
                    BRANCH.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch { record ->
                    BranchSummary(
                        id = requireNotNull(record.get(BRANCH.ID)),
                        organisationId = requireNotNull(record.get(BRANCH.ORGANISATION_ID)),
                        branchCode = requireNotNull(record.get(BRANCH.BRANCH_CODE)),
                        branchName = requireNotNull(record.get(BRANCH.BRANCH_NAME)),
                        branchType = requireNotNull(record.get(BRANCH.BRANCH_TYPE)),
                        status = requireNotNull(record.get(BRANCH.STATUS)),
                        createdAt = requireNotNull(record.get(BRANCH.CREATED_AT)).toInstant(),
                    )
                }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findBranchById(
        organisationId: UUID,
        id: UUID,
    ): BranchDetail? =
        dsl
            .select(
                BRANCH.ID,
                BRANCH.ORGANISATION_ID,
                BRANCH.BRANCH_CODE,
                BRANCH.BRANCH_NAME,
                BRANCH.BRANCH_TYPE,
                BRANCH.PARENT_BRANCH_ID,
                BRANCH.STATUS,
                BRANCH.TIMEZONE,
                BRANCH.ADDRESS_JSONB,
                BRANCH.OPENED_ON,
                BRANCH.CLOSED_ON,
                BRANCH.STATUS_REASON,
                BRANCH.CREATED_AT,
                BRANCH.UPDATED_AT,
            ).from(BRANCH)
            .where(BRANCH.ID.eq(id))
            .and(BRANCH.ORGANISATION_ID.eq(organisationId))
            .fetchOne { record ->
                BranchDetail(
                    id = requireNotNull(record.get(BRANCH.ID)),
                    organisationId = requireNotNull(record.get(BRANCH.ORGANISATION_ID)),
                    branchCode = requireNotNull(record.get(BRANCH.BRANCH_CODE)),
                    branchName = requireNotNull(record.get(BRANCH.BRANCH_NAME)),
                    branchType = requireNotNull(record.get(BRANCH.BRANCH_TYPE)),
                    parentBranchId = record.get(BRANCH.PARENT_BRANCH_ID),
                    status = requireNotNull(record.get(BRANCH.STATUS)),
                    timezone = requireNotNull(record.get(BRANCH.TIMEZONE)),
                    addressJson = requireNotNull(record.get(BRANCH.ADDRESS_JSONB)).data(),
                    openedOn = record.get(BRANCH.OPENED_ON),
                    closedOn = record.get(BRANCH.CLOSED_ON),
                    statusReason = record.get(BRANCH.STATUS_REASON),
                    createdAt = requireNotNull(record.get(BRANCH.CREATED_AT)).toInstant(),
                    updatedAt = requireNotNull(record.get(BRANCH.UPDATED_AT)).toInstant(),
                )
            }

    override fun listBusinessDateHistory(
        organisationId: UUID,
        filter: BusinessDateHistoryFilter,
    ): ApiPage<BusinessDateHistorySummary> {
        val condition = BUSINESS_DATE_HISTORY.ORGANISATION_ID.eq(organisationId)
        val total = dsl.fetchCount(BUSINESS_DATE_HISTORY, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(
                    BUSINESS_DATE_HISTORY.ID,
                    BUSINESS_DATE_HISTORY.ORGANISATION_ID,
                    BUSINESS_DATE_HISTORY.EVENT_TYPE,
                    BUSINESS_DATE_HISTORY.TO_STATUS,
                    BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE,
                    BUSINESS_DATE_HISTORY.OCCURRED_AT,
                ).from(BUSINESS_DATE_HISTORY)
                .where(condition)
                .orderBy(BUSINESS_DATE_HISTORY.OCCURRED_AT.desc(), BUSINESS_DATE_HISTORY.ID.desc())
                .limit(filter.size)
                .offset(offset)
                .fetch { record ->
                    BusinessDateHistorySummary(
                        id = requireNotNull(record.get(BUSINESS_DATE_HISTORY.ID)),
                        organisationId =
                            requireNotNull(
                                record.get(BUSINESS_DATE_HISTORY.ORGANISATION_ID),
                            ),
                        eventType =
                            requireNotNull(
                                record.get(BUSINESS_DATE_HISTORY.EVENT_TYPE),
                            ),
                        toStatus = requireNotNull(record.get(BUSINESS_DATE_HISTORY.TO_STATUS)),
                        toBusinessDate =
                            requireNotNull(
                                record.get(BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE),
                            ),
                        occurredAt =
                            requireNotNull(
                                record.get(BUSINESS_DATE_HISTORY.OCCURRED_AT),
                            ).toInstant(),
                    )
                }

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findBusinessDateHistoryById(
        organisationId: UUID,
        id: UUID,
    ): BusinessDateHistoryDetail? =
        dsl
            .select(
                BUSINESS_DATE_HISTORY.ID,
                BUSINESS_DATE_HISTORY.ORGANISATION_ID,
                BUSINESS_DATE_HISTORY.EVENT_TYPE,
                BUSINESS_DATE_HISTORY.FROM_STATUS,
                BUSINESS_DATE_HISTORY.TO_STATUS,
                BUSINESS_DATE_HISTORY.FROM_BUSINESS_DATE,
                BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE,
                BUSINESS_DATE_HISTORY.ACTOR_ID,
                BUSINESS_DATE_HISTORY.REASON,
                BUSINESS_DATE_HISTORY.OCCURRED_AT,
                BUSINESS_DATE_HISTORY.CREATED_AT,
                BUSINESS_DATE_HISTORY.CREATED_BY,
            ).from(BUSINESS_DATE_HISTORY)
            .where(BUSINESS_DATE_HISTORY.ID.eq(id))
            .and(BUSINESS_DATE_HISTORY.ORGANISATION_ID.eq(organisationId))
            .fetchOne { record ->
                BusinessDateHistoryDetail(
                    id = requireNotNull(record.get(BUSINESS_DATE_HISTORY.ID)),
                    organisationId =
                        requireNotNull(
                            record.get(BUSINESS_DATE_HISTORY.ORGANISATION_ID),
                        ),
                    eventType = requireNotNull(record.get(BUSINESS_DATE_HISTORY.EVENT_TYPE)),
                    fromStatus = record.get(BUSINESS_DATE_HISTORY.FROM_STATUS),
                    toStatus = requireNotNull(record.get(BUSINESS_DATE_HISTORY.TO_STATUS)),
                    fromBusinessDate = record.get(BUSINESS_DATE_HISTORY.FROM_BUSINESS_DATE),
                    toBusinessDate =
                        requireNotNull(
                            record.get(BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE),
                        ),
                    actorId = record.get(BUSINESS_DATE_HISTORY.ACTOR_ID),
                    reason = record.get(BUSINESS_DATE_HISTORY.REASON),
                    occurredAt =
                        requireNotNull(
                            record.get(BUSINESS_DATE_HISTORY.OCCURRED_AT),
                        ).toInstant(),
                    createdAt =
                        requireNotNull(
                            record.get(BUSINESS_DATE_HISTORY.CREATED_AT),
                        ).toInstant(),
                    createdBy = record.get(BUSINESS_DATE_HISTORY.CREATED_BY),
                )
            }

    private fun tenantSortField(sortBy: String?): org.jooq.Field<*> =
        when (sortBy) {
            "tenantCode" -> ORGANISATION.TENANT_CODE
            "displayName" -> ORGANISATION.DISPLAY_NAME
            "countryCode" -> ORGANISATION.COUNTRY_CODE
            else -> ORGANISATION.CREATED_AT
        }

    private fun tenantSortOrder(
        field: org.jooq.Field<*>,
        sortDir: String?,
    ): org.jooq.SortField<*> = if (sortDir?.uppercase() == "ASC") field.asc() else field.desc()

    private fun branchSortField(sortBy: String?): org.jooq.Field<*> =
        when (sortBy) {
            "branchCode" -> BRANCH.BRANCH_CODE
            "branchName" -> BRANCH.BRANCH_NAME
            "branchType" -> BRANCH.BRANCH_TYPE
            "status" -> BRANCH.STATUS
            else -> BRANCH.CREATED_AT
        }

    private fun branchSortOrder(
        field: org.jooq.Field<*>,
        sortDir: String?,
    ): org.jooq.SortField<*> = if (sortDir?.uppercase() == "ASC") field.asc() else field.desc()
}
