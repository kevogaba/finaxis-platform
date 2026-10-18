package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * `PATCH /platform/users/{id}` and `PATCH /memberships/{id}` (#161), through the real stack:
 * permission first, validation, the stored result, one audit row, and the platform-only profile
 * fields on the directory detail.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfileUpdateIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisations: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisations, dsl)

        @Test
        fun `a platform administrator edits a profile and the directory shows phone and login`() {
            val admin = seedUser("profile-admin")
            fixture.grantPlatformSuperAdmin(admin)
            val target = seedUser("profile-target")
            val token = platformToken(admin, "user.update", "user.view")

            val edit = """{"display_name":"Jane Wanjiru","phone_e164":"+254700000009"}"""
            patchUser(target, edit, token)
                .andExpect {
                    status { isOk() }
                    jsonPath("$.display_name") { value("Jane Wanjiru") }
                    jsonPath("$.phone_e164") { value("+254700000009") }
                    jsonPath("$.last_login_at") { value(null as Any?) }
                }
            mockMvc
                .get("${ApiPaths.PLATFORM_USERS}/$target") {
                    with(authentication(platformToken(admin, "user.view")))
                }.andExpect { jsonPath("$.phone_e164") { value("+254700000009") } }

            assertEquals(1, auditRows("user.update", target))
            patchUser(target, "{}", token).andExpect { status { isUnprocessableContent() } }
            patchUser(target, """{"phone_e164":"0700"}""", token)
                .andExpect { status { isBadRequest() } }
            patchUser(uuidV7(), """{"display_name":"Nobody"}""", token)
                .andExpect { status { isNotFound() } }
            assertEquals(1, auditRows("user.update", target), "a refusal audits nothing")
        }

        @Test
        fun `a deactivated account and a one-character trimmed name are refused`() {
            val admin = seedUser("profile-terminal")
            val target = seedUser("profile-terminal-target")
            fixture.grantPlatformSuperAdmin(admin)
            val token = platformToken(admin, "user.update", "user.view")

            patchUser(target, """{"display_name":" A "}""", token)
                .andExpect { status { isUnprocessableContent() } }
            dsl
                .update(USER_ACCOUNT)
                .set(USER_ACCOUNT.STATUS, "DEACTIVATED")
                .where(USER_ACCOUNT.ID.eq(target))
                .execute()
            patchUser(target, """{"display_name":"Jane Doe"}""", token)
                .andExpect { status { isUnprocessableContent() } }
            assertEquals(
                "profile-terminal-target",
                dsl
                    .select(USER_ACCOUNT.DISPLAY_NAME)
                    .from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.ID.eq(target))
                    .fetchOne(USER_ACCOUNT.DISPLAY_NAME),
            )
        }

        @Test
        fun `the user edit is refused without user update or without its view`() {
            val admin = seedUser("profile-denied")
            val target = seedUser("profile-denied-target")

            patchUser(target, """{"display_name":"X Y"}""", platformToken(admin, "user.view"))
                .andExpect { status { isForbidden() } }
            fixture.grantPlatformPermissionsExactly(admin, "user.update")
            patchUser(target, """{"display_name":"X Y"}""", platformToken(admin, "user.update"))
                .andExpect {
                    status { isForbidden() }
                    jsonPath("$.detail") { value("Missing permission: user.view.") }
                }
        }

        @Test
        fun `a tenant administrator edits a membership and a primary branch must be active`() {
            val member = seedUser("member-edit")
            val tenant = fixture.createActiveOrganisation("profile-membership", member)
            val membershipId = membershipOf(tenant, member)
            val token = tenantToken(member, tenant, "membership.update", "membership.view")

            patchMembership(membershipId, """{"membership_type":"AUDITOR"}""", token).andExpect {
                status { isOk() }
                jsonPath("$.membership_type") { value("AUDITOR") }
            }
            patchMembership(membershipId, """{"membership_type":"SYSTEM"}""", token)
                .andExpect { status { isUnprocessableContent() } }
            patchMembership(membershipId, """{"primary_branch_id":"${uuidV7()}"}""", token)
                .andExpect { status { isUnprocessableContent() } }
            patchMembership(membershipId, """{"primary_branch_id":null}""", token)
                .andExpect { status { isOk() } }
            patchMembership(membershipId, "{}", token)
                .andExpect { status { isUnprocessableContent() } }
            patchMembership(uuidV7(), """{"membership_type":"STAFF"}""", token)
                .andExpect { status { isNotFound() } }
            assertEquals(2, auditRows("membership.update", member))
        }

        @Test
        fun `an active auditor with no branch assignment cannot be made staff or admin`() {
            val member = seedUser("auditor-promote")
            val tenant = fixture.createActiveOrganisation("profile-promote", member)
            val membershipId = membershipOf(tenant, member)
            val token = tenantToken(member, tenant, "membership.update", "membership.view")
            dsl
                .deleteFrom(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.USER_ID.eq(member))
                .execute()

            patchMembership(membershipId, """{"membership_type":"AUDITOR"}""", token)
                .andExpect { status { isOk() } }
            patchMembership(membershipId, """{"membership_type":"ADMIN"}""", token)
                .andExpect { status { isConflict() } }
            patchMembership(membershipId, """{"membership_type":"STAFF"}""", token)
                .andExpect { status { isConflict() } }
        }

        @Test
        fun `the membership edit is refused without membership update`() {
            val member = seedUser("member-denied")
            val tenant =
                fixture.createActiveOrganisationWithMember(
                    "profile-denied",
                    member,
                    "membership.view",
                )
            val membershipId = membershipOf(tenant, member)

            patchMembership(
                membershipId,
                """{"membership_type":"ADMIN"}""",
                tenantToken(member, tenant, "membership.view"),
            ).andExpect { status { isForbidden() } }
            patchMembership(
                membershipId,
                """{"membership_type":"ADMIN"}""",
                tenantToken(member, tenant, "membership.update"),
            ).andExpect { status { isForbidden() } }
        }

        private fun patchUser(
            user: UUID,
            body: String,
            token: AppPrincipalAuthenticationToken,
        ): ResultActionsDsl = patch("${ApiPaths.PLATFORM_USERS}/$user", body, token)

        private fun patchMembership(
            membership: UUID,
            body: String,
            token: AppPrincipalAuthenticationToken,
        ): ResultActionsDsl = patch("${ApiPaths.MEMBERSHIPS}/$membership", body, token)

        private fun patch(
            path: String,
            body: String,
            token: AppPrincipalAuthenticationToken,
        ): ResultActionsDsl =
            mockMvc.patch(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token))
            }

        private fun membershipOf(
            tenant: UUID,
            user: UUID,
        ): UUID =
            requireNotNull(
                dsl
                    .select(USER_ORGANISATION_MEMBERSHIP.ID)
                    .from(USER_ORGANISATION_MEMBERSHIP)
                    .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(tenant))
                    .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(user))
                    .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID),
            )

        private fun auditRows(
            action: String,
            subject: UUID,
        ): Int =
            dsl
                .selectCount()
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ACTION.eq(action))
                .and(AUDIT_EVENT.SUBJECT_USER_ID.eq(subject))
                .and(AUDIT_EVENT.OUTCOME.eq("SUCCESS"))
                .fetchOne(0, Int::class.java) ?: 0

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@profile.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun platformToken(
            userId: UUID,
            vararg permissions: String,
        ) = principal(userId, PlatformOrganisation.ID, *permissions)

        private fun tenantToken(
            userId: UUID,
            tenant: UUID,
            vararg permissions: String,
        ) = principal(userId, tenant, *permissions)

        private fun principal(
            userId: UUID,
            organisationId: UUID,
            vararg permissions: String,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "user@profile.test",
                fullName = "Profile User",
                permissions = permissions.toSet(),
            ),
        )
    }
