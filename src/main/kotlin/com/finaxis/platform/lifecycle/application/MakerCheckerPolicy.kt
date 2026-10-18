package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Whether a tenant keeps maker-checker segregation on a flow, read from its settings.
 *
 * Segregation is on unless the tenant has stored the literal `false`: an unset setting, and any
 * other value, count as required, so a tenant never loses a control by accident. Turning it off
 * lifts only the segregation checks (who may approve); the approval step and its permission stay,
 * and an approval that relied on the lifted check is audited `HIGH`.
 */
@Component
class MakerCheckerPolicy(
    private val settings: OrganisationSettingsStore,
) {
    /** Whether a membership approval refuses the inviter and the invited user. */
    fun requiredForUserInvites(organisationId: UUID): Boolean =
        required(organisationId, TenantSettingCatalog.MAKER_CHECKER_USER_INVITES_KEY)

    /** Whether a branch approval refuses its maker and anyone who amended the branch. */
    fun requiredForBranchCreation(organisationId: UUID): Boolean =
        required(organisationId, TenantSettingCatalog.MAKER_CHECKER_BRANCH_CREATION_KEY)

    private fun required(
        organisationId: UUID,
        key: String,
    ): Boolean = settings.currentSetting(organisationId, key)?.value != "false"
}
