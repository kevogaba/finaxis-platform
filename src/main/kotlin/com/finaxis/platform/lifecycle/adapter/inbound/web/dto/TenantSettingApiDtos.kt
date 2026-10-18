package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(
        description =
            "True when `value` is the catalogue default because nothing is stored for the key " +
                "(and it is not the read-only base currency), so an unset value and an " +
                "explicitly stored one can be told apart.",
    )
    val isDefault: Boolean,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        nullable = true,
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Short name for a settings screen; `null` for a key outside the catalogue.",
    )
    val label: String?,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        nullable = true,
        requiredMode = Schema.RequiredMode.REQUIRED,
        description =
            "What the setting does, including whether the platform applies it yet; `null` for a " +
                "key outside the catalogue.",
    )
    val description: String?,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        nullable = true,
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Group a settings screen files it under; `null` outside the catalogue.",
    )
    val group: String?,
)
