package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BRANCH_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.AssignUserToBranchCommand
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.DeprovisionOrganisationCommand
import com.finaxis.platform.lifecycle.application.FoundationLifecycleService
import com.finaxis.platform.lifecycle.application.OrganisationListFilter
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.OrganisationTransitionCommand
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqFoundationLifecyclePersistenceTests(
    private val dsl: DSLContext,
    private val persistence: JooqFoundationLifecyclePersistence,
    private val lifecycleService: FoundationLifecycleService,
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val branchProvisioningService: BranchProvisioningService,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `organisation submission durably writes transition log and audit event`() {
        val organisationId = insertOrganisation(OrganisationLifecycleState.DRAFT)

        lifecycleService.transition(
            OrganisationTransitionCommand(
                organisationId,
                OrganisationLifecycleTransition.SUBMIT,
                TransitionCommand(reason = "Complete registration evidence received"),
            ),
        )

        assertEquals(
            OrganisationLifecycleState.PENDING_APPROVAL,
            persistence.findOrganisation(organisationId)?.state,
        )
        assertEquals(
            1,
            dsl.fetchCount(
                ORGANISATION_TRANSITION_LOG,
                ORGANISATION_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId),
            ),
        )
        assertEquals(1, dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ORGANISATION_ID.eq(organisationId)))
        assertEquals(
            "Complete registration evidence received",
            dsl
                .select(ORGANISATION.STATUS_REASON)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchOne(ORGANISATION.STATUS_REASON),
        )
        assertEquals(
            "Complete registration evidence received",
            dsl
                .select(ORGANISATION_TRANSITION_LOG.REASON)
                .from(ORGANISATION_TRANSITION_LOG)
                .where(ORGANISATION_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId))
                .fetchOne(ORGANISATION_TRANSITION_LOG.REASON),
        )
        assertEquals(
            "Complete registration evidence received",
            dsl
                .select(AUDIT_EVENT.REASON)
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
                .fetchOne(AUDIT_EVENT.REASON),
        )
    }

    @Test
    fun `organisation approval maps default roles to the baseline permission catalogue`() {
        val requestedBy = insertUser()
        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "approval-$requestedBy",
                        displayName = "Approval Organisation",
                        legalName = "Approval Organisation Limited",
                        registrationNumber = "APP-$requestedBy",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = requestedBy,
                    ),
                ).organisationId

        lifecycleService.transition(
            OrganisationTransitionCommand(organisationId, OrganisationLifecycleTransition.SUBMIT),
        )
        organisationProvisioningService.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId),
        )

        assertApprovalSetup(organisationId)
    }

    @Test
    fun `saveOrganisation rejects a stale row version instead of silently overwriting it`() {
        val organisationId = insertOrganisation()
        val staleRead = requireNotNull(persistence.findOrganisation(organisationId))

        persistence.saveOrganisation(
            LifecycleAggregate(
                organisationId,
                OrganisationLifecycleState.PROVISIONING,
                "ORGANISATION",
                organisationId = organisationId,
                rowVersion = staleRead.rowVersion,
            ),
        )

        assertThrows<OptimisticLockingFailureException> {
            persistence.saveOrganisation(
                LifecycleAggregate(
                    organisationId,
                    OrganisationLifecycleState.ACTIVE,
                    "ORGANISATION",
                    organisationId = organisationId,
                    rowVersion = staleRead.rowVersion,
                ),
            )
        }

        assertEquals(
            OrganisationLifecycleState.PROVISIONING,
            persistence.findOrganisation(organisationId)?.state,
        )
    }

    @Test
    fun `saveOrganisation succeeds when the row version still matches`() {
        val organisationId = insertOrganisation()
        val read = requireNotNull(persistence.findOrganisation(organisationId))

        persistence.saveOrganisation(
            LifecycleAggregate(
                organisationId,
                OrganisationLifecycleState.PROVISIONING,
                "ORGANISATION",
                organisationId = organisationId,
                rowVersion = read.rowVersion,
            ),
        )

        assertEquals(
            OrganisationLifecycleState.PROVISIONING,
            persistence.findOrganisation(organisationId)?.state,
        )
    }

    @Test
    fun `saveBranch does not mutate a branch belonging to a different organisation`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        val branchId = insertBranch(organisationId)
        val read = requireNotNull(persistence.findBranch(organisationId, branchId))

        assertThrows<OptimisticLockingFailureException> {
            persistence.saveBranch(
                LifecycleAggregate(
                    branchId,
                    BranchLifecycleState.ACTIVE,
                    "BRANCH",
                    organisationId = otherOrganisationId,
                    rowVersion = read.rowVersion,
                ),
            )
        }

        assertEquals(
            BranchLifecycleState.PENDING_APPROVAL,
            persistence.findBranch(organisationId, branchId)?.state,
        )
    }

    @Test
    fun `saveMembership does not mutate a membership belonging to a different organisation`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val read = requireNotNull(persistence.findMembership(organisationId, membershipId))

        assertThrows<OptimisticLockingFailureException> {
            persistence.saveMembership(
                LifecycleAggregate(
                    membershipId,
                    MembershipLifecycleState.ACTIVE,
                    "MEMBERSHIP",
                    organisationId = otherOrganisationId,
                    rowVersion = read.rowVersion,
                ),
            )
        }

        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            persistence.findMembership(organisationId, membershipId)?.state,
        )
    }

    @Test
    fun `saveOrganisation attributes the system actor when no request context is installed`() {
        val organisationId = insertOrganisation()
        val read = requireNotNull(persistence.findOrganisation(organisationId))
        RequestContexts.clear()

        persistence.saveOrganisation(
            LifecycleAggregate(
                organisationId,
                OrganisationLifecycleState.PROVISIONING,
                "ORGANISATION",
                organisationId = organisationId,
                rowVersion = read.rowVersion,
            ),
        )

        val updatedBy =
            dsl
                .select(ORGANISATION.UPDATED_BY)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchOne(ORGANISATION.UPDATED_BY)
        assertEquals(SystemActor.ID, updatedBy)
    }

    @Test
    fun `saveOrganisation attributes the actor when a request context is installed`() {
        val organisationId = insertOrganisation()
        val read = requireNotNull(persistence.findOrganisation(organisationId))
        val actorId = uuidV7()

        RequestContexts.withActor(
            ActorContext(actorId, "subject", "actor", "actor@example.test"),
        ) {
            persistence.saveOrganisation(
                LifecycleAggregate(
                    organisationId,
                    OrganisationLifecycleState.PROVISIONING,
                    "ORGANISATION",
                    organisationId = organisationId,
                    rowVersion = read.rowVersion,
                ),
            )
        }

        val updatedBy =
            dsl
                .select(ORGANISATION.UPDATED_BY)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchOne(ORGANISATION.UPDATED_BY)
        assertEquals(actorId, updatedBy)
        assertNotEquals(SystemActor.ID, updatedBy)
    }

    @Test
    fun `organisation listing applies status country created date and pagination filters`() {
        val earliest =
            insertOrganisation(OrganisationLifecycleState.ACTIVE, "KE", "2026-07-10T08:00:00Z")
        val latest =
            insertOrganisation(OrganisationLifecycleState.ACTIVE, "KE", "2026-07-12T08:00:00Z")
        insertOrganisation(OrganisationLifecycleState.ACTIVE, "TZ", "2026-07-13T08:00:00Z")
        insertOrganisation(OrganisationLifecycleState.SUSPENDED, "KE", "2026-07-13T08:00:00Z")
        insertOrganisation(OrganisationLifecycleState.ACTIVE, "KE", "2026-06-30T08:00:00Z")

        val page =
            organisationProvisioningService.list(
                OrganisationListFilter(
                    status = OrganisationLifecycleState.ACTIVE,
                    countryCode = "KE",
                    createdFrom = Instant.parse("2026-07-01T00:00:00Z"),
                    createdTo = Instant.parse("2026-07-31T23:59:59Z"),
                    page = 1,
                    size = 1,
                ),
            )

        assertEquals(3, page.totalItems)
        assertEquals(listOf(earliest), page.items.map { it.organisationId })
        assertNotEquals(latest, page.items.single().organisationId)
    }

    @Test
    fun `branch assignment is active once and never crosses organisation boundaries`() {
        val userId = insertUser()
        val organisationId = fixture.createActiveOrganisation("branch-assignment", userId)
        val otherOrganisationId = insertOrganisation(OrganisationLifecycleState.ACTIVE)
        val branchId = insertBranch(organisationId, BranchLifecycleState.ACTIVE)
        val otherBranchId = insertBranch(otherOrganisationId, BranchLifecycleState.ACTIVE)
        val command =
            AssignUserToBranchCommand(
                organisationId,
                userId,
                branchId,
                BranchAssignmentType.VIEW,
                userId,
            )

        withRequestContext {
            branchProvisioningService.assignUser(command)
            branchProvisioningService.assignUser(command)
        }

        assertEquals(
            1,
            dsl.fetchCount(
                USER_BRANCH_ASSIGNMENT,
                USER_BRANCH_ASSIGNMENT.ORGANISATION_ID
                    .eq(organisationId)
                    .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(userId))
                    .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(branchId))
                    .and(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE.eq(BranchAssignmentType.VIEW.name))
                    .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE")),
            ),
        )
        withRequestContext {
            assertThrows<ConflictException> {
                branchProvisioningService.assignUser(command.copy(branchId = otherBranchId))
            }
        }
    }

    @Test
    fun `branch draft creation records a lifecycle creation log`() {
        val requestedBy = insertUser()
        val organisationId = fixture.createActiveOrganisation("branch-creation-log", requestedBy)

        val result =
            withRequestContext {
                branchProvisioningService.createDraft(
                    CreateBranchCommand(
                        organisationId = organisationId,
                        branchCode = "NAIROBI",
                        branchName = "Nairobi Branch",
                        branchType = "OPERATIONS",
                        timezone = "Africa/Nairobi",
                        requestedBy = requestedBy,
                    ),
                )
            }

        assertEquals(
            "CREATE_DRAFT",
            dsl
                .select(BRANCH_TRANSITION_LOG.TRANSITION_NAME)
                .from(BRANCH_TRANSITION_LOG)
                .where(BRANCH_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId))
                .and(BRANCH_TRANSITION_LOG.BRANCH_ID.eq(result.branchId))
                .fetchOne(BRANCH_TRANSITION_LOG.TRANSITION_NAME),
        )
    }

    @Test
    fun `deprovisioning transitions access records and audits individual assignment revocations`() {
        val organisationId = insertOrganisation(OrganisationLifecycleState.ACTIVE)
        val branchId = insertBranch(organisationId, BranchLifecycleState.ACTIVE)
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId, MembershipLifecycleState.ACTIVE)
        insertActiveAssignments(organisationId, userId, branchId)

        organisationProvisioningService.deprovision(
            DeprovisionOrganisationCommand(organisationId, "Contract ended"),
        )

        assertDeprovisionedAccess(organisationId, branchId, membershipId)
    }

    private fun assertApprovalSetup(organisationId: UUID) {
        assertEquals(
            OrganisationLifecycleState.ACTIVE,
            persistence.findOrganisation(organisationId)?.state,
        )
        assertEquals(DEFAULT_ROLE_CODES, roleCodes(organisationId))
        assertEquals(REQUIRED_PERMISSION_CODES, baselinePermissionCodes())
        assertEquals(REQUIRED_PERMISSION_CODES.size, tenantAdminPermissionCount(organisationId))
        assertEquals(IAM_ADMIN_PERMISSION_CODES, rolePermissionCodes(organisationId, "IAM_ADMIN"))
    }

    private fun roleCodes(organisationId: UUID): Set<String> =
        dsl
            .select(ROLE.ROLE_CODE)
            .from(ROLE)
            .where(ROLE.ORGANISATION_ID.eq(organisationId))
            .fetch(ROLE.ROLE_CODE)
            .filterNotNull()
            .toSet()

    private fun baselinePermissionCodes(): Set<String> =
        dsl
            .select(PERMISSION.PERMISSION_CODE)
            .from(PERMISSION)
            .where(PERMISSION.PERMISSION_CODE.`in`(REQUIRED_PERMISSION_CODES))
            .fetch(PERMISSION.PERMISSION_CODE)
            .filterNotNull()
            .toSet()

    private fun tenantAdminPermissionCount(organisationId: UUID): Int =
        dsl
            .selectCount()
            .from(ROLE_PERMISSION)
            .join(ROLE)
            .on(ROLE_PERMISSION.ROLE_ID.eq(ROLE.ID))
            .join(PERMISSION)
            .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
            .where(ROLE.ORGANISATION_ID.eq(organisationId))
            .and(ROLE.ROLE_CODE.eq("TENANT_ADMIN"))
            .and(PERMISSION.PERMISSION_CODE.`in`(REQUIRED_PERMISSION_CODES))
            .fetchOne(0, Int::class.java) ?: 0

    private fun rolePermissionCodes(
        organisationId: UUID,
        roleCode: String,
    ): Set<String> =
        dsl
            .select(PERMISSION.PERMISSION_CODE)
            .from(ROLE_PERMISSION)
            .join(ROLE)
            .on(ROLE_PERMISSION.ROLE_ID.eq(ROLE.ID))
            .join(PERMISSION)
            .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
            .where(ROLE.ORGANISATION_ID.eq(organisationId))
            .and(ROLE.ROLE_CODE.eq(roleCode))
            .fetch(PERMISSION.PERMISSION_CODE)
            .filterNotNull()
            .toSet()

    private fun assertDeprovisionedAccess(
        organisationId: UUID,
        branchId: UUID,
        membershipId: UUID,
    ) {
        assertEquals(
            OrganisationLifecycleState.DEPROVISIONED.name,
            organisationStatus(organisationId),
        )
        assertEquals(BranchLifecycleState.SUSPENDED.name, branchStatus(branchId))
        assertEquals(MembershipLifecycleState.REVOKED.name, membershipStatus(membershipId))
        assertEquals(0, activeBranchAssignmentCount(organisationId))
        assertEquals(0, activeRoleAssignmentCount(organisationId))
        assertEquals(2, deprovisionAssignmentAuditCount(organisationId))
    }

    private fun organisationStatus(organisationId: UUID): String? =
        dsl
            .select(ORGANISATION.STATUS)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne(ORGANISATION.STATUS)

    private fun branchStatus(branchId: UUID): String? =
        dsl
            .select(BRANCH.STATUS)
            .from(BRANCH)
            .where(BRANCH.ID.eq(branchId))
            .fetchOne(BRANCH.STATUS)

    private fun membershipStatus(membershipId: UUID): String? =
        dsl
            .select(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)

    private fun activeBranchAssignmentCount(organisationId: UUID): Int =
        dsl.fetchCount(
            USER_BRANCH_ASSIGNMENT,
            USER_BRANCH_ASSIGNMENT.ORGANISATION_ID
                .eq(organisationId)
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE")),
        )

    private fun activeRoleAssignmentCount(organisationId: UUID): Int =
        dsl.fetchCount(
            USER_ROLE_ASSIGNMENT,
            USER_ROLE_ASSIGNMENT.ORGANISATION_ID
                .eq(organisationId)
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq("ACTIVE")),
        )

    private fun deprovisionAssignmentAuditCount(organisationId: UUID): Int =
        dsl.fetchCount(
            AUDIT_EVENT,
            AUDIT_EVENT.ORGANISATION_ID
                .eq(organisationId)
                .and(AUDIT_EVENT.ACTION.eq("organisation.deprovision_assignment_revoked")),
        )

    private fun insertOrganisation(
        state: OrganisationLifecycleState = OrganisationLifecycleState.PENDING_APPROVAL,
        countryCode: String = "KE",
        createdAt: String = "2026-07-14T10:00:00Z",
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.parse(createdAt)
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "tenant-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Test Organisation")
            .set(ORGANISATION.COUNTRY_CODE, countryCode)
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, state.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranch(
        organisationId: UUID,
        state: BranchLifecycleState = BranchLifecycleState.PENDING_APPROVAL,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "branch-$id")
            .set(BRANCH.BRANCH_NAME, "Test Branch")
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, state.name)
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUser(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "user-$id")
            .set(USER_ACCOUNT.EMAIL, "user-$id@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Test User")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
        state: MembershipLifecycleState = MembershipLifecycleState.PENDING_APPROVAL,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                state.name,
            ).set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertActiveAssignments(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
    ) {
        val now = OffsetDateTime.now()
        val roleId = uuidV7()
        dsl
            .insertInto(ROLE)
            .set(ROLE.ID, roleId)
            .set(ROLE.ORGANISATION_ID, organisationId)
            .set(ROLE.ROLE_CODE, "deprovision-role-$roleId")
            .set(ROLE.ROLE_NAME, "Deprovision Role")
            .set(ROLE.SYSTEM_ROLE, false)
            .set(ROLE.STATUS, "ACTIVE")
            .set(ROLE.CREATED_AT, now)
            .set(ROLE.UPDATED_AT, now)
            .execute()
        dsl
            .insertInto(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.ID, uuidV7())
            .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_BRANCH_ASSIGNMENT.USER_ID, userId)
            .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, BranchAssignmentType.VIEW.name)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now)
            .execute()
        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ID, uuidV7())
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, userId)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, "TENANT")
            .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .execute()
    }

    private companion object {
        val DEFAULT_ROLE_CODES =
            setOf(
                "TENANT_ADMIN",
                "TENANT_AUDITOR",
                "IAM_ADMIN",
                "BRANCH_MANAGER",
                "BRANCH_OPERATOR",
            )
        val IAM_ADMIN_PERMISSION_CODES =
            setOf(
                "user.view",
                "user.invite",
                "user.approve",
                "user.assign_branch",
                "user.assign_role",
                "user.revoke_branch",
                "user.revoke_role",
                "membership.view",
                "membership.suspend",
                "membership.reactivate",
                "membership.revoke",
                "branch_assignment.view",
                "role.create",
                "role.update",
                "role.assign_permission",
                "role.view",
                "role.activate",
                "role.deactivate",
                "role.remove_permission",
                "role_assignment.view",
                "permission.view",
                "audit.view",
                "auth.select_organisation",
                "auth.select_branch",
                "iam.profile.read",
            )
        val REQUIRED_PERMISSION_CODES =
            setOf(
                "tenant.create",
                "tenant.submit_for_approval",
                "tenant.approve",
                "tenant.activate",
                "tenant.suspend",
                "tenant.deprovision",
                "tenant.view",
                "tenant.update_draft",
                "tenant.reject",
                "tenant.reactivate",
                "tenant.bootstrap_retry",
                "branch.create",
                "branch.approve",
                "branch.activate",
                "branch.suspend",
                "branch.close",
                "branch.view",
                "branch.reactivate",
                "user.view",
                "user.invite",
                "user.approve",
                "user.assign_branch",
                "user.assign_role",
                "user.revoke_branch",
                "user.revoke_role",
                "membership.view",
                "membership.suspend",
                "membership.reactivate",
                "membership.revoke",
                "branch_assignment.view",
                "role.create",
                "role.update",
                "role.assign_permission",
                "role.view",
                "role.activate",
                "role.deactivate",
                "role.remove_permission",
                "role_assignment.view",
                "permission.view",
                "audit.view",
                "settings.view",
                "settings.update",
                "business_date.view",
                "business_date.advance",
                "business_date.reopen",
                "cob.start",
                "cob.complete",
                "auth.select_organisation",
                "auth.select_branch",
                "iam.profile.read",
            )
    }
}
