package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.audit.Redacted
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.web.api.boundedPageOffset
import com.finaxis.platform.common.web.api.requireValidPage
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import com.finaxis.platform.lifecycle.domain.TenantSettingDefinition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Manages setup-level tenant settings validated against [TenantSettingCatalog]. Settings are
 * effective-dated (never overwritten destructively); mutations require the tenant to be active,
 * are permission-gated per setting, and are audited with sensitive values redacted.
 */
@Service
class TenantSettingsService(
    private val lifecycleStore: OrganisationLifecycleProvisioningStore,
    private val settingsStore: OrganisationSettingsStore,
    private val organisations: OrganisationBootstrapStore,
    private val permissionGuard: PermissionGuard,
    private val auditService: AuditService,
    private val eventPublisher: TransitionEventPublisher,
    private val clock: Clock,
) {
    /** Creates or updates one tenant setting for an active organisation. */
    @Transactional
    fun createOrUpdate(command: CreateOrUpdateTenantSettingCommand): TenantSettingView {
        if (command.platformScope) requirePlatformAdministrator(command.actorId)
        requireActive(command.organisationId, command.platformScope)
        val definition = TenantSettingCatalog.require(command.key)
        authorize(definition, command.organisationId, command.actorId, command.platformScope)
        refuseMirroredKey(command.key)
        serialiseWithApprovals(command.organisationId, command.key)
        val canonical = TenantSettingCatalog.canonicalize(command.key, command.value)

        val before =
            settingsStore.upsertSetting(
                command.organisationId,
                command.key,
                canonical,
                definition.valueType.name,
                definition.sensitive,
                command.actorId,
            )
        auditChange(
            command.organisationId,
            command.actorId,
            command.key,
            definition.sensitive,
            beforeValue = before?.value,
            afterValue = canonical,
            reason = command.reason,
        )
        publish(command.organisationId, command.actorId, command.key, "TenantSettingsUpdated")
        return view(command.key, definition, stored = null, rawValue = canonical)
    }

    /**
     * Deactivates one tenant setting for an active organisation. `base_currency` is refused, as
     * [createOrUpdate] refuses it: it mirrors the organisation's own column.
     */
    @Transactional
    fun deactivate(command: DeactivateTenantSettingCommand) {
        if (command.platformScope) requirePlatformAdministrator(command.actorId)
        requireActive(command.organisationId, command.platformScope)
        val definition = TenantSettingCatalog.require(command.key)
        authorize(definition, command.organisationId, command.actorId, command.platformScope)
        refuseMirroredKey(command.key)
        serialiseWithApprovals(command.organisationId, command.key)

        val before = settingsStore.currentSetting(command.organisationId, command.key)
        val deactivated =
            settingsStore.deactivateSetting(command.organisationId, command.key, command.actorId)
        if (!deactivated) return
        auditChange(
            command.organisationId,
            command.actorId,
            command.key,
            definition.sensitive,
            beforeValue = before?.value,
            afterValue = null,
            reason = command.reason,
        )
        publish(command.organisationId, command.actorId, command.key, "TenantSettingDeactivated")
    }

    /**
     * Reads one tenant setting, redacting sensitive values. When the key is in the catalog its
     * declared metadata drives the view; otherwise the metadata falls back to the stored row so a
     * setting persisted before a catalog change (or under a legacy key) is still readable. An
     * unknown key with no stored row is rejected via [TenantSettingCatalog.require], after
     * the permission check.
     */
    @GatedRead
    @Transactional(readOnly = true)
    fun get(query: GetTenantSettingQuery): TenantSettingView {
        // Authorise per key before the stored row is read, so a caller without the permission
        // learns nothing about whether a key exists (403, never 400 or 404 first).
        if (query.platformScope) return getAsPlatform(query)
        val definition = TenantSettingCatalog.definition(query.key)
        if (definition != null) {
            authorize(definition, query.organisationId, query.actorId, isMutation = false)
        } else {
            permissionGuard.requirePermission(
                query.actorId,
                query.organisationId,
                SETTING_READ_PERMISSION,
            )
        }
        val stored = storedSetting(query.organisationId, query.key)
        if (definition == null && stored == null) {
            TenantSettingCatalog.require(query.key)
        }
        return view(
            query.key,
            definition,
            stored,
            rawValue = effectiveValue(query.organisationId, query.key, stored),
        )
    }

    /**
     * Lists all catalog-defined settings plus any stored settings whose key is not in the catalog,
     * redacting sensitive values. Catalog metadata is preferred; stored-only keys fall back to
     * their own persisted [StoredSetting] metadata.
     */
    @GatedRead
    @Transactional(readOnly = true)
    fun list(query: ListTenantSettingsQuery): TenantSettingPage {
        if (query.platformScope) return listAsPlatform(query)
        permissionGuard.requirePermission(
            query.actorId,
            query.organisationId,
            SETTING_READ_PERMISSION,
        )
        requireValidPage(query.page, query.size)

        val canManagePlatformSettings = canManagePlatformSettings(query.actorId)
        val stored =
            settingsStore
                .currentSettingsList(query.organisationId)
                .filterNot { it.key in TenantSettingCatalog.internalKeys }
                .associateBy { it.key }
        val catalogViews =
            TenantSettingCatalog.definitions.map { definition ->
                view(
                    definition.key,
                    definition,
                    stored[definition.key],
                    effectiveValue(query.organisationId, definition.key, stored[definition.key]),
                )
            }
        val extraViews =
            stored.values
                .filter { TenantSettingCatalog.definition(it.key) == null }
                .map { view(it.key, definition = null, stored = it, rawValue = it.value) }

        return pageOf((catalogViews + extraViews).sortedBy { it.key }, query) { setting ->
            val masked = setting.platformAdminOnly && !canManagePlatformSettings
            if (masked && setting.value != null) setting.copy(value = MASK) else setting
        }
    }

    private fun pageOf(
        sortedList: List<TenantSettingView>,
        query: ListTenantSettingsQuery,
        shown: (TenantSettingView) -> TenantSettingView = { it },
    ): TenantSettingPage {
        val total = sortedList.size.toLong()
        val fromIndex =
            boundedPageOffset(query.page, query.size, total)
                ?: return TenantSettingPage(emptyList(), total)
        val toIndex = (fromIndex.toLong() + query.size.toLong()).coerceAtMost(total).toInt()
        return TenantSettingPage(sortedList.subList(fromIndex, toIndex).map(shown), total)
    }

    /**
     * The stored row for [key], or null. The internal key has no tenant-visible row, so the caller
     * refuses it as an unknown key; the mirror answers from the organisation column instead, so
     * neither stored row is read.
     */
    private fun storedSetting(
        organisationId: UUID,
        key: String,
    ): StoredSetting? =
        if (key in TenantSettingCatalog.internalKeys ||
            key == TenantSettingCatalog.BASE_CURRENCY_KEY
        ) {
            null
        } else {
            settingsStore.currentSetting(organisationId, key)
        }

    /**
     * The value a read reports. `base_currency` is a read-only mirror of the organisation's own
     * column, the one the ledger reads, so the column answers and any stored row is ignored.
     */
    private fun effectiveValue(
        organisationId: UUID,
        key: String,
        stored: StoredSetting?,
    ): String? =
        if (key == TenantSettingCatalog.BASE_CURRENCY_KEY) {
            organisations.baseCurrencyCode(organisationId)
        } else {
            stored?.value
        }

    /** Refuses a write to the read-only `base_currency` mirror, before anything is touched. */
    private fun refuseMirroredKey(key: String) {
        if (key == TenantSettingCatalog.BASE_CURRENCY_KEY) {
            throw InvalidOperationException(safeDetail = BASE_CURRENCY_MIRROR_DETAIL)
        }
    }

    /**
     * A maker-checker switch is read by a membership or branch approval after it locks the
     * organisation row, so a change takes the same lock: an approval then reads the switch as it
     * stands once this change commits, never a value this change has already replaced.
     */
    private fun serialiseWithApprovals(
        organisationId: UUID,
        key: String,
    ) {
        if (key in TenantSettingCatalog.makerCheckerKeys) {
            lifecycleStore.lockOrganisation(organisationId)
        }
    }

    private fun requireActive(
        organisationId: UUID,
        platformScope: Boolean = false,
    ) {
        if (platformScope) {
            auditService.requireNotPlatformOrganisation(organisationId, SETTINGS_AUDIT_ACTION)
        }
        val state = lifecycleStore.lifecycleState(organisationId)
        if (state == null && platformScope) throw ResourceNotFoundException()
        if (state != OrganisationLifecycleState.ACTIVE) throw ConflictException()
    }

    private fun requirePlatformAdministrator(actorId: UUID) {
        permissionGuard.requirePermission(
            actorId,
            PlatformOrganisation.ID,
            PLATFORM_SETTING_PERMISSION,
        )
    }

    /** Reads one platform-only setting of a tenant for a platform administrator. */
    private fun getAsPlatform(query: GetTenantSettingQuery): TenantSettingView {
        requirePlatformAdministrator(query.actorId)
        auditService.requireNotPlatformOrganisation(query.organisationId, SETTINGS_AUDIT_ACTION)
        if (lifecycleStore.lifecycleState(query.organisationId) == null) {
            throw ResourceNotFoundException()
        }
        val definition = TenantSettingCatalog.require(query.key)
        authorize(definition, query.organisationId, query.actorId, platformScope = true)
        val stored = storedSetting(query.organisationId, query.key)
        return view(query.key, definition, stored, rawValue = stored?.value)
    }

    /** Lists a tenant's platform-only settings, which only a platform administrator may see. */
    private fun listAsPlatform(query: ListTenantSettingsQuery): TenantSettingPage {
        requirePlatformAdministrator(query.actorId)
        requireValidPage(query.page, query.size)
        auditService.requireNotPlatformOrganisation(query.organisationId, SETTINGS_AUDIT_ACTION)
        if (lifecycleStore.lifecycleState(query.organisationId) == null) {
            throw ResourceNotFoundException()
        }
        val stored = settingsStore.currentSettingsList(query.organisationId).associateBy { it.key }
        val views =
            TenantSettingCatalog.definitions
                .filter { it.platformAdminOnly }
                .sortedBy { it.key }
                .map { view(it.key, it, stored[it.key], stored[it.key]?.value) }
        return pageOf(views, query)
    }

    private fun authorize(
        definition: TenantSettingDefinition,
        organisationId: UUID,
        actorId: UUID,
        platformScope: Boolean = false,
        isMutation: Boolean = true,
    ) {
        if (platformScope) {
            // The platform administrator is no tenant member, so only a platform-only key is
            // reachable here; the caller has already passed the platform permission.
            if (!definition.platformAdminOnly) {
                throw InvalidOperationException(safeDetail = NOT_PLATFORM_SETTING_DETAIL)
            }
        } else if (definition.platformAdminOnly) {
            permissionGuard.requirePermission(
                actorId,
                PlatformOrganisation.ID,
                PLATFORM_SETTING_PERMISSION,
            )
        } else {
            val permission = if (isMutation) SETTING_MUTATE_PERMISSION else SETTING_READ_PERMISSION
            permissionGuard.requirePermission(actorId, organisationId, permission)
        }
    }

    private fun canManagePlatformSettings(actorId: UUID): Boolean =
        runCatching {
            permissionGuard.requirePermission(
                actorId,
                PlatformOrganisation.ID,
                PLATFORM_SETTING_PERMISSION,
            )
        }.isSuccess

    private fun auditChange(
        organisationId: UUID,
        actorId: UUID,
        key: String,
        sensitive: Boolean,
        beforeValue: String?,
        afterValue: String?,
        reason: String?,
    ) {
        auditService.recordSettingsChange(
            actorId = actorId,
            tenantId = organisationId,
            resourceId = key,
            before = mapOf(key to auditValue(sensitive, beforeValue)),
            after = mapOf(key to auditValue(sensitive, afterValue)),
            reason = reason,
            // Switching a maker-checker control is loosening a safeguard, so it is HIGH.
            severity =
                if (key in TenantSettingCatalog.makerCheckerKeys) {
                    AuditSeverity.HIGH
                } else {
                    AuditSeverity.INFO
                },
        )
    }

    private fun auditValue(
        sensitive: Boolean,
        value: String?,
    ): Any? = if (sensitive && value != null) Redacted(value) else value

    /**
     * Builds a read view, preferring catalog [definition] metadata and falling back to the
     * [stored] row's own metadata for keys not in the catalog. Sensitive values are masked.
     */
    private fun view(
        key: String,
        definition: TenantSettingDefinition?,
        stored: StoredSetting?,
        rawValue: String?,
    ): TenantSettingView {
        val sensitive = definition?.sensitive ?: stored?.sensitive ?: false
        val valueType = definition?.valueType?.name ?: stored?.valueType ?: UNKNOWN_VALUE_TYPE
        val platformAdminOnly = definition?.platformAdminOnly ?: false
        val effectiveRawValue = rawValue ?: definition?.defaultValue
        val display = if (sensitive && effectiveRawValue != null) MASK else effectiveRawValue
        return TenantSettingView(
            key = key,
            value = display,
            valueType = valueType,
            sensitive = sensitive,
            platformAdminOnly = platformAdminOnly,
            isDefault = rawValue == null,
            label = definition?.label,
            description = definition?.description,
            group = definition?.group,
        )
    }

    private fun publish(
        organisationId: UUID,
        actorId: UUID,
        key: String,
        eventType: String,
    ) {
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = SETTINGS_UPDATED_TARGET,
                aggregateType = ORGANISATION_SETTING,
                aggregateId = organisationId.toString(),
                transition = "UPDATE",
                fromState = "PREVIOUS",
                toState = "CURRENT",
                actor = TransitionActor("USER", actorId.toString()),
                occurredAt = clock.instant(),
                metadata =
                    mapOf(
                        "organisationId" to organisationId.toString(),
                        "eventType" to eventType,
                        "key" to key,
                    ),
            ),
        )
    }

    private companion object {
        const val NOT_PLATFORM_SETTING_DETAIL =
            "Only platform-only settings can be managed on the platform route."
        const val BASE_CURRENCY_MIRROR_DETAIL =
            "base_currency is derived from the organisation's base currency " +
                "and cannot be changed here."

        const val SETTINGS_AUDIT_ACTION = "settings.update"
        const val SETTINGS_UPDATED_TARGET = "finaxis.lifecycle.organisation.settings-updated"
        const val ORGANISATION_SETTING = "ORGANISATION_SETTING"
        const val SETTING_MUTATE_PERMISSION = "settings.update"
        const val SETTING_READ_PERMISSION = "settings.view"
        const val PLATFORM_SETTING_PERMISSION = "tenant_setting.manage_platform"
        const val MASK = "***REDACTED***"
        const val UNKNOWN_VALUE_TYPE = "STRING"
    }
}
