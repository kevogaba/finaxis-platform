package com.finaxis.platform.lifecycle.application

import java.util.UUID

/** Creates or updates one tenant setting, adding a new effective-dated row. */
data class CreateOrUpdateTenantSettingCommand(
    val organisationId: UUID,
    val key: String,
    val value: String,
    val actorId: UUID,
    val reason: String? = null,
    /** True on the platform route: the caller is a platform administrator, not a tenant member. */
    val platformScope: Boolean = false,
)

/** Deactivates one tenant setting by closing its currently effective row with no replacement. */
data class DeactivateTenantSettingCommand(
    val organisationId: UUID,
    val key: String,
    val actorId: UUID,
    val reason: String? = null,
    val platformScope: Boolean = false,
)

/** Reads one tenant setting for an organisation. */
data class GetTenantSettingQuery(
    val organisationId: UUID,
    val key: String,
    val actorId: UUID,
    val platformScope: Boolean = false,
)

/** Lists all currently effective tenant settings for an organisation with pagination. */
data class ListTenantSettingsQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val page: Int = 0,
    val size: Int = 25,
    val platformScope: Boolean = false,
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
    /** True when [value] is the catalogue default, because no value is stored or mirrored. */
    val isDefault: Boolean,
    val label: String? = null,
    val description: String? = null,
    val group: String? = null,
)
