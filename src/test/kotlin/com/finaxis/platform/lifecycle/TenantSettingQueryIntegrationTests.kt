package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.DeactivateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.GetTenantSettingQuery
import com.finaxis.platform.lifecycle.application.ListTenantSettingsQuery
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TenantSettingQueryIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val tenantSettingsService: TenantSettingsService,
    private val dsl: DSLContext,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `tenant setting get and list return catalog-backed read views`() {
        val organisationId = fixture.createActiveOrganisation("setting-query", LOCAL_USER_ID)

        // base_currency is a read-only mirror of the organisation's column, which the fixture set
        // to KES at provisioning: both reads answer that column, and no write can change it.
        val baseCurrency =
            withRequestContext {
                tenantSettingsService.get(
                    GetTenantSettingQuery(organisationId, "base_currency", LOCAL_USER_ID),
                )
            }
        val page =
            withRequestContext {
                tenantSettingsService.list(ListTenantSettingsQuery(organisationId, LOCAL_USER_ID))
            }
        val settings = page.items

        assertEquals("KES", baseCurrency.value)
        val catalogKeys = TenantSettingCatalog.keys()
        val catalogSettings = settings.filter { it.key in catalogKeys }
        assertEquals(catalogKeys, catalogSettings.map { it.key }.toSet())
        assertEquals(6, catalogSettings.size)

        val listedBaseCurrency = catalogSettings.single { it.key == "base_currency" }
        assertEquals("KES", listedBaseCurrency.value)

        val timezone = catalogSettings.single { it.key == "default_timezone" }
        assertNull(timezone.value)
        assertFalse(timezone.sensitive)

        val makerChecker =
            catalogSettings.single { it.key == "require_maker_checker_for_user_invites" }
        assertEquals("false", makerChecker.value)
    }

    @Test
    fun `base_currency write and deactivate are refused and its read stays the column`() {
        val organisationId = fixture.createActiveOrganisation("setting-mirror", LOCAL_USER_ID)

        assertFailsWith<InvalidOperationException> {
            withRequestContext {
                tenantSettingsService.createOrUpdate(
                    CreateOrUpdateTenantSettingCommand(
                        organisationId,
                        key = "base_currency",
                        value = "USD",
                        actorId = LOCAL_USER_ID,
                    ),
                )
            }
        }
        assertFailsWith<InvalidOperationException> {
            withRequestContext {
                tenantSettingsService.deactivate(
                    DeactivateTenantSettingCommand(organisationId, "base_currency", LOCAL_USER_ID),
                )
            }
        }

        val baseCurrency =
            withRequestContext {
                tenantSettingsService.get(
                    GetTenantSettingQuery(organisationId, "base_currency", LOCAL_USER_ID),
                )
            }
        assertEquals("KES", baseCurrency.value)
        assertEquals(
            0,
            dsl.fetchCount(
                ORGANISATION_SETTING,
                ORGANISATION_SETTING.ORGANISATION_ID
                    .eq(organisationId)
                    .and(ORGANISATION_SETTING.SETTING_KEY.eq("base_currency")),
            ),
            "a refused write must leave no base_currency settings row behind",
        )
    }

    @Test
    fun `the internal operational marker is not a setting, so list totals the catalogue alone`() {
        // Approval writes `settings.operational`, so the tenant is past the point where it exists.
        val organisationId = fixture.createActiveOrganisation("setting-operational", LOCAL_USER_ID)

        val page =
            withRequestContext {
                tenantSettingsService.list(ListTenantSettingsQuery(organisationId, LOCAL_USER_ID))
            }
        assertEquals(TenantSettingCatalog.keys().size.toLong(), page.totalItems)
        assertFalse(page.items.any { it.key == "settings.operational" })

        assertFailsWith<InvalidOperationException> {
            withRequestContext {
                tenantSettingsService.get(
                    GetTenantSettingQuery(organisationId, "settings.operational", LOCAL_USER_ID),
                )
            }
        }
    }

    @Test
    fun `an unknown setting key is forbidden without the permission and invalid with it`() {
        val organisationId = fixture.createActiveOrganisation("setting-unknown-key", LOCAL_USER_ID)

        // Same refusal for every key, known or not, so the 403 reveals nothing about the key.
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                tenantSettingsService.get(
                    GetTenantSettingQuery(organisationId, "no_such_key", STRANGER_ID),
                )
            }
        }
        assertFailsWith<InvalidOperationException> {
            withRequestContext {
                tenantSettingsService.get(
                    GetTenantSettingQuery(organisationId, "no_such_key", LOCAL_USER_ID),
                )
            }
        }
    }

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val STRANGER_ID: UUID = UUID.fromString("99999999-9999-9999-9999-999999999999")
    }
}
