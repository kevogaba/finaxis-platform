package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.Redacted
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
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
    private val permissionGuard: PermissionGuard,
    private val auditService: AuditService,
    private val eventPublisher: TransitionEventPublisher,
    private val clock: Clock,
) {
    /** Creates or updates one tenant setting for an active organisation. */
    @Transactional
    fun createOrUpdate(command: CreateOrUpdateTenantSettingCommand): TenantSettingView {
        requireActive(command.organisationId)
        val definition = TenantSettingCatalog.require(command.key)
        authorize(definition, command.organisationId, command.actorId)
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

    /** Deactivates one tenant setting for an active organisation. */
    @Transactional
    fun deactivate(command: DeactivateTenantSettingCommand) {
        requireActive(command.organisationId)
        val definition = TenantSettingCatalog.require(command.key)
        authorize(definition, command.organisationId, command.actorId)

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
     * unknown key with no stored row is rejected via [TenantSettingCatalog.require].
     */
    @Transactional(readOnly = true)
    fun get(query: GetTenantSettingQuery): TenantSettingView {
        val definition = TenantSettingCatalog.definition(query.key)
        val stored = settingsStore.currentSetting(query.organisationId, query.key)
        if (definition == null && stored == null) {
            TenantSettingCatalog.require(query.key)
        }
        if (definition != null) {
            authorize(definition, query.organisationId, query.actorId)
        } else {
            permissionGuard.requirePermission(
                query.actorId,
                query.organisationId,
                SETTING_PERMISSION,
            )
        }
        return view(query.key, definition, stored, rawValue = stored?.value)
    }

    /**
     * Lists all catalog-defined settings plus any stored settings whose key is not in the catalog,
     * redacting sensitive values. Catalog metadata is preferred; stored-only keys fall back to
     * their own persisted [StoredSetting] metadata.
     */
    @Transactional(readOnly = true)
    fun list(query: ListTenantSettingsQuery): List<TenantSettingView> {
        permissionGuard.requirePermission(query.actorId, query.organisationId, SETTING_PERMISSION)
        val canManagePlatformSettings = canManagePlatformSettings(query.actorId)
        val stored =
            settingsStore
                .currentSettingsList(query.organisationId)
                .associateBy { it.key }
        val catalogViews =
            TenantSettingCatalog.definitions.map { definition ->
                view(
                    definition.key,
                    definition,
                    stored[definition.key],
                    stored[definition.key]?.value,
                )
            }
        val extraViews =
            stored.values
                .filter { TenantSettingCatalog.definition(it.key) == null }
                .map { view(it.key, definition = null, stored = it, rawValue = it.value) }
        return (catalogViews + extraViews).map { setting ->
            if (setting.platformAdminOnly && !canManagePlatformSettings) {
                setting.copy(value = MASK)
            } else {
                setting
            }
        }
    }

    private fun requireActive(organisationId: UUID) {
        require(
            lifecycleStore.lifecycleState(organisationId) == OrganisationLifecycleState.ACTIVE,
        ) {
            "Settings can be changed only for an active organisation."
        }
    }

    private fun authorize(
        definition: TenantSettingDefinition,
        organisationId: UUID,
        actorId: UUID,
    ) {
        if (definition.platformAdminOnly) {
            permissionGuard.requirePermission(
                actorId,
                PlatformOrganisation.ID,
                PLATFORM_SETTING_PERMISSION,
            )
        } else {
            permissionGuard.requirePermission(actorId, organisationId, SETTING_PERMISSION)
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
        const val SETTINGS_UPDATED_TARGET = "finaxis.lifecycle.organisation.settings-updated"
        const val ORGANISATION_SETTING = "ORGANISATION_SETTING"
        const val SETTING_PERMISSION = "settings.update"
        const val PLATFORM_SETTING_PERMISSION = "tenant_setting.manage_platform"
        const val MASK = "***REDACTED***"
        const val UNKNOWN_VALUE_TYPE = "STRING"
    }
}
