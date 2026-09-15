package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Organisation lifecycle and local-setup persistence port. */
interface OrganisationLifecycleProvisioningStore {
    /** Resolves the current lifecycle state by the internal organisation identifier. */
    fun lifecycleState(organisationId: UUID): OrganisationLifecycleState?

    /** Persists an organisation draft and returns its identifier. */
    fun createDraft(command: CreateOrganisationDraftCommand): UUID

    /** Amends the organisation draft details. */
    fun amendDraft(command: AmendOrganisationDraftCommand) {}

    /**
     * Stores already-canonicalized initial organisation settings.
     *
     * Each entry carries its own `valueType` and `sensitive` metadata rather than letting the
     * adapter guess: the caller has resolved them from `TenantSettingCatalog`, which is the same
     * source `OrganisationSettingsStore.upsertSetting` writes from, so a row created at
     * provisioning is indistinguishable from one written later through the settings endpoint.
     */
    fun saveSettings(
        organisationId: UUID,
        settings: List<StoredSetting>,
        actorId: UUID,
    )

    /** Returns whether submit-required organisation metadata is complete. */
    fun hasRequiredMetadata(organisationId: UUID): Boolean
}

/** Persistence operations for the local setup created within organisation provisioning. */
interface OrganisationBootstrapStore {
    /** Creates or preserves the organisation's current business date. */
    fun ensureBusinessDate(
        organisationId: UUID,
        date: LocalDate,
    )

    /** Returns the configured IANA timezone used to derive the default business date. */
    fun timezone(organisationId: UUID): String

    /** Returns the organisation's base currency code, or null when it does not exist. */
    fun baseCurrencyCode(organisationId: UUID): String?

    /**
     * The same code, read under a shared lock on the organisation row, for a caller that is about
     * to write something denominated in it.
     *
     * Accounting asks this - through `AccountingTenantLookup.functionalCurrencyForPosting` - once
     * its posting holds the tenant-currency lock, because [baseCurrencyCode] cannot answer the
     * question it is actually asking. A posting runs at `SERIALIZABLE`, so every plain read in it
     * comes from one snapshot: a base-currency change that commits after that snapshot is simply
     * invisible, and the journal is written in the superseded code. A read that locks the row
     * either answers from a row this transaction holds or fails outright, and failing is the
     * correct outcome - the caller's retry then reads at a fresh snapshot.
     *
     * Requires an active transaction; the lock is held to its end. Lifecycle's own provisioning
     * does not need it: the column is written only while the organisation is a draft, before
     * anything can be denominated in it.
     */
    fun lockBaseCurrencyCode(organisationId: UUID): String?

    /** Creates (or finds) the head-office branch in [BranchLifecycleState.DRAFT]. */
    fun ensureHeadOfficeDraft(organisationId: UUID): HeadOfficeDraftResult

    /** Creates the organisation's mandatory reference sequences. */
    fun createDefaultReferenceSequences(organisationId: UUID)

    /** Delegates default tenant-administration role setup to the IAM boundary. */
    fun createDefaultRoles(organisationId: UUID)

    /** Returns the current lifecycle state of the mandatory head-office branch. */
    fun headOfficeState(organisationId: UUID): BranchLifecycleState?
}

/** Result of ensuring the mandatory head-office branch during organisation provisioning. */
data class HeadOfficeDraftResult(
    val branchId: UUID,
    val created: Boolean,
)

/** Durable prerequisites required before an organisation can operate or be reactivated. */
enum class OrganisationSetupRequirement {
    DEFAULT_SETTINGS,
    BUSINESS_DATE,
    ACTIVE_HEAD_OFFICE,
    REFERENCE_SEQUENCES,
    DEFAULT_ROLES_AND_PERMISSIONS,
}

/** Access-control cleanup port for controlled organisation deprovisioning. */
interface OrganisationAccessStore {
    /** Lists every non-terminal branch that must be handled during deprovisioning. */
    fun branchesForDeprovisioning(organisationId: UUID): List<BranchLifecycleSnapshot>

    /** Lists every non-revoked membership that must be handled during deprovisioning. */
    fun membershipsForDeprovisioning(organisationId: UUID): List<MembershipLifecycleSnapshot>

    /** Returns each required durable setup prerequisite which is currently missing. */
    fun missingRequiredSetup(organisationId: UUID): Set<OrganisationSetupRequirement>

    /** Revokes each active assignment and returns its durable audit identity. */
    fun revokeActiveAssignments(organisationId: UUID): List<DeprovisionedAssignment>
}

/** Lifecycle state read for a branch before controlled organisation deprovisioning. */
data class BranchLifecycleSnapshot(
    val branchId: UUID,
    val status: BranchLifecycleState,
)

/** Lifecycle state read for a membership before controlled organisation deprovisioning. */
data class MembershipLifecycleSnapshot(
    val membershipId: UUID,
    val status: MembershipLifecycleState,
)

/** Assignment revoked as part of organisation deprovisioning and retained for audit reporting. */
data class DeprovisionedAssignment(
    val assignmentId: UUID,
    val assignmentType: String,
)

/** Organisation settings persistence port for post-provisioning configuration changes. */
interface OrganisationSettingsStore {
    /** Returns the currently effective values for the requested setting keys. */
    fun currentSettings(
        organisationId: UUID,
        keys: Set<String>,
    ): Map<String, String>

    /** Closes each currently effective row and inserts a new effective-dated row per key. */
    fun updateSettings(
        organisationId: UUID,
        updates: Map<String, String>,
        actorId: UUID,
    )

    /** Returns the currently effective stored setting for [key], or null when unset. */
    fun currentSetting(
        organisationId: UUID,
        key: String,
    ): StoredSetting?

    /** Returns every currently effective stored setting for the organisation. */
    fun currentSettingsList(organisationId: UUID): List<StoredSetting>

    /**
     * Closes the open row for [key], inserts a typed replacement, and returns the row closed while
     * holding the per-key lock; null means no row was currently effective.
     */
    fun upsertSetting(
        organisationId: UUID,
        key: String,
        value: String,
        valueType: String,
        sensitive: Boolean,
        actorId: UUID,
    ): StoredSetting?

    /** Closes the open row for [key] with no replacement; false when no open row existed. */
    fun deactivateSetting(
        organisationId: UUID,
        key: String,
        actorId: UUID,
    ): Boolean
}

/** An organisation setting row with its type and sensitivity metadata, as read back or written. */
data class StoredSetting(
    val key: String,
    val value: String,
    val valueType: String,
    val sensitive: Boolean,
) {
    /** Builders for setting rows the platform is about to write. */
    companion object {
        /**
         * Validates [rawValue] against [TenantSettingCatalog] and builds the row [key] must be
         * stored as, so a caller supplying settings cannot choose their own storage metadata.
         *
         * Throws [com.finaxis.platform.common.application.InvalidOperationException] for a key the
         * catalog does not define, and whatever the key's own type rule throws for a bad value -
         * `accounting.currency_invalid` for `base_currency`, for instance. That is the same
         * treatment `TenantSettingsService.createOrUpdate` gives a key, which is the point: an
         * uncatalogued key accepted here would be a setting the `/settings` endpoint refuses to
         * create and can only ever report as unmanaged.
         */
        fun fromCatalog(
            key: String,
            rawValue: String,
        ): StoredSetting {
            val definition = TenantSettingCatalog.require(key)
            return StoredSetting(
                key = definition.key,
                value = TenantSettingCatalog.canonicalize(key, rawValue),
                valueType = definition.valueType.name,
                sensitive = definition.sensitive,
            )
        }
    }
}

/** Business date persistence port for the controlled, optimistically-locked business date. */
interface BusinessDateStore {
    /** Returns the current business date snapshot used for optimistic-lock validation. */
    fun current(organisationId: UUID): BusinessDateSnapshot?

    /** Advances the business date; returns `false` when [expectedRowVersion] is stale. */
    fun advance(
        organisationId: UUID,
        newDate: LocalDate,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean

    /** Inserts the singleton business-date row as OPEN; false when one already exists. */
    fun initialize(
        organisationId: UUID,
        initialDate: LocalDate,
        actorId: UUID,
    ): Boolean

    /** Sets status under optimistic lock without touching the COB date; false when stale. */
    fun changeStatus(
        organisationId: UUID,
        newStatus: String,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean

    /** Starts COB: sets status CLOSING and the COB date under optimistic lock; false when stale. */
    fun startCob(
        organisationId: UUID,
        cobDate: LocalDate,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean
}

/** Current business date read for optimistic-lock validation before advancing it. */
data class BusinessDateSnapshot(
    val currentBusinessDate: LocalDate,
    val status: String,
    val rowVersion: Long,
)

/** Append-only persistence port for business-date / COB status change history. */
interface BusinessDateHistoryStore {
    /** Appends one history entry. */
    fun append(entry: BusinessDateHistoryEntry)

    /** Returns a page of history entries newest-first for the organisation. */
    fun list(
        organisationId: UUID,
        page: Int,
        size: Int,
    ): BusinessDateHistoryPage
}

/** A business-date / COB status change to append to history. */
data class BusinessDateHistoryEntry(
    val organisationId: UUID,
    val eventType: String,
    val fromStatus: String?,
    val toStatus: String,
    val fromBusinessDate: LocalDate?,
    val toBusinessDate: LocalDate,
    val actorId: UUID?,
    val reason: String?,
    val occurredAt: Instant,
)

/** A history entry read back for the history query. */
data class BusinessDateHistoryRecord(
    val eventType: String,
    val fromStatus: String?,
    val toStatus: String,
    val fromBusinessDate: LocalDate?,
    val toBusinessDate: LocalDate,
    val actorId: UUID?,
    val reason: String?,
    val occurredAt: Instant,
)

/** A page of business-date history entries. */
data class BusinessDateHistoryPage(
    val items: List<BusinessDateHistoryRecord>,
    val totalItems: Long,
)

/** Read port for pagination-safe organisation administration queries. */
interface OrganisationQueryStore {
    /** Retrieves an organisation by the externally stable tenant code. */
    fun findByCode(tenantCode: String): OrganisationSummary?

    /** Lists organisations using the bounded filter supplied by the application layer. */
    fun list(filter: OrganisationListFilter): OrganisationPage
}

/** Branch lifecycle persistence port, always scoped to its owning organisation. */
interface BranchLifecycleStore {
    /** Resolves the lifecycle state of an owning organisation. */
    fun organisationState(organisationId: UUID): OrganisationLifecycleState?

    /** Persists a branch draft and returns its identifier. */
    fun createDraft(command: CreateBranchCommand): UUID

    /** Checks uniqueness of a branch code within the selected organisation. */
    fun branchCodeExists(
        organisationId: UUID,
        branchCode: String,
        excludingBranchId: UUID? = null,
    ): Boolean

    /** Resolves a branch code only within the selected organisation. */
    fun branchCode(
        organisationId: UUID,
        branchId: UUID,
    ): String?

    /** Checks that a parent branch is scoped to the selected organisation. */
    fun parentBelongsToOrganisation(
        organisationId: UUID,
        parentBranchId: UUID,
    ): Boolean

    /** Resolves a branch lifecycle state only within the selected organisation. */
    fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ): BranchLifecycleState?

    /** Resolves an existing branch parent only inside its organisation. */
    fun parentBranchId(
        organisationId: UUID,
        branchId: UUID,
    ): UUID?

    /** Resolves the creator (maker) user ID of a branch. */
    fun createdBy(
        organisationId: UUID,
        branchId: UUID,
    ): UUID?
}

/** Organisation-scoped persistence port for user-to-branch assignments. */
interface BranchAssignmentStore {
    /** Returns whether the global application user exists. */
    fun userExists(userId: UUID): Boolean

    /** Resolves a user membership only within the selected organisation. */
    fun membership(
        organisationId: UUID,
        userId: UUID,
    ): MembershipSnapshot?

    /** Creates or reactivates a user-to-branch assignment; returns true only if state changed. */
    fun assign(command: AssignUserToBranchCommand): Boolean

    /** Returns whether the exact assignment is currently active. */
    fun isActive(command: RevokeUserBranchAssignmentCommand): Boolean

    /** Counts active assignments for membership-revocation safety validation. */
    fun activeAssignments(
        organisationId: UUID,
        userId: UUID,
    ): Int

    /** Marks a user-to-branch assignment revoked; returns true only if state changed. */
    fun revoke(command: RevokeUserBranchAssignmentCommand): Boolean
}
