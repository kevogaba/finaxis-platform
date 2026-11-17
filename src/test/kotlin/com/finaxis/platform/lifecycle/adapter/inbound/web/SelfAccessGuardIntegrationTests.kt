package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Full-stack proof of the self-access guard of #341 (ADR 0043). A caller can't suspend or revoke
 * their own membership, nor suspend or deactivate their own account: each is a 403 with its own
 * code, changes nothing, and leaves one `DENIED` audit row and no `api.access_denied` row. Acting
 * on someone else is unchanged, and an unknown membership is still a 404.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SelfAccessGuardIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("self-guard", admin)
        private val platformAdmin =
            seedUser("platform-admin").also(fixture::grantPlatformSuperAdmin)
        private val reason = """{"reason":"Testing the self-access guard."}"""

        @Test
        fun `an administrator cannot suspend or revoke their own membership`() {
            val own = membershipId(admin)

            tenantPost("${ApiPaths.MEMBERSHIPS}/$own/suspend", "membership.suspend").andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("lifecycle.self_membership_change") }
            }
            tenantPost("${ApiPaths.MEMBERSHIPS}/$own/revoke", "membership.revoke").andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("lifecycle.self_membership_change") }
            }

            assertEquals("ACTIVE", membershipStatus(own))
            assertEquals(2, deniedRows(admin, "membership.suspend", "membership.revoke"))
            assertEquals(0, accessDeniedRows(admin))
        }

        @Test
        fun `another member can still be suspended`() {
            val member = seedUser("member")
            fixture.grantTenantAdmin(organisationId, member)

            tenantPost(
                "${ApiPaths.MEMBERSHIPS}/${membershipId(member)}/suspend",
                "membership.suspend",
            ).andExpect { status { isOk() } }

            assertEquals("SUSPENDED", membershipStatus(membershipId(member)))
        }

        @Test
        fun `an unknown membership is still not found`() {
            tenantPost("${ApiPaths.MEMBERSHIPS}/${uuidV7()}/suspend", "membership.suspend")
                .andExpect { status { isNotFound() } }
        }

        @Test
        fun `a platform administrator cannot suspend or deactivate their own account`() {
            platformPost("${ApiPaths.PLATFORM_USERS}/$platformAdmin/suspend", "user.suspend")
                .andExpect {
                    status { isForbidden() }
                    jsonPath("$.code") { value("lifecycle.self_account_change") }
                }
            platformPost("${ApiPaths.PLATFORM_USERS}/$platformAdmin/deactivate", "user.deactivate")
                .andExpect {
                    status { isForbidden() }
                    jsonPath("$.code") { value("lifecycle.self_account_change") }
                }

            assertEquals("ACTIVE", accountStatus(platformAdmin))
            assertEquals(
                2,
                deniedRows(
                    platformAdmin,
                    "user_account.suspend",
                    "user_account.start_deactivation",
                ),
            )
            assertEquals(0, accessDeniedRows(platformAdmin))
        }

        @Test
        fun `another account can still be suspended`() {
            val other = seedUser("other")
            linkKeycloakIdentity(other)

            platformPost("${ApiPaths.PLATFORM_USERS}/$other/suspend", "user.suspend")
                .andExpect { status { isOk() } }

            assertEquals("SUSPENDED", accountStatus(other))
        }

        private fun tenantPost(
            path: String,
            permission: String,
        ): ResultActionsDsl = post(path, token(admin, organisationId, permission))

        private fun platformPost(
            path: String,
            permission: String,
        ): ResultActionsDsl = post(path, token(platformAdmin, PlatformOrganisation.ID, permission))

        private fun post(
            path: String,
            token: AppPrincipalAuthenticationToken,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = reason
                with(authentication(token))
            }

        private fun membershipId(userId: UUID): UUID =
            requireNotNull(
                dsl
                    .select(USER_ORGANISATION_MEMBERSHIP.ID)
                    .from(USER_ORGANISATION_MEMBERSHIP)
                    .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                    .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
                    .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID),
            )

        private fun membershipStatus(membershipId: UUID): String =
            dsl
                .fetchValue(
                    "SELECT membership_status FROM user_organisation_membership WHERE id = ?",
                    membershipId,
                ).toString()

        private fun accountStatus(userId: UUID): String =
            dsl.fetchValue("SELECT status FROM user_account WHERE id = ?", userId).toString()

        private fun deniedRows(
            subject: UUID,
            vararg actions: String,
        ): Int =
            actions.sumOf { action ->
                dsl
                    .fetchValue(
                        "SELECT count(*) FROM audit_event WHERE outcome = 'DENIED' " +
                            "AND action = ? AND subject_user_id = ?",
                        action,
                        subject,
                    ).toString()
                    .toInt()
            }

        private fun accessDeniedRows(actor: UUID): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM audit_event WHERE action = 'api.access_denied' " +
                        "AND actor_user_id = ?",
                    actor,
                ).toString()
                .toInt()

        private fun linkKeycloakIdentity(userId: UUID) {
            val now = OffsetDateTime.now()
            dsl
                .insertInto(KEYCLOAK_IDENTITY_LINK)
                .set(KEYCLOAK_IDENTITY_LINK.USER_ID, userId)
                .set(KEYCLOAK_IDENTITY_LINK.SUBJECT, "subject-$userId")
                .set(KEYCLOAK_IDENTITY_LINK.LINKED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.CREATED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.UPDATED_AT, now)
                .execute()
        }

        private fun token(
            userId: UUID,
            tenantId: UUID,
            permission: String,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = null,
                email = "user@self-guard.test",
                fullName = "Self Guard User",
                permissions = setOf(permission),
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@self-guard.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }
    }
