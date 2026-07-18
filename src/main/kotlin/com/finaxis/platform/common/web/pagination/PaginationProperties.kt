package com.finaxis.platform.common.web.pagination

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Application-wide pagination defaults and hard limits for listing APIs.
 */
@ConfigurationProperties(prefix = "finaxis.pagination")
data class PaginationProperties(
    val defaultPageSize: Int = 25,
    val maxPageSize: Int = 100,
) {
    init {
        require(defaultPageSize > 0) { "Default page size must be positive" }
        require(maxPageSize >= defaultPageSize) { "Maximum page size must be at least the default" }
    }
}
