package com.finaxis.platform.common.web.api

import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Date and time formatters for the public REST representation. */
object ApiDateFormats {
    /** Date-only values use day-first, zero-padded format. */
    val DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT)

    /** Time-only values use a zero-padded 24-hour clock. */
    val TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

    /** Datetime values must explicitly identify their UTC offset. */
    val OFFSET_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
}
