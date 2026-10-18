package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.api.boundedPageOffset
import com.finaxis.platform.iam.application.query.GlobalUserFilter
import com.finaxis.platform.iam.application.query.GlobalUserMembership
import com.finaxis.platform.iam.application.query.GlobalUserProfile
import com.finaxis.platform.iam.application.query.GlobalUserQueries
import com.finaxis.platform.iam.application.query.GlobalUserSummary
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.SortField
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/** jOOQ implementation of the platform-wide user directory. */
@Component
class JooqGlobalUserQueries(
    private val dsl: DSLContext,
) : GlobalUserQueries {
    override fun searchUsers(filter: GlobalUserFilter): ApiPage<GlobalUserSummary> {
        val condition = filter.condition()
        val total = dsl.fetchCount(USER_ACCOUNT, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(USER_ACCOUNT.fields().toList() + MEMBERSHIP_COUNT)
                .from(USER_ACCOUNT)
                .where(condition)
                .orderBy(filter.sortOrder(), USER_ACCOUNT.ID.desc())
                .limit(filter.size)
                .offset(offset)
                .fetch { it.toSummary() }
        return apiPageOf(items, filter.page, filter.size, total)
    }

    override fun findUser(userId: UUID): GlobalUserProfile? {
        val user =
            dsl
                .select(USER_ACCOUNT.fields().toList() + MEMBERSHIP_COUNT)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(userId))
                .fetchOne { it.toSummary() }
                ?: return null
        val memberships =
            dsl
                .select(
                    USER_ORGANISATION_MEMBERSHIP.ID,
                    USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                    ORGANISATION.TENANT_CODE,
                    ORGANISATION.DISPLAY_NAME,
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE,
                    USER_ORGANISATION_MEMBERSHIP.CREATED_AT,
                ).from(USER_ORGANISATION_MEMBERSHIP)
                .join(ORGANISATION)
                .on(ORGANISATION.ID.eq(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID))
                .where(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
                .orderBy(
                    USER_ORGANISATION_MEMBERSHIP.CREATED_AT.desc(),
                    USER_ORGANISATION_MEMBERSHIP.ID.desc(),
                ).limit(GlobalUserQueries.MAX_MEMBERSHIPS)
                .fetch { it.toMembership() }
        return GlobalUserProfile(user, memberships)
    }

    private companion object {
        /** A correlated count on `idx_membership_user`, so a page is still one statement. */
        val MEMBERSHIP_COUNT: Field<Int> =
            DSL
                .field(
                    DSL
                        .selectCount()
                        .from(USER_ORGANISATION_MEMBERSHIP)
                        .where(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID)),
                ).`as`("membership_count")
    }
}

private fun GlobalUserFilter.condition(): Condition {
    var condition: Condition = DSL.trueCondition()
    userStatus?.let { condition = condition.and(USER_ACCOUNT.STATUS.eq(it)) }
    organisationId?.let {
        condition =
            condition.and(
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(USER_ORGANISATION_MEMBERSHIP)
                        .where(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID))
                        .and(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(it)),
                ),
            )
    }
    q?.let { text ->
        val pattern = "%$text%"
        condition =
            condition.and(
                USER_ACCOUNT.USERNAME
                    .likeIgnoreCase(pattern)
                    .or(USER_ACCOUNT.EMAIL.likeIgnoreCase(pattern))
                    .or(USER_ACCOUNT.DISPLAY_NAME.likeIgnoreCase(pattern)),
            )
    }
    return condition
}

private fun GlobalUserFilter.sortOrder(): SortField<*> {
    val field: Field<*> =
        when (sortBy) {
            "username" -> USER_ACCOUNT.USERNAME
            "displayName" -> USER_ACCOUNT.DISPLAY_NAME
            "email" -> USER_ACCOUNT.EMAIL
            "userStatus" -> USER_ACCOUNT.STATUS
            else -> USER_ACCOUNT.CREATED_AT
        }
    // `sort_dir` applies only with a `sort_by`: the default key stays newest first.
    return if (sortBy != null && sortDir?.uppercase() == "ASC") field.asc() else field.desc()
}

private fun Record.toSummary() =
    GlobalUserSummary(
        id = requireNotNull(get(USER_ACCOUNT.ID)),
        username = requireNotNull(get(USER_ACCOUNT.USERNAME)),
        email = requireNotNull(get(USER_ACCOUNT.EMAIL)),
        displayName = requireNotNull(get(USER_ACCOUNT.DISPLAY_NAME)),
        userStatus = requireNotNull(get(USER_ACCOUNT.STATUS)),
        createdAt = requireNotNull(get(USER_ACCOUNT.CREATED_AT)).toInstant(),
        membershipCount = requireNotNull(get("membership_count", Int::class.java)),
    )

private fun Record.toMembership() =
    GlobalUserMembership(
        membershipId = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.ID)),
        organisationId = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID)),
        tenantCode = requireNotNull(get(ORGANISATION.TENANT_CODE)),
        organisationName = requireNotNull(get(ORGANISATION.DISPLAY_NAME)),
        membershipStatus = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)),
        membershipType = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE)),
        createdAt = requireNotNull(get(USER_ORGANISATION_MEMBERSHIP.CREATED_AT)).toInstant(),
    )
