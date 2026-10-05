package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogRepository
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.LifecyclePrerequisites
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleTransition
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// One class per service keeps the private in-memory fakes it shares in a single file.
@Suppress("LargeClass")
class UserProvisioningServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T11:00:00Z"), ZoneOffset.UTC)
    private val fake = UserProvisioningFake()
    private val events = UserProvisioningEventCapture()
    private val audits = UserProvisioningAuditCapture()
    private val logs = UserProvisioningTransitionLogCapture()
    private val permissionGuard = mock(PermissionGuard::class.java)
    private val lifecycle =
        FoundationLifecycleService(
            TransitionExecutor(clock, logs, events),
            fake,
            fake,
            fake,
            AuditService(audits, clock),
        )
    private val branches =
        BranchProvisioningService(
            lifecycle,
            fake,
            fake,
            AuditService(audits, clock),
            events,
            permissionGuard,
        )
    private val deactivationAssignmentRevoker =
        UserDeactivationAssignmentRevoker(fake, fake, AuditService(audits, clock), events, clock)
    private val service =
        UserProvisioningService(
            lifecycle,
            fake,
            branches,
            deactivationAssignmentRevoker,
            AuditService(audits, clock),
            events,
            clock,
            permissionGuard,
        )

    @Test
    fun `new user invite creates draft user pending membership assignments and audit`() {
        val context = activeInvitationContext()

        val result = service.inviteUser(inviteCommand(context))

        assertEquals(UserLifecycleState.DRAFT, result.userStatus)
        assertEquals(MembershipLifecycleState.PENDING_APPROVAL, result.membershipStatus)
        assertTrue(fake.branchAssignments.contains(BranchAssignmentKey(context.org, result.userId)))
        assertEquals(1, fake.roleAssignments.size)
        assertTrue(audits.events.any { it.action == "user.invite" })
    }

    @Test
    fun `invitation reuses existing user by email`() {
        val context = activeInvitationContext()
        val existingUserId =
            fake.addUser("member@example.test", "member", UserLifecycleState.INVITED)

        val result = service.inviteUser(inviteCommand(context))

        assertEquals(existingUserId, result.userId)
        assertEquals(1, fake.users.size)
    }

    @Test
    fun `invitation to an inactive organisation is a 409 conflict that writes nothing`() {
        val context = activeInvitationContext()
        fake.organisationStates[context.org] = OrganisationLifecycleState.SUSPENDED

        val failure =
            assertFailsWith<ConflictException> { service.inviteUser(inviteCommand(context)) }

        assertEquals("conflict", failure.code)
        assertEquals("User invitations require an active organisation.", failure.safeDetail)
        assertNothingWritten()
    }

    @Test
    fun `invitation without a branch assignment for an ordinary member is a 400`() {
        val context = activeInvitationContext()

        val failure =
            assertFailsWith<InvalidRequestException> {
                service.inviteUser(inviteCommand(context).copy(branchAssignments = emptyList()))
            }

        assertEquals("validation_failed", failure.code)
        assertEquals("At least one branch assignment is required.", failure.safeDetail)
        assertNothingWritten()
    }

    @Test
    fun `invitation without a role assignment is a 400`() {
        val context = activeInvitationContext()

        val failure =
            assertFailsWith<InvalidRequestException> {
                service.inviteUser(inviteCommand(context).copy(roleAssignments = emptyList()))
            }

        assertEquals("validation_failed", failure.code)
        assertEquals("At least one role assignment is required.", failure.safeDetail)
        assertNothingWritten()
    }

    @Test
    fun `invitation with a malformed email or a blank name is a 400 that writes nothing`() {
        val context = activeInvitationContext()
        val base = inviteCommand(context)

        listOf(
            base.copy(email = "user@localhost") to "A valid email address is required.",
            base.copy(email = " ") to "A valid email address is required.",
            base.copy(username = " ") to "Username is required.",
            base.copy(displayName = " ") to "Display name is required.",
        ).forEach { (command, detail) ->
            val failure = assertFailsWith<InvalidRequestException> { service.inviteUser(command) }
            assertEquals("validation_failed", failure.code)
            assertEquals(detail, failure.safeDetail)
        }
        assertNothingWritten()
    }

    @Test
    fun `invitation naming an unknown inactive or foreign role is a 422 that writes nothing`() {
        val context = activeInvitationContext()
        val foreignRole = uuidV7()
        fake.roles += uuidV7() to foreignRole
        val unknownRole = uuidV7()

        listOf(unknownRole, foreignRole).forEach { roleId ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    service.inviteUser(
                        inviteCommand(context)
                            .copy(
                                roleAssignments =
                                    listOf(
                                        RoleAssignmentRequest(
                                            roleId,
                                            RoleAssignmentScopeType.TENANT,
                                        ),
                                    ),
                            ),
                    )
                }
            assertEquals("invalid_operation", failure.code)
            assertEquals(
                "A role in the request was not found or is not active in the organisation.",
                failure.safeDetail,
            )
            assertFalse(failure.safeDetail.contains(roleId.toString()))
        }
        assertNothingWritten()
    }

    @Test
    fun `invitation naming an unknown suspended or foreign branch is a 422 that writes nothing`() {
        val context = activeInvitationContext()
        val suspended = uuidV7()
        fake.branchStates[context.org to suspended] = BranchLifecycleState.SUSPENDED
        val foreign = uuidV7()
        fake.branchStates[uuidV7() to foreign] = BranchLifecycleState.ACTIVE
        val unknown = uuidV7()

        listOf(unknown, suspended, foreign).forEach { branchId ->
            val withAssignment =
                inviteCommand(context)
                    .copy(
                        branchAssignments =
                            listOf(BranchAssignmentRequest(branchId, BranchAssignmentType.HOME)),
                    )
            val withPrimary = inviteCommand(context).copy(primaryBranchId = branchId)
            val withScopedRole =
                inviteCommand(context)
                    .copy(
                        roleAssignments =
                            listOf(
                                RoleAssignmentRequest(
                                    context.role,
                                    RoleAssignmentScopeType.BRANCH,
                                    branchId,
                                ),
                            ),
                    )
            listOf(withAssignment, withPrimary, withScopedRole).forEach { command ->
                val failure =
                    assertFailsWith<InvalidOperationException> { service.inviteUser(command) }
                assertEquals("invalid_operation", failure.code)
                assertEquals(
                    "A branch in the request was not found or is not active in the organisation.",
                    failure.safeDetail,
                )
                assertFalse(failure.safeDetail.contains(branchId.toString()))
            }
        }
        assertNothingWritten()
    }

    @Test
    fun `invitation of a user who already has a membership is a 409 that writes nothing`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        val membershipId = fake.addMembership(context.org, userId, MembershipLifecycleState.ACTIVE)
        val users = fake.users.size

        val failure =
            assertFailsWith<ConflictException> { service.inviteUser(inviteCommand(context)) }

        assertEquals("conflict", failure.code)
        assertEquals(
            "This user already has a membership in the selected organisation.",
            failure.safeDetail,
        )
        assertEquals(users, fake.users.size)
        assertEquals(setOf(context.org to membershipId), fake.memberships.keys)
        assertTrue(fake.branchAssignments.isEmpty())
        assertTrue(fake.roleAssignments.isEmpty())
        assertTrue(audits.events.isEmpty())
    }

    @Test
    fun `invitation of a revoked member is the same 409 and creates no second membership`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.addMembership(context.org, userId, MembershipLifecycleState.REVOKED)

        assertFailsWith<ConflictException> { service.inviteUser(inviteCommand(context)) }

        assertEquals(1, fake.memberships.size)
        assertTrue(audits.events.isEmpty())
    }

    @Test
    fun `invitation of a new email with a username already taken is a 409`() {
        val context = activeInvitationContext()
        fake.addUser("someone-else@example.test", "Member", UserLifecycleState.ACTIVE)

        val failure =
            assertFailsWith<ConflictException> { service.inviteUser(inviteCommand(context)) }

        assertEquals("conflict", failure.code)
        assertEquals("That username is already in use.", failure.safeDetail)
        assertEquals(1, fake.users.size)
        assertTrue(fake.memberships.isEmpty())
    }

    @Test
    fun `a lost race at the unique index is the same 409 and writes nothing`() {
        // The pre-checks are a courtesy: two concurrent invitations for one new email both pass
        // them and the loser fails uq_user_account_lower_email / _username.
        val context = activeInvitationContext()
        fake.duplicateOnCreateUser = true

        val failure =
            assertFailsWith<ConflictException> { service.inviteUser(inviteCommand(context)) }

        assertEquals("conflict", failure.code)
        assertEquals(
            "A user with this email or username already exists; retry the invitation.",
            failure.safeDetail,
        )
        assertIs<DuplicateKeyException>(failure.cause)
        assertNothingWritten()
    }

    @Test
    fun `approving a membership with no branch assignment is a 409 that changes nothing`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))
        fake.branchAssignments.clear()

        val failure = assertFailsWith<ConflictException> { approve(context, invitation) }

        assertEquals("conflict", failure.code)
        assertEquals(
            "The membership cannot be approved until the user has an active branch assignment.",
            failure.safeDetail,
        )
        assertApprovalChangedNothing(context, invitation)
    }

    @Test
    fun `approving a membership with no role assignment is a 409 that changes nothing`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))
        fake.roleAssignments.clear()

        val failure = assertFailsWith<ConflictException> { approve(context, invitation) }

        assertEquals(
            "The membership cannot be approved until the user has an active role assignment.",
            failure.safeDetail,
        )
        assertApprovalChangedNothing(context, invitation)
    }

    @Test
    fun `an auditor membership needs no branch assignment but still needs a role`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("aud@example.test", "aud", UserLifecycleState.ACTIVE)
        fake.identityLinks += userId
        val membershipId =
            fake.addMembership(
                context.org,
                userId,
                MembershipLifecycleState.PENDING_APPROVAL,
                MembershipType.AUDITOR,
            )
        fake.membershipInviters[context.org to membershipId] = context.actor
        val command = ApproveUserCommand(context.org, membershipId, context.checker)

        assertFailsWith<ConflictException> { service.approveUser(command) }

        fake.roleAssignments += RoleAssignmentKey(context.org, userId, context.role, null)
        assertEquals(MembershipLifecycleState.ACTIVE, service.approveUser(command).membershipStatus)
    }

    @Test
    fun `approving a user who cannot start identity provisioning is a 409 with no change`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))
        fake.users.getValue(invitation.userId).state = UserLifecycleState.SUSPENDED

        val failure = assertFailsWith<ConflictException> { approve(context, invitation) }

        assertEquals(
            "The user account is not in a state that allows identity provisioning.",
            failure.safeDetail,
        )
        assertEquals(UserLifecycleState.SUSPENDED, fake.users.getValue(invitation.userId).state)
        assertApprovalChangedNothing(context, invitation)
    }

    @Test
    fun `approving a linked user who cannot be activated is a 409 with no change`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))
        fake.identityLinks += invitation.userId
        fake.users.getValue(invitation.userId).state = UserLifecycleState.SUSPENDED

        val failure = assertFailsWith<ConflictException> { approve(context, invitation) }

        assertEquals(
            "The user account is not in a state that allows the membership to be activated.",
            failure.safeDetail,
        )
        assertApprovalChangedNothing(context, invitation)
    }

    @Test
    fun `approval of new user requests keycloak provisioning and leaves membership pending`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))

        val approval =
            service.approveUser(
                ApproveUserCommand(context.org, invitation.membershipId, context.checker, "req-1"),
            )

        assertTrue(approval.keycloakProvisioningRequested)
        assertEquals(UserLifecycleState.PROVISIONING_IDP, approval.userStatus)
        assertEquals(MembershipLifecycleState.PENDING_APPROVAL, approval.membershipStatus)
        assertTrue(
            events.externalTargets().contains(
                "finaxis.lifecycle.user.keycloak-provisioning-requested",
            ),
        )
        assertTrue(
            fake.dispatches.contains(
                DispatchRecord(
                    invitation.userId,
                    IdentityDispatchType.KEYCLOAK_PROVISIONING,
                    "${context.org}:${invitation.userId}:KEYCLOAK_PROVISIONING",
                ),
            ),
        )
    }

    @Test
    fun `approval of existing active user activates membership immediately`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.identityLinks += userId
        val invitation = service.inviteUser(inviteCommand(context))

        val approval =
            service.approveUser(
                ApproveUserCommand(context.org, invitation.membershipId, context.checker),
            )

        assertFalse(approval.keycloakProvisioningRequested)
        assertEquals(MembershipLifecycleState.ACTIVE, approval.membershipStatus)
        assertEquals(MembershipLifecycleTransition.ACTIVATE.name, logs.logs.last().transition)
    }

    @Test
    fun `a decision remark on an immediate activation reaches the transition and both audits`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.identityLinks += userId
        val invitation = service.inviteUser(inviteCommand(context))

        service.approveUser(
            ApproveUserCommand(
                context.org,
                invitation.membershipId,
                context.checker,
                reason = DecisionRemark.optional("Verified against the signed request form."),
            ),
        )

        val activate = logs.logs.last { it.transition == "ACTIVATE" }
        assertEquals("Verified against the signed request form.", activate.reason)
        assertEquals(
            "Verified against the signed request form.",
            audits.events.single { it.action == "user.approve" }.reason,
        )
        assertEquals(
            "Verified against the signed request form.",
            audits.events.single { it.action == "membership.activate" }.reason,
        )
    }

    @Test
    fun `a decision remark on a queued activation is on the user approve audit row only`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))

        val approval =
            service.approveUser(
                ApproveUserCommand(
                    context.org,
                    invitation.membershipId,
                    context.checker,
                    reason = DecisionRemark.optional("Approved pending identity creation."),
                ),
            )

        assertTrue(approval.keycloakProvisioningRequested)
        assertEquals(
            "Approved pending identity creation.",
            audits.events.single { it.action == "user.approve" }.reason,
        )
        assertTrue(
            logs.logs.none { it.reason == "Approved pending identity creation." },
            "the remark describes the membership decision, not the user-account transitions",
        )
    }

    @Test
    fun `an approval without a remark records none`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.identityLinks += userId
        val invitation = service.inviteUser(inviteCommand(context))

        service.approveUser(
            ApproveUserCommand(context.org, invitation.membershipId, context.checker),
        )

        assertEquals(null, audits.events.single { it.action == "user.approve" }.reason)
        assertEquals(null, logs.logs.last { it.transition == "ACTIVATE" }.reason)
    }

    @Test
    fun `application invite event is emitted when enabled`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context, sendApplicationInvite = true))

        val approval =
            service.approveUser(
                ApproveUserCommand(context.org, invitation.membershipId, context.checker),
            )

        assertTrue(approval.applicationInviteRequested)
        assertTrue(
            events.externalTargets().contains(
                "finaxis.lifecycle.user.application-invite-requested",
            ),
        )
        assertTrue(
            fake.dispatches.contains(
                DispatchRecord(
                    invitation.userId,
                    IdentityDispatchType.APPLICATION_INVITE,
                    "${invitation.userId}:${context.org}:APPLICATION_INVITE",
                ),
            ),
        )
    }

    @Test
    fun `inviter cannot approve membership but a distinct checker can`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))

        assertFailsWith<ForbiddenOperationException> {
            service.approveUser(
                ApproveUserCommand(context.org, invitation.membershipId, context.actor),
            )
        }

        val approval =
            service.approveUser(
                ApproveUserCommand(context.org, invitation.membershipId, context.checker),
            )

        assertTrue(approval.keycloakProvisioningRequested)
    }

    @Test
    fun `platform checker activates a pending membership and the audit names the scope`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.identityLinks += userId
        val invitation = service.inviteUser(inviteCommand(context))
        val platformChecker = uuidV7()

        val approval =
            service.approveUser(
                ApproveUserCommand(
                    context.org,
                    invitation.membershipId,
                    platformChecker,
                    scope = ActingScope.PLATFORM,
                ),
            )

        assertEquals(MembershipLifecycleState.ACTIVE, approval.membershipStatus)
        assertEquals(MembershipLifecycleTransition.ACTIVATE.name, logs.logs.last().transition)
        assertTrue(
            events.externalTargets().contains("finaxis.lifecycle.membership.activated"),
            "the platform path must raise the same activation event as the tenant path",
        )
        val audit = audits.events.single { it.action == "user.approve" }
        assertEquals(platformChecker.toString(), audit.actorId)
        assertEquals(context.org.toString(), audit.tenantId)
        assertEquals("PLATFORM", audit.metadata["checkerScope"])
    }

    @Test
    fun `a tenant approval records no platform checker scope`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))

        service.approveUser(
            ApproveUserCommand(context.org, invitation.membershipId, context.checker),
        )

        assertEquals(
            null,
            audits.events.single { it.action == "user.approve" }.metadata["checkerScope"],
        )
    }

    @Test
    fun `the platform checker keeps the maker-checker rule for memberships it created`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))

        assertFailsWith<ForbiddenOperationException> {
            service.approveUser(
                ApproveUserCommand(
                    context.org,
                    invitation.membershipId,
                    context.actor,
                    scope = ActingScope.PLATFORM,
                ),
            )
        }
    }

    @Test
    fun `platform approval answers 404 for a membership outside the tenant path`() {
        val context = activeInvitationContext()
        val otherTenant = uuidV7()
        fake.organisationStates[otherTenant] = OrganisationLifecycleState.ACTIVE
        val userId = fake.addUser("other@example.test", "other", UserLifecycleState.ACTIVE)
        val foreignMembership =
            fake.addMembership(otherTenant, userId, MembershipLifecycleState.PENDING_APPROVAL)

        listOf(foreignMembership, uuidV7()).forEach { membershipId ->
            assertFailsWith<ResourceNotFoundException> {
                service.approveUser(
                    ApproveUserCommand(
                        context.org,
                        membershipId,
                        uuidV7(),
                        scope = ActingScope.PLATFORM,
                    ),
                )
            }
        }
    }

    @Test
    fun `platform approval is refused while the tenant is not active`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))
        fake.organisationStates[context.org] = OrganisationLifecycleState.SUSPENDED

        assertFailsWith<ConflictException> {
            service.approveUser(
                ApproveUserCommand(
                    context.org,
                    invitation.membershipId,
                    uuidV7(),
                    scope = ActingScope.PLATFORM,
                ),
            )
        }
    }

    @Test
    fun `approving a membership that is no longer pending is a conflict`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        val membershipId = fake.addMembership(context.org, userId, MembershipLifecycleState.ACTIVE)

        assertFailsWith<ConflictException> {
            service.approveUser(ApproveUserCommand(context.org, membershipId, context.checker))
        }
    }

    @Test
    fun `no acting scope lets a user approve their own membership`() {
        ActingScope.entries.forEach { scope ->
            val context = activeInvitationContext()
            val beneficiary =
                fake.addUser(
                    "self-$scope@example.test",
                    "self$scope",
                    UserLifecycleState.ACTIVE,
                )
            fake.identityLinks += beneficiary
            val membershipId =
                fake.addMembership(
                    context.org,
                    beneficiary,
                    MembershipLifecycleState.PENDING_APPROVAL,
                )
            fake.membershipInviters[context.org to membershipId] = context.actor

            assertFailsWith<ForbiddenOperationException>(scope.name) {
                service.approveUser(
                    ApproveUserCommand(context.org, membershipId, beneficiary, scope = scope),
                )
            }
            assertEquals(
                MembershipLifecycleState.PENDING_APPROVAL,
                fake.memberships.getValue(context.org to membershipId).state,
            )
        }
    }

    @Test
    fun `a platform approval asks the platform permission and is refused without it`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))
        val platformChecker = uuidV7()
        doThrow(AccessDeniedException("no"))
            .whenever(permissionGuard)
            .requirePlatformPermission(platformChecker, "user.approve")

        assertFailsWith<AccessDeniedException> {
            service.approveUser(
                ApproveUserCommand(
                    context.org,
                    invitation.membershipId,
                    platformChecker,
                    scope = ActingScope.PLATFORM,
                ),
            )
        }
        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            fake.memberships.getValue(context.org to invitation.membershipId).state,
        )
    }

    @Test
    fun `a platform approval cannot act inside the platform organisation itself`() {
        val platformChecker = uuidV7()
        val userId = fake.addUser("p@example.test", "p", UserLifecycleState.ACTIVE)
        fake.organisationStates[PlatformOrganisation.ID] = OrganisationLifecycleState.ACTIVE
        val membershipId =
            fake.addMembership(
                PlatformOrganisation.ID,
                userId,
                MembershipLifecycleState.PENDING_APPROVAL,
            )

        assertFailsWith<ResourceNotFoundException> {
            service.approveUser(
                ApproveUserCommand(
                    PlatformOrganisation.ID,
                    membershipId,
                    platformChecker,
                    scope = ActingScope.PLATFORM,
                ),
            )
        }
        verify(permissionGuard).requirePlatformPermission(platformChecker, "user.approve")
    }

    @Test
    fun `the platform checker is closed once the tenant has an active member of its own`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))
        val other = fake.addUser("other@example.test", "other", UserLifecycleState.ACTIVE)
        fake.addMembership(context.org, other, MembershipLifecycleState.ACTIVE)

        val error =
            assertFailsWith<ConflictException> {
                service.approveUser(
                    ApproveUserCommand(
                        context.org,
                        invitation.membershipId,
                        uuidV7(),
                        scope = ActingScope.PLATFORM,
                    ),
                )
            }
        assertEquals(LifecycleErrorCodes.PLATFORM_CHECKER_CLOSED, error.code)
        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            fake.memberships.getValue(context.org to invitation.membershipId).state,
        )
        // The tenant's own approver is not bounded.
        service.approveUser(ApproveUserCommand(context.org, invitation.membershipId, uuidV7()))
    }

    @Test
    fun `the bootstrap administrator alone does not close the platform checker`() {
        val context = activeInvitationContext()
        val admin = fake.addUser("admin@example.test", "admin", UserLifecycleState.ACTIVE)
        val adminMembership =
            fake.addMembership(
                context.org,
                admin,
                MembershipLifecycleState.ACTIVE,
            )
        fake.membershipInviters[context.org to adminMembership] = SystemActor.ID
        val user = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.identityLinks += user
        val invitation = service.inviteUser(inviteCommand(context))

        val approval =
            service.approveUser(
                ApproveUserCommand(
                    context.org,
                    invitation.membershipId,
                    uuidV7(),
                    scope = ActingScope.PLATFORM,
                ),
            )

        assertEquals(MembershipLifecycleState.ACTIVE, approval.membershipStatus)
    }

    @Test
    fun `suspend reactivate and deactivate drive user lifecycle transitions`() {
        val org = uuidV7()
        val actor = uuidV7()
        val userId = fake.addUser("user@example.test", "user", UserLifecycleState.ACTIVE)
        fake.organisationStates[org] = OrganisationLifecycleState.ACTIVE
        fake.identityLinks += userId

        service.suspendUser(SuspendUserCommand(org, userId, actor, Reason.required("risk")))
        assertEquals(UserLifecycleState.SUSPENDED, fake.users.getValue(userId).state)

        service.reactivateUser(
            ReactivateUserCommand(org, userId, actor, DecisionRemark.optional("cleared")),
        )
        assertEquals(UserLifecycleState.ACTIVE, fake.users.getValue(userId).state)

        service.deactivateUser(
            DeactivateUserCommand(org, userId, actor, Reason.required("left")),
        )
        assertEquals(UserLifecycleState.DEACTIVATED, fake.users.getValue(userId).state)
        assertTrue(
            logs.logs.map { it.transition }.contains(UserLifecycleTransition.REACTIVATE.name),
        )
    }

    @Test
    fun `completed deactivation revokes active assignments with audit and externalized events`() {
        val org = uuidV7()
        val actor = uuidV7()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.organisationStates[org] = OrganisationLifecycleState.ACTIVE
        fake.identityLinks += userId
        fake.branchAssignments += BranchAssignmentKey(org, userId)
        val roleId = uuidV7()
        fake.roleAssignments += RoleAssignmentKey(org, userId, roleId, null)

        service.deactivateUser(
            DeactivateUserCommand(org, userId, actor, Reason.required("left")),
        )

        assertTrue(fake.branchAssignments.isEmpty())
        assertTrue(fake.roleAssignments.isEmpty())
        assertTrue(
            audits.events.any { it.action == "user.deactivation_assignment_revoked" },
        )
        assertEquals(
            2,
            audits.events.count { it.action == "user.deactivation_assignment_revoked" },
        )
        assertTrue(
            events.externalTargets().count {
                it == "finaxis.lifecycle.user.deactivation-assignment-revoked"
            } == 2,
        )
    }

    @Test
    fun `global deactivation revokes assignments in every tenant organisation`() {
        val firstTenantId = uuidV7()
        val secondTenantId = uuidV7()
        val actor = uuidV7()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        fake.addMembership(firstTenantId, userId, MembershipLifecycleState.ACTIVE)
        fake.addMembership(secondTenantId, userId, MembershipLifecycleState.ACTIVE)
        fake.branchAssignments += BranchAssignmentKey(firstTenantId, userId)
        fake.branchAssignments += BranchAssignmentKey(secondTenantId, userId)
        fake.roleAssignments += RoleAssignmentKey(firstTenantId, userId, uuidV7(), null)
        fake.roleAssignments += RoleAssignmentKey(secondTenantId, userId, uuidV7(), null)

        service.deactivateUser(
            DeactivateUserCommand(
                PlatformOrganisation.ID,
                userId,
                actor,
                Reason.required("left"),
            ),
        )

        assertTrue(fake.branchAssignments.isEmpty())
        assertTrue(fake.roleAssignments.isEmpty())
        val revocationAudits =
            audits.events.filter {
                it.action ==
                    "user.deactivation_assignment_revoked"
            }
        assertEquals(4, revocationAudits.size)
        assertEquals(
            setOf(firstTenantId.toString(), secondTenantId.toString()),
            revocationAudits.map { it.tenantId }.toSet(),
        )
    }

    @Test
    fun `membership revocation cascades branch and role revocation`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        val membershipId = fake.addMembership(context.org, userId, MembershipLifecycleState.ACTIVE)
        fake.branchAssignments += BranchAssignmentKey(context.org, userId)
        fake.roleAssignments += RoleAssignmentKey(context.org, userId, context.role, null)

        service.revokeTenantMembership(
            RevokeTenantMembershipCommand(
                context.org,
                membershipId,
                context.actor,
                Reason.required("offboard"),
            ),
        )

        assertEquals(
            MembershipLifecycleState.REVOKED,
            fake.memberships.getValue(context.org to membershipId).state,
        )
        assertTrue(fake.branchAssignments.isEmpty())
        assertTrue(fake.roleAssignments.isEmpty())
        assertTrue(audits.events.any { it.action == "membership.revoke" })
    }

    @Test
    fun `revoking an already revoked membership is a 409 conflict that changes nothing`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        val membershipId = fake.addMembership(context.org, userId, MembershipLifecycleState.REVOKED)
        val command =
            RevokeTenantMembershipCommand(
                context.org,
                membershipId,
                context.actor,
                Reason.required("Offboarding."),
            )

        val failure = assertFailsWith<ConflictException> { service.revokeTenantMembership(command) }

        assertEquals("This membership has already been revoked.", failure.safeDetail)
        assertEquals(
            MembershipLifecycleState.REVOKED,
            fake.memberships.getValue(context.org to membershipId).state,
        )
        assertTrue(audits.events.none { it.action == "membership.revoke" })
        assertTrue(logs.logs.isEmpty())
    }

    @Test
    fun `invitation rejects a branch scoped role without a branch as a validation error`() {
        val context = activeInvitationContext()
        val command =
            inviteCommand(context)
                .copy(
                    roleAssignments =
                        listOf(RoleAssignmentRequest(context.role, RoleAssignmentScopeType.BRANCH)),
                )

        val failure = assertFailsWith<InvalidRequestException> { service.inviteUser(command) }

        assertEquals("validation_failed", failure.code)
        assertEquals("A branch-scoped role assignment requires a branch.", failure.safeDetail)
        assertTrue(fake.users.isEmpty())
    }

    @Test
    fun `invitation rejects a tenant scoped role with a branch as an invalid operation`() {
        val context = activeInvitationContext()
        val command =
            inviteCommand(context)
                .copy(
                    roleAssignments =
                        listOf(
                            RoleAssignmentRequest(
                                context.role,
                                RoleAssignmentScopeType.TENANT,
                                context.branch,
                            ),
                        ),
                )

        val failure = assertFailsWith<InvalidOperationException> { service.inviteUser(command) }

        assertEquals("invalid_operation", failure.code)
        assertTrue(fake.users.isEmpty())
    }

    @Test
    fun `suspend membership transitions active membership and audits`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        val membershipId = fake.addMembership(context.org, userId, MembershipLifecycleState.ACTIVE)

        service.suspendMembership(
            SuspendMembershipCommand(
                context.org,
                membershipId,
                context.actor,
                Reason.required("risk"),
            ),
        )

        assertEquals(
            MembershipLifecycleState.SUSPENDED,
            fake.memberships.getValue(context.org to membershipId).state,
        )
        assertTrue(audits.events.any { it.action == "membership.suspend" })
    }

    @Test
    fun `reactivate membership transitions suspended membership and audits`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        val membershipId =
            fake.addMembership(
                context.org,
                userId,
                MembershipLifecycleState.SUSPENDED,
            )

        service.reactivateMembership(
            ReactivateMembershipCommand(
                context.org,
                membershipId,
                context.actor,
                DecisionRemark.optional("cleared"),
            ),
        )

        assertEquals(
            MembershipLifecycleState.ACTIVE,
            fake.memberships.getValue(context.org to membershipId).state,
        )
        assertTrue(audits.events.any { it.action == "membership.reactivate" })
    }

    @Test
    fun `an invitation asks user invite in the tenant first and a refusal writes nothing`() {
        val context = activeInvitationContext()
        // The organisation is not active: a 409 would follow if the permission were not first.
        fake.organisationStates[context.org] = OrganisationLifecycleState.SUSPENDED
        doThrow(MissingPermissionException("user.invite"))
            .whenever(permissionGuard)
            .requireTenantPermission(context.actor, context.org, "user.invite")

        val failure =
            assertFailsWith<MissingPermissionException> {
                service.inviteUser(inviteCommand(context))
            }

        assertEquals("Missing permission: user.invite.", failure.safeDetail)
        assertNothingWritten()
    }

    @Test
    fun `a system invitation and approval ask no actor permission and keep the maker checker`() {
        val context = activeInvitationContext()
        val bootstrapActor = SystemActor.ID

        val invitation =
            service.inviteAsSystem(inviteCommand(context).copy(invitedBy = bootstrapActor))
        val approval =
            service.approveAsSystem(
                ApproveUserCommand(context.org, invitation.membershipId, context.checker),
            )

        assertTrue(approval.keycloakProvisioningRequested)
        verify(permissionGuard, never()).requireTenantPermission(any(), any(), any())
        verify(permissionGuard, never()).requirePlatformPermission(any(), any())
        val beneficiary = fake.users.keys.single()
        assertFailsWith<ForbiddenOperationException> {
            service.approveAsSystem(
                ApproveUserCommand(context.org, invitation.membershipId, beneficiary),
            )
        }
    }

    @Test
    fun `a system approval cannot act in the platform scope`() {
        val context = activeInvitationContext()

        assertFailsWith<IllegalArgumentException> {
            service.approveAsSystem(
                ApproveUserCommand(
                    context.org,
                    uuidV7(),
                    context.checker,
                    scope = ActingScope.PLATFORM,
                ),
            )
        }
    }

    @Test
    fun `a tenant approval asks user approve in the tenant before any lookup`() {
        val context = activeInvitationContext()
        val invitation = service.inviteUser(inviteCommand(context))
        doThrow(MissingPermissionException("user.approve"))
            .whenever(permissionGuard)
            .requireTenantPermission(context.checker, context.org, "user.approve")

        // A known and an unknown membership are refused identically: no existence signal.
        listOf(invitation.membershipId, uuidV7()).forEach { membershipId ->
            val failure =
                assertFailsWith<MissingPermissionException> {
                    service.approveUser(
                        ApproveUserCommand(context.org, membershipId, context.checker),
                    )
                }
            assertEquals("Missing permission: user.approve.", failure.safeDetail)
        }

        assertApprovalChangedNothing(context, invitation)
        verify(permissionGuard, never()).requirePlatformPermission(any(), any())
    }

    @Test
    fun `each membership mutation asks its own permission in the tenant before any lookup`() {
        val context = activeInvitationContext()
        val actor = context.actor
        val unknown = uuidV7()
        val reason = Reason.required("because")
        val cases =
            mapOf<String, () -> Unit>(
                "membership.suspend" to {
                    service.suspendMembership(
                        SuspendMembershipCommand(context.org, unknown, actor, reason),
                    )
                },
                "membership.reactivate" to {
                    service.reactivateMembership(
                        ReactivateMembershipCommand(context.org, unknown, actor),
                    )
                },
                "membership.revoke" to {
                    service.revokeTenantMembership(
                        RevokeTenantMembershipCommand(context.org, unknown, actor, reason),
                    )
                },
            )

        cases.forEach { (code, action) ->
            doThrow(MissingPermissionException(code))
                .whenever(permissionGuard)
                .requireTenantPermission(actor, context.org, code)

            // An unknown membership would be a 404: the refusal came first.
            val failure = assertFailsWith<MissingPermissionException>(code) { action() }

            assertEquals("Missing permission: $code.", failure.safeDetail)
        }
        assertTrue(logs.logs.isEmpty())
        assertTrue(audits.events.isEmpty())
    }

    @Test
    fun `a refused membership suspension leaves the membership and writes no log or audit`() {
        val context = activeInvitationContext()
        val userId = fake.addUser("member@example.test", "member", UserLifecycleState.ACTIVE)
        val membershipId = fake.addMembership(context.org, userId, MembershipLifecycleState.ACTIVE)
        doThrow(MissingPermissionException("membership.view"))
            .whenever(permissionGuard)
            .requireTenantPermission(context.actor, context.org, "membership.suspend")

        assertFailsWith<MissingPermissionException> {
            service.suspendMembership(
                SuspendMembershipCommand(
                    context.org,
                    membershipId,
                    context.actor,
                    Reason.required("risk"),
                ),
            )
        }

        assertEquals(
            MembershipLifecycleState.ACTIVE,
            fake.memberships.getValue(context.org to membershipId).state,
        )
        assertTrue(logs.logs.isEmpty())
        assertTrue(audits.events.isEmpty())
    }

    private fun approve(
        context: InvitationContext,
        invitation: UserInvitationResult,
    ) = service.approveUser(
        ApproveUserCommand(context.org, invitation.membershipId, context.checker, "req-1"),
    )

    private fun assertNothingWritten() {
        assertTrue(fake.users.isEmpty())
        assertTrue(fake.memberships.isEmpty())
        assertTrue(fake.branchAssignments.isEmpty())
        assertTrue(fake.roleAssignments.isEmpty())
        assertTrue(audits.events.isEmpty())
    }

    private fun assertApprovalChangedNothing(
        context: InvitationContext,
        invitation: UserInvitationResult,
    ) {
        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            fake.memberships.getValue(context.org to invitation.membershipId).state,
        )
        assertTrue(fake.dispatches.isEmpty())
        assertTrue(
            events.externalTargets().none {
                it.contains("keycloak-provisioning") ||
                    it.contains("application-invite") ||
                    it.contains("membership.activated")
            },
        )
        assertTrue(logs.logs.isEmpty())
        assertTrue(audits.events.none { it.action == "user.approve" })
    }

    private fun activeInvitationContext(): InvitationContext {
        val context =
            InvitationContext(
                uuidV7(),
                uuidV7(),
                uuidV7(),
                uuidV7(),
                uuidV7(),
            )
        fake.organisationStates[context.org] = OrganisationLifecycleState.ACTIVE
        fake.branchStates[context.org to context.branch] = BranchLifecycleState.ACTIVE
        fake.roles += context.org to context.role
        return context
    }

    private fun inviteCommand(
        context: InvitationContext,
        sendApplicationInvite: Boolean = false,
    ): InviteUserCommand =
        InviteUserCommand(
            organisationId = context.org,
            email = "member@example.test",
            username = "member",
            displayName = "Member One",
            membershipType = MembershipType.STAFF,
            primaryBranchId = context.branch,
            branchAssignments =
                listOf(BranchAssignmentRequest(context.branch, BranchAssignmentType.HOME)),
            roleAssignments =
                listOf(RoleAssignmentRequest(context.role, RoleAssignmentScopeType.TENANT)),
            invitedBy = context.actor,
            sendKeycloakInvite = true,
            sendApplicationInvite = sendApplicationInvite,
        )
}

private class UserProvisioningFake :
    FoundationLifecycleReader,
    FoundationLifecycleWriter,
    LifecyclePrerequisites,
    UserProvisioningStore,
    BranchLifecycleStore,
    BranchAssignmentStore {
    val organisationStates = mutableMapOf<UUID, OrganisationLifecycleState>()
    val branchStates = mutableMapOf<Pair<UUID, UUID>, BranchLifecycleState>()
    val users = mutableMapOf<UUID, LifecycleAggregate<UserLifecycleState>>()
    val userEmails = mutableMapOf<UUID, String>()
    val usernames = mutableMapOf<UUID, String>()
    val displayNames = mutableMapOf<UUID, String>()
    val memberships = mutableMapOf<Pair<UUID, UUID>, LifecycleAggregate<MembershipLifecycleState>>()
    val membershipUsers = mutableMapOf<Pair<UUID, UUID>, UUID>()
    val membershipTypes = mutableMapOf<Pair<UUID, UUID>, MembershipType>()
    val membershipInviters = mutableMapOf<Pair<UUID, UUID>, UUID>()
    val preferences = mutableMapOf<Pair<UUID, UUID>, Pair<Boolean, Boolean>>()
    val roles = mutableSetOf<Pair<UUID, UUID>>()
    val identityLinks = mutableSetOf<UUID>()
    val branchAssignments = mutableSetOf<BranchAssignmentKey>()
    val roleAssignments = mutableSetOf<RoleAssignmentKey>()
    val dispatches = mutableSetOf<DispatchRecord>()
    var duplicateOnCreateUser = false

    fun addUser(
        email: String,
        username: String,
        status: UserLifecycleState,
        displayName: String = username,
    ): UUID {
        val userId = uuidV7()
        users[userId] = LifecycleAggregate(userId, status, "USER_ACCOUNT")
        userEmails[userId] = email
        usernames[userId] = username
        displayNames[userId] = displayName
        return userId
    }

    fun addMembership(
        organisationId: UUID,
        userId: UUID,
        status: MembershipLifecycleState,
        type: MembershipType = MembershipType.STAFF,
    ): UUID {
        val membershipId = uuidV7()
        memberships[organisationId to membershipId] =
            LifecycleAggregate(membershipId, status, "MEMBERSHIP", organisationId)
        membershipUsers[organisationId to membershipId] = userId
        membershipTypes[organisationId to membershipId] = type
        preferences[organisationId to membershipId] = true to true
        return membershipId
    }

    override fun organisationState(organisationId: UUID) = organisationStates[organisationId]

    override fun findUserIdByEmail(email: String): UUID? =
        userEmails.entries.firstOrNull { it.value.equals(email, ignoreCase = true) }?.key

    override fun usernameInUse(username: String): Boolean =
        usernames.values.any { it.equals(username, ignoreCase = true) }

    override fun createUserAccount(
        email: String,
        username: String,
        displayName: String,
        phoneE164: String?,
        actorId: UUID,
    ): UUID {
        if (duplicateOnCreateUser) throw DuplicateKeyException("uq_user_account_lower_email")
        return addUser(email, username, UserLifecycleState.DRAFT, displayName)
    }

    override fun userStatus(userId: UUID): UserLifecycleState? = users[userId]?.state

    override fun createMembership(
        organisationId: UUID,
        userId: UUID,
        membershipType: MembershipType,
        primaryBranchId: UUID?,
        actorId: UUID,
    ): UUID =
        addMembership(
            organisationId,
            userId,
            MembershipLifecycleState.PENDING_APPROVAL,
            membershipType,
        ).also { membershipInviters[organisationId to it] = actorId }

    override fun saveInvitationPreferences(
        organisationId: UUID,
        membershipId: UUID,
        sendKeycloakInvite: Boolean,
        sendApplicationInvite: Boolean,
        actorId: UUID,
    ) {
        preferences[organisationId to membershipId] = sendKeycloakInvite to sendApplicationInvite
    }

    override fun membershipSnapshot(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot? {
        val key = organisationId to membershipId
        val aggregate = memberships[key] ?: return null
        val userId = requireNotNull(membershipUsers[key])
        val preference = preferences[key] ?: (true to true)
        return MembershipProvisioningSnapshot(
            membershipId,
            userId,
            aggregate.state,
            requireNotNull(membershipTypes[key]),
            requireNotNull(userEmails[userId]),
            requireNotNull(usernames[userId]),
            requireNotNull(displayNames[userId]),
            requireNotNull(userStatus(userId)),
            preference.first,
            preference.second,
        )
    }

    override fun hasActiveMembershipBeyondBootstrap(organisationId: UUID): Boolean =
        memberships.any { (key, aggregate) ->
            key.first == organisationId &&
                aggregate.state == MembershipLifecycleState.ACTIVE &&
                membershipInviters[key] != SystemActor.ID
        }

    override fun membershipExists(
        organisationId: UUID,
        userId: UUID,
    ): Boolean =
        memberships.any { (key, _) ->
            key.first == organisationId && membershipUsers[key] == userId
        }

    override fun membershipInvitedBy(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID? = membershipInviters[organisationId to membershipId]

    override fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ): BranchLifecycleState? = branchStates[organisationId to branchId]

    override fun createDraft(command: CreateBranchCommand): UUID = uuidV7()

    override fun branchCodeExists(
        organisationId: UUID,
        branchCode: String,
        excludingBranchId: UUID?,
    ): Boolean = false

    override fun branchCode(
        organisationId: UUID,
        branchId: UUID,
    ): String? = "branch-$branchId"

    override fun parentBelongsToOrganisation(
        organisationId: UUID,
        parentBranchId: UUID,
    ): Boolean = branchStates.containsKey(organisationId to parentBranchId)

    override fun parentBranchId(
        organisationId: UUID,
        branchId: UUID,
    ): UUID? = null

    override fun roleExists(
        organisationId: UUID,
        roleId: UUID,
    ): Boolean = roles.contains(organisationId to roleId)

    override fun findRoleIdByCode(
        organisationId: UUID,
        roleCode: String,
    ): UUID? = null

    override fun hasKeycloakIdentity(userId: UUID): Boolean = identityLinks.contains(userId)

    override fun assignRole(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        scopeType: RoleAssignmentScopeType,
        branchId: UUID?,
        actorId: UUID,
    ): UUID {
        roleAssignments += RoleAssignmentKey(organisationId, userId, roleId, branchId)
        return uuidV7()
    }

    override fun hasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = branchAssignments.contains(BranchAssignmentKey(organisationId, userId))

    override fun hasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = roleAssignments.any { it.organisationId == organisationId && it.userId == userId }

    override fun recordDispatch(
        organisationId: UUID,
        userId: UUID,
        dispatchType: IdentityDispatchType,
        dispatchKey: String,
        actorId: UUID,
    ) {
        dispatches += DispatchRecord(userId, dispatchType, dispatchKey)
    }

    override fun linkKeycloakIdentity(
        userId: UUID,
        subject: String,
        realm: String,
        actorId: UUID,
    ) {
        identityLinks += userId
    }

    override fun markDispatchSucceeded(
        dispatchKey: String,
        externalRef: String,
    ) = Unit

    override fun markDispatchFailed(
        dispatchKey: String,
        error: String,
    ) = Unit

    override fun dispatchStatus(dispatchKey: String): String? = null

    override fun revokeBranchAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int {
        val userId = requireNotNull(membershipUsers[organisationId to membershipId])
        val before = branchAssignments.size
        branchAssignments.removeIf { it.organisationId == organisationId && it.userId == userId }
        return before - branchAssignments.size
    }

    override fun revokeRoleAssignmentsForMembership(
        organisationId: UUID,
        membershipId: UUID,
        actorId: UUID,
    ): Int {
        val userId = requireNotNull(membershipUsers[organisationId to membershipId])
        val before = roleAssignments.size
        roleAssignments.removeIf { it.organisationId == organisationId && it.userId == userId }
        return before - roleAssignments.size
    }

    override fun findOrganisation(id: UUID): LifecycleAggregate<OrganisationLifecycleState>? = null

    override fun findBranch(
        organisationId: UUID,
        branchId: UUID,
    ): LifecycleAggregate<BranchLifecycleState>? = null

    override fun findUser(userId: UUID): LifecycleAggregate<UserLifecycleState>? = users[userId]

    override fun findMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): LifecycleAggregate<MembershipLifecycleState>? = memberships[organisationId to membershipId]

    override fun membershipUserId(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID? = membershipUsers[organisationId to membershipId]

    override fun findOrganisationIdsForActiveUserAccess(userId: UUID): Set<UUID> =
        memberships
            .filter { (key, membership) ->
                membershipUsers[key] == userId &&
                    membership.state == MembershipLifecycleState.ACTIVE
            }.keys
            .map { it.first }
            .plus(branchAssignments.filter { it.userId == userId }.map { it.organisationId })
            .plus(roleAssignments.filter { it.userId == userId }.map { it.organisationId })
            .toSet()

    override fun saveOrganisation(
        aggregate: LifecycleAggregate<OrganisationLifecycleState>,
    ): LifecycleAggregate<OrganisationLifecycleState> = aggregate

    override fun saveBranch(
        aggregate: LifecycleAggregate<BranchLifecycleState>,
    ): LifecycleAggregate<BranchLifecycleState> = aggregate

    override fun saveUser(
        aggregate: LifecycleAggregate<UserLifecycleState>,
    ): LifecycleAggregate<UserLifecycleState> = aggregate

    override fun saveMembership(
        aggregate: LifecycleAggregate<MembershipLifecycleState>,
    ): LifecycleAggregate<MembershipLifecycleState> = aggregate

    override fun revokeActiveAssignments(
        organisationId: UUID,
        userId: UUID,
    ): List<DeprovisionedAssignment> {
        val matchingBranch =
            branchAssignments.filter { it.organisationId == organisationId && it.userId == userId }
        val matchingRole =
            roleAssignments.filter { it.organisationId == organisationId && it.userId == userId }
        branchAssignments.removeAll(matchingBranch.toSet())
        roleAssignments.removeAll(matchingRole.toSet())
        return matchingBranch.map {
            DeprovisionedAssignment(uuidV7(), "USER_BRANCH_ASSIGNMENT")
        } +
            matchingRole.map { DeprovisionedAssignment(uuidV7(), "USER_ROLE_ASSIGNMENT") }
    }

    override fun branchHasActiveAssignments(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean = false

    override fun branchHasActiveChildren(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean = false

    override fun userHasKeycloakIdentity(userId: UUID): Boolean = identityLinks.contains(userId)

    override fun userState(userId: UUID): UserLifecycleState? = userStatus(userId)

    override fun membershipIsBranchExempt(
        organisationId: UUID,
        userId: UUID,
    ): Boolean {
        val key =
            membershipUsers.entries
                .find {
                    it.key.first == organisationId &&
                        it.value == userId
                }?.key
        val type = key?.let { membershipTypes[it] }
        return type == MembershipType.SYSTEM || type == MembershipType.AUDITOR
    }

    override fun membershipHasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = hasActiveBranchAssignment(organisationId, userId)

    override fun membershipHasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean = hasActiveRoleAssignment(organisationId, userId)

    override fun userExists(userId: UUID): Boolean = users.containsKey(userId)

    override fun membership(
        organisationId: UUID,
        userId: UUID,
    ): MembershipSnapshot? {
        val membership =
            memberships.entries.firstOrNull { (key, _) ->
                key.first == organisationId && membershipUsers[key] == userId
            } ?: return null
        return MembershipSnapshot(
            membership.value.state,
            requireNotNull(membershipTypes[membership.key]),
        )
    }

    override fun assign(command: AssignUserToBranchCommand): BranchAssignmentWrite {
        val key = BranchAssignmentKey(command.organisationId, command.userId)
        return BranchAssignmentWrite(
            assignmentIds.getOrPut(key) { uuidV7() },
            branchAssignments.add(key),
        )
    }

    private val assignmentIds = mutableMapOf<BranchAssignmentKey, UUID>()

    override fun findAssignment(
        organisationId: UUID,
        assignmentId: UUID,
    ): BranchAssignmentTarget? = null

    override fun isActive(command: RevokeUserBranchAssignmentCommand): Boolean =
        branchAssignments.contains(BranchAssignmentKey(command.organisationId, command.userId))

    override fun activeAssignments(
        organisationId: UUID,
        userId: UUID,
    ): Int = if (branchAssignments.contains(BranchAssignmentKey(organisationId, userId))) 1 else 0

    override fun revoke(command: RevokeUserBranchAssignmentCommand): Boolean =
        branchAssignments.remove(BranchAssignmentKey(command.organisationId, command.userId))

    override fun createdBy(
        organisationId: UUID,
        branchId: UUID,
    ): UUID? = null

    override fun hasActiveBranchBeyondBootstrap(organisationId: UUID): Boolean = false

    override fun submittedBy(
        organisationId: UUID,
        branchId: UUID,
    ): UUID? = null

    override fun hasAmended(
        organisationId: UUID,
        branchId: UUID,
        actorId: UUID,
    ): Boolean = false

    override fun updateBranch(command: UpdateBranchCommand) = false

    override fun claimOpenParent(
        organisationId: UUID,
        parentBranchId: UUID,
    ) = true

    override fun lockBranchHierarchy(organisationId: UUID) = Unit
}

private class UserProvisioningEventCapture : TransitionEventPublisher {
    val events = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        events += event
    }

    fun externalTargets(): List<String> =
        events.mapNotNull { (it as? ExternalizedTransitionEvent)?.target }
}

private class UserProvisioningAuditCapture : AuditEventRepository {
    val events = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        events += event
    }
}

private class UserProvisioningTransitionLogCapture : TransitionLogRepository {
    val logs = mutableListOf<TransitionLog>()

    override fun save(log: TransitionLog) {
        logs += log
    }
}

private data class InvitationContext(
    val org: UUID,
    val branch: UUID,
    val role: UUID,
    val actor: UUID,
    val checker: UUID,
)

private data class BranchAssignmentKey(
    val organisationId: UUID,
    val userId: UUID,
)

private data class RoleAssignmentKey(
    val organisationId: UUID,
    val userId: UUID,
    val roleId: UUID,
    val branchId: UUID?,
)

private data class DispatchRecord(
    val userId: UUID,
    val type: IdentityDispatchType,
    val key: String,
)
