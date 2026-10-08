package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.BranchAssignmentEntryDto
import com.finaxis.platform.iam.adapter.inbound.web.dto.InviteUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.RoleAssignmentEntryDto
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.InitialAdminDto
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapJobRequest
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapJobRequestHandler
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapRecord
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.KeycloakUserProvisioningJobRequest
import com.finaxis.platform.lifecycle.application.KeycloakUserProvisioningJobRequestHandler
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The first-approval deadlock (#153, ADR 0028) resolved end to end with nothing stood in (#223):
 * a tenant approved through the API is bootstrapped by the real asynchronous jobs (the bootstrap
 * job, then the Keycloak provisioning job against a stubbed identity provider), its bootstrap
 * administrator invites a second person, a platform administrator activates that membership as
 * the audited checker, and the second person then approves the administrator's next invitation
 * on the tenant route. From that point the tenant is self-sufficient and the platform checker is
 * closed to it.
 *
 * `PlatformFirstApprovalIntegrationTests` covers each rule in isolation with a fixture-made
 * administrator; this test only proves the pieces join up. Same Spring context as
 * `FoundationLifecycleWebIntegrationTests` (it mocks the same identity gateway), so it is reused.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PlatformCheckedOnboardingIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val bootstrapJob: InitialAdministratorBootstrapJobRequestHandler,
        private val keycloakJob: KeycloakUserProvisioningJobRequestHandler,
        private val bootstrapStore: InitialAdministratorBootstrapStore,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        @MockitoBean
        private lateinit var identityProvisioningGateway: IdentityProvisioningGateway

        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        /** Keeps the invitees' emails and usernames unique across runs on a shared database. */
        private val runId = shortId()

        /** An invited account: its user, its membership, and the label its email is built from. */
        private data class Invitee(
            val userId: UUID,
            val membershipId: UUID,
            val label: String = "",
        )

        @Test
        fun `a bootstrapped tenant onboards a second approver through the platform checker`() {
            whenever(identityProvisioningGateway.findOrCreateUser(any()))
                .thenReturn(KeycloakUserRef(subject = "kc-${uuidV7()}", created = true))
            val maker = platformAdministrator("onboard-maker")
            val approver = platformAdministrator("onboard-approver")
            val checker = platformAdministrator("onboard-checker")

            // 1. The tenant is drafted, submitted and approved through the platform API.
            val tenantId = approvedTenant(maker, approver)

            // 2. The real bootstrap: the job invites the administrator as the system actor and
            //    approves it as the tenant's approver, then the Keycloak job activates it.
            val bootstrap = runBootstrap(tenantId, approver)
            val admin = requireNotNull(bootstrap.userId)
            val headOffice = requireNotNull(bootstrap.headOfficeId)
            assertEquals(InitialAdministratorBootstrapStatus.COMPLETED, bootstrap.status)
            assertEquals("ACTIVE", membershipStatus(requireNotNull(bootstrap.membershipId)))
            assertEquals(SystemActor.ID, membershipCreator(bootstrap.membershipId))

            // 3. The administrator invites a second person, a brand-new account with no identity
            //    yet, and cannot approve the invitation: the maker rule, not a missing permission.
            val second = invite(tenantId, admin, "onboard-second", headOffice, bootstrap.roleId)
            tenantPost(tenantId, admin, "${ApiPaths.MEMBERSHIPS}/${second.membershipId}/activate")
                .andExpect {
                    status { isForbidden() }
                    jsonPath("$.detail") { value(MAKER_REFUSAL) }
                }

            // 4. A platform administrator is the audited checker while the window is open. The
            //    invitee has no identity, so the approval is accepted and the membership stays
            //    pending until the Keycloak job has created the identity and activated it.
            val memberships = "${ApiPaths.PLATFORM_TENANTS}/$tenantId/memberships"
            platformPost("$memberships/${second.membershipId}/activate", checker).andExpect {
                status { isAccepted() }
                jsonPath("$.membership_status") { value("PENDING_APPROVAL") }
            }
            runKeycloakJob(tenantId, second, checker)
            assertEquals("ACTIVE", membershipStatus(second.membershipId))

            // 5. The administrator's next invitation: the platform checker is now closed, and
            //    the second person approves it on the tenant route; the job then activates it.
            val third = invite(tenantId, admin, "onboard-third", headOffice, bootstrap.roleId)
            platformPost("$memberships/${third.membershipId}/activate", checker).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("lifecycle.platform_checker_closed") }
            }
            tenantPost(
                tenantId,
                second.userId,
                "${ApiPaths.MEMBERSHIPS}/${third.membershipId}/activate",
            ).andExpect {
                status { isAccepted() }
                jsonPath("$.id") { value(third.membershipId.toString()) }
                jsonPath("$.membership_status") { value("PENDING_APPROVAL") }
            }
            runKeycloakJob(tenantId, third, second.userId)
            assertEquals("ACTIVE", membershipStatus(third.membershipId))

            // Only the second member carries the platform marker; the third is the tenant's own.
            assertEquals(listOf("PLATFORM"), checkerScopes(tenantId, second.userId))
            assertEquals(listOf<String?>(null), checkerScopes(tenantId, third.userId))
            assertEquals(listOf(checker), approvers(tenantId, second.userId))
            assertEquals(listOf(second.userId), approvers(tenantId, third.userId))
        }

        private fun approvedTenant(
            maker: UUID,
            approver: UUID,
        ): UUID {
            val code = "onboard-${shortId()}"
            val tenantId =
                idOf(
                    platformPost(
                        ApiPaths.PLATFORM_TENANTS,
                        maker,
                        apiJsonCodec.mapper.writeValueAsString(
                            CreateTenantDraftRequest(
                                tenantCode = code,
                                displayName = "Onboarding Tenant",
                                legalName = "Onboarding Tenant Ltd",
                                registrationNumber = "REG-${shortId()}",
                                countryCode = "KE",
                                baseCurrencyCode = "KES",
                                timezone = "Africa/Nairobi",
                                admin =
                                    InitialAdminDto(
                                        email = "admin-$code@onboarding.test",
                                        username = "admin-$code",
                                        displayName = "Initial Admin",
                                        phoneE164 = "+254700000000",
                                        sendApplicationInvite = false,
                                    ),
                            ),
                        ),
                    ).andExpect { status { isCreated() } }
                        .andReturn()
                        .response.contentAsString,
                    "organisation_id",
                )
            platformPost("${ApiPaths.PLATFORM_TENANTS}/$tenantId/submit", maker)
                .andExpect { status { isOk() } }
            platformPost("${ApiPaths.PLATFORM_TENANTS}/$tenantId/approve", approver)
                .andExpect {
                    status { isAccepted() }
                    jsonPath("$.status") { value("ACTIVE") }
                }
            return tenantId
        }

        /** Runs the two jobs JobRunr would run (its server is off in tests), in order. */
        private fun runBootstrap(
            tenantId: UUID,
            approver: UUID,
        ): InitialAdministratorBootstrapRecord {
            bootstrapJob.run(InitialAdministratorBootstrapJobRequest(tenantId))
            val queued = requireNotNull(bootstrapStore.find(tenantId))
            assertEquals(InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY, queued.status)
            keycloakJob.run(
                keycloakRequest(
                    tenantId,
                    Invitee(requireNotNull(queued.userId), requireNotNull(queued.membershipId)),
                    queued.adminEmail,
                    queued.adminUsername,
                    approver,
                ),
            )
            return requireNotNull(bootstrapStore.find(tenantId))
        }

        /** A pending membership of a brand-new account, invited through the tenant API. */
        private fun invite(
            tenantId: UUID,
            inviter: UUID,
            label: String,
            branchId: UUID,
            roleId: UUID?,
        ): Invitee {
            val body =
                tenantPost(
                    tenantId,
                    inviter,
                    ApiPaths.TENANT_USERS,
                    apiJsonCodec.mapper.writeValueAsString(
                        InviteUserRequest(
                            email = emailOf(label),
                            username = usernameOf(label),
                            displayName = "Invitee",
                            membershipType = MembershipType.STAFF,
                            primaryBranchId = branchId,
                            branchAssignments =
                                listOf(
                                    BranchAssignmentEntryDto(branchId, BranchAssignmentType.HOME),
                                ),
                            roleAssignments =
                                listOf(
                                    RoleAssignmentEntryDto(
                                        requireNotNull(roleId),
                                        RoleAssignmentScopeType.TENANT,
                                    ),
                                ),
                            sendKeycloakInvite = false,
                            sendApplicationInvite = false,
                        ),
                    ),
                ).andExpect {
                    status { isCreated() }
                    jsonPath("$.user_status") { value("DRAFT") }
                }.andReturn()
                    .response.contentAsString
            return Invitee(idOf(body, "user_id"), idOf(body, "membership_id"), label)
        }

        /** Runs the Keycloak provisioning job an approval queued (JobRunr's server is off). */
        private fun runKeycloakJob(
            tenantId: UUID,
            invitee: Invitee,
            approvedBy: UUID,
        ) = keycloakJob.run(
            keycloakRequest(
                tenantId,
                invitee,
                emailOf(invitee.label),
                usernameOf(invitee.label),
                approvedBy,
            ),
        )

        private fun keycloakRequest(
            tenantId: UUID,
            invitee: Invitee,
            email: String,
            username: String,
            approvedBy: UUID,
        ) = KeycloakUserProvisioningJobRequest(
            organisationId = tenantId,
            membershipId = invitee.membershipId,
            userId = invitee.userId,
            email = email,
            username = username,
            displayName = "Invitee",
            sendKeycloakInvite = true,
            // The key the approval recorded its dispatch under (UserProvisioningService).
            dispatchKey = "$tenantId:${invitee.userId}:KEYCLOAK_PROVISIONING",
            actorId = approvedBy,
        )

        /** `checkerScope` of each `user.approve` row about [userId] in the tenant's log. */
        private fun checkerScopes(
            tenantId: UUID,
            userId: UUID,
        ): List<String?> =
            dsl
                .fetch(
                    "SELECT metadata_jsonb ->> 'checkerScope' FROM audit_event " +
                        "WHERE organisation_id = ? AND action = 'user.approve' AND entity_id = ?",
                    tenantId,
                    userId,
                ).map { it.get(0, String::class.java) }

        private fun approvers(
            tenantId: UUID,
            userId: UUID,
        ): List<UUID> =
            dsl
                .fetch(
                    "SELECT actor_user_id FROM audit_event " +
                        "WHERE organisation_id = ? AND action = 'user.approve' AND entity_id = ?",
                    tenantId,
                    userId,
                ).map { it.get(0, UUID::class.java) }

        private fun membershipStatus(membershipId: UUID): String? =
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)

        private fun membershipCreator(membershipId: UUID?): UUID? =
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.CREATED_BY)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.CREATED_BY)

        private fun platformAdministrator(label: String): UUID =
            seedUser(label).also { fixture.grantPlatformSuperAdmin(it) }

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, emailOf(id))
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun emailOf(userId: UUID) = "onboarding-$userId@example.test"

        private fun emailOf(label: String) = "$label-$runId@onboarding.test"

        private fun usernameOf(label: String) = "$label-$runId"

        private fun platformPost(
            path: String,
            actor: UUID,
            body: String? = null,
        ): ResultActionsDsl = post(path, token(actor, PlatformOrganisation.ID), body)

        private fun tenantPost(
            tenantId: UUID,
            actor: UUID,
            path: String,
            body: String? = null,
        ): ResultActionsDsl = post(path, token(actor, tenantId), body)

        private fun post(
            path: String,
            token: AppPrincipalAuthenticationToken,
            body: String?,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = it
                }
                with(authentication(token))
            }

        private fun idOf(
            body: String,
            field: String,
        ): UUID =
            UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get(field)
                    .asString(),
            )

        private fun shortId(): String = uuidV7().toString().takeLast(8)

        private fun token(
            userId: UUID,
            organisationId: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "it-user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "it@example.test",
                fullName = "Integration User",
                permissions = COARSE_AUTHORITIES,
            ),
        )

        private companion object {
            /** The maker rule's refusal; a missing permission would name the code instead. */
            const val MAKER_REFUSAL = "You are not permitted to perform this action."

            /**
             * Every coarse authority a route here needs. The coarse gate only filters; the
             * application layer re-checks the caller's real permissions in the database.
             */
            val COARSE_AUTHORITIES =
                setOf(
                    "tenant.create",
                    "tenant.submit_for_approval",
                    "tenant.approve",
                    "tenant.view",
                    "user.invite",
                    "user.approve",
                    "user.view",
                    "membership.view",
                )
        }
    }
