package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Creates an organisation in its non-operational draft state. */
data class CreateOrganisationDraftCommand(
    val tenantCode: String,
    val displayName: String,
    val legalName: String?,
    val registrationNumber: String?,
    val countryCode: String,
    val baseCurrencyCode: String,
    val timezone: String,
    val initialSettings: Map<String, String> = emptyMap(),
    val businessDate: LocalDate? = null,
    val requestedBy: UUID,
    val admin: InitialAdministratorDraft =
        InitialAdministratorDraft(
            email = "admin@test.com",
            username = "admin",
            displayName = "Admin",
            phoneE164 = null,
            sendApplicationInvite = false,
        ),
)

/** Amends an existing organisation draft before it is submitted. */
data class AmendOrganisationDraftCommand(
    val organisationId: UUID,
    val tenantCode: String,
    val displayName: String,
    val legalName: String?,
    val registrationNumber: String?,
    val countryCode: String,
    val baseCurrencyCode: String,
    val timezone: String,
    val initialSettings: Map<String, String> = emptyMap(),
    val businessDate: LocalDate? = null,
    val actorId: UUID,
    val requestId: UUID,
    val admin: InitialAdministratorDraft,
)

/** Submits an organisation draft to the approval workflow. */
data class SubmitOrganisationForApprovalCommand(
    val organisationId: UUID,
    val reason: DecisionRemark? = null,
    val actorId: UUID = uuidV7(),
    val requestId: UUID = uuidV7(),
)

/** Approves a submitted organisation and performs its durable local setup. */
data class ApproveOrganisationProvisioningCommand(
    val organisationId: UUID,
    val reason: DecisionRemark? = null,
    val actorId: UUID = uuidV7(),
    val requestId: UUID = uuidV7(),
)

/** Rejects an organisation approval request without deleting the draft data. */
data class RejectOrganisationProvisioningCommand(
    val organisationId: UUID,
    val reason: Reason,
    val actorId: UUID = uuidV7(),
    val requestId: UUID = uuidV7(),
)

/** Returns a pending organisation to draft so its maker can amend and resubmit it. */
data class ReturnOrganisationForChangesCommand(
    val organisationId: UUID,
    val reason: Reason,
    val actorId: UUID,
)

/** Suspends an active organisation while retaining all of its data. */
data class SuspendOrganisationCommand(
    val organisationId: UUID,
    val reason: Reason,
)

/** Reactivates an organisation after its operational prerequisites are checked. */
data class ReactivateOrganisationCommand(
    val organisationId: UUID,
    val reason: DecisionRemark? = null,
)

/** Starts and completes metadata-only organisation deprovisioning. */
data class DeprovisionOrganisationCommand(
    val organisationId: UUID,
    val reason: Reason,
)

/** Compact organisation projection for application queries. */
data class OrganisationSummary(
    val organisationId: UUID,
    val tenantCode: String,
    val displayName: String,
    val countryCode: String,
    val status: OrganisationLifecycleState,
    val createdAt: Instant,
    /** Current bootstrap lifecycle status, or null when not yet requested. */
    val bootstrapStatus: InitialAdministratorBootstrapStatus? = null,
    /** Running attempt count for asynchronous bootstrap retries. */
    val bootstrapAttempts: Int? = null,
    /** Resolved user account ID, present once the admin identity has been created. */
    val bootstrapUserId: UUID? = null,
    /** Resolved membership ID, present once the admin has been enrolled in the organisation. */
    val bootstrapMembershipId: UUID? = null,
    /**
     * Safe failure code recorded on the last failed bootstrap attempt.
     * Never contains raw exception messages or Keycloak payloads.
     */
    val lastBootstrapFailureCode: String? = null,
)

/** Pagination-safe filter for organisation administration queries. */
data class OrganisationListFilter(
    val status: OrganisationLifecycleState? = null,
    val countryCode: String? = null,
    val createdFrom: Instant? = null,
    val createdTo: Instant? = null,
    val page: Int = 0,
    val size: Int = 25,
)

/** Page response for organisation administration queries. */
data class OrganisationPage(
    val items: List<OrganisationSummary>,
    val totalItems: Long,
)

/** Result returned after an organisation draft has been recorded. */
data class OrganisationDraftResult(
    val organisationId: UUID,
    val status: OrganisationLifecycleState,
)

/**
 * The authority a maker or checker step is exercised under. [PLATFORM] means the permission is
 * checked in the reserved platform organisation, so no tenant membership is required; an actor
 * who also happens to be an active tenant member may still use it. See ADR 0028.
 */
enum class ActingScope {
    TENANT,
    PLATFORM,
}

/** Creates a branch draft inside an active or provisioning organisation. */
data class CreateBranchCommand(
    val organisationId: UUID,
    val branchCode: String,
    val branchName: String,
    val branchType: String,
    val parentBranchId: UUID? = null,
    val timezone: String,
    val address: Map<String, String> = emptyMap(),
    val requestedBy: UUID,
    val scope: ActingScope = ActingScope.TENANT,
)

/**
 * Changes some of a branch's descriptive fields in place; a null field is left unchanged.
 *
 * The parent is the one field where null is also a value (a branch with no parent), so it carries
 * [changesParent]: `false` leaves the parent alone, `true` sets it to [parentBranchId] - which may
 * be null to detach the branch. The code and type are never updatable.
 */
data class UpdateBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val actorId: UUID,
    val branchName: String? = null,
    val changesParent: Boolean = false,
    val parentBranchId: UUID? = null,
    val timezone: String? = null,
    val address: Map<String, String>? = null,
) {
    /** The wire names of the fields this command changes, in a stable order. */
    val changedFields: List<String>
        get() =
            listOfNotNull(
                "branch_name".takeIf { branchName != null },
                "parent_branch_id".takeIf { changesParent },
                "timezone".takeIf { timezone != null },
                "address".takeIf { address != null },
            )
}

/** Submits a branch draft to approval. */
data class SubmitBranchForApprovalCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: DecisionRemark? = null,
    val actorId: UUID,
    val requestId: UUID,
    val scope: ActingScope = ActingScope.TENANT,
)

/** Activates an approved branch. */
data class ActivateBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: DecisionRemark? = null,
    val actorId: UUID,
    val requestId: UUID,
    val scope: ActingScope = ActingScope.TENANT,
)

/** Returns a pending branch to draft: a checker's return or the maker's own withdrawal. */
data class ReturnBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: Reason,
    val actorId: UUID,
    val scope: ActingScope = ActingScope.TENANT,
)

/** Suspends an active branch. */
data class SuspendBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: Reason,
    val actorId: UUID,
)

/** Reactivates a suspended branch. */
data class ReactivateBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: DecisionRemark? = null,
    val actorId: UUID,
)

/** Closes a branch without physically deleting it. */
data class CloseBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: Reason,
    val actorId: UUID,
)

/** Types of active operational access a user may have at a branch. */
enum class BranchAssignmentType {
    HOME,
    OPERATE,
    APPROVE,
    VIEW,
}

/** Creates or reactivates an active user-to-branch assignment. */
data class AssignUserToBranchCommand(
    val organisationId: UUID,
    val userId: UUID,
    val branchId: UUID,
    val assignmentType: BranchAssignmentType,
    val assignedBy: UUID,
)

/** Revokes a user-to-branch assignment, subject to membership safety guards. */
data class RevokeUserBranchAssignmentCommand(
    val organisationId: UUID,
    val userId: UUID,
    val branchId: UUID,
    val assignmentType: BranchAssignmentType,
    val revokedBy: UUID,
)

/** Membership categories that influence branch-assignment requirements. */
enum class MembershipType {
    STAFF,
    ADMIN,
    AUDITOR,
    SYSTEM,
}

/** Snapshot used by assignment application guards. */
data class MembershipSnapshot(
    val status: MembershipLifecycleState,
    val type: MembershipType,
)

/** Result returned after a branch draft has been created. */
data class BranchDraftResult(
    val branchId: UUID,
    val status: BranchLifecycleState,
)

/** Advances the controlled organisation business date by one optimistic-locked step. */
data class AdvanceBusinessDateCommand(
    val organisationId: UUID,
    val newBusinessDate: LocalDate,
    val actorId: UUID,
    val reason: String? = null,
)

/** Result returned after a business date advance. */
data class BusinessDateAdvanceResult(
    val organisationId: UUID,
    val previousBusinessDate: LocalDate,
    val newBusinessDate: LocalDate,
)

/** Initializes the controlled business date for an active organisation. */
data class InitializeBusinessDateCommand(
    val organisationId: UUID,
    val initialBusinessDate: LocalDate,
    val actorId: UUID,
    val reason: String? = null,
)

/** Starts close-of-business processing for the current organisation business date. */
data class StartCobCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val reason: String? = null,
)

/** Completes close-of-business status processing for the current organisation business date. */
data class CompleteCobCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val reason: String? = null,
)

/** Reopens a closed organisation business date. */
data class ReopenBusinessDateCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val reason: String? = null,
)

/** Requests the current organisation business date. */
data class GetBusinessDateQuery(
    val organisationId: UUID,
    val actorId: UUID,
)

/** Requests a bounded page of organisation business-date history. */
data class ListBusinessDateHistoryQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val page: Int = 0,
    val size: Int = 25,
)

/** Read model for the current organisation business date. */
data class BusinessDateView(
    val organisationId: UUID,
    val currentBusinessDate: LocalDate,
    val status: String,
)

/**
 * Command to retry failed initial administrator bootstrap process. Platform operators only:
 * `tenant.bootstrap_retry` is a platform-scope permission, evaluated in the PLATFORM
 * organisation, so no tenant caller can issue it.
 */
data class RetryInitialAdministratorBootstrapCommand(
    val organisationId: UUID,
    val caller: PlatformCaller,
    val requestId: String? = null,
)
