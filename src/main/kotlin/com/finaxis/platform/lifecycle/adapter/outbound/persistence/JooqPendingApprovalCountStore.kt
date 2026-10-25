package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.query.PendingApprovalCountStore
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * jOOQ adapter for the pending-approval counts: three single-statement `COUNT(*)` reads. The
 * membership and branch counts are tenant-filtered and range over the existing
 * `(organisation_id, status)` indexes; the tenant count reads the organisation table itself, one
 * row per tenant, so it is bounded by the number of tenants and by nothing a tenant writes.
 */
@Component
class JooqPendingApprovalCountStore(
    private val dsl: DSLContext,
) : PendingApprovalCountStore {
    override fun pendingMemberships(
        organisationId: UUID,
        excludingMaker: UUID?,
    ): Long =
        dsl
            .selectCount()
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS.eq(PENDING_APPROVAL))
            .and(
                excludingMaker
                    ?.let { USER_ORGANISATION_MEMBERSHIP.CREATED_BY.isDistinctFrom(it) }
                    ?: DSL.noCondition(),
            ).fetchSingle(0, Long::class.java) ?: 0L

    override fun pendingBranches(
        organisationId: UUID,
        excludingMaker: UUID?,
    ): Long =
        dsl
            .selectCount()
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(organisationId))
            .and(BRANCH.STATUS.eq(PENDING_APPROVAL))
            .and(excludingMaker?.let { BRANCH.CREATED_BY.isDistinctFrom(it) } ?: DSL.noCondition())
            .fetchSingle(0, Long::class.java) ?: 0L

    override fun pendingTenants(excludingMaker: UUID?): Long {
        val bootstrap = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        val madeByCaller =
            excludingMaker?.let {
                DSL.notExists(
                    DSL
                        .selectOne()
                        .from(bootstrap)
                        .where(bootstrap.ORGANISATION_ID.eq(ORGANISATION.ID))
                        .and(bootstrap.REQUESTED_BY.eq(it).or(bootstrap.SUBMITTED_BY.eq(it))),
                )
            } ?: DSL.noCondition()
        return dsl
            .selectCount()
            .from(ORGANISATION)
            .where(ORGANISATION.STATUS.eq(PENDING_APPROVAL))
            .and(ORGANISATION.ID.ne(PlatformOrganisation.ID))
            .and(madeByCaller)
            .fetchSingle(0, Long::class.java) ?: 0L
    }

    private companion object {
        const val PENDING_APPROVAL = "PENDING_APPROVAL"
    }
}
