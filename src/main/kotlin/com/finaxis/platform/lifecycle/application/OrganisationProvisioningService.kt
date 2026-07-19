package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Organisation-first provisioning and metadata-only deprovisioning use cases. The shared FSM
 * service remains responsible for every lifecycle mutation, transition log, and outbox event.
 */
@Service
class OrganisationProvisioningService(
    private val lifecycleService: FoundationLifecycleService,
    private val lifecycleStore: OrganisationLifecycleProvisioningStore,
    private val bootstrapStore: OrganisationBootstrapStore,
    private val accessStore: OrganisationAccessStore,
    private val queryStore: OrganisationQueryStore,
    private val auditService: AuditService,
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
    private val clock: Clock,
) {
    /** Creates a non-operational organisation draft with its initial local configuration. */
    @Transactional
    fun createDraft(command: CreateOrganisationDraftCommand): OrganisationDraftResult {
        validateCreate(command)
        validateAdmin(command.admin)
        require(command.requestedBy != SYSTEM_ACTOR) { "Maker identity is required." }
        val organisationId = lifecycleStore.createDraft(command)
        adminBootstrapStore.createDraft(organisationId, command.admin, command.requestedBy)
        lifecycleStore.saveSettings(organisationId, command.initialSettings, command.requestedBy)
        bootstrapStore.ensureBusinessDate(
            organisationId,
            command.businessDate ?: businessDate(command.timezone),
        )
        audit(
            organisationId = organisationId,
            action = "organisation.create_draft",
            actorId = command.requestedBy,
            metadata = mapOf("tenantCode" to command.tenantCode),
        )
        return OrganisationDraftResult(organisationId, OrganisationLifecycleState.DRAFT)
    }

    /** Amends an existing organisation draft before it is submitted. */
    @Transactional
    fun amendDraft(command: AmendOrganisationDraftCommand) {
        val state =
            lifecycleStore.lifecycleState(command.organisationId)
                ?: throw IllegalArgumentException("Organisation not found.")
        require(state == OrganisationLifecycleState.DRAFT) {
            "Only organisation drafts can be amended."
        }
        validateAmend(command)
        validateAdmin(command.admin)
        require(command.actorId != SYSTEM_ACTOR) { "Maker identity is required." }

        lifecycleStore.amendDraft(command)
        adminBootstrapStore.amendDraft(command.organisationId, command.admin)

        audit(
            organisationId = command.organisationId,
            action = "organisation.amend_draft",
            actorId = command.actorId,
            metadata = mapOf("tenantCode" to command.tenantCode),
        )
    }

    /** Validates metadata and moves a draft into the approval workflow. */
    @Transactional
    fun submitForApproval(command: SubmitOrganisationForApprovalCommand) {
        require(lifecycleStore.hasRequiredMetadata(command.organisationId)) {
            "Organisation metadata is incomplete."
        }
        val record =
            adminBootstrapStore.find(command.organisationId)
                ?: throw IllegalArgumentException("Initial administrator details must be provided.")
        validateAdmin(
            InitialAdministratorDraft(
                email = record.adminEmail,
                username = record.adminUsername,
                displayName = record.adminDisplayName,
                phoneE164 = record.adminPhoneE164,
                sendApplicationInvite = record.sendApplicationInvite,
            ),
        )
        require(command.actorId != SYSTEM_ACTOR) { "Maker identity is required." }

        adminBootstrapStore.submit(command.organisationId, command.actorId)

        lifecycleService.transition(
            OrganisationTransitionCommand(
                command.organisationId,
                OrganisationLifecycleTransition.SUBMIT,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Creates mandatory durable setup and activates an approved organisation atomically. */
    @Transactional
    fun approveProvisioning(command: ApproveOrganisationProvisioningCommand) {
        val record =
            adminBootstrapStore.find(command.organisationId)
                ?: throw IllegalArgumentException("Initial administrator details must be provided.")
        require(command.actorId != record.requestedBy) {
            "Maker cannot approve their own tenant."
        }
        if (record.submittedBy != null) {
            require(command.actorId != record.submittedBy) {
                "Maker cannot approve their own tenant."
            }
        }
        require(command.actorId != SYSTEM_ACTOR) { "Checker identity is required." }

        lifecycleService.transition(
            OrganisationTransitionCommand(
                command.organisationId,
                OrganisationLifecycleTransition.START_PROVISIONING,
                TransitionCommand(reason = command.reason),
            ),
        )
        lifecycleStore.saveSettings(command.organisationId, DEFAULT_SETTINGS, SYSTEM_ACTOR)
        bootstrapStore.ensureBusinessDate(
            command.organisationId,
            businessDate(bootstrapStore.timezone(command.organisationId)),
        )
        activateHeadOffice(
            lifecycleService,
            bootstrapStore,
            auditService,
            command.organisationId,
            command.reason,
        )
        bootstrapStore.createDefaultReferenceSequences(command.organisationId)
        bootstrapStore.createDefaultRoles(command.organisationId)

        adminBootstrapStore.approve(command.organisationId, command.actorId)

        accessStore.requireCompleteSetup(command.organisationId)
        lifecycleService.transition(
            OrganisationTransitionCommand(
                command.organisationId,
                OrganisationLifecycleTransition.ACTIVATE,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Rejects a pending organisation request and preserves its draft data for auditability. */
    @Transactional
    fun rejectProvisioning(command: RejectOrganisationProvisioningCommand) {
        require(command.actorId != SYSTEM_ACTOR) { "Checker identity is required." }
        adminBootstrapStore.reject(command.organisationId)

        lifecycleService.transition(
            OrganisationTransitionCommand(
                command.organisationId,
                OrganisationLifecycleTransition.REJECT,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Suspends an active organisation without deleting data. */
    @Transactional
    fun suspend(command: SuspendOrganisationCommand) {
        lifecycleService.transition(
            OrganisationTransitionCommand(
                command.organisationId,
                OrganisationLifecycleTransition.SUSPEND,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Reactivates an organisation only when its local operating prerequisites still exist. */
    @Transactional
    fun reactivate(command: ReactivateOrganisationCommand) {
        accessStore.requireCompleteSetup(command.organisationId)
        lifecycleService.transition(
            OrganisationTransitionCommand(
                command.organisationId,
                OrganisationLifecycleTransition.REACTIVATE,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Performs controlled metadata-only deprovisioning and intentionally retains tenant data. */
    @Transactional
    fun deprovision(command: DeprovisionOrganisationCommand) {
        startDeprovisioning(command)
        deprovisionBranches(command)
        revokeMemberships(command)
        revokeAssignments(command)
        completeDeprovisioning(command)
    }

    private fun startDeprovisioning(command: DeprovisionOrganisationCommand) {
        val transition =
            when (lifecycleStore.lifecycleState(command.organisationId)) {
                OrganisationLifecycleState.ACTIVE -> {
                    OrganisationLifecycleTransition.START_DEPROVISIONING
                }

                OrganisationLifecycleState.SUSPENDED -> {
                    OrganisationLifecycleTransition.START_SUSPENDED_DEPROVISIONING
                }

                else -> {
                    throw IllegalStateException(
                        "Only active or suspended organisations can be deprovisioned.",
                    )
                }
            }
        lifecycleService.transition(
            OrganisationTransitionCommand(
                command.organisationId,
                transition,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    private fun deprovisionBranches(command: DeprovisionOrganisationCommand) {
        accessStore.branchesForDeprovisioning(command.organisationId).forEach { branch ->
            lifecycleService.transition(
                BranchTransitionCommand(
                    command.organisationId,
                    branch.branchId,
                    branchSuspensionTransition(branch.status),
                    TransitionCommand(reason = command.reason),
                ),
            )
        }
    }

    private fun revokeMemberships(command: DeprovisionOrganisationCommand) {
        accessStore.membershipsForDeprovisioning(command.organisationId).forEach { membership ->
            lifecycleService.transition(
                MembershipTransitionCommand(
                    command.organisationId,
                    membership.membershipId,
                    transition = membershipDeprovisioningTransition(membership.status),
                    command = TransitionCommand(reason = command.reason),
                ),
            )
        }
    }

    private fun revokeAssignments(command: DeprovisionOrganisationCommand) {
        accessStore.revokeActiveAssignments(command.organisationId).forEach { assignment ->
            audit(
                organisationId = command.organisationId,
                action = "organisation.deprovision_assignment_revoked",
                actorId = SYSTEM_ACTOR,
                metadata =
                    mapOf(
                        "assignmentId" to assignment.assignmentId.toString(),
                        "assignmentType" to assignment.assignmentType,
                    ),
                reason = command.reason,
            )
        }
    }

    private fun completeDeprovisioning(command: DeprovisionOrganisationCommand) {
        lifecycleService.transition(
            OrganisationTransitionCommand(
                command.organisationId,
                OrganisationLifecycleTransition.COMPLETE_DEPROVISIONING,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Finds an organisation by its stable external tenant code. */
    fun getByCode(tenantCode: String): OrganisationSummary? = queryStore.findByCode(tenantCode)

    /** Returns only the current lifecycle status for an organisation code. */
    fun statusByCode(tenantCode: String): OrganisationLifecycleState? =
        queryStore.findByCode(tenantCode)?.status

    /** Lists organisations using a bounded, pagination-aware application filter. */
    fun list(filter: OrganisationListFilter): OrganisationPage {
        require(filter.page >= 0) { "Page must not be negative." }
        require(filter.size in 1..MAXIMUM_PAGE_SIZE) {
            "Page size must be between 1 and $MAXIMUM_PAGE_SIZE."
        }
        return queryStore.list(filter)
    }

    private fun businessDate(timezone: String): LocalDate =
        LocalDate.now(clock.withZone(ZoneId.of(timezone)))

    private fun audit(
        organisationId: UUID,
        action: String,
        actorId: UUID,
        metadata: Map<String, String>,
        reason: String? = null,
    ) {
        auditService.record(
            AuditCommand(
                actorType = if (SystemActor.isSystemActor(actorId)) SYSTEM else USER,
                actorId = actorId.toString(),
                tenantId = organisationId.toString(),
                action = action,
                resourceType = ORGANISATION,
                resourceId = organisationId.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = reason,
                metadata = metadata,
            ),
        )
    }

    private companion object {
        val DEFAULT_SETTINGS = mapOf("settings.operational" to "true")
        const val USER = "USER"
        const val SYSTEM = "SYSTEM"
        const val ORGANISATION = "ORGANISATION"
        const val MAXIMUM_PAGE_SIZE = 100
        val SYSTEM_ACTOR = UUID(0L, 0L)
    }
}

private val COUNTRY_CODE = Regex("[A-Z]{2}")
private val CURRENCY_CODE = Regex("[A-Z]{3}")
private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val PHONE_E164_REGEX = Regex("^\\+[1-9]\\d{1,14}$")

private fun validateCreate(command: CreateOrganisationDraftCommand) {
    require(command.tenantCode.isNotBlank()) { "Tenant code is required." }
    require(command.displayName.isNotBlank()) { "Display name is required." }
    require(
        COUNTRY_CODE.matches(command.countryCode),
    ) { "Country code must be ISO-3166 alpha-2." }
    require(CURRENCY_CODE.matches(command.baseCurrencyCode)) {
        "Base currency code must be ISO-4217 alpha-3."
    }
    ZoneId.of(command.timezone)
}

private fun validateAmend(command: AmendOrganisationDraftCommand) {
    require(command.tenantCode.isNotBlank()) { "Tenant code is required." }
    require(command.displayName.isNotBlank()) { "Display name is required." }
    require(
        COUNTRY_CODE.matches(command.countryCode),
    ) { "Country code must be ISO-3166 alpha-2." }
    require(CURRENCY_CODE.matches(command.baseCurrencyCode)) {
        "Base currency code must be ISO-4217 alpha-3."
    }
    ZoneId.of(command.timezone)
}

private fun validateAdmin(admin: InitialAdministratorDraft) {
    require(admin.email.isNotBlank() && EMAIL_REGEX.matches(admin.email)) {
        "A valid email address is required."
    }
    require(admin.username.isNotBlank()) { "Username is required." }
    require(admin.displayName.isNotBlank()) { "Display name is required." }
    admin.phoneE164?.let { phone ->
        require(PHONE_E164_REGEX.matches(phone)) {
            "Phone number must be in E.164 format."
        }
    }
}

private fun branchSuspensionTransition(status: BranchLifecycleState): BranchLifecycleTransition =
    when (status) {
        BranchLifecycleState.DRAFT -> {
            BranchLifecycleTransition.SUSPEND_DRAFT
        }

        BranchLifecycleState.PENDING_APPROVAL -> {
            BranchLifecycleTransition.SUSPEND_PENDING_APPROVAL
        }

        BranchLifecycleState.ACTIVE -> {
            BranchLifecycleTransition.SUSPEND
        }

        BranchLifecycleState.SUSPENDED -> {
            BranchLifecycleTransition.CONFIRM_SUSPENDED
        }

        BranchLifecycleState.CLOSED,
        BranchLifecycleState.ARCHIVED,
        -> {
            throw IllegalArgumentException("Terminal branches cannot be deprovisioned again.")
        }
    }

private fun membershipDeprovisioningTransition(
    status: MembershipLifecycleState,
): MembershipLifecycleTransition =
    when (status) {
        MembershipLifecycleState.PENDING_APPROVAL -> {
            MembershipLifecycleTransition.REVOKE_PENDING
        }

        MembershipLifecycleState.ACTIVE -> {
            MembershipLifecycleTransition.REVOKE
        }

        MembershipLifecycleState.SUSPENDED -> {
            MembershipLifecycleTransition.REVOKE_SUSPENDED
        }

        MembershipLifecycleState.REVOKED -> {
            error("Revoked memberships are not deprovisioned.")
        }
    }

private fun activateHeadOffice(
    lifecycleService: FoundationLifecycleService,
    bootstrapStore: OrganisationBootstrapStore,
    auditService: AuditService,
    organisationId: UUID,
    reason: String?,
) {
    val headOffice = bootstrapStore.ensureHeadOfficeDraft(organisationId)
    val branchId = headOffice.branchId
    if (headOffice.created) {
        auditService.record(
            AuditCommand(
                actorType = SYSTEM_ACTOR_TYPE,
                actorId = BOOTSTRAP_SYSTEM_ACTOR.toString(),
                tenantId = organisationId.toString(),
                action = "branch.create_draft",
                resourceType = BRANCH_RESOURCE,
                resourceId = branchId.toString(),
                outcome = AuditOutcome.SUCCESS,
                metadata =
                    mapOf(
                        BOOTSTRAP_METADATA_KEY to "true",
                        BOOTSTRAP_SOURCE_METADATA_KEY to ORGANISATION_PROVISIONING_SOURCE,
                    ),
            ),
        )
    }
    var state = bootstrapStore.headOfficeState(organisationId)
    if (state == BranchLifecycleState.DRAFT) {
        state =
            lifecycleService
                .transition(
                    BranchTransitionCommand(
                        organisationId,
                        branchId,
                        BranchLifecycleTransition.SUBMIT,
                        TransitionCommand(reason = reason),
                    ),
                ).toState
    }
    require(state in setOf(BranchLifecycleState.PENDING_APPROVAL, BranchLifecycleState.ACTIVE)) {
        "Head office must be draft, pending approval, or active."
    }
    if (state == BranchLifecycleState.PENDING_APPROVAL) {
        lifecycleService.transition(
            BranchTransitionCommand(
                organisationId,
                branchId,
                BranchLifecycleTransition.ACTIVATE,
                TransitionCommand(reason = reason),
            ),
        )
    }
}

private val BOOTSTRAP_SYSTEM_ACTOR: UUID = UUID(0L, 0L)
private const val SYSTEM_ACTOR_TYPE = "SYSTEM"
private const val BRANCH_RESOURCE = "BRANCH"
private const val BOOTSTRAP_METADATA_KEY = "bootstrap"
private const val BOOTSTRAP_SOURCE_METADATA_KEY = "bootstrapSource"
private const val ORGANISATION_PROVISIONING_SOURCE = "organisation_provisioning"

private fun OrganisationAccessStore.requireCompleteSetup(organisationId: UUID) {
    val missing = missingRequiredSetup(organisationId)
    require(missing.isEmpty()) {
        "Organisation mandatory setup is incomplete: ${missing.joinToString()}."
    }
}
