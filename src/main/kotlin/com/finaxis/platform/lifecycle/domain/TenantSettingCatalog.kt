package com.finaxis.platform.lifecycle.domain

import java.time.ZoneId
import java.util.Currency

/** Storage/validation kind of a tenant setting value. */
enum class TenantSettingValueType { STRING, BOOLEAN, INT, TIMEZONE, CURRENCY }

/** One allowed tenant setting: its key, value kind, sensitivity, and required authority scope. */
data class TenantSettingDefinition(
    val key: String,
    val valueType: TenantSettingValueType,
    val sensitive: Boolean,
    val platformAdminOnly: Boolean,
    val defaultValue: String?,
)

/**
 * Authoritative registry of the setup-level tenant settings this platform supports. Commands
 * validate keys and values against this catalog; arbitrary keys are rejected. See
 * `docs/operations/tenant-settings.md` for what is deliberately not a tenant setting.
 */
object TenantSettingCatalog {
    private val definitions0 =
        listOf(
            TenantSettingDefinition(
                "default_timezone",
                TenantSettingValueType.TIMEZONE,
                false,
                false,
                null,
            ),
            TenantSettingDefinition(
                "base_currency",
                TenantSettingValueType.CURRENCY,
                false,
                false,
                null,
            ),
            TenantSettingDefinition(
                "require_maker_checker_for_user_invites",
                TenantSettingValueType.BOOLEAN,
                false,
                false,
                "false",
            ),
            TenantSettingDefinition(
                "require_maker_checker_for_branch_creation",
                TenantSettingValueType.BOOLEAN,
                false,
                false,
                "false",
            ),
            TenantSettingDefinition(
                "business_date_auto_advance_enabled",
                TenantSettingValueType.BOOLEAN,
                false,
                false,
                "false",
            ),
            TenantSettingDefinition(
                "audit_retention_days",
                TenantSettingValueType.INT,
                false,
                true,
                null,
            ),
        )

    /** All setting definitions in declaration order. */
    val definitions: List<TenantSettingDefinition> get() = definitions0

    private val byKey = definitions0.associateBy(TenantSettingDefinition::key)

    /** Returns the definition for [key], or null when the key is not in the catalog. */
    fun definition(key: String): TenantSettingDefinition? = byKey[key]

    /** Returns the definition for [key]; throws [IllegalArgumentException] when unknown. */
    fun require(key: String): TenantSettingDefinition =
        requireNotNull(byKey[key]) { "Unknown tenant setting key: $key" }

    /** Returns every catalog key. */
    fun keys(): Set<String> = byKey.keys

    /**
     * Validates [rawValue] for [key] and returns the canonical stored string form. Throws
     * [IllegalArgumentException] for an unknown key or a value that fails its type rule.
     */
    fun canonicalize(
        key: String,
        rawValue: String,
    ): String {
        val definition = require(key)
        val trimmed = rawValue.trim()
        require(trimmed.isNotEmpty()) { "Setting $key must not be blank." }
        return when (definition.valueType) {
            TenantSettingValueType.STRING -> trimmed
            TenantSettingValueType.BOOLEAN -> canonicalizeBoolean(key, trimmed)
            TenantSettingValueType.INT -> canonicalizeInt(key, trimmed)
            TenantSettingValueType.TIMEZONE -> canonicalizeTimezone(key, trimmed)
            TenantSettingValueType.CURRENCY -> canonicalizeCurrency(key, trimmed)
        }
    }

    private fun canonicalizeBoolean(
        key: String,
        value: String,
    ): String =
        when (value.lowercase()) {
            "true" -> "true"
            "false" -> "false"
            else -> throw IllegalArgumentException("Setting $key must be true or false.")
        }

    private fun canonicalizeInt(
        key: String,
        value: String,
    ): String {
        val parsed = value.toIntOrNull()
        require(parsed != null && parsed >= 0) {
            "Setting $key must be a non-negative integer."
        }
        return parsed.toString()
    }

    private fun canonicalizeTimezone(
        key: String,
        value: String,
    ): String {
        require(ZoneId.getAvailableZoneIds().contains(value)) {
            "Setting $key must be a valid IANA time-zone id."
        }
        return value
    }

    private fun canonicalizeCurrency(
        key: String,
        value: String,
    ): String {
        val upper = value.uppercase()
        val valid = Currency.getAvailableCurrencies().any { it.currencyCode == upper }
        require(valid) { "Setting $key must be a valid ISO 4217 currency code." }
        return upper
    }
}
