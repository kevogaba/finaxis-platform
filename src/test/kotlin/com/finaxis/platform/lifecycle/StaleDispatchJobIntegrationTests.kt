package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.jobs.JobStepGuard
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.ApplicationInviteJobRequest
import com.finaxis.platform.lifecycle.application.ApplicationInviteJobRequestHandler
import com.finaxis.platform.lifecycle.application.ApproveUserCommand
import com.finaxis.platform.lifecycle.application.BranchAssignmentRequest
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.KeycloakUserProvisioningJobRequest
import com.finaxis.platform.lifecycle.application.KeycloakUserProvisioningJobRequestHandler
import com.finaxis.platform.lifecycle.application.LifecycleErrorCodes
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ProvisionedActivation
import com.finaxis.platform.lifecycle.application.ProvisionedMembershipActivation
import com.finaxis.platform.lifecycle.application.Reason
import com.finaxis.platform.lifecycle.application.RevokeTenantMembershipCommand
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.UserInvitationResult
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stale-job safety of a re-invited member (#172, ADR 0037), against a real database and the
 * real job handlers. A job queued in the membership's first life, running or retrying after the
 * member was revoked and invited again, must neither activate the row (it is `PENDING_APPROVAL`
 * again and awaits a new checker) nor send an email, and must not stop the new life's own jobs; a
 * job of a membership that was never revoked is processed exactly as before.
 */
@Import(
    PostgresTestConfiguration::class,
    StaleDispatchJobIntegrationTests.StepGuardConfiguration::class,
)
@SpringBootTest
@Suppress("LargeClass")
class StaleDispatchJobIntegrationTests
    @Autowired
    constructor(
        private val organisationProvisioningService: OrganisationProvisioningService,
        private val dsl: DSLContext,
        private val userProvisioningService: UserProvisioningService,
        private val keycloakHandler: KeycloakUserProvisioningJobRequestHandler,
        private val applicationInviteHandler: ApplicationInviteJobRequestHandler,
        private val userStore: UserProvisioningStore,
        private val membershipActivation: ProvisionedMembershipActivation,
        private val transactionManager: PlatformTransactionManager,
    ) {
        @MockitoBean
        private lateinit var gateway: IdentityProvisioningGateway

        @MockitoBean
        private lateinit var emailGateway: EmailGateway

        private val probe = LockOverlapProbe(dsl)
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val makerOne = seedUser("maker-one")
        private val makerTwo = seedUser("maker-two")
        private val checker = seedUser("checker")
        private val organisationId =
            fixture.createActiveOrganisation("stale-dispatch", makerOne).also {
                fixture.grantTenantAdmin(it, makerTwo)
                fixture.grantTenantAdmin(it, checker)
            }

        @Test
        fun `a first-life Keycloak job run or retried after the re-invite activates nothing`() {
            stubGateway()
            val life = invitedNewUser()
            approve(life)
            val firstKey = keycloakKey(life)
            revoke(life)
            reinvite(life)

            // Run, then run again as JobRunr would retry it: both end quietly and change nothing.
            repeat(2) { keycloakHandler.run(keycloakJob(life, firstKey, generation = 0)) }

            verifyNoInteractions(gateway)
            assertEquals("PENDING_APPROVAL", membershipStatus(life))
            assertEquals("PROVISIONING_IDP", userStatus(life))
            assertEquals(0, identityLinks(life))
            assertEquals(0, activationLogRows(life), "no ACTIVATE was recorded")
            assertEquals("FAILED", dispatchStatus(firstKey))
            assertEquals("CANCELLED:MembershipReinvited", dispatchError(firstKey))
        }

        @Test
        fun `a first-life Keycloak job leaves a dispatch the revoke already cancelled alone`() {
            stubGateway()
            val life = invitedNewUser()
            approve(life)
            val firstKey = keycloakKey(life)
            revoke(life)
            // The first job ran after the revoke and ended: what it leaves is a FAILED dispatch.
            keycloakHandler.run(keycloakJob(life, firstKey, generation = 0))
            assertEquals("CANCELLED:MembershipRevoked", dispatchError(firstKey))
            val attempts = dispatchAttempts(firstKey)
            reinvite(life)

            // A redelivery after the re-invite changes nothing: the row stays PENDING_APPROVAL.
            keycloakHandler.run(keycloakJob(life, firstKey, generation = 0))

            verifyNoInteractions(gateway)
            assertEquals("PENDING_APPROVAL", membershipStatus(life))
            assertEquals("CANCELLED:MembershipRevoked", dispatchError(firstKey))
            assertEquals(attempts, dispatchAttempts(firstKey))
        }

        @Test
        fun `the new life is provisioned and activated by its own job beside a stale one`() {
            stubGateway()
            val life = invitedNewUser()
            approve(life)
            val firstKey = keycloakKey(life)
            revoke(life)
            reinvite(life)
            keycloakHandler.run(keycloakJob(life, firstKey, generation = 0))

            // The second approval is the first of its life, not a 409, and queues its own job.
            val approval = approve(life)

            assertTrue(approval.keycloakProvisioningRequested)
            val secondKey = "$firstKey:G1"
            assertEquals("PENDING", dispatchStatus(secondKey))
            assertEquals("PENDING_APPROVAL", membershipStatus(life))
            keycloakHandler.run(keycloakJob(life, secondKey, generation = 1))

            verify(gateway, times(1)).findOrCreateUser(any())
            assertEquals("ACTIVE", membershipStatus(life))
            assertEquals("SUCCEEDED", dispatchStatus(secondKey))
            assertEquals("FAILED", dispatchStatus(firstKey), "the first life's row is untouched")
            assertEquals(1, activationLogRows(life))
        }

        @Test
        fun `a first-life application-invite job after the re-invite sends no email`() {
            val life = invitedExistingUser(sendApplicationInvite = true)
            approve(life)
            val firstKey = applicationInviteKey(life)
            assertEquals("ACTIVE", membershipStatus(life))
            revoke(life)
            reinvite(life, sendApplicationInvite = true)

            applicationInviteHandler.run(applicationInviteJob(life, firstKey, generation = 0))
            applicationInviteHandler.run(applicationInviteJob(life, firstKey, generation = 0))

            verifyNoInteractions(emailGateway)
            assertEquals("PENDING_APPROVAL", membershipStatus(life))
            assertEquals("FAILED", dispatchStatus(firstKey))
            assertEquals("CANCELLED:MembershipReinvited", dispatchError(firstKey))

            // The new life's invite is its own dispatch and job, and goes out exactly once.
            whenever(emailGateway.send(any())).thenReturn(EmailDeliveryReceipt("msg-1"))
            approve(life)
            val secondKey = "$firstKey:G1"
            assertEquals("PENDING", dispatchStatus(secondKey))
            applicationInviteHandler.run(applicationInviteJob(life, secondKey, generation = 1))

            verify(emailGateway, times(1)).send(any())
            assertEquals("SUCCEEDED", dispatchStatus(secondKey))
            assertEquals("ACTIVE", membershipStatus(life))
        }

        @Test
        fun `a job stored for a live never-revoked membership is processed as before`() {
            stubGateway()
            whenever(emailGateway.send(any())).thenReturn(EmailDeliveryReceipt("msg-1"))
            val provisioning = invitedNewUser()
            approve(provisioning)
            val invite = invitedExistingUser(sendApplicationInvite = true)
            approve(invite)

            // Requests built with no generation at all are what a job stored by the previous
            // release deserialises to. Their keys are the keys of that release, unchanged.
            val keycloakKey = "$organisationId:${provisioning.userId}:KEYCLOAK_PROVISIONING"
            assertEquals(keycloakKey, keycloakKey(provisioning))
            val inviteKey = "${invite.userId}:$organisationId:APPLICATION_INVITE"
            assertEquals(inviteKey, applicationInviteKey(invite))
            keycloakHandler.run(
                KeycloakUserProvisioningJobRequest(
                    organisationId = organisationId,
                    membershipId = provisioning.membershipId,
                    userId = provisioning.userId,
                    email = provisioning.email,
                    username = provisioning.username,
                    displayName = "Invitee",
                    sendKeycloakInvite = true,
                    dispatchKey = keycloakKey,
                    actorId = checker,
                ),
            )
            applicationInviteHandler.run(
                ApplicationInviteJobRequest(
                    organisationId = organisationId,
                    membershipId = invite.membershipId,
                    userId = invite.userId,
                    email = invite.email,
                    dispatchKey = inviteKey,
                ),
            )

            assertEquals("ACTIVE", membershipStatus(provisioning))
            assertEquals("SUCCEEDED", dispatchStatus(keycloakKey))
            verify(gateway, times(1)).findOrCreateUser(any())
            verify(emailGateway, times(1)).send(any())
            assertEquals("SUCCEEDED", dispatchStatus(inviteKey))
        }

        @Test
        fun `a job that becomes stale during its Keycloak call does not activate the member`() {
            val life = invitedNewUser()
            approve(life)
            val firstKey = keycloakKey(life)
            // The revoke and the re-invite land while the first-life job is inside the Keycloak
            // call, after its entry check: the row is PENDING_APPROVAL again when it activates.
            whenever(gateway.findOrCreateUser(any())).thenAnswer {
                revoke(life)
                reinvite(life)
                KeycloakUserRef(subject = "kc-${life.userId}", created = true)
            }

            keycloakHandler.run(keycloakJob(life, firstKey, generation = 0))

            assertEquals("PENDING_APPROVAL", membershipStatus(life))
            assertEquals(0, activationLogRows(life))
            assertEquals("FAILED", dispatchStatus(firstKey))
            assertEquals("CANCELLED:MembershipReinvited", dispatchError(firstKey))
            assertEquals(
                0,
                count(
                    "SELECT COUNT(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND action = 'user.keycloak_provisioning' AND outcome = 'SUCCESS' " +
                        "AND entity_id = ?",
                    organisationId,
                    life.userId,
                ),
            )
        }

        @Test
        fun `the activation check holds the membership row against a concurrent revoke`() {
            val life = invitedNewUser()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val revoked =
                    TransactionTemplate(transactionManager).execute {
                        val locked =
                            userStore.lockedMembershipSnapshot(
                                organisationId,
                                life.membershipId,
                            )
                        assertNotNull(locked)
                        // A revoke (or, from REVOKED, a re-invite) writes this row: it must wait.
                        val rival = executor.submit { revoke(life) }
                        assertFailsWith<TimeoutException> { rival.get(750, TimeUnit.MILLISECONDS) }
                        assertEquals("PENDING_APPROVAL", membershipStatus(life))
                        rival
                    }!!

                revoked.get(30, TimeUnit.SECONDS)
                assertEquals("REVOKED", membershipStatus(life))
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `an activation that waits for a re-invite to commit finds its job stale`() {
            stubGateway()
            val life = invitedNewUser()
            approve(life)
            revoke(life)

            // The re-invitation holds the membership row, uncommitted, while the first-life job's
            // activation arrives: it blocks on the lock, and must then read the generation the
            // re-invitation committed, not the count from before it.
            val result =
                holdThenContend(
                    holder = { reinvite(life) },
                    contender = {
                        membershipActivation.activate(organisationId, life.membershipId, 0)
                    },
                )

            assertEquals(ProvisionedActivation.STALE, result.getOrThrow())
            assertEquals("PENDING_APPROVAL", membershipStatus(life))
            assertEquals(0, activationLogRows(life))
        }

        @Test
        fun `an approval that waits for a revoke and re-invite acts on the new life`() {
            val life = invitedExistingUser(sendApplicationInvite = false)

            // The checker revokes and re-invites in one transaction held open, so the approval
            // meets the membership while its life is changing under it.
            val result =
                holdThenContend(
                    holder = {
                        revoke(life)
                        reinvite(life, by = checker)
                    },
                    contender = { approve(life) },
                )

            // Life 0's maker was makerOne; with the lock the approval waits, then reads life 1,
            // whose maker is the approver, and is refused as the new maker. Without the lock it
            // read life 0 and passed the rule, and the interleaving ended in an
            // OptimisticLockingFailureException from the FSM's row-version check instead.
            val refusal = assertFailsWith<ForbiddenOperationException> { result.getOrThrow() }
            assertEquals(LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_MAKER, refusal.code)
            assertEquals("PENDING_APPROVAL", membershipStatus(life))
            assertEquals(0, activationLogRows(life))
        }

        /**
         * Runs [holder] in a transaction held open until [contender] is observed blocked behind it
         * in PostgreSQL's own lock tables, then lets the holder commit and returns the contender's
         * result.
         */
        private fun <T> holdThenContend(
            holder: () -> Unit,
            contender: () -> T,
        ): Result<T> {
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holderPid = AtomicInteger()
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val holding =
                    executor.submit {
                        TransactionTemplate(transactionManager).execute {
                            holder()
                            holderPid.set(probe.currentBackendPid())
                            held.countDown()
                            assertTrue(release.await(LATCH_SECONDS, TimeUnit.SECONDS))
                        }
                    }
                assertTrue(held.await(LATCH_SECONDS, TimeUnit.SECONDS), "holder never took hold")
                val contending = executor.submit<Result<T>> { runCatching(contender) }
                try {
                    // Without this the holder may commit before the contender's first statement
                    // and the scenario passes with no lock at all.
                    probe.awaitBlockedBehind(holderPid.get())
                } finally {
                    release.countDown()
                }
                holding.get(FUTURE_SECONDS, TimeUnit.SECONDS)
                return contending.get(FUTURE_SECONDS, TimeUnit.SECONDS)
            }
        }

        // ----- the member's life, through the real services -----

        private fun stubGateway() {
            whenever(gateway.findOrCreateUser(any()))
                .thenReturn(KeycloakUserRef(subject = UUID.randomUUID().toString(), created = true))
        }

        /** A new account, invited and awaiting approval: it needs Keycloak provisioning. */
        private fun invitedNewUser(): Life {
            val email = "invitee-${uuidV7()}@stale-dispatch.test"
            val username = "inv-${uuidV7()}".take(30)
            val result = invite(email, username, makerOne, sendApplicationInvite = false)
            return Life(result.membershipId, result.userId, email, username)
        }

        /** An account that already has an identity, so approving activates the member at once. */
        private fun invitedExistingUser(sendApplicationInvite: Boolean): Life {
            val userId = seedUser("existing")
            linkIdentity(userId)
            val email = emailOf(userId)
            val username = usernameOf(userId)
            val result = invite(email, username, makerOne, sendApplicationInvite)
            return Life(result.membershipId, result.userId, email, username)
        }

        private fun invite(
            email: String,
            username: String,
            by: UUID,
            sendApplicationInvite: Boolean,
        ): UserInvitationResult =
            withRequestContext {
                userProvisioningService.inviteUser(
                    InviteUserCommand(
                        organisationId = organisationId,
                        email = email,
                        username = username,
                        displayName = "Invitee",
                        membershipType = MembershipType.STAFF,
                        primaryBranchId = headOfficeId(),
                        branchAssignments =
                            listOf(
                                BranchAssignmentRequest(headOfficeId(), BranchAssignmentType.HOME),
                            ),
                        roleAssignments =
                            listOf(RoleAssignmentRequest(roleId(), RoleAssignmentScopeType.TENANT)),
                        invitedBy = by,
                        sendKeycloakInvite = true,
                        sendApplicationInvite = sendApplicationInvite,
                        requestId = uuidV7().toString(),
                    ),
                )
            }

        private fun reinvite(
            life: Life,
            sendApplicationInvite: Boolean = false,
            by: UUID = makerTwo,
        ) {
            val result = invite(life.email, life.username, by, sendApplicationInvite)
            assertEquals(life.membershipId, result.membershipId, "the membership row is reused")
        }

        private fun approve(life: Life) =
            withRequestContext {
                userProvisioningService.approveUser(
                    ApproveUserCommand(
                        organisationId,
                        life.membershipId,
                        checker,
                        requestId = uuidV7().toString(),
                    ),
                )
            }

        private fun revoke(life: Life) =
            withRequestContext {
                userProvisioningService.revokeTenantMembership(
                    RevokeTenantMembershipCommand(
                        organisationId,
                        life.membershipId,
                        makerOne,
                        Reason.required("Offboarding."),
                        uuidV7().toString(),
                    ),
                )
            }

        private fun keycloakKey(life: Life): String =
            singleDispatchKey(life.userId, "KEYCLOAK_PROVISIONING")

        private fun applicationInviteKey(life: Life): String =
            singleDispatchKey(life.userId, "APPLICATION_INVITE")

        private fun singleDispatchKey(
            userId: UUID,
            type: String,
        ): String =
            dsl
                .fetch(
                    "SELECT dispatch_key FROM identity_dispatch_log WHERE user_id = ? " +
                        "AND dispatch_type = ? ORDER BY created_at, id",
                    userId,
                    type,
                ).map { it.get(0, String::class.java) }
                .first()

        private fun keycloakJob(
            life: Life,
            key: String,
            generation: Int,
        ) = KeycloakUserProvisioningJobRequest(
            organisationId = organisationId,
            membershipId = life.membershipId,
            userId = life.userId,
            email = life.email,
            username = life.username,
            displayName = "Invitee",
            sendKeycloakInvite = true,
            dispatchKey = key,
            actorId = checker,
            generation = generation,
        )

        private fun applicationInviteJob(
            life: Life,
            key: String,
            generation: Int,
        ) = ApplicationInviteJobRequest(
            organisationId = organisationId,
            membershipId = life.membershipId,
            userId = life.userId,
            email = life.email,
            dispatchKey = key,
            generation = generation,
        )

        // ----- reads -----

        private fun membershipStatus(life: Life): String =
            dsl
                .fetchOne(
                    "SELECT membership_status FROM user_organisation_membership WHERE id = ?",
                    life.membershipId,
                )!!
                .get(0, String::class.java)

        private fun userStatus(life: Life): String =
            dsl
                .fetchOne("SELECT status FROM user_account WHERE id = ?", life.userId)!!
                .get(0, String::class.java)

        private fun identityLinks(life: Life): Int =
            count("SELECT COUNT(*) FROM keycloak_identity_link WHERE user_id = ?", life.userId)

        private fun activationLogRows(life: Life): Int =
            count(
                "SELECT COUNT(*) FROM user_organisation_membership_transition_log " +
                    "WHERE entity_id = ? AND transition_name = 'ACTIVATE'",
                life.membershipId,
            )

        private fun dispatchStatus(key: String): String? =
            dsl
                .fetchOne("SELECT status FROM identity_dispatch_log WHERE dispatch_key = ?", key)
                ?.get(0, String::class.java)

        private fun dispatchError(key: String): String? =
            dsl
                .fetchOne(
                    "SELECT last_error FROM identity_dispatch_log WHERE dispatch_key = ?",
                    key,
                )?.get(0, String::class.java)

        private fun dispatchAttempts(key: String): Int =
            count("SELECT attempts FROM identity_dispatch_log WHERE dispatch_key = ?", key)

        private fun count(
            sql: String,
            vararg bindings: Any,
        ): Int = dsl.fetchOne(sql, *bindings)!!.get(0, Int::class.java)

        private fun roleId(): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM role WHERE organisation_id = ? AND role_code = 'TENANT_ADMIN'",
                    organisationId,
                )!!
                .get(0, UUID::class.java)

        private fun headOfficeId(): UUID =
            dsl
                .fetch("SELECT id FROM branch WHERE organisation_id = ?", organisationId)
                .first()
                .get(0, UUID::class.java)

        private fun emailOf(userId: UUID): String =
            dsl
                .fetchOne("SELECT email FROM user_account WHERE id = ?", userId)!!
                .get(0, String::class.java)

        private fun usernameOf(userId: UUID): String =
            dsl
                .fetchOne("SELECT username FROM user_account WHERE id = ?", userId)!!
                .get(0, String::class.java)

        private fun linkIdentity(userId: UUID) {
            val now = OffsetDateTime.now()
            dsl
                .insertInto(KEYCLOAK_IDENTITY_LINK)
                .set(KEYCLOAK_IDENTITY_LINK.USER_ID, userId)
                .set(KEYCLOAK_IDENTITY_LINK.SUBJECT, "kc-$userId")
                .set(KEYCLOAK_IDENTITY_LINK.LINKED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.CREATED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.UPDATED_AT, now)
                .execute()
        }

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-${id.toString().takeLast(12)}")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@stale-dispatch.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            const val LATCH_SECONDS = 30L
            const val FUTURE_SECONDS = 60L
        }

        private data class Life(
            val membershipId: UUID,
            val userId: UUID,
            val email: String,
            val username: String,
        )

        /** Runs a job step directly: these tests call the handlers outside a JobRunr job. */
        @TestConfiguration(proxyBeanMethods = false)
        class StepGuardConfiguration {
            @Bean
            @Primary
            fun passThroughJobStepGuard(): JobStepGuard = JobStepGuard { _, action -> action() }
        }
    }
