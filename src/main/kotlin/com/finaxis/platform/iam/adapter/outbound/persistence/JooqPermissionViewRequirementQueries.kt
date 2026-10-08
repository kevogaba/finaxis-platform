package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.port.outbound.PermissionViewRequirementQueries
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION_VIEW_REQUIREMENT
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/**
 * jOOQ implementation of the permission view-requirement port: one bounded read of the whole
 * pairing table (a few dozen rows), so the guard asks once per request.
 */
@Component
class JooqPermissionViewRequirementQueries(
    private val dsl: DSLContext,
) : PermissionViewRequirementQueries {
    override fun requiredViewCodesByPermission(): Map<String, List<String>> {
        val mutation = PERMISSION.`as`("mutation")
        val view = PERMISSION.`as`("required_view")
        return dsl
            .select(mutation.PERMISSION_CODE, view.PERMISSION_CODE)
            .from(PERMISSION_VIEW_REQUIREMENT)
            .join(mutation)
            .on(mutation.ID.eq(PERMISSION_VIEW_REQUIREMENT.PERMISSION_ID))
            .join(view)
            .on(view.ID.eq(PERMISSION_VIEW_REQUIREMENT.REQUIRED_VIEW_PERMISSION_ID))
            .where(mutation.KIND.eq("MUTATION"))
            .orderBy(mutation.PERMISSION_CODE, view.PERMISSION_CODE)
            .fetch()
            .groupBy(
                { requireNotNull(it.get(mutation.PERMISSION_CODE)) },
                { requireNotNull(it.get(view.PERMISSION_CODE)) },
            )
    }
}
