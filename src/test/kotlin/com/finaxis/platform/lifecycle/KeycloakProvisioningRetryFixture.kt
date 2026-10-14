package com.finaxis.platform.lifecycle

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.jobs.deterministicJobId
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.ApproveUserCommand
import com.finaxis.platform.lifecycle.application.BranchAssignmentRequest
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import com.finaxis.platform.lifecycle.application.UserApprovalResult
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Builds the state a real-JobRunr test needs: an ACTIVE organisation with an ACTIVE branch, a user
 * invited with Keycloak provisioning, and the approval that queues that provisioning job. The
 * approval goes through the real outbox, RabbitMQ listener and JobRunr enqueue, so the job id the
 * tests read is the one the listener really used.
 */
class KeycloakProvisioningRetryFixture(
    organisationProvisioningService: OrganisationProvisioningService,
    private val branchProvisioningService: BranchProvisioningService,
    private val userProvisioningService: UserProvisioningService,
    private val dsl: DSLContext,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    init {
        insertUserIfMissing(ACTOR_ID, "keycloak-retry-actor")
        insertUserIfMissing(CHECKER_ID, "keycloak-retry-checker")
    }

    /**
     * Invites a user with [email] who needs Keycloak provisioning, and returns the keys of that
     * dispatch. The email is the user's identity to the gateway, so a test can match on it: the
     * organisation's own bootstrap administrator is provisioned by the same gateway.
     */
    fun invitePendingUser(email: String): PendingInvite {
        val organisationId = tenants.createActiveOrganisation("keycloak-retry", ACTOR_ID)
        tenants.grantTenantAdmin(organisationId, CHECKER_ID)
        val branchId = createActiveBranch(organisationId)
        val roleId = tenantAdminRoleId(organisationId)
        val invitation =
            userProvisioningService.inviteAsSystem(
                InviteUserCommand(
                    organisationId = organisationId,
                    email = email,
                    username = "keycloak-retry-${uuidV7()}",
                    displayName = "Keycloak Retry Invitee",
                    membershipType = MembershipType.STAFF,
                    primaryBranchId = branchId,
                    branchAssignments =
                        listOf(BranchAssignmentRequest(branchId, BranchAssignmentType.HOME)),
                    roleAssignments =
                        listOf(RoleAssignmentRequest(roleId, RoleAssignmentScopeType.TENANT)),
                    invitedBy = SystemActor.ID,
                    sendKeycloakInvite = true,
                    sendApplicationInvite = false,
                ),
            )
        val dispatchKey = "$organisationId:${invitation.userId}:KEYCLOAK_PROVISIONING"
        return PendingInvite(
            organisationId = organisationId,
            membershipId = invitation.membershipId,
            userId = invitation.userId,
            jobId = deterministicJobId(dispatchKey),
        )
    }

    /** The approval an operator makes: the checker approves through the tenant route's service. */
    fun approve(invite: PendingInvite): UserApprovalResult =
        userProvisioningService.approveUser(
            ApproveUserCommand(
                organisationId = invite.organisationId,
                membershipId = invite.membershipId,
                approvedBy = CHECKER_ID,
                requestId = uuidV7().toString(),
            ),
        )

    fun userStatus(userId: UUID): String =
        requireNotNull(
            dsl
                .select(USER_ACCOUNT.STATUS)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(userId))
                .fetchOne(USER_ACCOUNT.STATUS),
        )

    fun membershipStatus(membershipId: UUID): String =
        requireNotNull(
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS),
        )

    private fun createActiveBranch(organisationId: UUID): UUID =
        withRequestContext {
            val branchId =
                branchProvisioningService
                    .createDraft(
                        CreateBranchCommand(
                            organisationId = organisationId,
                            branchCode = "retry-${uuidV7()}",
                            branchName = "Keycloak Retry Branch",
                            branchType = "SERVICE",
                            timezone = "Africa/Nairobi",
                            requestedBy = ACTOR_ID,
                        ),
                    ).branchId
            branchProvisioningService.submitForApproval(
                SubmitBranchForApprovalCommand(
                    organisationId = organisationId,
                    branchId = branchId,
                    actorId = ACTOR_ID,
                    requestId = uuidV7(),
                ),
            )
            branchProvisioningService.activate(
                ActivateBranchCommand(
                    organisationId = organisationId,
                    branchId = branchId,
                    actorId = CHECKER_ID,
                    requestId = uuidV7(),
                ),
            )
            branchId
        }

    private fun tenantAdminRoleId(organisationId: UUID): UUID =
        requireNotNull(
            dsl
                .select(ROLE.ID)
                .from(ROLE)
                .where(ROLE.ORGANISATION_ID.eq(organisationId))
                .and(ROLE.ROLE_CODE.eq("TENANT_ADMIN"))
                .fetchOne(ROLE.ID),
        ) { "TENANT_ADMIN role was not provisioned for the organisation." }

    private fun insertUserIfMissing(
        userId: UUID,
        username: String,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, userId)
            .set(USER_ACCOUNT.USERNAME, username)
            .set(USER_ACCOUNT.EMAIL, "$username@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, username)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .onConflict(USER_ACCOUNT.ID)
            .doNothing()
            .execute()
    }

    /** The invited user, the membership awaiting approval, and the provisioning job's id. */
    data class PendingInvite(
        val organisationId: UUID,
        val membershipId: UUID,
        val userId: UUID,
        val jobId: UUID,
    )

    companion object {
        val ACTOR_ID: UUID = uuidV7()
        val CHECKER_ID: UUID = uuidV7()
    }
}
