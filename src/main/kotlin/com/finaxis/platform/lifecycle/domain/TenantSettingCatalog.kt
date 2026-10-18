package com.finaxis.platform.lifecycle.domain

import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.common.application.InvalidOperationException
import java.time.ZoneId

/** Storage/validation kind of a tenant setting value. */
enum class TenantSettingValueType { STRING, BOOLEAN, INT, TIMEZONE, CURRENCY }

/**
 * One allowed tenant setting: its key, value kind, sensitivity, required authority scope, and the
 * human-facing [label], [description] and [group] a settings screen shows.
 */
data class TenantSettingDefinition(
    val key: String,
    val valueType: TenantSettingValueType,
    val sensitive: Boolean,
    val platformAdminOnly: Boolean,
    val defaultValue: String?,
    val label: String,
    val description: String,
    val group: String,
)

/**
 * Authoritative registry of the setup-level tenant settings this platform supports. Commands
 * validate keys and values against this catalog; arbitrary keys are rejected. See
 * `docs/operations/tenant-settings.md` for what is deliberately not a tenant setting.
 */
object TenantSettingCatalog {
    /**
     * The key of the read-only mirror of the organisation's `base_currency_code`. Lifecycle
     * refuses every write to it and answers its reads from the organisation column.
     */
    const val BASE_CURRENCY_KEY = "base_currency"

    private const val GROUP_GENERAL = "General"
    private const val GROUP_APPROVALS = "Approvals"
    private const val GROUP_BUSINESS_DATE = "Business date"
    private const val GROUP_AUDIT = "Audit"

    /**
     * Whether a membership approval keeps its maker-checker segregation (the inviter and the
     * invited user may not approve). `true` unless a tenant sets it to `false`.
     */
    const val MAKER_CHECKER_USER_INVITES_KEY = "require_maker_checker_for_user_invites"

    /**
     * Whether a branch approval keeps its maker-checker segregation (the creator, the submitter
     * on the platform route and anyone who amended the branch may not approve it). `true` unless
     * a tenant sets it to `false`.
     */
    const val MAKER_CHECKER_BRANCH_CREATION_KEY = "require_maker_checker_for_branch_creation"

    private val definitions0 =
        listOf(
            TenantSettingDefinition(
                key = "default_timezone",
                valueType = TenantSettingValueType.TIMEZONE,
                sensitive = false,
                platformAdminOnly = false,
                defaultValue = null,
                label = "Default time zone",
                description =
                    "Reserved: the time zone new branches would default to. Not yet applied by " +
                        "the platform; the organisation's own time zone is what is used today.",
                group = GROUP_GENERAL,
            ),
            TenantSettingDefinition(
                key = BASE_CURRENCY_KEY,
                valueType = TenantSettingValueType.CURRENCY,
                sensitive = false,
                platformAdminOnly = false,
                defaultValue = null,
                label = "Base currency",
                description =
                    "The currency the ledger posts in. Read-only: it mirrors the organisation's " +
                        "base currency and cannot be changed here.",
                group = GROUP_GENERAL,
            ),
            TenantSettingDefinition(
                key = MAKER_CHECKER_USER_INVITES_KEY,
                valueType = TenantSettingValueType.BOOLEAN,
                sensitive = false,
                platformAdminOnly = false,
                defaultValue = "true",
                label = "Require maker-checker for user invitations",
                description =
                    "When on, the person who invited a member and the invited user cannot " +
                        "approve the membership. Turning it off lets them; each such approval is " +
                        "audited as high severity. The platform checker is never relaxed.",
                group = GROUP_APPROVALS,
            ),
            TenantSettingDefinition(
                key = MAKER_CHECKER_BRANCH_CREATION_KEY,
                valueType = TenantSettingValueType.BOOLEAN,
                sensitive = false,
                platformAdminOnly = false,
                defaultValue = "true",
                label = "Require maker-checker for branch creation",
                description =
                    "When on, the person who created a branch and anyone who amended it cannot " +
                        "approve it. Turning it off lets them; each such approval is audited as " +
                        "high severity. The platform checker is never relaxed.",
                group = GROUP_APPROVALS,
            ),
            TenantSettingDefinition(
                key = "business_date_auto_advance_enabled",
                valueType = TenantSettingValueType.BOOLEAN,
                sensitive = false,
                platformAdminOnly = false,
                defaultValue = "false",
                label = "Advance the business date automatically",
                description =
                    "Reserved: would advance the business date each day without a manual " +
                        "request. Not yet applied; the business date moves only when someone " +
                        "advances it.",
                group = GROUP_BUSINESS_DATE,
            ),
            TenantSettingDefinition(
                key = "audit_retention_days",
                valueType = TenantSettingValueType.INT,
                sensitive = false,
                platformAdminOnly = true,
                defaultValue = null,
                label = "Audit retention (days)",
                description =
                    "Reserved: how long audit events would be kept. Not yet applied; nothing " +
                        "is purged today. Platform administrators only.",
                group = GROUP_AUDIT,
            ),
        )

    /** The settings that switch a maker-checker control; changing one is audited HIGH. */
    val makerCheckerKeys: Set<String> =
        setOf(MAKER_CHECKER_USER_INVITES_KEY, MAKER_CHECKER_BRANCH_CREATION_KEY)

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
     * Rows the platform writes for its own bookkeeping, never as tenant settings. Reads treat them
     * as keys that do not exist, so the catalogue is the whole of what a tenant sees.
     */
    val internalKeys: Set<String> = setOf("settings.operational")

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
