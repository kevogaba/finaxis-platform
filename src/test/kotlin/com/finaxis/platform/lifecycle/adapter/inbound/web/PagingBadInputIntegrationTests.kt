package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
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
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Full-stack proof that bad paging and sorting input on every list endpoint family answers a 400
 * `invalid_parameter` problem and never a 500 `internal_error`, while valid requests are
 * unchanged.
 *
 * `sort_by` and `sort_dir` are the parameters that used to escape: an unchecked `require` in the
 * query services surfaced `?sort_by=bogus` as an unmapped `IllegalArgumentException`. Negative and
 * oversized `page` and `size` are screened earlier, by the pagination interceptor, and are pinned
 * here for every family so neither layer can regress alone.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PagingBadInputIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
        private val apiJsonCodec: ApiJsonCodec,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val platformAdmin = seedUser("platform-admin")
        private val organisationId = fixture.createActiveOrganisation("paging-input", admin)
        private val allPermissions: Set<String> by lazy {
            dsl
                .fetch("SELECT permission_code FROM permission")
                .map { it.get(0, String::class.java) }
                .toSet()
        }

        init {
            fixture.grantPlatformSuperAdmin(platformAdmin)
        }

        // -- families that validate sort_by / sort_dir ------------------------------------------

        @Test
        fun `roles reject bad sort and paging with 400 and still list valid requests`() {
            family(Endpoint(ApiPaths.ROLES), sortField = "roleCode")
        }

        @Test
        fun `permissions reject bad sort and paging with 400 and still list valid requests`() {
            family(Endpoint(ApiPaths.PERMISSIONS), sortField = "permissionCode")
        }

        @Test
        fun `tenant branches reject bad sort and paging with 400 and still list valid requests`() {
            family(Endpoint(ApiPaths.BRANCHES), sortField = "branchCode")
        }

        @Test
        fun `platform tenant branches reject bad sort and paging with 400`() {
            family(
                Endpoint("${ApiPaths.PLATFORM_TENANTS}/$organisationId/branches", platform = true),
                sortField = "branchCode",
            )
        }

        @Test
        fun `platform tenants reject bad sort and paging with 400 and still list valid requests`() {
            family(Endpoint(ApiPaths.PLATFORM_TENANTS, platform = true), sortField = "tenantCode")
        }

        // -- families that take paging only (sort_by / sort_dir are accepted and ignored) ------

        @Test
        fun `memberships reject bad paging with 400 and still list valid requests`() {
            family(Endpoint(ApiPaths.MEMBERSHIPS), sortField = null)
        }

        @Test
        fun `branch assignments reject bad paging with 400 and still list valid requests`() {
            family(Endpoint(ApiPaths.BRANCH_ASSIGNMENTS), sortField = null)
        }

        @Test
        fun `role assignments reject bad paging with 400 and still list valid requests`() {
            family(Endpoint(ApiPaths.ROLE_ASSIGNMENTS), sortField = null)
        }

        @Test
        fun `role permissions reject bad paging with 400 and still list valid requests`() {
            family(Endpoint("${ApiPaths.ROLES}/${adminRoleId()}/permissions"), sortField = null)
        }

        @Test
        fun `tenant users reject bad paging with 400 and still list valid requests`() {
            family(Endpoint(ApiPaths.TENANT_USERS), sortField = null)
        }

        @Test
        fun `platform users reject bad paging with 400 and still list valid requests`() {
            family(
                Endpoint("${ApiPaths.PLATFORM_TENANTS}/$organisationId/users", platform = true),
                sortField = null,
            )
        }

        @Test
        fun `audit events reject bad paging and sort direction with 400`() {
            // The audit searches have no sort_by but do honour sort_dir (on event time, #183).
            listOf(
                Endpoint(ApiPaths.AUDIT_EVENTS),
                Endpoint(ApiPaths.PLATFORM_AUDIT_EVENTS, platform = true),
                Endpoint("${ApiPaths.PLATFORM_TENANTS}/$organisationId/audit-events", true),
            ).forEach { endpoint ->
                family(endpoint, sortField = null)
                assertBadRequest(endpoint, "sort_dir=sideways", "sort_dir")
                get(endpoint, "sort_dir=asc&page=0&size=1").andExpect { status { isOk() } }
            }
        }

        @Test
        fun `settings and business date history reject bad paging with 400`() {
            family(Endpoint(ApiPaths.TENANT_SETTINGS), sortField = null)
            family(Endpoint("${ApiPaths.BUSINESS_DATE}/history"), sortField = null)
        }

        @Test
        fun `auth selection lists reject bad paging with 400`() {
            listOf("${ApiPaths.AUTH}/organisations", "${ApiPaths.AUTH}/branches").forEach { path ->
                PAGING_FAILURES.forEach { query -> assertBadRequest(Endpoint(path), query) }
            }
        }

        @Test
        fun `auth organisation selection still lists valid requests`() {
            linkKeycloakIdentity(admin)
            val endpoint = Endpoint("${ApiPaths.AUTH}/organisations")

            listOf("", "page=0&size=1", "page=0&size=100").forEach { query ->
                get(endpoint, query).andExpect {
                    status { isOk() }
                    jsonPath("$.page.number") { value(0) }
                }
            }
        }

        @Test
        fun `every sort_by value the OpenAPI document publishes is accepted by its endpoint`() {
            val body =
                mockMvc
                    .get("/v3/api-docs")
                    .andReturn()
                    .response.contentAsString
            val paths = apiJsonCodec.mapper.readTree(body).path("paths")
            var checked = 0
            paths.properties().forEach { (path, item) ->
                val sortBy =
                    item
                        .path("get")
                        .path("parameters")
                        .firstOrNull {
                            it.path("name").asString() == "sort_by" &&
                                it.path("in").asString() == "query"
                        } ?: return@forEach
                val published =
                    sortBy
                        .path("schema")
                        .path("enum")
                        .toList()
                        .map { it.asString() }
                assertThat(published).describedAs("GET %s published sort_by", path).isNotEmpty
                val endpoint =
                    Endpoint(
                        path.replace("{tenant_id}", organisationId.toString()),
                        platform = path.startsWith(ApiPaths.PLATFORM),
                    )
                published.forEach { value ->
                    listOf("ASC", "DESC").forEach { direction ->
                        get(endpoint, "sort_by=$value&sort_dir=$direction&size=1").andExpect {
                            status { isOk() }
                        }
                    }
                }
                checked++
            }
            // roles, permissions, platform tenants, branches, platform tenant branches
            assertThat(checked).isGreaterThanOrEqualTo(MINIMUM_SORTING_ENDPOINTS)
        }

        @Test
        fun `a bad value anywhere in a repeated paging parameter is a 400`() {
            listOf("page=1&page=-1", "page=-1&page=1", "size=10&size=101", "size=101&size=10")
                .forEach { assertBadRequest(Endpoint(ApiPaths.ROLES), it) }
        }

        @Test
        fun `a bad sort direction is a 400 even when the sort field is valid or absent`() {
            assertBadRequest(Endpoint(ApiPaths.ROLES), "sort_dir=sideways", "sort_dir")
            assertBadRequest(Endpoint(ApiPaths.ROLES), "sort_by=roleCode&sort_dir=", "sort_dir")
        }

        @Test
        fun `a bad sort field does not echo the rejected value`() {
            get(Endpoint(ApiPaths.ROLES), "sort_by=zzz-secret-zzz").andExpect {
                status { isBadRequest() }
                content { string(not(containsString("zzz-secret-zzz"))) }
            }
        }

        // -- helpers -----------------------------------------------------------------------------

        private fun family(
            endpoint: Endpoint,
            sortField: String?,
        ) {
            PAGING_FAILURES.forEach { query -> assertBadRequest(endpoint, query) }
            if (sortField != null) {
                assertBadRequest(endpoint, "sort_by=bogus", "sort_by")
                assertBadRequest(endpoint, "sort_by=$sortField&sort_dir=sideways", "sort_dir")
                // Valid sorting and the paging bounds themselves are unchanged.
                get(endpoint, "sort_by=$sortField&sort_dir=desc&page=0&size=1").andExpect {
                    status { isOk() }
                }
                get(endpoint, "sort_by=$sortField&sort_dir=ASC").andExpect { status { isOk() } }
            }
            get(endpoint, "").andExpect { status { isOk() } }
            get(endpoint, "page=0&size=1").andExpect { status { isOk() } }
            get(endpoint, "page=0&size=100").andExpect { status { isOk() } }
        }

        private fun assertBadRequest(
            endpoint: Endpoint,
            query: String,
            violationField: String? = null,
        ) {
            get(endpoint, query).andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.status") { value(400) }
                jsonPath("$.code") { value("invalid_parameter") }
                jsonPath("$.instance") { value(endpoint.path) }
                if (violationField != null) {
                    jsonPath("$.violations[0].field") { value(violationField) }
                    jsonPath("$.violations.length()") { value(1) }
                } else {
                    // Paging problems carry no violation, as documented.
                    jsonPath("$.violations") { doesNotExist() }
                }
            }
        }

        private fun get(
            endpoint: Endpoint,
            query: String,
        ): ResultActionsDsl =
            mockMvc.get(if (query.isEmpty()) endpoint.path else "${endpoint.path}?$query") {
                with(authentication(token(endpoint.platform)))
            }

        private fun token(platform: Boolean) =
            if (platform) {
                token(platformAdmin, PlatformOrganisation.ID)
            } else {
                token(admin, organisationId)
            }

        private fun token(
            userId: UUID,
            tenantId: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = null,
                email = "user@paging-input.test",
                fullName = "Paging Input User",
                permissions = allPermissions,
            ),
        )

        // The auth selection routes resolve the caller from the Keycloak identity link.
        private fun linkKeycloakIdentity(userId: UUID) {
            val now = OffsetDateTime.now()
            dsl
                .insertInto(KEYCLOAK_IDENTITY_LINK)
                .set(KEYCLOAK_IDENTITY_LINK.USER_ID, userId)
                .set(KEYCLOAK_IDENTITY_LINK.SUBJECT, "user-$userId")
                .set(KEYCLOAK_IDENTITY_LINK.LINKED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.CREATED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.UPDATED_AT, now)
                .execute()
        }

        private fun adminRoleId(): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM role WHERE organisation_id = ? AND role_code = 'TENANT_ADMIN'",
                    organisationId,
                )!!
                .get(0, UUID::class.java)

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@paging-input.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private data class Endpoint(
            val path: String,
            val platform: Boolean = false,
        )

        private companion object {
            const val MINIMUM_SORTING_ENDPOINTS = 5
            val PAGING_FAILURES =
                listOf("page=-1", "page=abc", "size=0", "size=101", "size=-5", "size=abc")
        }
    }
