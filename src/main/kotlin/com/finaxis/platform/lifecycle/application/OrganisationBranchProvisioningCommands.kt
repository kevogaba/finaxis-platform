package com.finaxis.platform.lifecycle.application

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
)

/** Submits an organisation draft to the approval workflow. */
data class SubmitOrganisationForApprovalCommand(
    val organisationId: UUID,
    val reason: String? = null,
)

/** Approves a submitted organisation and performs its durable local setup. */
data class ApproveOrganisationProvisioningCommand(
    val organisationId: UUID,
    val reason: String? = null,
)

/** Rejects an organisation approval request without deleting the draft data. */
data class RejectOrganisationProvisioningCommand(
    val organisationId: UUID,
    val reason: String,
)

/** Suspends an active organisation while retaining all of its data. */
data class SuspendOrganisationCommand(
    val organisationId: UUID,
    val reason: String,
)

/** Reactivates an organisation after its operational prerequisites are checked. */
data class ReactivateOrganisationCommand(
    val organisationId: UUID,
    val reason: String? = null,
)

/** Starts and completes metadata-only organisation deprovisioning. */
data class DeprovisionOrganisationCommand(
    val organisationId: UUID,
    val reason: String,
)

/** Compact organisation projection for application queries. */
data class OrganisationSummary(
    val organisationId: UUID,
    val tenantCode: String,
    val displayName: String,
    val countryCode: String,
    val status: OrganisationLifecycleState,
    val createdAt: Instant,
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
)

/** Submits a branch draft to approval. */
data class SubmitBranchForApprovalCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: String? = null,
)

/** Activates an approved branch. */
data class ActivateBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: String? = null,
)

/** Suspends an active branch. */
data class SuspendBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: String,
)

/** Reactivates a suspended branch. */
data class ReactivateBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: String? = null,
)

/** Closes a branch without physically deleting it. */
data class CloseBranchCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val reason: String,
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

/** Updates organisation settings after provisioning; each key gets a new effective-dated row. */
data class UpdateOrganisationSettingsCommand(
    val organisationId: UUID,
    val updates: Map<String, String>,
    val actorId: UUID,
    val reason: String? = null,
)

/** Result returned after an organisation settings update. */
data class OrganisationSettingsResult(
    val organisationId: UUID,
    val updated: Map<String, String>,
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
