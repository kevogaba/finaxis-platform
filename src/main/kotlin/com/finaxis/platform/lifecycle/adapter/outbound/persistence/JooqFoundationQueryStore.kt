package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.common.application.ActorRef
import com.finaxis.platform.common.persistence.ActorRefLookup
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.api.boundedPageOffset
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BRANCH_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE_HISTORY
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode
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
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.util.UUID

private const val ACTIVE = "ACTIVE"

/**
 * jOOQ implementation of the [FoundationQueryStore] port.
 * Executes database reads for tenants, branches, and business date histories.
 */
@Component
class JooqFoundationQueryStore(
    private val dsl: DSLContext,
) : FoundationQueryStore {
    private val actors = ActorRefLookup(dsl)

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
                    ORGANISATION.BASE_CURRENCY_CODE,
                    ORGANISATION.TIMEZONE,
                    BOOTSTRAP.STATUS,
                    BOOTSTRAP.REQUESTED_BY,
                    BOOTSTRAP.SUBMITTED_BY,
                    ACTIVE_BRANCH_COUNT,
                    ACTIVE_MEMBER_COUNT,
                ).from(ORGANISATION)
                .leftJoin(BOOTSTRAP)
                .on(BOOTSTRAP.ORGANISATION_ID.eq(ORGANISATION.ID))
                .where(condition)
                .orderBy(
                    tenantSortOrder(tenantSortField(filter.sortBy), filter.sortDir),
                    ORGANISATION.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch()
                .let(::tenantSummaries)

        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findTenantById(id: UUID): TenantDetail? {
        val bootstrap = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        return dsl
            .select(
                ORGANISATION.ID,
                ORGANISATION.TENANT_CODE,
                ORGANISATION.DISPLAY_NAME,
                ORGANISATION.COUNTRY_CODE,
                ORGANISATION.BASE_CURRENCY_CODE,
                ORGANISATION.TIMEZONE,
                ORGANISATION.STATUS,
                ORGANISATION.STATUS_REASON,
                ORGANISATION.CREATED_AT,
                ORGANISATION.UPDATED_AT,
                ORGANISATION.LEGAL_NAME,
                ORGANISATION.REGISTRATION_NUMBER,
                bootstrap.STATUS,
                bootstrap.LAST_FAILURE_CODE,
                bootstrap.REQUESTED_BY,
                bootstrap.SUBMITTED_BY,
            ).from(ORGANISATION)
            .leftJoin(bootstrap)
            .on(bootstrap.ORGANISATION_ID.eq(ORGANISATION.ID))
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
                    statusReason = record.get(ORGANISATION.STATUS_REASON),
                    createdAt = requireNotNull(record.get(ORGANISATION.CREATED_AT)).toInstant(),
                    updatedAt = requireNotNull(record.get(ORGANISATION.UPDATED_AT)).toInstant(),
                    bootstrapStatus = record.get(bootstrap.STATUS),
                    bootstrapFailureCode =
                        InitialAdministratorBootstrapFailureCode.fromStored(
                            record.get(bootstrap.LAST_FAILURE_CODE),
                        ),
                    legalName = record.get(ORGANISATION.LEGAL_NAME),
                    registrationNumber = record.get(ORGANISATION.REGISTRATION_NUMBER),
                    requestedBy = makerOf(null, record.get(bootstrap.REQUESTED_BY)),
                    submittedBy = makerOf(null, record.get(bootstrap.SUBMITTED_BY)),
                )
            }
    }

    override fun searchBranches(
        organisationId: UUID,
        filter: BranchFilter,
        restrictToBranchIds: Set<UUID>?,
    ): ApiPage<BranchSummary> {
        var condition: Condition = BRANCH.ORGANISATION_ID.eq(organisationId)
        restrictToBranchIds?.let { condition = condition.and(BRANCH.ID.`in`(it)) }
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
                    BRANCH.PARENT_BRANCH_ID,
                    BRANCH.TIMEZONE,
                    BRANCH.OPENED_ON,
                    BRANCH.CLOSED_ON,
                    BRANCH.CREATED_BY,
                    ACTIVE_ASSIGNEE_COUNT,
                ).from(BRANCH)
                .where(condition)
                .orderBy(
                    branchSortOrder(branchSortField(filter.sortBy), filter.sortDir),
                    BRANCH.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch()
                .let { rows ->
                    val submitters =
                        branchSubmitters(organisationId, rows.map { it.get(BRANCH.ID) })
                    val makers =
                        actors.find(
                            organisationId,
                            rows.map { it.get(BRANCH.CREATED_BY) } + submitters.values,
                        )
                    rows.map { it.toBranchSummary(makers, submitters) }
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
                BRANCH.CREATED_BY,
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
                    createdBy = makerOf(organisationId, record.get(BRANCH.CREATED_BY)),
                    submittedBy =
                        makerOf(organisationId, branchSubmitters(organisationId, listOf(id))[id]),
                )
            }

    /**
     * The page's tenants with their makers resolved in one lookup. The makers of a tenant are
     * platform users, so nobody is a member of the tenant.
     */
    private fun tenantSummaries(rows: List<Record>): List<TenantSummary> {
        val makers =
            actors.find(
                null,
                rows.flatMap {
                    listOf(it.get(BOOTSTRAP.REQUESTED_BY), it.get(BOOTSTRAP.SUBMITTED_BY))
                },
            )
        return rows.map { it.toTenantSummary(makers) }
    }

    /** The maker [id] names, or null for no id, the system actor or an unknown account. */
    private fun makerOf(
        organisationId: UUID?,
        id: UUID?,
    ): ActorRef? = id?.let { actors.find(organisationId, listOf(it))[it] }

    /**
     * The actor of the latest SUBMIT of each of [branchIds], read for the whole page in one
     * statement (the same log and index the approver rule reads); a branch never submitted is
     * absent.
     */
    private fun branchSubmitters(
        organisationId: UUID,
        branchIds: Collection<UUID?>,
    ): Map<UUID, UUID> {
        val ids = branchIds.filterNotNull()
        if (ids.isEmpty()) return emptyMap()
        val latest = mutableMapOf<UUID, UUID?>()
        dsl
            .select(BRANCH_TRANSITION_LOG.ENTITY_ID, BRANCH_TRANSITION_LOG.CREATED_BY)
            .from(BRANCH_TRANSITION_LOG)
            .where(BRANCH_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId))
            .and(BRANCH_TRANSITION_LOG.ENTITY_ID.`in`(ids))
            .and(BRANCH_TRANSITION_LOG.TRANSITION_NAME.eq(BranchLifecycleTransition.SUBMIT.name))
            .orderBy(BRANCH_TRANSITION_LOG.CREATED_AT.desc(), BRANCH_TRANSITION_LOG.ID.desc())
            .fetch()
            .forEach { row ->
                // Newest first: the first row of a branch is its latest submission.
                val branchId = requireNotNull(row.get(BRANCH_TRANSITION_LOG.ENTITY_ID))
                if (branchId !in
                    latest
                ) {
                    latest[branchId] = row.get(BRANCH_TRANSITION_LOG.CREATED_BY)
                }
            }
        return latest.mapNotNull { (branch, actor) -> actor?.let { branch to it } }.toMap()
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

private val BOOTSTRAP = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP

// The list counts are correlated subselects inside the page's one statement, so a page costs the
// same number of statements at any size. The first two lean on idx_branch_organisation_status and
// the (organisation_id, user_id) key of the membership, the third on
// idx_user_branch_assignment_organisation_branch.
private val ACTIVE_BRANCH_COUNT: Field<Int> =
    DSL.field(
        DSL
            .selectCount()
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(ORGANISATION.ID))
            .and(BRANCH.STATUS.eq(ACTIVE)),
    )

private val ACTIVE_MEMBER_COUNT: Field<Int> =
    DSL.field(
        DSL
            .selectCount()
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(ORGANISATION.ID))
            .and(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS.eq(ACTIVE)),
    )

private val ACTIVE_ASSIGNEE_COUNT: Field<Int> =
    DSL.field(
        DSL
            .select(DSL.countDistinct(USER_BRANCH_ASSIGNMENT.USER_ID))
            .from(USER_BRANCH_ASSIGNMENT)
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(BRANCH.ORGANISATION_ID))
            .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(BRANCH.ID))
            .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE)),
    )

private fun Record.toTenantSummary(makers: Map<UUID, ActorRef>) =
    TenantSummary(
        id = requireNotNull(get(ORGANISATION.ID)),
        tenantCode = requireNotNull(get(ORGANISATION.TENANT_CODE)),
        displayName = requireNotNull(get(ORGANISATION.DISPLAY_NAME)),
        countryCode = requireNotNull(get(ORGANISATION.COUNTRY_CODE)),
        status = requireNotNull(get(ORGANISATION.STATUS)),
        createdAt = requireNotNull(get(ORGANISATION.CREATED_AT)).toInstant(),
        baseCurrencyCode =
            requireNotNull(get(ORGANISATION.BASE_CURRENCY_CODE)),
        timezone = requireNotNull(get(ORGANISATION.TIMEZONE)),
        bootstrapStatus = get(BOOTSTRAP.STATUS),
        activeBranchCount = get(ACTIVE_BRANCH_COUNT),
        activeUserCount = get(ACTIVE_MEMBER_COUNT),
        requestedBy = get(BOOTSTRAP.REQUESTED_BY)?.let(makers::get),
        submittedBy = get(BOOTSTRAP.SUBMITTED_BY)?.let(makers::get),
    )

private fun Record.toBranchSummary(
    makers: Map<UUID, ActorRef>,
    submitters: Map<UUID, UUID>,
) = BranchSummary(
    id = requireNotNull(get(BRANCH.ID)),
    organisationId = requireNotNull(get(BRANCH.ORGANISATION_ID)),
    branchCode = requireNotNull(get(BRANCH.BRANCH_CODE)),
    branchName = requireNotNull(get(BRANCH.BRANCH_NAME)),
    branchType = requireNotNull(get(BRANCH.BRANCH_TYPE)),
    status = requireNotNull(get(BRANCH.STATUS)),
    createdAt = requireNotNull(get(BRANCH.CREATED_AT)).toInstant(),
    parentBranchId = get(BRANCH.PARENT_BRANCH_ID),
    timezone = requireNotNull(get(BRANCH.TIMEZONE)),
    openedOn = get(BRANCH.OPENED_ON),
    closedOn = get(BRANCH.CLOSED_ON),
    activeUserCount = get(ACTIVE_ASSIGNEE_COUNT),
    createdBy = get(BRANCH.CREATED_BY)?.let(makers::get),
    submittedBy = submitters[get(BRANCH.ID)]?.let(makers::get),
)
