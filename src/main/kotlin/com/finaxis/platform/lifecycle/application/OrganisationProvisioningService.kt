package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.toAuditFailureReason
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DateTimeException
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
    private val bootstrapService: InitialAdministratorBootstrapService,
    private val permissionGuard: PermissionGuard,
    private val clock: Clock,
) {
    /** Creates a non-operational organisation draft with its initial local configuration. */
    @Transactional
    fun createDraft(command: CreateOrganisationDraftCommand): OrganisationDraftResult {
        validateCreate(command)
        validateAdmin(command.admin)
        errorUnless(command.requestedBy != SYSTEM_ACTOR, SafeError.INVALID_OPERATION)
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
            lifecycleStore
                .lifecycleState(command.organisationId)
                .orResourceNotFound()
        errorUnless(state == OrganisationLifecycleState.DRAFT, SafeError.CONFLICT)
        validateAmend(command)
        validateAdmin(command.admin)
        errorUnless(command.actorId != SYSTEM_ACTOR, SafeError.INVALID_OPERATION)

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
        errorUnless(lifecycleStore.hasRequiredMetadata(command.organisationId), SafeError.CONFLICT)
        val record =
            adminBootstrapStore
                .find(command.organisationId)
                .orResourceNotFound()
        validateAdmin(
            InitialAdministratorDraft(
                email = record.adminEmail,
                username = record.adminUsername,
                displayName = record.adminDisplayName,
                phoneE164 = record.adminPhoneE164,
                sendApplicationInvite = record.sendApplicationInvite,
            ),
        )
        errorUnless(command.actorId != SYSTEM_ACTOR, SafeError.INVALID_OPERATION)

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
            adminBootstrapStore
                .find(command.organisationId)
                .orResourceNotFound()
        errorUnless(command.actorId != record.requestedBy, SafeError.FORBIDDEN)
        if (record.submittedBy != null) {
            errorUnless(command.actorId != record.submittedBy, SafeError.FORBIDDEN)
        }
        errorUnless(command.actorId != SYSTEM_ACTOR, SafeError.INVALID_OPERATION)

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
        errorUnless(command.actorId != SYSTEM_ACTOR, SafeError.INVALID_OPERATION)
        adminBootstrapStore.reject(command.organisationId)

        lifecycleService.transition(
            OrganisationTransitionCommand(
                command.organisationId,
                OrganisationLifecycleTransition.REJECT,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Retries a failed initial administrator bootstrap process. */
    @Transactional
    @Suppress("TooGenericExceptionCaught")
    fun retryBootstrap(command: RetryInitialAdministratorBootstrapCommand) {
        val record =
            adminBootstrapStore
                .find(command.organisationId)
                .orResourceNotFound()
        errorUnless(record.status == InitialAdministratorBootstrapStatus.FAILED, SafeError.CONFLICT)
        when (val caller = command.caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    command.organisationId,
                    "tenant.bootstrap_retry",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(
                    caller.actorId,
                    "tenant.bootstrap_retry",
                )
            }
        }
        try {
            bootstrapService.bootstrap(command.organisationId)
        } catch (ex: Exception) {
            // Recorded in a new transaction (recordIndependently) because this method's caller
            // rethrows ex, which rolls back this @Transactional retryBootstrap call - without that
            // isolation this HIGH-risk audit row would be rolled back along with it.
            auditService.recordIndependently(
                AuditCommand(
                    actorType =
                        if (SystemActor.isSystemActor(command.caller.actorId)) SYSTEM else USER,
                    actorId = command.caller.actorId.toString(),
                    tenantId = command.organisationId.toString(),
                    action = "tenant.bootstrap_retry",
                    resourceType = ORGANISATION,
                    resourceId = command.organisationId.toString(),
                    outcome = AuditOutcome.FAILURE,
                    reason = ex.toAuditFailureReason(),
                    metadata = mapOf("previousBootstrapStatus" to record.status.name),
                ),
            )
            throw ex
        }
        // Re-read after bootstrapping: `record` was loaded before the retry and the precondition
        // above guarantees it was FAILED, so reporting it here would describe the retry's input
        // rather than its outcome.
        val resultingStatus =
            adminBootstrapStore
                .find(command.organisationId)
                ?.status
                ?.name
                ?: "UNKNOWN"
        audit(
            organisationId = command.organisationId,
            action = "tenant.bootstrap_retry",
            actorId = command.caller.actorId,
            metadata =
                mapOf(
                    "previousBootstrapStatus" to record.status.name,
                    "bootstrapStatus" to resultingStatus,
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
                    throw ConflictException()
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
        errorUnless(filter.page >= 0, SafeError.INVALID_PAGE_REQUEST)
        errorUnless(filter.size in 1..MAXIMUM_PAGE_SIZE, SafeError.INVALID_PAGE_REQUEST)
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
    errorUnless(command.tenantCode.isNotBlank(), SafeError.INVALID_OPERATION)
    errorUnless(command.displayName.isNotBlank(), SafeError.INVALID_OPERATION)
    errorUnless(COUNTRY_CODE.matches(command.countryCode), SafeError.INVALID_OPERATION)
    errorUnless(CURRENCY_CODE.matches(command.baseCurrencyCode), SafeError.INVALID_OPERATION)
    validateTimezone(command.timezone)
}

private fun validateAmend(command: AmendOrganisationDraftCommand) {
    errorUnless(command.tenantCode.isNotBlank(), SafeError.INVALID_OPERATION)
    errorUnless(command.displayName.isNotBlank(), SafeError.INVALID_OPERATION)
    errorUnless(COUNTRY_CODE.matches(command.countryCode), SafeError.INVALID_OPERATION)
    errorUnless(CURRENCY_CODE.matches(command.baseCurrencyCode), SafeError.INVALID_OPERATION)
    validateTimezone(command.timezone)
}

private fun validateAdmin(admin: InitialAdministratorDraft) {
    errorUnless(
        admin.email.isNotBlank() && EMAIL_REGEX.matches(admin.email),
        SafeError.INVALID_OPERATION,
    )
    errorUnless(admin.username.isNotBlank(), SafeError.INVALID_OPERATION)
    errorUnless(admin.displayName.isNotBlank(), SafeError.INVALID_OPERATION)
    admin.phoneE164?.let { phone ->
        errorUnless(PHONE_E164_REGEX.matches(phone), SafeError.INVALID_OPERATION)
    }
}

private fun validateTimezone(timezone: String) {
    try {
        ZoneId.of(timezone)
    } catch (_: DateTimeException) {
        throw InvalidOperationException()
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
            throw ConflictException()
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
            throw ConflictException()
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
    errorUnless(
        state in setOf(BranchLifecycleState.PENDING_APPROVAL, BranchLifecycleState.ACTIVE),
        SafeError.CONFLICT,
    )
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
    errorUnless(missing.isEmpty(), SafeError.CONFLICT)
}

private enum class SafeError(
    val exception: () -> RuntimeException,
) {
    INVALID_OPERATION({ InvalidOperationException() }),
    CONFLICT({ ConflictException() }),
    FORBIDDEN({ ForbiddenOperationException() }),
    INVALID_PAGE_REQUEST({ InvalidPageRequestException() }),
}

private fun errorUnless(
    condition: Boolean,
    error: SafeError,
) {
    if (!condition) throw error.exception()
}

private fun <T : Any> T?.orResourceNotFound(): T = this ?: throw ResourceNotFoundException()
