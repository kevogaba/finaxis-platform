package com.finaxis.platform.common.web.api

import java.util.Locale

/**
 * Rejects an out-of-range zero-based page number or page size with a 400.
 *
 * The HTTP layer already screens the `page` and `size` query parameters; this is the single
 * application-layer backstop for callers that reach a query service another way. It throws the
 * bare exception, so the problem body is identical to the interceptor's: no violation entry.
 *
 * @throws InvalidPageRequestException when [page] is negative or [size] is outside the bounds
 */
fun requireValidPage(
    page: Int,
    size: Int,
) {
    if (page < 0 || size !in MINIMUM_PAGE_SIZE..MAXIMUM_PAGE_SIZE) {
        throw InvalidPageRequestException()
    }
}

/**
 * Rejects a client-supplied sort field outside [allowedFields] and a sort direction other than
 * `ASC` or `DESC` (case-insensitive) with a 400. Absent values are valid.
 *
 * @throws InvalidPageRequestException naming `sort_by` or `sort_dir`
 */
fun requireValidSort(
    sortBy: String?,
    sortDir: String?,
    allowedFields: Set<String>,
) {
    if (sortBy != null && sortBy !in allowedFields) {
        throw InvalidPageRequestException(
            "sort_by",
            "Sort field must be one of: ${allowedFields.sorted().joinToString(", ")}.",
        )
    }
    if (sortDir != null && sortDir.uppercase(Locale.ROOT) !in SORT_DIRECTIONS) {
        throw InvalidPageRequestException("sort_dir", "Sort direction must be ASC or DESC.")
    }
}

private val SORT_DIRECTIONS = setOf("ASC", "DESC")
