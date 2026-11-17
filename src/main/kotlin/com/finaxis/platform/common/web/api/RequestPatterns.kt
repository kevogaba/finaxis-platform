package com.finaxis.platform.common.web.api

/**
 * The `@Pattern` of an optional request field that, when present, must hold a non-whitespace
 * character. `@NotBlank` rejects null, and an optional field is allowed to be absent. `[\s\S]`
 * rather than `.` so a line break inside a value is accepted, as `@NotBlank` (used on create)
 * accepts it, and the pattern stays valid in the published JSON Schema.
 */
const val NOT_BLANK_PATTERN = "[\\s\\S]*\\S[\\s\\S]*"
