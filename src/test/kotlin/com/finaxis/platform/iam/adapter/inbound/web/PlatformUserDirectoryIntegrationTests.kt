package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.hamcrest.Matchers.containsInAnyOrder
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `GET /api/v1/platform/users` and `/{user_id}` (#160): user accounts across organisations for a
 * platform caller holding `user.view`, and nobody else.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PlatformUserDirectoryIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisations: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisations, dsl)

        @Test
        fun `a platform administrator finds a user in every tenant and reads the account`() {
            val admin = seedUser("directory-admin")
            fixture.grantPlatformSuperAdmin(admin)
            val member = seedUser("shared-member")
            val tenantA = fixture.createActiveOrganisation("dir-a", member)
            val tenantB = fixture.createActiveOrganisation("dir-b", member)
            val token = platformToken(admin)

            mockMvc
                .get(ApiPaths.PLATFORM_USERS) {
                    param("q", "shared-member")
                    with(authentication(token))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items.length()") { value(1) }
                    jsonPath("$.items[0].id") { value(member.toString()) }
                    jsonPath("$.items[0].membership_count") { value(2) }
                    jsonPath("$.page.total_items") { value(1) }
                }
            mockMvc
                .get("${ApiPaths.PLATFORM_USERS}/$member") { with(authentication(token)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.membership_count") { value(2) }
                    jsonPath("$.memberships.length()") { value(2) }
                    jsonPath("$.memberships[*].organisation_id") {
                        value(containsInAnyOrder(tenantA.toString(), tenantB.toString()))
                    }
                }
        }

        @Test
        fun `the directory is refused without user view and in a tenant context`() {
            val user = seedUser("directory-denied")
            val tenant = fixture.createActiveOrganisation("dir-denied", user)

            mockMvc
                .get(ApiPaths.PLATFORM_USERS) { with(authentication(platformToken(user, null))) }
                .andExpect { status { isForbidden() } }
            // The authority is claimed but the user holds no platform grant: the service refuses.
            mockMvc
                .get(ApiPaths.PLATFORM_USERS) { with(authentication(platformToken(user))) }
                .andExpect { status { isForbidden() } }
            mockMvc
                .get("${ApiPaths.PLATFORM_USERS}/${uuidV7()}") {
                    with(authentication(platformToken(user)))
                }.andExpect { status { isForbidden() } }
            mockMvc
                .get(ApiPaths.PLATFORM_USERS) {
                    with(authentication(principal(user, tenant, "user.view")))
                }.andExpect { status { isForbidden() } }
        }

        @Test
        fun `an unknown account is not found and a bad sort is a 400`() {
            val admin = seedUser("directory-misc")
            fixture.grantPlatformSuperAdmin(admin)
            val token = platformToken(admin)

            mockMvc
                .get("${ApiPaths.PLATFORM_USERS}/${uuidV7()}") { with(authentication(token)) }
                .andExpect { status { isNotFound() } }
            mockMvc
                .get(ApiPaths.PLATFORM_USERS) {
                    param("sort_by", "membershipCount")
                    with(authentication(token))
                }.andExpect { status { isBadRequest() } }
        }

        @Test
        fun `the platform organisation is left out of the tenant list unless asked for`() {
            val admin = seedUser("tenant-list-admin")
            fixture.grantPlatformSuperAdmin(admin)
            val token = platformToken(admin, "tenant.view")

            mockMvc
                .get(ApiPaths.PLATFORM_TENANTS) {
                    param("q", "PLATFORM")
                    with(authentication(token))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items.length()") { value(0) }
                    jsonPath("$.page.total_items") { value(0) }
                }
            mockMvc
                .get(ApiPaths.PLATFORM_TENANTS) {
                    param("q", "PLATFORM")
                    param("include_platform", "true")
                    with(authentication(token))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(PlatformOrganisation.ID.toString()) }
                    jsonPath("$.page.total_items") { value(1) }
                }
            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/${PlatformOrganisation.ID}") {
                    with(authentication(token))
                }.andExpect { status { isOk() } }
        }

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@directory.test")
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
            permission: String? = "user.view",
        ) = principal(userId, PlatformOrganisation.ID, permission)

        private fun principal(
            userId: UUID,
            organisationId: UUID,
            permission: String?,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "user@directory.test",
                fullName = "Directory User",
                permissions = setOfNotNull(permission),
            ),
        )
    }
