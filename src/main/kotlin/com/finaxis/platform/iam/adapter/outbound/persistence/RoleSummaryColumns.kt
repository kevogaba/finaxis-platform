package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION_VIEW_REQUIREMENT
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * The derived columns of a role page: the view gaps and the two counts. Each is one grouped query
 * over the page's role ids, so a page costs a fixed number of statements whatever its size and the
 * role query itself keeps paging and totals on `role` alone.
 */
internal class RoleSummaryColumns(
    private val dsl: DSLContext,
) {
    /** The number of permission grants of each of [roleIds], absent for a role with none. */
    fun permissionCounts(
        organisationId: UUID,
        roleIds: Collection<UUID>,
    ): Map<UUID, Int> =
        if (roleIds.isEmpty()) {
            emptyMap()
        } else {
            dsl
                .select(ROLE_PERMISSION.ROLE_ID, DSL.count())
                .from(ROLE_PERMISSION)
                .where(ROLE_PERMISSION.ORGANISATION_ID.eq(organisationId))
                .and(ROLE_PERMISSION.ROLE_ID.`in`(roleIds))
                .groupBy(ROLE_PERMISSION.ROLE_ID)
                .fetch()
                .associate { requireNotNull(it.value1()) to it.value2() }
        }

    /** The distinct users holding each of [roleIds] through an ACTIVE assignment. */
    fun activeUserCounts(
        organisationId: UUID,
        roleIds: Collection<UUID>,
    ): Map<UUID, Int> =
        if (roleIds.isEmpty()) {
            emptyMap()
        } else {
            dsl
                .select(
                    USER_ROLE_ASSIGNMENT.ROLE_ID,
                    DSL.countDistinct(USER_ROLE_ASSIGNMENT.USER_ID),
                ).from(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
                .and(USER_ROLE_ASSIGNMENT.ROLE_ID.`in`(roleIds))
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
                .groupBy(USER_ROLE_ASSIGNMENT.ROLE_ID)
                .fetch()
                .associate { requireNotNull(it.value1()) to it.value2() }
        }

    /**
     * Sorted view codes each of [roleIds] needs and lacks, one query for the whole set.
     *
     * A role needs a view when it holds an `ACTIVE` mutation whose `permission_view_requirement`
     * names it, and lacks it when it holds no `ACTIVE` grant of that view. Roles that comply are
     * absent from the result. The operator report
     * (`docs/operations/sql/permission-view-gap-roles.sql`) states the same rule.
     */
    fun missingViewPermissions(
        organisationId: UUID,
        roleIds: Collection<UUID>,
    ): Map<UUID, List<String>> {
        if (roleIds.isEmpty()) return emptyMap()
        val held = ROLE_PERMISSION.`as`("held")
        val heldView = ROLE_PERMISSION.`as`("held_view")
        val mutation = PERMISSION.`as`("held_mutation")
        val heldViewPermission = PERMISSION.`as`("held_view_permission")
        val view = PERMISSION.`as`("needed_view")
        return dsl
            .selectDistinct(held.ROLE_ID, view.PERMISSION_CODE)
            .from(held)
            .join(mutation)
            .on(mutation.ID.eq(held.PERMISSION_ID))
            .and(mutation.STATUS.eq(ACTIVE))
            .join(PERMISSION_VIEW_REQUIREMENT)
            .on(PERMISSION_VIEW_REQUIREMENT.PERMISSION_ID.eq(mutation.ID))
            .join(view)
            .on(view.ID.eq(PERMISSION_VIEW_REQUIREMENT.REQUIRED_VIEW_PERMISSION_ID))
            .where(held.ORGANISATION_ID.eq(organisationId))
            .and(held.ROLE_ID.`in`(roleIds))
            .andNotExists(
                dsl
                    .selectOne()
                    .from(heldView)
                    .join(heldViewPermission)
                    .on(heldViewPermission.ID.eq(heldView.PERMISSION_ID))
                    .where(heldView.ORGANISATION_ID.eq(held.ORGANISATION_ID))
                    .and(heldView.ROLE_ID.eq(held.ROLE_ID))
                    .and(heldView.PERMISSION_ID.eq(view.ID))
                    .and(heldViewPermission.STATUS.eq(ACTIVE)),
            ).orderBy(view.PERMISSION_CODE)
            .fetch()
            .groupBy(
                { requireNotNull(it.get(held.ROLE_ID)) },
                { requireNotNull(it.get(view.PERMISSION_CODE)) },
            )
    }
}

private const val ACTIVE = "ACTIVE"
