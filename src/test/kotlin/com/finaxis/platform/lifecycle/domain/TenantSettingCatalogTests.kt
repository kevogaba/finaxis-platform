package com.finaxis.platform.lifecycle.domain

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
        assertFailsWith<InvalidOperationException> {
            TenantSettingCatalog.canonicalize("base_currency", "XXY")
        }
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
