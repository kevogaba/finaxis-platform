package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.TenantSettingResponse
import com.finaxis.platform.lifecycle.application.TenantSettingPage
import com.finaxis.platform.lifecycle.application.TenantSettingView

/** Maps a setting page to its public page, shared by the tenant and platform routes. */
internal fun TenantSettingPage.toApiPage(
    page: Int,
    size: Int,
): ApiPage<TenantSettingResponse> = apiPageOf(items.map { it.toResponse() }, page, size, totalItems)

/** Maps one setting view to its public representation. */
internal fun TenantSettingView.toResponse() =
    TenantSettingResponse(
        key = key,
        value = value,
        valueType = valueType,
        sensitive = sensitive,
        platformAdminOnly = platformAdminOnly,
        isDefault = isDefault,
        label = label,
        description = description,
        group = group,
    )
