package com.finaxis.platform.common.web.api

import kotlin.math.ceil

/** Bounded representation returned by every public collection endpoint. */
data class ApiPage<T>(
    val items: List<T>,
    val page: ApiPageMetadata,
)

/** Metadata describing a zero-based public collection page. */
data class ApiPageMetadata(
    val number: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
)

/**
 * Converts already-bounded results to the public page contract.
 *
 * Later list adapters must use this single validation point rather than duplicating page rules.
 */
fun <T> apiPageOf(
    items: List<T>,
    number: Int,
    size: Int,
    totalItems: Long,
): ApiPage<T> {
    require(number >= 0) { "Page number must not be negative" }
    require(size in MINIMUM_PAGE_SIZE..MAXIMUM_PAGE_SIZE) { "Page size must be between 1 and 100" }
    require(totalItems >= 0) { "Total item count must not be negative" }
    val totalPages = ceil(totalItems.toDouble() / size).toInt()
    return ApiPage(
        items = items,
        page =
            ApiPageMetadata(
                number = number,
                size = size,
                totalItems = totalItems,
                totalPages = totalPages,
                hasNext = number + 1 < totalPages,
                hasPrevious = number > 0,
            ),
    )
}

private const val MINIMUM_PAGE_SIZE = 1
private const val MAXIMUM_PAGE_SIZE = 100
