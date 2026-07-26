package com.finaxis.platform.lifecycle.application

import java.util.UUID

/** Creates or updates one tenant setting, adding a new effective-dated row. */
data class CreateOrUpdateTenantSettingCommand(
    val organisationId: UUID,
    val key: String,
    val value: String,
    val actorId: UUID,
    val reason: String? = null,
)

/** Deactivates one tenant setting by closing its currently effective row with no replacement. */
data class DeactivateTenantSettingCommand(
    val organisationId: UUID,
    val key: String,
    val actorId: UUID,
    val reason: String? = null,
)

/** Reads one tenant setting for an organisation. */
data class GetTenantSettingQuery(
    val organisationId: UUID,
    val key: String,
    val actorId: UUID,
)

/** Lists all currently effective tenant settings for an organisation with pagination. */
data class ListTenantSettingsQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val page: Int = 0,
    val size: Int = 25,
)

/** A bounded page of tenant settings. */
data class TenantSettingPage(
    val items: List<TenantSettingView>,
    val totalItems: Long,
)

/** A tenant setting projected for reads; [value] is redacted or null when unset. */
data class TenantSettingView(
    val key: String,
    val value: String?,
    val valueType: String,
    val sensitive: Boolean,
    val platformAdminOnly: Boolean,
)
