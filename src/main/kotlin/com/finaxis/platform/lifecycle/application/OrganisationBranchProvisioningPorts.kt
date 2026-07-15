package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import java.time.LocalDate
import java.util.UUID

/** Organisation lifecycle and local-setup persistence port. */
interface OrganisationLifecycleProvisioningStore {
    /** Resolves the current lifecycle state by the internal organisation identifier. */
    fun lifecycleState(organisationId: UUID): OrganisationLifecycleState?

    /** Persists an organisation draft and returns its identifier. */
    fun createDraft(command: CreateOrganisationDraftCommand): UUID

    /** Stores supplied initial non-sensitive organisation settings. */
    fun saveSettings(
        organisationId: UUID,
        settings: Map<String, String>,
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
}

/** Current business date read for optimistic-lock validation before advancing it. */
data class BusinessDateSnapshot(
    val currentBusinessDate: LocalDate,
    val status: String,
    val rowVersion: Long,
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
