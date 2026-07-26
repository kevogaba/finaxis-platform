package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import jakarta.validation.constraints.NotBlank

/** Request payload for creating or updating a tenant setup-level setting. */
data class CreateOrUpdateTenantSettingRequest(
    @field:NotBlank
    val value: String,
    val reason: String? = null,
)

/** Optional request payload for recording why a tenant setting was deactivated. */
data class DeactivateTenantSettingRequest(
    val reason: String? = null,
)

/**
 * Public tenant-setting representation with any sensitive value already redacted by the service.
 */
data class TenantSettingResponse(
    val key: String,
    val value: String?,
    val valueType: String,
    val sensitive: Boolean,
    val platformAdminOnly: Boolean,
)
