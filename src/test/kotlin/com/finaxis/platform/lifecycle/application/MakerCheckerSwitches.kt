package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import java.util.UUID

/** A maker-checker policy over a mocked settings port: every control is on unless switched off. */
internal class MakerCheckerSwitches {
    private val settings = mock(OrganisationSettingsStore::class.java)

    /** The policy under test, reading what [switchOff] stored. */
    val policy = MakerCheckerPolicy(settings)

    /** Stores `false` for the membership approval control of [organisationId]. */
    fun switchOffUserInvites(organisationId: UUID) =
        switchOff(organisationId, TenantSettingCatalog.MAKER_CHECKER_USER_INVITES_KEY)

    /** Stores `false` for the branch approval control of [organisationId]. */
    fun switchOffBranchCreation(organisationId: UUID) =
        switchOff(organisationId, TenantSettingCatalog.MAKER_CHECKER_BRANCH_CREATION_KEY)

    private fun switchOff(
        organisationId: UUID,
        key: String,
    ) {
        whenever(settings.currentSetting(organisationId, key))
            .thenReturn(StoredSetting(key, "false", "BOOLEAN", false))
    }
}

/** A policy with every control on, for tests that do not exercise the switches. */
internal fun allControlsOn(): MakerCheckerPolicy = MakerCheckerSwitches().policy
