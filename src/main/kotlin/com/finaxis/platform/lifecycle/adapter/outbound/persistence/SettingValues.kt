package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * A setting's value as text, read in SQL. `organisation_setting.setting_value` is JSONB holding a
 * JSON string (`"Africa/Nairobi"`, `"true"`, `"2555"`), and `#>> '{}'` unwraps that scalar to its
 * text, so a query can compare or return it without the Kotlin JSON mapper the store uses.
 */
internal fun settingText(): Field<String?> =
    DSL.field("{0} #>> '{}'", SQLDataType.VARCHAR, ORGANISATION_SETTING.SETTING_VALUE)
