package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogRepository
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrganisationInitialAdministratorBootstrapServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC)
    private val lifecyclePersistence = BootstrapLifecycleFake()
    private val events = BootstrapEventCapture()
    private val audits = BootstrapAuditCapture()
    private val transitionLogs = BootstrapTransitionLogCapture()
    private val lifecycle =
        FoundationLifecycleService(
            TransitionExecutor(clock, transitionLogs, events),
            lifecyclePersistence,
            lifecyclePersistence,
            lifecyclePersistence,
            AuditService(audits, clock),
        )
    private val store = BootstrapProvisioningFake(lifecyclePersistence)
    private val adminBootstrapStore = FakeInitialAdminBootstrapStore()
    private val bootstrapService = mock(InitialAdministratorBootstrapService::class.java)
    private val permissionGuard = mock(com.finaxis.platform.lifecycle.PermissionGuard::class.java)
    private val organisations =
        OrganisationProvisioningService(
            lifecycle,
            store,
            store,
            store,
            store,
            AuditService(audits, clock),
            adminBootstrapStore,
            bootstrapService,
            permissionGuard,
            clock,
        )

    @Test
    fun `createDraft persists initial administrator bootstrap record in DRAFT status`() {
        val maker = uuidV7()
        val result =
            organisations.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "boot-test",
                    displayName = "Bootstrap Test SACCO",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = maker,
                    admin =
                        InitialAdministratorDraft(
                            email = "admin@boot.test",
                            username = "bootstrapadmin",
                            displayName = "Bootstrap Admin",
                            phoneE164 = "+254700000001",
                            sendApplicationInvite = true,
                        ),
                ),
            )

        val record = adminBootstrapStore.records.getValue(result.organisationId)
        assertEquals(InitialAdministratorBootstrapStatus.DRAFT, record.status)
        assertEquals("admin@boot.test", record.adminEmail)
        assertEquals("bootstrapadmin", record.adminUsername)
        assertEquals("Bootstrap Admin", record.adminDisplayName)
        assertEquals("+254700000001", record.adminPhoneE164)
        assertEquals(true, record.sendApplicationInvite)
        assertEquals(maker, record.requestedBy)
    }

    @Test
    fun `createDraft rejects blank admin email`() {
        assertFailsWith<InvalidOperationException> {
            organisations.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "bad-email",
                    displayName = "Bad Email SACCO",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = uuidV7(),
                    admin =
                        InitialAdministratorDraft(
                            email = "",
                            username = "admin",
                            displayName = "Admin",
                            phoneE164 = null,
                            sendApplicationInvite = false,
                        ),
                ),
            )
        }
    }

    @Test
    fun `createDraft rejects malformed admin email`() {
        assertFailsWith<InvalidOperationException> {
            organisations.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "bad-email2",
                    displayName = "Bad Email2 SACCO",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = uuidV7(),
                    admin =
                        InitialAdministratorDraft(
                            email = "not-an-email",
                            username = "admin",
                            displayName = "Admin",
                            phoneE164 = null,
                            sendApplicationInvite = false,
                        ),
                ),
            )
        }
    }

    @Test
    fun `createDraft rejects blank admin username`() {
        assertFailsWith<InvalidOperationException> {
            organisations.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "bad-user",
                    displayName = "Bad User SACCO",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = uuidV7(),
                    admin =
                        InitialAdministratorDraft(
                            email = "admin@valid.test",
                            username = "   ",
                            displayName = "Admin",
                            phoneE164 = null,
                            sendApplicationInvite = false,
                        ),
                ),
            )
        }
    }

    @Test
    fun `createDraft rejects blank admin display name`() {
        assertFailsWith<InvalidOperationException> {
            organisations.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "bad-display",
                    displayName = "Bad Display SACCO",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = uuidV7(),
                    admin =
                        InitialAdministratorDraft(
                            email = "admin@valid.test",
                            username = "admin",
                            displayName = "",
                            phoneE164 = null,
                            sendApplicationInvite = false,
                        ),
                ),
            )
        }
    }

    @Test
    fun `createDraft rejects malformed E164 phone number`() {
        assertFailsWith<InvalidOperationException> {
            organisations.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "bad-phone",
                    displayName = "Bad Phone SACCO",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = uuidV7(),
                    admin =
                        InitialAdministratorDraft(
                            email = "admin@valid.test",
                            username = "admin",
                            displayName = "Admin",
                            phoneE164 = "07001234567", // missing + prefix
                            sendApplicationInvite = false,
                        ),
                ),
            )
        }
    }

    @Test
    fun `createDraft rejects nil (system-actor sentinel) maker identity`() {
        assertFailsWith<InvalidOperationException> {
            organisations.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "no-maker",
                    displayName = "No Maker SACCO",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = UUID(0L, 0L), // nil / bootstrap sentinel
                    admin =
                        InitialAdministratorDraft(
                            email = "admin@valid.test",
                            username = "admin",
                            displayName = "Admin",
                            phoneE164 = null,
                            sendApplicationInvite = false,
                        ),
                ),
            )
        }
    }

    @Test
    fun `approving a tenant as the same actor who requested the draft is rejected`() {
        val maker = uuidV7()
        val organisationId = activeDraftWithMaker(maker)

        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = uuidV7()),
        )

        assertFailsWith<ForbiddenOperationException> {
            organisations.approveProvisioning(
                ApproveOrganisationProvisioningCommand(organisationId, actorId = maker),
            )
        }
    }

    @Test
    fun `approving a tenant as the same actor who submitted the draft is rejected`() {
        val submitter = uuidV7()
        val organisationId = activeDraft()

        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = submitter),
        )

        assertFailsWith<ForbiddenOperationException> {
            organisations.approveProvisioning(
                ApproveOrganisationProvisioningCommand(organisationId, actorId = submitter),
            )
        }
    }

    @Test
    fun `rejecting a submitted organisation returns bootstrap status to DRAFT`() {
        val organisationId = activeDraft()
        val submitter = uuidV7()
        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = submitter),
        )

        organisations.rejectProvisioning(
            RejectOrganisationProvisioningCommand(
                organisationId = organisationId,
                reason = "Documents incomplete",
                actorId = uuidV7(),
            ),
        )

        assertEquals(
            InitialAdministratorBootstrapStatus.DRAFT,
            adminBootstrapStore.records.getValue(organisationId).status,
        )
    }

    @Test
    fun `approving a tenant records checker in bootstrap and sets status to QUEUED`() {
        val maker = uuidV7()
        val checker = uuidV7()
        val organisationId = activeDraftWithMaker(maker)
        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = uuidV7()),
        )

        organisations.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId, actorId = checker),
        )

        val record = adminBootstrapStore.records.getValue(organisationId)
        assertEquals(InitialAdministratorBootstrapStatus.QUEUED, record.status)
        assertEquals(checker, record.approvedBy)
    }

    @Test
    fun `retryBootstrap restarts a bootstrap marked FAILED`() {
        val organisationId = activeDraft()
        adminBootstrapStore.updateStatus(
            organisationId,
            InitialAdministratorBootstrapStatus.FAILED,
            lastFailureCode = "Keycloak unavailable",
        )

        organisations.retryBootstrap(
            RetryInitialAdministratorBootstrapCommand(
                organisationId,
                PlatformCaller(uuidV7(), uuidV7()),
            ),
        )

        org.mockito.Mockito
            .verify(bootstrapService)
            .bootstrap(organisationId)
    }

    @Test
    fun `retryBootstrap audits a repeat failure instead of staying silent`() {
        val organisationId = activeDraft()
        adminBootstrapStore.updateStatus(
            organisationId,
            InitialAdministratorBootstrapStatus.FAILED,
            lastFailureCode = "Keycloak unavailable",
        )
        val failure = IllegalStateException("Keycloak unavailable")
        org.mockito.Mockito
            .doThrow(failure)
            .`when`(bootstrapService)
            .bootstrap(organisationId)

        assertFailsWith<IllegalStateException> {
            organisations.retryBootstrap(
                RetryInitialAdministratorBootstrapCommand(
                    organisationId,
                    PlatformCaller(uuidV7(), uuidV7()),
                ),
            )
        }

        val recorded = audits.events.single { it.action == "tenant.bootstrap_retry" }
        assertEquals(
            com.finaxis.platform.common.audit.AuditOutcome.FAILURE,
            recorded.outcome,
        )
        assertEquals(organisationId.toString(), recorded.resourceId)
    }

    private fun activeDraft(): UUID {
        val organisationId = uuidV7()
        lifecyclePersistence.organisations[organisationId] =
            aggregate(organisationId, OrganisationLifecycleState.DRAFT, "ORGANISATION")
        store.metadataComplete += organisationId
        store.businessDates[organisationId] = LocalDate.of(2026, 7, 14)
        adminBootstrapStore.createDraft(
            organisationId = organisationId,
            admin =
                InitialAdministratorDraft(
                    email = "admin@test.com",
                    username = "admin",
                    displayName = "Admin",
                    phoneE164 = null,
                    sendApplicationInvite = false,
                ),
            requestedBy = UUID.randomUUID(),
        )
        return organisationId
    }

    private fun activeDraftWithMaker(maker: UUID): UUID {
        val organisationId = uuidV7()
        lifecyclePersistence.organisations[organisationId] =
            aggregate(organisationId, OrganisationLifecycleState.DRAFT, "ORGANISATION")
        store.metadataComplete += organisationId
        store.businessDates[organisationId] = LocalDate.of(2026, 7, 14)
        adminBootstrapStore.createDraft(
            organisationId = organisationId,
            admin =
                InitialAdministratorDraft(
                    email = "admin@test.com",
                    username = "admin",
                    displayName = "Admin",
                    phoneE164 = null,
                    sendApplicationInvite = false,
                ),
            requestedBy = maker,
        )
        return organisationId
    }
}

private class BootstrapProvisioningFake(
    private val lifecycle: BootstrapLifecycleFake,
) : OrganisationLifecycleProvisioningStore,
    OrganisationBootstrapStore,
    OrganisationAccessStore,
    OrganisationQueryStore,
    BranchLifecycleStore,
    BranchAssignmentStore {
    val settings = mutableMapOf<UUID, Map<String, String>>()
    val businessDates = mutableMapOf<UUID, LocalDate>()
    val headOffices = mutableSetOf<UUID>()
    val headOfficeIds = mutableMapOf<UUID, UUID>()
    val referenceSequences = mutableSetOf<UUID>()
    val defaultRoles = mutableSetOf<UUID>()
    val revokedAssignments = mutableListOf<DeprovisionedAssignment>()
    val metadataComplete = mutableSetOf<UUID>()
    val missingSetup = mutableSetOf<OrganisationSetupRequirement>()
    val organisationStates = mutableMapOf<UUID, OrganisationLifecycleState>()
    val branchStates = mutableMapOf<Pair<UUID, UUID>, BranchLifecycleState>()
    val memberships = mutableMapOf<Pair<UUID, UUID>, MembershipSnapshot>()
    val assignments = mutableSetOf<BootstrapAssignmentKey>()
    var listResult = OrganisationPage(emptyList(), 0)
    var lastListFilter: OrganisationListFilter? = null

    override fun createDraft(command: CreateOrganisationDraftCommand): UUID = uuidV7()

    override fun amendDraft(command: AmendOrganisationDraftCommand) {
        // no-op
    }

    override fun lifecycleState(organisationId: UUID) = organisationStates[organisationId]

    override fun saveSettings(
        organisationId: UUID,
        settings: Map<String, String>,
        actorId: UUID,
    ) {
        this.settings[organisationId] =
            settings
    }

    override fun ensureBusinessDate(
        organisationId: UUID,
        date: LocalDate,
    ) {
        businessDates[organisationId] =
            date
    }

    override fun hasRequiredMetadata(organisationId: UUID) = organisationId in metadataComplete

    override fun timezone(organisationId: UUID) = "Africa/Nairobi"

    override fun baseCurrencyCode(organisationId: UUID) = "KES"

    override fun ensureHeadOfficeDraft(organisationId: UUID): HeadOfficeDraftResult {
        headOfficeIds[organisationId]?.let { return HeadOfficeDraftResult(it, false) }
        val branchId = uuidV7()
        headOfficeIds[organisationId] = branchId
        headOffices += organisationId
        lifecycle.branches[organisationId to branchId] =
            aggregate(branchId, BranchLifecycleState.DRAFT, "BRANCH")
        return HeadOfficeDraftResult(branchId, true)
    }

    override fun createDefaultReferenceSequences(organisationId: UUID) {
        referenceSequences +=
            organisationId
    }

    override fun createDefaultRoles(organisationId: UUID) {
        defaultRoles += organisationId
    }

    override fun headOfficeState(organisationId: UUID) =
        headOfficeIds[organisationId]?.let { lifecycle.branches[organisationId to it]?.state }

    override fun missingRequiredSetup(organisationId: UUID): Set<OrganisationSetupRequirement> {
        val actualMissing = mutableSetOf<OrganisationSetupRequirement>()
        if (settings[organisationId]?.containsKey("settings.operational") != true) {
            actualMissing += OrganisationSetupRequirement.DEFAULT_SETTINGS
        }
        if (organisationId !in businessDates) {
            actualMissing += OrganisationSetupRequirement.BUSINESS_DATE
        }
        if (headOfficeState(organisationId) != BranchLifecycleState.ACTIVE) {
            actualMissing += OrganisationSetupRequirement.ACTIVE_HEAD_OFFICE
        }
        if (organisationId !in referenceSequences) {
            actualMissing += OrganisationSetupRequirement.REFERENCE_SEQUENCES
        }
        if (organisationId !in defaultRoles) {
            actualMissing += OrganisationSetupRequirement.DEFAULT_ROLES_AND_PERMISSIONS
        }
        return actualMissing + missingSetup
    }

    override fun branchesForDeprovisioning(organisationId: UUID) =
        emptyList<BranchLifecycleSnapshot>()

    override fun membershipsForDeprovisioning(organisationId: UUID) =
        emptyList<MembershipLifecycleSnapshot>()

    override fun revokeActiveAssignments(organisationId: UUID) = revokedAssignments

    override fun findByCode(tenantCode: String): OrganisationSummary? = null

    override fun list(filter: OrganisationListFilter): OrganisationPage {
        lastListFilter = filter
        return listResult
    }

    override fun organisationState(organisationId: UUID) = organisationStates[organisationId]

    override fun createDraft(command: CreateBranchCommand): UUID = uuidV7()

    override fun branchCodeExists(
        organisationId: UUID,
        branchCode: String,
        excludingBranchId: UUID?,
    ) = false

    override fun branchCode(
        organisationId: UUID,
        branchId: UUID,
    ) = "branch-$branchId"

    override fun parentBelongsToOrganisation(
        organisationId: UUID,
        parentBranchId: UUID,
    ) = true

    override fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ) = branchStates[
        organisationId to
            branchId,
    ]

    override fun parentBranchId(
        organisationId: UUID,
        branchId: UUID,
    ): UUID? = null

    override fun createdBy(
        organisationId: UUID,
        branchId: UUID,
    ): UUID? = null

    override fun userExists(userId: UUID) = true

    override fun membership(
        organisationId: UUID,
        userId: UUID,
    ) = memberships[
        organisationId to
            userId,
    ]

    override fun assign(command: AssignUserToBranchCommand): Boolean =
        assignments.add(
            BootstrapAssignmentKey(
                command.organisationId,
                command.userId,
                command.branchId,
                command.assignmentType,
            ),
        )

    override fun isActive(command: RevokeUserBranchAssignmentCommand) =
        BootstrapAssignmentKey(
            command.organisationId,
            command.userId,
            command.branchId,
            command.assignmentType,
        ) in assignments

    override fun activeAssignments(
        organisationId: UUID,
        userId: UUID,
    ) = assignments.count { it.organisationId == organisationId && it.userId == userId }

    override fun revoke(command: RevokeUserBranchAssignmentCommand): Boolean =
        assignments.remove(
            BootstrapAssignmentKey(
                command.organisationId,
                command.userId,
                command.branchId,
                command.assignmentType,
            ),
        )
}

private data class BootstrapAssignmentKey(
    val organisationId: UUID,
    val userId: UUID,
    val branchId: UUID,
    val type: BranchAssignmentType,
)

private class BootstrapLifecycleFake :
    FoundationLifecycleReader,
    FoundationLifecycleWriter,
    com.finaxis.platform.lifecycle.domain.LifecyclePrerequisites {
    val organisations = mutableMapOf<UUID, LifecycleAggregate<OrganisationLifecycleState>>()
    val branches = mutableMapOf<Pair<UUID, UUID>, LifecycleAggregate<BranchLifecycleState>>()
    val branchesWithActiveChildren = mutableSetOf<Pair<UUID, UUID>>()

    override fun findOrganisation(id: UUID) = organisations[id]

    override fun findBranch(
        organisationId: UUID,
        branchId: UUID,
    ): LifecycleAggregate<BranchLifecycleState>? = branches[organisationId to branchId]

    override fun findUser(userId: UUID): LifecycleAggregate<UserLifecycleState>? = null

    override fun findMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): LifecycleAggregate<MembershipLifecycleState>? = null

    override fun membershipUserId(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID? = null

    override fun findOrganisationIdsForActiveUserAccess(userId: UUID): Set<UUID> = emptySet()

    override fun saveOrganisation(aggregate: LifecycleAggregate<OrganisationLifecycleState>) =
        aggregate

    override fun saveBranch(
        aggregate: LifecycleAggregate<BranchLifecycleState>,
    ): LifecycleAggregate<BranchLifecycleState> {
        branches[
            requireNotNull(
                aggregate.organisationId,
            ) to UUID.fromString(aggregate.aggregateId),
        ] =
            aggregate
        return aggregate
    }

    override fun saveUser(aggregate: LifecycleAggregate<UserLifecycleState>) = aggregate

    override fun saveMembership(aggregate: LifecycleAggregate<MembershipLifecycleState>) = aggregate

    override fun revokeActiveAssignments(
        organisationId: UUID,
        userId: UUID,
    ): List<DeprovisionedAssignment> = emptyList()

    override fun organisationState(organisationId: UUID) = organisations[organisationId]?.state

    override fun branchHasActiveAssignments(
        organisationId: UUID,
        branchId: UUID,
    ) = false

    override fun branchHasActiveChildren(
        organisationId: UUID,
        branchId: UUID,
    ) = organisationId to branchId in branchesWithActiveChildren

    override fun userHasKeycloakIdentity(userId: UUID) = true

    override fun userState(userId: UUID) = UserLifecycleState.ACTIVE

    override fun membershipIsBranchExempt(
        organisationId: UUID,
        userId: UUID,
    ) = false

    override fun membershipHasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ) = true

    override fun membershipHasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ) = true
}

private class BootstrapAuditCapture : AuditEventRepository {
    val events = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        events += event
    }
}

private class BootstrapEventCapture : TransitionEventPublisher {
    val events = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        events += event
    }
}

private class BootstrapTransitionLogCapture : TransitionLogRepository {
    val logs = mutableListOf<TransitionLog>()

    override fun save(log: TransitionLog) {
        logs += log
    }
}

private fun <S : Enum<S>> aggregate(
    id: UUID,
    state: S,
    type: String,
) = LifecycleAggregate(id, state, type, id, 0)

private class FakeInitialAdminBootstrapStore : InitialAdministratorBootstrapStore {
    val records = mutableMapOf<UUID, InitialAdministratorBootstrapRecord>()

    override fun createDraft(
        organisationId: UUID,
        admin: InitialAdministratorDraft,
        requestedBy: UUID,
    ) {
        records[organisationId] =
            InitialAdministratorBootstrapRecord(
                organisationId = organisationId,
                adminEmail = admin.email,
                adminUsername = admin.username,
                adminDisplayName = admin.displayName,
                adminPhoneE164 = admin.phoneE164,
                sendApplicationInvite = admin.sendApplicationInvite,
                status = InitialAdministratorBootstrapStatus.DRAFT,
                attempts = 0,
                requestedBy = requestedBy,
                submittedBy = null,
                approvedBy = null,
                userId = null,
                membershipId = null,
                headOfficeId = null,
                roleId = null,
                lastFailureCode = null,
                createdAt = java.time.Instant.now(),
                submittedAt = null,
                approvedAt = null,
                updatedAt = java.time.Instant.now(),
                rowVersion = 0L,
            )
    }

    override fun amendDraft(
        organisationId: UUID,
        admin: InitialAdministratorDraft,
    ) {
        val record = records[organisationId] ?: error("Not found")
        records[organisationId] =
            record.copy(
                adminEmail = admin.email,
                adminUsername = admin.username,
                adminDisplayName = admin.displayName,
                adminPhoneE164 = admin.phoneE164,
                sendApplicationInvite = admin.sendApplicationInvite,
                updatedAt = java.time.Instant.now(),
                rowVersion = record.rowVersion + 1,
            )
    }

    override fun submit(
        organisationId: UUID,
        actorId: UUID,
    ) {
        val record = records[organisationId] ?: error("Not found")
        records[organisationId] =
            record.copy(
                status = InitialAdministratorBootstrapStatus.PENDING_ACTIVATION,
                submittedBy = actorId,
                submittedAt = java.time.Instant.now(),
                updatedAt = java.time.Instant.now(),
                rowVersion = record.rowVersion + 1,
            )
    }

    override fun approve(
        organisationId: UUID,
        actorId: UUID,
    ) {
        val record = records[organisationId] ?: error("Not found")
        records[organisationId] =
            record.copy(
                status = InitialAdministratorBootstrapStatus.QUEUED,
                approvedBy = actorId,
                approvedAt = java.time.Instant.now(),
                updatedAt = java.time.Instant.now(),
                rowVersion = record.rowVersion + 1,
            )
    }

    override fun reject(organisationId: UUID) {
        val record = records[organisationId] ?: error("Not found")
        records[organisationId] =
            record.copy(
                status = InitialAdministratorBootstrapStatus.DRAFT,
                submittedBy = null,
                submittedAt = null,
                approvedBy = null,
                approvedAt = null,
                updatedAt = java.time.Instant.now(),
                rowVersion = record.rowVersion + 1,
            )
    }

    override fun find(organisationId: UUID): InitialAdministratorBootstrapRecord? =
        records[organisationId]

    override fun updateStatus(
        organisationId: UUID,
        status: InitialAdministratorBootstrapStatus,
        lastFailureCode: String?,
        incrementAttempts: Boolean,
    ) {
        val record = records[organisationId] ?: error("Not found")
        records[organisationId] =
            record.copy(
                status = status,
                lastFailureCode = lastFailureCode,
                attempts = if (incrementAttempts) record.attempts + 1 else record.attempts,
                updatedAt = java.time.Instant.now(),
                rowVersion = record.rowVersion + 1,
            )
    }

    override fun linkResolvedEntities(
        organisationId: UUID,
        userId: UUID?,
        membershipId: UUID?,
        headOfficeId: UUID?,
        roleId: UUID?,
    ) {
        val record = records[organisationId] ?: error("Not found")
        records[organisationId] =
            record.copy(
                userId = userId,
                membershipId = membershipId,
                headOfficeId = headOfficeId,
                roleId = roleId,
                updatedAt = java.time.Instant.now(),
                rowVersion = record.rowVersion + 1,
            )
    }
}
