package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.api.boundedPageOffset
import com.finaxis.platform.iam.application.query.IamPermissionQueries
import com.finaxis.platform.iam.application.query.PermissionDetail
import com.finaxis.platform.iam.application.query.PermissionFilter
import com.finaxis.platform.iam.application.query.PermissionSummary
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION_VIEW_REQUIREMENT
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * jOOQ implementation of the permission catalogue read port.
 *
 * Reads the immutable catalogue, including the `V22` metadata: each code's `kind`, `grant_scope`
 * and the view permissions a mutation requires (`permission_view_requirement`, ADR 0030).
 */
@Component
class JooqIamPermissionQueries(
    private val dsl: DSLContext,
) : IamPermissionQueries {
    override fun searchPermissions(filter: PermissionFilter): ApiPage<PermissionSummary> {
        var condition: Condition = DSL.noCondition()
        filter.status?.let { condition = condition.and(PERMISSION.STATUS.eq(it)) }
        filter.riskLevel?.let { condition = condition.and(PERMISSION.RISK_LEVEL.eq(it)) }
        filter.q?.let { q ->
            val query = "%$q%"
            condition =
                condition.and(
                    PERMISSION.PERMISSION_CODE
                        .likeIgnoreCase(query)
                        .or(PERMISSION.PERMISSION_NAME.likeIgnoreCase(query)),
                )
        }

        val total = dsl.fetchCount(PERMISSION, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return apiPageOf(emptyList(), filter.page, filter.size, total)
        val items =
            dsl
                .select(
                    PERMISSION.ID,
                    PERMISSION.PERMISSION_CODE,
                    PERMISSION.PERMISSION_NAME,
                    PERMISSION.MODULE_CODE,
                    PERMISSION.RISK_LEVEL,
                    PERMISSION.STATUS,
                    PERMISSION.KIND,
                    PERMISSION.GRANT_SCOPE,
                ).from(PERMISSION)
                .where(condition)
                .orderBy(
                    permissionSortOrder(permissionSortField(filter.sortBy), filter.sortDir),
                    PERMISSION.ID.desc(),
                ).limit(filter.size)
                .offset(offset)
                .fetch()
        // One bounded lookup for the page's ids, so paging and sorting stay on `permission`.
        val requiredViews =
            requiredViewCodes(items.map { requireNotNull(it.get(PERMISSION.ID)) })

        return apiPageOf(
            items.map { record ->
                val id = requireNotNull(record.get(PERMISSION.ID))
                PermissionSummary(
                    id = id,
                    permissionCode = requireNotNull(record.get(PERMISSION.PERMISSION_CODE)),
                    permissionName = requireNotNull(record.get(PERMISSION.PERMISSION_NAME)),
                    moduleCode = requireNotNull(record.get(PERMISSION.MODULE_CODE)),
                    riskLevel = requireNotNull(record.get(PERMISSION.RISK_LEVEL)),
                    status = requireNotNull(record.get(PERMISSION.STATUS)),
                    kind = requireNotNull(record.get(PERMISSION.KIND)),
                    grantScope = requireNotNull(record.get(PERMISSION.GRANT_SCOPE)),
                    requiredViewPermissions = requiredViews[id].orEmpty(),
                )
            },
            filter.page,
            filter.size,
            total,
        )
    }

    override fun findPermissionById(id: UUID): PermissionDetail? =
        dsl
            .selectFrom(PERMISSION)
            .where(PERMISSION.ID.eq(id))
            .fetchOne { record ->
                PermissionDetail(
                    id = requireNotNull(record.id),
                    permissionCode = requireNotNull(record.permissionCode),
                    permissionName = requireNotNull(record.permissionName),
                    moduleCode = requireNotNull(record.moduleCode),
                    description = record.description,
                    riskLevel = requireNotNull(record.riskLevel),
                    status = requireNotNull(record.status),
                    kind = requireNotNull(record.kind),
                    grantScope = requireNotNull(record.grantScope),
                    requiredViewPermissions = requiredViewCodes(listOf(id))[id].orEmpty(),
                    createdAt = requireNotNull(record.createdAt).toInstant(),
                    updatedAt = requireNotNull(record.updatedAt).toInstant(),
                )
            }

    /** Sorted codes of the views each of [permissionIds] requires; absent when it needs none. */
    private fun requiredViewCodes(permissionIds: Collection<UUID>): Map<UUID, List<String>> {
        val view = PERMISSION.`as`("required_view")
        return dsl
            .select(PERMISSION_VIEW_REQUIREMENT.PERMISSION_ID, view.PERMISSION_CODE)
            .from(PERMISSION_VIEW_REQUIREMENT)
            .join(view)
            .on(view.ID.eq(PERMISSION_VIEW_REQUIREMENT.REQUIRED_VIEW_PERMISSION_ID))
            .where(PERMISSION_VIEW_REQUIREMENT.PERMISSION_ID.`in`(permissionIds))
            .orderBy(view.PERMISSION_CODE)
            .fetch()
            .groupBy(
                { requireNotNull(it.get(PERMISSION_VIEW_REQUIREMENT.PERMISSION_ID)) },
                { requireNotNull(it.get(view.PERMISSION_CODE)) },
            )
    }

    private fun permissionSortField(sortBy: String?): org.jooq.Field<*> =
        when (sortBy) {
            "permissionCode" -> PERMISSION.PERMISSION_CODE
            "permissionName" -> PERMISSION.PERMISSION_NAME
            "riskLevel" -> PERMISSION.RISK_LEVEL
            "status" -> PERMISSION.STATUS
            else -> PERMISSION.CREATED_AT
        }

    private fun permissionSortOrder(
        field: org.jooq.Field<*>,
        sortDir: String?,
    ): org.jooq.SortField<*> = if (sortDir?.uppercase() == "ASC") field.asc() else field.desc()
}
