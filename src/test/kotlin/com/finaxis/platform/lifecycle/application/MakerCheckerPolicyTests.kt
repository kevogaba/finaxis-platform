package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** The maker-checker policy: required unless a tenant stored the literal `false`. */
class MakerCheckerPolicyTests {
    private val settings = mock(OrganisationSettingsStore::class.java)
    private val policy = MakerCheckerPolicy(settings)
    private val organisationId = uuidV7()

    private fun store(
        organisationId: UUID,
        key: String,
        value: String,
    ) {
        whenever(settings.currentSetting(organisationId, key))
            .thenReturn(StoredSetting(key, value, "BOOLEAN", false))
    }

    @Test
    fun `an unset setting means maker-checker is required`() {
        assertEquals(true, policy.requiredForUserInvites(organisationId))
        assertEquals(true, policy.requiredForBranchCreation(organisationId))
    }

    @Test
    fun `the catalogue default is on, so reading the default and the policy agree`() {
        listOf(
            TenantSettingCatalog.MAKER_CHECKER_USER_INVITES_KEY,
            TenantSettingCatalog.MAKER_CHECKER_BRANCH_CREATION_KEY,
        ).forEach { assertEquals("true", TenantSettingCatalog.require(it).defaultValue, it) }
    }

    @Test
    fun `only a stored false switches a control off, and each control on its own`() {
        store(organisationId, TenantSettingCatalog.MAKER_CHECKER_USER_INVITES_KEY, "false")

        assertEquals(false, policy.requiredForUserInvites(organisationId))
        assertEquals(true, policy.requiredForBranchCreation(organisationId))
    }

    @Test
    fun `a stored true or anything unrecognised keeps the control on`() {
        store(organisationId, TenantSettingCatalog.MAKER_CHECKER_USER_INVITES_KEY, "true")
        store(organisationId, TenantSettingCatalog.MAKER_CHECKER_BRANCH_CREATION_KEY, "no")

        assertEquals(true, policy.requiredForUserInvites(organisationId))
        assertEquals(true, policy.requiredForBranchCreation(organisationId))
    }

    @Test
    fun `a tenant switching a control off leaves another tenant's control on`() {
        val other = uuidV7()
        store(organisationId, TenantSettingCatalog.MAKER_CHECKER_BRANCH_CREATION_KEY, "false")

        assertEquals(false, policy.requiredForBranchCreation(organisationId))
        assertEquals(true, policy.requiredForBranchCreation(other))
    }
}
