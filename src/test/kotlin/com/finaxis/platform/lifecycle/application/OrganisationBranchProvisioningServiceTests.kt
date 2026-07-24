package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
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
import com.finaxis.platform.lifecycle.PermissionGuard
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

class OrganisationBranchProvisioningServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC)
    private val lifecyclePersistence = LifecycleFake()
    private val events = EventCapture()
    private val audits = AuditCapture()
    private val transitionLogs = TransitionLogCapture()
    private val lifecycle =
        FoundationLifecycleService(
            TransitionExecutor(clock, transitionLogs, events),
            lifecyclePersistence,
            lifecyclePersistence,
            lifecyclePersistence,
            AuditService(audits, clock),
        )
    private val store = ProvisioningFake(lifecyclePersistence)
    private val adminBootstrapStore = FakeInitialAdministratorBootstrapStore()
    private val bootstrapService = mock(InitialAdministratorBootstrapService::class.java)
    private val permissionGuard = mock(PermissionGuard::class.java)
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
    private val branches =
        BranchProvisioningService(lifecycle, store, store, AuditService(audits, clock), events)

    @Test
    fun `creates organisation draft with timezone business date and requested settings`() {
        val result =
            organisations.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "acme",
                    displayName = "Acme SACCO",
                    legalName = "Acme SACCO Limited",
                    registrationNumber = "C-100",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    initialSettings = mapOf("settings.locale" to "en-KE"),
                    requestedBy = uuidV7(),
                ),
            )

        assertEquals(OrganisationLifecycleState.DRAFT, result.status)
        assertEquals(LocalDate.of(2026, 7, 14), store.businessDates.getValue(result.organisationId))
        assertEquals(
            "en-KE",
            store.settings.getValue(result.organisationId).getValue("settings.locale"),
        )
        assertEquals("organisation.create_draft", audits.events.single().action)
    }

    @Test
    fun `classifies the common system actor as system when creating an organisation draft`() {
        val result =
            organisations.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "system-acme",
                    displayName = "System Acme SACCO",
                    legalName = "System Acme SACCO Limited",
                    registrationNumber = "C-101",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = SystemActor.ID,
                ),
            )

        val audit = audits.events.single()
        assertEquals(result.organisationId.toString(), audit.tenantId)
        assertEquals("organisation.create_draft", audit.action)
        assertEquals("SYSTEM", audit.actorType)
        assertEquals(SystemActor.ID.toString(), audit.actorId)
    }

    @Test
    fun `submitting organisation emits durable approval request after metadata validation`() {
        val organisationId = activeDraft()

        organisations.submitForApproval(SubmitOrganisationForApprovalCommand(organisationId))

        assertEquals(
            OrganisationLifecycleState.PENDING_APPROVAL,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
        assertEquals(
            "finaxis.lifecycle.organisation.approval-requested",
            (events.events.single() as ExternalizedTransitionEvent).target,
        )
        assertEquals(
            OrganisationLifecycleTransition.SUBMIT.name,
            (events.events.single() as ExternalizedTransitionEvent).transition,
        )
    }

    @Test
    fun `approving organisation creates mandatory local setup before activation`() {
        val organisationId = activeDraft()
        organisations.submitForApproval(SubmitOrganisationForApprovalCommand(organisationId))

        organisations.approveProvisioning(ApproveOrganisationProvisioningCommand(organisationId))

        assertEquals(
            OrganisationLifecycleState.ACTIVE,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
        assertTrue(store.headOffices.contains(organisationId))
        assertTrue(store.referenceSequences.contains(organisationId))
        assertTrue(store.defaultRoles.contains(organisationId))
        assertTrue(
            audits.events.any {
                it.action == "branch.create_draft" &&
                    it.actorType == "SYSTEM" &&
                    it.resourceType == "BRANCH" &&
                    it.metadata["bootstrap"] == "true"
            },
        )
        assertTrue(
            events.events.any {
                (it as? ExternalizedTransitionEvent)?.target ==
                    "finaxis.lifecycle.organisation.activated"
            },
        )
        assertEquals(
            OrganisationLifecycleTransition.ACTIVATE.name,
            (events.events.last() as ExternalizedTransitionEvent).transition,
        )
    }

    @Test
    fun `rejecting an organisation publishes the external rejected lifecycle event`() {
        val organisationId = activeDraft()
        organisations.submitForApproval(SubmitOrganisationForApprovalCommand(organisationId))
        events.events.clear()

        organisations.rejectProvisioning(
            RejectOrganisationProvisioningCommand(organisationId, "Registration validation failed"),
        )

        assertEquals(
            OrganisationLifecycleState.REJECTED,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
        assertEquals("Registration validation failed", transitionLogs.logs.last().reason)
        assertExternalizedTarget("finaxis.lifecycle.organisation.rejected")
    }

    @Test
    fun `suspending and reactivating an organisation publish their external lifecycle events`() {
        val organisationId = uuidV7()
        lifecyclePersistence.organisations[organisationId] =
            aggregate(organisationId, OrganisationLifecycleState.ACTIVE, "ORGANISATION")
        store.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        store.completeSetup(organisationId)

        organisations.suspend(SuspendOrganisationCommand(organisationId, "Regulatory review"))

        assertEquals(
            OrganisationLifecycleState.SUSPENDED,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
        assertEquals("Regulatory review", transitionLogs.logs.last().reason)
        assertExternalizedTarget("finaxis.lifecycle.organisation.suspended")

        events.events.clear()
        organisations.reactivate(ReactivateOrganisationCommand(organisationId, "Review complete"))

        assertEquals(
            OrganisationLifecycleState.ACTIVE,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
        assertExternalizedTarget("finaxis.lifecycle.organisation.reactivated")
    }

    @Test
    fun `activate branch rejects maker activating their own branch draft`() {
        val organisationId = uuidV7()
        val makerId = uuidV7()
        val checkerId = uuidV7()

        store.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        lifecyclePersistence.organisations[organisationId] =
            aggregate(organisationId, OrganisationLifecycleState.ACTIVE, "ORGANISATION")

        val branchId =
            branches
                .createDraft(
                    CreateBranchCommand(
                        organisationId = organisationId,
                        branchCode = "BR-TEST",
                        branchName = "Test Branch",
                        branchType = "OPERATIONAL",
                        timezone = "UTC",
                        requestedBy = makerId,
                    ),
                ).branchId

        lifecyclePersistence.branches[organisationId to branchId] =
            aggregate(branchId, BranchLifecycleState.PENDING_APPROVAL, "BRANCH")

        // Maker cannot activate
        assertFailsWith<ForbiddenOperationException> {
            branches.activate(
                ActivateBranchCommand(
                    organisationId = organisationId,
                    branchId = branchId,
                    actorId = makerId,
                    requestId = uuidV7(),
                ),
            )
        }

        // Distinct checker can activate
        branches.activate(
            ActivateBranchCommand(
                organisationId = organisationId,
                branchId = branchId,
                actorId = checkerId,
                requestId = uuidV7(),
            ),
        )
        assertEquals(
            BranchLifecycleState.ACTIVE,
            lifecyclePersistence.branches.getValue(organisationId to branchId).state,
        )
    }

    @Test
    fun `reactivation rejects every missing mandatory setup prerequisite`() {
        OrganisationSetupRequirement.entries.forEach { missing ->
            val organisationId = uuidV7()
            lifecyclePersistence.organisations[organisationId] =
                aggregate(organisationId, OrganisationLifecycleState.SUSPENDED, "ORGANISATION")
            store.organisationStates[organisationId] = OrganisationLifecycleState.SUSPENDED
            store.completeSetup(organisationId)
            store.missingSetup += missing

            assertFailsWith<ConflictException> {
                organisations.reactivate(ReactivateOrganisationCommand(organisationId))
            }
            store.missingSetup -= missing
        }
    }

    @Test
    fun `lists organisations with status country date and pagination filters`() {
        val filter =
            OrganisationListFilter(
                status = OrganisationLifecycleState.ACTIVE,
                countryCode = "KE",
                createdFrom = Instant.parse("2026-07-01T00:00:00Z"),
                createdTo = Instant.parse("2026-07-31T23:59:59Z"),
                page = 1,
                size = 10,
            )
        val expected =
            OrganisationPage(
                listOf(
                    OrganisationSummary(
                        uuidV7(),
                        "KE-ONE",
                        "Kenya One",
                        "KE",
                        OrganisationLifecycleState.ACTIVE,
                        Instant.parse("2026-07-14T10:00:00Z"),
                    ),
                ),
                11,
            )
        store.listResult = expected

        assertEquals(expected, organisations.list(filter))
        assertEquals(filter, store.lastListFilter)
    }

    @Test
    fun `deprovisioning revokes access and retains organisation metadata`() {
        val organisationId = uuidV7()
        lifecyclePersistence.organisations[organisationId] =
            aggregate(organisationId, OrganisationLifecycleState.ACTIVE, "ORGANISATION")
        store.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE

        organisations.deprovision(DeprovisionOrganisationCommand(organisationId, "contract ended"))

        assertEquals(
            OrganisationLifecycleState.DEPROVISIONED,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
        assertEquals("contract ended", transitionLogs.logs.last().reason)
        assertTrue(store.revokedAssignments.isEmpty())
    }

    @Test
    fun `branch assignment rejects an inactive organisation and protects ordinary membership`() {
        val organisationId = uuidV7()
        val branchId = uuidV7()
        val userId = uuidV7()
        store.organisationStates[organisationId] = OrganisationLifecycleState.SUSPENDED
        store.branchStates[organisationId to branchId] = BranchLifecycleState.ACTIVE

        assertFailsWith<ConflictException> {
            branches.assignUser(
                AssignUserToBranchCommand(
                    organisationId,
                    userId,
                    branchId,
                    BranchAssignmentType.OPERATE,
                    uuidV7(),
                ),
            )
        }

        store.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        store.memberships[organisationId to userId] =
            MembershipSnapshot(MembershipLifecycleState.ACTIVE, MembershipType.STAFF)
        branches.assignUser(
            AssignUserToBranchCommand(
                organisationId,
                userId,
                branchId,
                BranchAssignmentType.OPERATE,
                uuidV7(),
            ),
        )

        assertFailsWith<ConflictException> {
            branches.revokeUserAssignment(
                RevokeUserBranchAssignmentCommand(
                    organisationId,
                    userId,
                    branchId,
                    BranchAssignmentType.OPERATE,
                    uuidV7(),
                ),
            )
        }
    }

    @Test
    fun `branch assignment rejects an inactive branch`() {
        val organisationId = uuidV7()
        val branchId = uuidV7()
        val userId = uuidV7()
        store.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        store.branchStates[organisationId to branchId] = BranchLifecycleState.SUSPENDED
        store.memberships[organisationId to userId] =
            MembershipSnapshot(MembershipLifecycleState.ACTIVE, MembershipType.STAFF)

        assertFailsWith<ConflictException> {
            branches.assignUser(
                AssignUserToBranchCommand(
                    organisationId,
                    userId,
                    branchId,
                    BranchAssignmentType.OPERATE,
                    uuidV7(),
                ),
            )
        }

        assertEquals(0, store.assignmentCount(organisationId, userId))
    }

    @Test
    fun `branch assignment is idempotent and cannot cross organisation boundaries`() {
        val organisationId = uuidV7()
        val otherOrganisationId = uuidV7()
        val branchId = uuidV7()
        val userId = uuidV7()
        store.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        store.organisationStates[otherOrganisationId] = OrganisationLifecycleState.ACTIVE
        store.branchStates[otherOrganisationId to branchId] = BranchLifecycleState.ACTIVE
        store.memberships[organisationId to userId] =
            MembershipSnapshot(MembershipLifecycleState.ACTIVE, MembershipType.STAFF)

        assertFailsWith<ConflictException> {
            branches.assignUser(
                AssignUserToBranchCommand(
                    organisationId,
                    userId,
                    branchId,
                    BranchAssignmentType.VIEW,
                    uuidV7(),
                ),
            )
        }

        store.branchStates[organisationId to branchId] = BranchLifecycleState.ACTIVE
        val command =
            AssignUserToBranchCommand(
                organisationId,
                userId,
                branchId,
                BranchAssignmentType.VIEW,
                uuidV7(),
            )
        branches.assignUser(command)
        branches.assignUser(command)

        assertEquals(1, store.assignmentCount(organisationId, userId))
        assertEquals(1, events.events.size)
        assertEquals(1, audits.events.count { it.action == "branch.assign_user" })
        assertTrue(
            events.events.all {
                (it as? ExternalizedTransitionEvent)?.target ==
                    "finaxis.lifecycle.branch.user-assigned"
            },
        )
    }

    @Test
    fun `repeating a completed assignment revocation emits no duplicate audit or outbox event`() {
        val organisationId = uuidV7()
        val branchId = uuidV7()
        val userId = uuidV7()
        store.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        store.branchStates[organisationId to branchId] = BranchLifecycleState.ACTIVE
        store.memberships[organisationId to userId] =
            MembershipSnapshot(MembershipLifecycleState.ACTIVE, MembershipType.AUDITOR)
        val assignment =
            AssignUserToBranchCommand(
                organisationId,
                userId,
                branchId,
                BranchAssignmentType.VIEW,
                uuidV7(),
            )
        branches.assignUser(assignment)
        events.events.clear()
        val revocation =
            RevokeUserBranchAssignmentCommand(
                organisationId,
                userId,
                branchId,
                BranchAssignmentType.VIEW,
                uuidV7(),
            )

        branches.revokeUserAssignment(revocation)
        branches.revokeUserAssignment(revocation)

        assertEquals(1, events.events.size)
        assertEquals(1, audits.events.count { it.action == "branch.revoke_user" })
    }

    @Test
    fun `branch lifecycle emits externalized events for each operational transition`() {
        val organisationId = uuidV7()
        val branchId = uuidV7()
        lifecyclePersistence.organisations[organisationId] =
            aggregate(organisationId, OrganisationLifecycleState.ACTIVE, "ORGANISATION")
        lifecyclePersistence.branches[organisationId to branchId] =
            aggregate(branchId, BranchLifecycleState.DRAFT, "BRANCH")
        store.organisationStates[organisationId] = OrganisationLifecycleState.ACTIVE
        store.branchStates[organisationId to branchId] = BranchLifecycleState.ACTIVE

        branches.submitForApproval(
            SubmitBranchForApprovalCommand(
                organisationId = organisationId,
                branchId = branchId,
                actorId = uuidV7(),
                requestId = uuidV7(),
            ),
        )
        assertExternalizedTarget("finaxis.lifecycle.branch.approval-requested")

        events.events.clear()
        branches.activate(
            ActivateBranchCommand(
                organisationId = organisationId,
                branchId = branchId,
                actorId = uuidV7(),
                requestId = uuidV7(),
            ),
        )
        assertExternalizedTarget("finaxis.lifecycle.branch.activated")

        events.events.clear()
        branches.suspend(SuspendBranchCommand(organisationId, branchId, "Maintenance"))
        assertExternalizedTarget("finaxis.lifecycle.branch.suspended")

        events.events.clear()
        branches.reactivate(ReactivateBranchCommand(organisationId, branchId, "Maintenance done"))
        assertExternalizedTarget("finaxis.lifecycle.branch.reactivated")

        events.events.clear()
        branches.close(CloseBranchCommand(organisationId, branchId, "Branch consolidation"))
        assertExternalizedTarget("finaxis.lifecycle.branch.closed")
        assertEquals("Branch consolidation", transitionLogs.logs.last().reason)
    }

    @Test
    fun `closing a branch rejects active child branches`() {
        val organisationId = uuidV7()
        val branchId = uuidV7()
        lifecyclePersistence.organisations[organisationId] =
            aggregate(organisationId, OrganisationLifecycleState.ACTIVE, "ORGANISATION")
        lifecyclePersistence.branches[organisationId to branchId] =
            aggregate(branchId, BranchLifecycleState.ACTIVE, "BRANCH")
        store.branchStates[organisationId to branchId] = BranchLifecycleState.ACTIVE
        lifecyclePersistence.branchesWithActiveChildren += organisationId to branchId

        assertFailsWith<com.finaxis.platform.common.transitions.TransitionGuardException> {
            branches.close(CloseBranchCommand(organisationId, branchId, "Consolidation"))
        }
    }

    private fun assertExternalizedTarget(expectedTarget: String) {
        val event = assertIs<ExternalizedTransitionEvent>(events.events.single())
        assertEquals(expectedTarget, event.target)
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
}

private class ProvisioningFake(
    private val lifecycle: LifecycleFake,
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
    val assignments = mutableSetOf<AssignmentKey>()
    var listResult = OrganisationPage(emptyList(), 0)
    var lastListFilter: OrganisationListFilter? = null

    override fun createDraft(command: CreateOrganisationDraftCommand): UUID = uuidV7()

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

    val branchCreators = mutableMapOf<Pair<UUID, UUID>, UUID>()

    override fun organisationState(organisationId: UUID) = organisationStates[organisationId]

    override fun createDraft(command: CreateBranchCommand): UUID {
        val id = uuidV7()
        branchCreators[command.organisationId to id] = command.requestedBy
        return id
    }

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
    ): UUID? = branchCreators[organisationId to branchId]

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
            AssignmentKey(
                command.organisationId,
                command.userId,
                command.branchId,
                command.assignmentType,
            ),
        )

    override fun isActive(command: RevokeUserBranchAssignmentCommand) =
        AssignmentKey(
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
            AssignmentKey(
                command.organisationId,
                command.userId,
                command.branchId,
                command.assignmentType,
            ),
        )

    fun assignmentCount(
        organisationId: UUID,
        userId: UUID,
    ): Int = assignments.count { it.organisationId == organisationId && it.userId == userId }

    fun completeSetup(organisationId: UUID) {
        settings[organisationId] = mapOf("settings.operational" to "true")
        businessDates[organisationId] = LocalDate.of(2026, 7, 14)
        referenceSequences += organisationId
        defaultRoles += organisationId
        val branchId = uuidV7()
        headOfficeIds[organisationId] = branchId
        headOffices += organisationId
        lifecycle.branches[organisationId to branchId] =
            aggregate(branchId, BranchLifecycleState.ACTIVE, "BRANCH")
    }
}

private data class AssignmentKey(
    val organisationId: UUID,
    val userId: UUID,
    val branchId: UUID,
    val type: BranchAssignmentType,
)

private class LifecycleFake :
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

private class AuditCapture : AuditEventRepository {
    val events = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        events += event
    }
}

private class EventCapture : TransitionEventPublisher {
    val events = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        events += event
    }
}

private class TransitionLogCapture : TransitionLogRepository {
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

private class FakeInitialAdministratorBootstrapStore : InitialAdministratorBootstrapStore {
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
