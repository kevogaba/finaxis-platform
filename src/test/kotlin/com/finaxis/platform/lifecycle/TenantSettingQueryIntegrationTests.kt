package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
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
import kotlin.test.assertFalse
import kotlin.test.assertNull

@Import(TestcontainersConfiguration::class)
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

        withRequestContext {
            tenantSettingsService.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(
                    organisationId,
                    key = "base_currency",
                    value = "kes",
                    actorId = LOCAL_USER_ID,
                ),
            )
        }

        val baseCurrency =
            withRequestContext {
                tenantSettingsService.get(
                    GetTenantSettingQuery(organisationId, "base_currency", LOCAL_USER_ID),
                )
            }
        val settings =
            withRequestContext {
                tenantSettingsService.list(ListTenantSettingsQuery(organisationId, LOCAL_USER_ID))
            }

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

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
