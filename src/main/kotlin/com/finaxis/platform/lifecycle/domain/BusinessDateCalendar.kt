package com.finaxis.platform.lifecycle.domain

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * What "today" is for an organisation: the calendar date in its own time zone. Approval derives a
 * new tenant's first business date from it, and the scheduled advance (#277) advances an open
 * business date until it reaches it. The organisation's `timezone` is the only zone that decides a
 * business date; a branch's zone and `default_timezone` never do.
 */
object BusinessDateCalendar {
    /** Today's date in [timezone], as [clock] reads it. */
    fun localToday(
        clock: Clock,
        timezone: String,
    ): LocalDate = LocalDate.now(clock.withZone(ZoneId.of(timezone)))
}
