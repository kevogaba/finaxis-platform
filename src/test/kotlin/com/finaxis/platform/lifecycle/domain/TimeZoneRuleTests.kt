package com.finaxis.platform.lifecycle.domain

import com.finaxis.platform.common.application.InvalidOperationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one time-zone rule the organisation, its branches and `default_timezone` share (#276). */
class TimeZoneRuleTests {
    @Test
    fun `region ids are accepted exactly as spelled`() {
        listOf("Africa/Nairobi", "Europe/London", "UTC", "Etc/GMT-3").forEach { zone ->
            assertTrue(TimeZoneRule.isValid(zone), zone)
            assertEquals(zone, TimeZoneRule.require(zone))
        }
    }

    @Test
    fun `raw offsets are refused even though ZoneId parses them`() {
        listOf("+03:00", "-05:00", "UTC+3", "UTC+03:00", "GMT+3", "Z").forEach { zone ->
            assertFalse(TimeZoneRule.isValid(zone), zone)
        }
    }

    @Test
    fun `an unknown, misspelt or wrongly cased id is refused with the safe detail`() {
        listOf("Mars/Olympus", "africa/nairobi", "Africa/Nairobi ", "EST", "").forEach { zone ->
            val error =
                assertFailsWith<InvalidOperationException>(zone) { TimeZoneRule.require(zone) }
            assertEquals(TimeZoneRule.SAFE_DETAIL, error.safeDetail)
        }
    }

    @Test
    fun `the settings catalogue applies the same rule`() {
        assertEquals(
            "Etc/GMT-3",
            TenantSettingCatalog.canonicalize(
                TenantSettingCatalog.DEFAULT_TIMEZONE_KEY,
                "Etc/GMT-3",
            ),
        )
        assertFailsWith<InvalidOperationException> {
            TenantSettingCatalog.canonicalize(TenantSettingCatalog.DEFAULT_TIMEZONE_KEY, "+03:00")
        }
    }
}
