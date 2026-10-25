package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Pending approvals the caller could act on. A key is omitted when the caller lacks that
 * resource's approve authority, so an absent key and a zero mean different things.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TenantPendingCountResponse(
    @field:Schema(description = "Pending memberships; present only for `user.approve` holders.")
    val memberships: Long? = null,
    @field:Schema(
        description = "Pending branches; present only for tenant-wide `branch.approve` holders.",
    )
    val branches: Long? = null,
)

/** Pending tenants the platform caller could approve as the tenant checker. */
data class PlatformPendingCountResponse(
    val tenants: Long,
)
