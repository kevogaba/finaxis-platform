package com.finaxis.platform.lifecycle.domain

import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.common.application.InvalidOperationException
import java.time.ZoneId

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

    /** Returns the definition for [key]; throws [InvalidOperationException] when unknown. */
    fun require(key: String): TenantSettingDefinition =
        byKey[key]
            ?: throw InvalidOperationException(safeDetail = "Unknown tenant setting key: $key")

    /** Returns every catalog key. */
    fun keys(): Set<String> = byKey.keys

    /**
     * Validates [rawValue] for [key] and returns the canonical stored string form. Throws
     * [InvalidOperationException] for an unknown key or a value that fails its type rule.
     */
    fun canonicalize(
        key: String,
        rawValue: String,
    ): String {
        val definition = require(key)
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) {
            throw InvalidOperationException()
        }
        return when (definition.valueType) {
            TenantSettingValueType.STRING -> trimmed
            TenantSettingValueType.BOOLEAN -> canonicalizeBoolean(key, trimmed)
            TenantSettingValueType.INT -> canonicalizeInt(key, trimmed)
            TenantSettingValueType.TIMEZONE -> canonicalizeTimezone(key, trimmed)
            TenantSettingValueType.CURRENCY -> canonicalizeCurrency(trimmed)
        }
    }

    private fun canonicalizeBoolean(
        key: String,
        value: String,
    ): String =
        when (value.lowercase()) {
            "true" -> "true"

            "false" -> "false"

            else -> throw InvalidOperationException(
                safeDetail = "Setting $key must be true or false.",
            )
        }

    private fun canonicalizeInt(
        key: String,
        value: String,
    ): String {
        val parsed = value.toIntOrNull()
        if (parsed == null || parsed < 0) {
            throw InvalidOperationException(
                safeDetail = "Setting $key must be a non-negative integer.",
            )
        }
        return parsed.toString()
    }

    private fun canonicalizeTimezone(
        key: String,
        value: String,
    ): String {
        if (!ZoneId.getAvailableZoneIds().contains(value)) {
            throw InvalidOperationException(
                safeDetail = "Setting $key must be a valid IANA time-zone id.",
            )
        }
        return value
    }

    /**
     * Canonicalizes a currency setting by asking the ledger, which is the only currency authority.
     *
     * This previously checked membership of `Currency.getAvailableCurrencies()` itself. That was a
     * second reading of the same JDK table, and it accepted codes with no minor unit - `XXX` and
     * the metals - which no amount can be settled in, so a tenant could be left with a base
     * currency it could never post in. [MoneyPolicy.requireSettlementCurrency] answers both
     * questions at once and throws `accounting.currency_invalid`, the same code a posting would
     * report for the same string, rather than the catalog's generic `invalid_operation`.
     */
    private fun canonicalizeCurrency(value: String): String =
        MoneyPolicy.requireSettlementCurrency(value.uppercase()).currencyCode
}
