package com.finaxis.platform.lifecycle.domain

import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.common.application.InvalidOperationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TenantSettingCatalogTests {
    @Test
    fun `catalog exposes the six foundation settings`() {
        assertEquals(
            setOf(
                "default_timezone",
                "base_currency",
                "require_maker_checker_for_user_invites",
                "require_maker_checker_for_branch_creation",
                "business_date_auto_advance_enabled",
                "audit_retention_days",
            ),
            TenantSettingCatalog.keys(),
        )
    }

    @Test
    fun `audit_retention_days is platform-admin-only`() {
        assertTrue(TenantSettingCatalog.require("audit_retention_days").platformAdminOnly)
        assertFalse(TenantSettingCatalog.require("base_currency").platformAdminOnly)
    }

    @Test
    fun `require rejects an unknown key`() {
        assertFailsWith<InvalidOperationException> { TenantSettingCatalog.require("nope") }
    }

    @Test
    fun `canonicalize accepts a valid IANA timezone`() {
        assertEquals(
            "Africa/Nairobi",
            TenantSettingCatalog.canonicalize("default_timezone", "Africa/Nairobi"),
        )
    }

    @Test
    fun `canonicalize rejects a non-IANA timezone`() {
        assertFailsWith<InvalidOperationException> {
            TenantSettingCatalog.canonicalize("default_timezone", "EST")
        }
    }

    @Test
    fun `canonicalize normalizes and validates an ISO 4217 currency`() {
        assertEquals("KES", TenantSettingCatalog.canonicalize("base_currency", "kes"))
    }

    @Test
    fun `canonicalize rejects an unknown currency`() {
        listOf("XXY", "ZZZ").forEach { code ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    TenantSettingCatalog.canonicalize("base_currency", code)
                }
            // Accounting's code, not the catalog's generic invalid_operation: the same string is
            // refused with the same code here, at provisioning, and at a posting.
            assertEquals(MoneyPolicy.CURRENCY_INVALID, failure.code)
        }
    }

    @Test
    fun `canonicalize rejects a currency with no minor unit`() {
        // XXX and the metals are known to the JDK but have defaultFractionDigits -1, so no amount
        // can be settled in them. Accepting one would leave a tenant unable to post in either
        // direction, which is the failure this check exists to prevent.
        listOf("XXX", "XAU", "xau").forEach { code ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    TenantSettingCatalog.canonicalize("base_currency", code)
                }
            assertEquals(MoneyPolicy.CURRENCY_INVALID, failure.code)
        }
    }

    @Test
    fun `canonicalize accepts real settlement currencies`() {
        assertEquals("USD", TenantSettingCatalog.canonicalize("base_currency", " usd "))
        assertEquals("JPY", TenantSettingCatalog.canonicalize("base_currency", "JPY"))
    }

    @Test
    fun `canonicalize validates boolean and integer settings`() {
        assertEquals(
            "true",
            TenantSettingCatalog.canonicalize("business_date_auto_advance_enabled", "TRUE"),
        )
        assertEquals("30", TenantSettingCatalog.canonicalize("audit_retention_days", "30"))
        assertFailsWith<InvalidOperationException> {
            TenantSettingCatalog.canonicalize("business_date_auto_advance_enabled", "yes")
        }
        assertFailsWith<InvalidOperationException> {
            TenantSettingCatalog.canonicalize("audit_retention_days", "-1")
        }
    }
}
