package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.lifecycle.application.DeprovisionedAssignment
import org.jooq.Record3
import java.util.UUID

/**
 * Maps one `(id, branch_id, user_id)` row of an active assignment, read by a deprovisioning revoke,
 * to a [DeprovisionedAssignment]. The branch is null for a tenant-scoped role. `user_id` is NOT
 * NULL in V1; jOOQ types every column nullable, so the invariant is asserted here, once.
 */
internal fun Record3<UUID?, UUID?, UUID?>.toDeprovisionedAssignment(
    assignmentType: String,
): DeprovisionedAssignment? =
    value1()?.let {
        DeprovisionedAssignment(it, assignmentType, value2(), requireNotNull(value3()))
    }
