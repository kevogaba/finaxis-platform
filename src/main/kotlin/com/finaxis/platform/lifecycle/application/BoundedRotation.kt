package com.finaxis.platform.lifecycle.application

/**
 * Takes a bounded scheduled job's candidates in turns (#277, ADR 0041). When more candidates exist
 * than one run may consider, each run reads a different window of `limit` of them, in a stable
 * order, chosen by the run's time slot (its hour, or its day). Every candidate is then considered
 * within `ceil(total / limit)` slots, and candidates that stay first in the order, such as tenants
 * whose dates cannot move, cannot hold the rest back for good. It keeps no cursor, so a restart or
 * a new scheduling server does not reset it.
 */
internal object BoundedRotation {
    /** The offset of the window that [slot] reads from [total] candidates, [limit] at a time. */
    fun windowOffset(
        total: Int,
        limit: Int,
        slot: Long,
    ): Int {
        require(limit > 0) { "limit must be positive." }
        if (total <= limit) {
            return 0
        }
        val windows = (total + limit - 1) / limit
        return Math.floorMod(slot, windows.toLong()).toInt() * limit
    }
}
