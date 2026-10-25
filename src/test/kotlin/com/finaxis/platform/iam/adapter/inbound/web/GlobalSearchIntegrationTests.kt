package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.ratelimit.RateLimitIdentity
import com.finaxis.platform.common.web.ratelimit.RateLimitKeyResolver
import com.finaxis.platform.common.web.ratelimit.RateLimitPolicyId
import com.finaxis.platform.common.web.ratelimit.RateLimitProperties
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ROLE
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
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Full-stack proof of the tenant global search (#167, ADR 0039): one bounded page per type, a type
 * the caller cannot view omitted, branches filtered by branch visibility, `q` validated first and
 * matched literally, the active organisation the only tenant ever read, and no audit row for a
 * search that is answered.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("LargeClass", "TooManyFunctions")
class GlobalSearchIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val rateLimitProperties: RateLimitProperties,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val mapper = ObjectMapper()
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("gs", admin)

        @Test
        fun `each type returns its match with exactly the fields of the decision`() {
            val tag = tag()
            val member = seedMember("Alice $tag", "alice-$tag", "alice-$tag@seed.test")
            val branch = seedBranch("BR-$tag", "Branch $tag")
            val role = seedRole("ROLE-$tag", "Role $tag")

            val body = search(tag)

            assertEquals(setOf("users", "branches", "roles", "memberships"), body.keys())
            val user = body["users"].single()
            assertEquals(
                mapOf(
                    "id" to member.userId.toString(),
                    "label" to "Alice $tag",
                    "status" to "ACTIVE",
                    "email" to "alice-$tag@seed.test",
                ),
                user.asMap(),
            )
            assertEquals(
                mapOf(
                    "id" to branch.toString(),
                    "label" to "Branch $tag",
                    "status" to "ACTIVE",
                    "branch_code" to "BR-$tag",
                ),
                body["branches"].single().asMap(),
            )
            assertEquals(
                mapOf(
                    "id" to role.toString(),
                    "label" to "Role $tag",
                    "status" to "ACTIVE",
                    "role_code" to "ROLE-$tag",
                ),
                body["roles"].single().asMap(),
            )
            assertEquals(
                mapOf(
                    "id" to member.membershipId.toString(),
                    "label" to "Alice $tag",
                    "status" to "ACTIVE",
                    "membership_type" to "STAFF",
                ),
                body["memberships"].single().asMap(),
            )
            assertTrue(member.userId != member.membershipId, "two hits, two different ids")
        }

        @Test
        fun `matching uses the columns of each per resource filter, ignoring case`() {
            val tag = tag()
            // Matches on the user name alone, on the email alone, and on the display name alone.
            val byUsername = seedMember("Plain One", "uname-$tag", "one-${uuidV7()}@seed.test")
            val byEmail = seedMember("Plain Two", "two-${uuidV7()}", "mail-$tag@seed.test")
            val byDisplay = seedMember("Display $tag", "three-${uuidV7()}", "x-${uuidV7()}@s.test")
            // Branch and role: code alone, name alone.
            val branchByCode = seedBranch("CODE-$tag", "Plain branch")
            val branchByName = seedBranch("PLAIN-${uuidV7()}", "Named $tag")
            val roleByCode = seedRole("RCODE-$tag", "Plain role")
            val roleByName = seedRole("PLAIN_${uuidV7()}", "Named role $tag")

            val body = search(tag.uppercase())

            val users = body["users"].ids()
            assertEquals(
                setOf(byUsername.userId, byEmail.userId, byDisplay.userId)
                    .map { it.toString() }
                    .toSet(),
                users,
            )
            assertEquals(
                setOf(byUsername.membershipId, byEmail.membershipId, byDisplay.membershipId)
                    .map { it.toString() }
                    .toSet(),
                body["memberships"].ids(),
            )
            assertEquals(
                setOf(branchByCode, branchByName).map { it.toString() }.toSet(),
                body["branches"].ids(),
            )
            assertEquals(
                setOf(roleByCode, roleByName).map { it.toString() }.toSet(),
                body["roles"].ids(),
            )
        }

        @Test
        fun `a type never returns more than ten hits and the response carries no counts`() {
            val tag = tag()
            repeat(12) { index ->
                seedMember("Bulk $index $tag", "bulk-$index-$tag", "bulk-$index-$tag@seed.test")
                seedBranch("B$index-$tag", "Bulk branch $index $tag")
                seedRole("R$index-$tag", "Bulk role $index $tag")
            }

            val raw = searchRaw(tag)
            val body = mapper.readTree(raw)

            assertEquals(setOf("users", "branches", "roles", "memberships"), body.keys())
            listOf("users", "branches", "roles", "memberships").forEach {
                assertEquals(10, body[it].size(), "$it is capped at ten hits")
            }
            listOf("total", "count", "has_more", "page", "next").forEach {
                assertFalse(it in raw, "no $it anywhere in the response")
            }
        }

        @Test
        fun `a caller without a type's view code gets no key for it`() {
            val tag = tag()
            seedMember("Carol $tag", "carol-$tag", "carol-$tag@seed.test")
            seedBranch("BR-$tag", "Branch $tag")
            seedRole("ROLE-$tag", "Role $tag")
            val caller = seedUser("viewer")
            fixture.grantTenantPermissionsWithViews(
                organisationId,
                caller,
                "user.view",
                "role.view",
            )

            val body = search(tag, caller)

            assertEquals(setOf("users", "roles"), body.keys())
            assertEquals(1, body["users"].size())
            assertEquals(1, body["roles"].size())
        }

        @Test
        fun `a viewable type with no match is an empty array and not an omitted one`() {
            val caller = seedUser("viewer")
            fixture.grantTenantPermissionsWithViews(organisationId, caller, "membership.view")

            val body = search("no-such-${tag()}", caller)

            assertEquals(setOf("memberships"), body.keys())
            assertEquals(0, body["memberships"].size())
        }

        @Test
        fun `a caller who may view no type gets the named 403 and one denial row`() {
            val caller = seedUser("nobody")
            fixture.grantTenantPermissionsExactly(organisationId, caller, "audit.view")
            val before = deniedRows(caller)

            get("/api/v1/tenant/search?q=anything", caller).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
                jsonPath("$.detail") { value("Missing permission: user.view.") }
            }

            assertEquals(before + 1, deniedRows(caller))
        }

        @Test
        fun `a partly refused search writes no denial row for the omitted types`() {
            val caller = seedUser("partial")
            fixture.grantTenantPermissionsExactly(organisationId, caller, "role.view")
            val before = deniedRows(caller)

            val body = search("admin", caller)

            assertEquals(setOf("roles"), body.keys())
            assertEquals(before, deniedRows(caller))
        }

        @Test
        fun `a branch scoped caller sees only the branches it holds, other types unaffected`() {
            val tag = tag()
            val visible = seedBranch("VIS-$tag", "Visible $tag")
            seedBranch("HID-$tag", "Hidden $tag")
            val member = seedMember("Dana $tag", "dana-$tag", "dana-$tag@seed.test")
            val caller = seedUser("scoped")
            fixture.grantTenantPermissionsExactly(organisationId, caller, "user.view")
            fixture.grantBranchPermissionsExactly(organisationId, visible, caller, "branch.view")

            val body = search(tag, caller)

            assertEquals(setOf("users", "branches"), body.keys())
            assertEquals(setOf(visible.toString()), body["branches"].ids())
            assertEquals(setOf(member.userId.toString()), body["users"].ids())
        }

        @Test
        fun `a branch scoped caller whose only branch is no longer active has no branches key`() {
            val tag = tag()
            val branch = seedBranch("SUS-$tag", "Suspended $tag")
            val caller = seedUser("scoped-suspended")
            fixture.grantTenantPermissionsExactly(organisationId, caller, "user.view")
            fixture.grantBranchPermissionsExactly(organisationId, branch, caller, "branch.view")
            dsl.execute("UPDATE branch SET status = 'SUSPENDED' WHERE id = ?", branch)

            val body = search(tag, caller)

            assertEquals(setOf("users"), body.keys())
        }

        @Test
        fun `a caller holding branch view nowhere has no branches key and not a 403`() {
            val tag = tag()
            seedBranch("NOW-$tag", "Nowhere $tag")
            val caller = seedUser("no-branch-grant")
            fixture.grantTenantPermissionsExactly(organisationId, caller, "user.view")

            val body = search(tag, caller)

            assertFalse("branches" in body.keys())
            assertTrue("users" in body.keys())
        }

        @Test
        fun `percent and underscore in q match only themselves`() {
            val tag = tag()
            val literal = seedMember("Rate 100% $tag", "pct-$tag", "pct-$tag@seed.test")
            seedMember("Rate 1000 $tag", "thousand-$tag", "thousand-$tag@seed.test")
            val underscore = seedBranch("SNAKE_$tag", "Snake $tag")
            seedBranch("SNAKEX$tag", "Snakex $tag")
            val role = seedRole("ROLE_%_$tag", "Odd $tag")
            seedRole("ROLEXYZ$tag", "Odd two $tag")

            val percent = search("100% $tag")
            assertEquals(setOf(literal.userId.toString()), percent["users"].ids())
            assertEquals(setOf(literal.membershipId.toString()), percent["memberships"].ids())

            val underscored = search("SNAKE_$tag")
            assertEquals(setOf(underscore.toString()), underscored["branches"].ids())

            val both = search("ROLE_%_$tag")
            assertEquals(setOf(role.toString()), both["roles"].ids())

            // A lone wildcard is two characters of text, not "everything".
            assertEquals(0, search("%%").size("users"))
            assertEquals(0, search("__").size("users"))
        }

        @Test
        fun `a backslash in q matches a backslash`() {
            val tag = tag()
            val member = seedMember("Back\\slash $tag", "back-$tag", "back-$tag@seed.test")

            val body = search("Back\\slash $tag")

            assertEquals(setOf(member.userId.toString()), body["users"].ids())
        }

        @Test
        fun `a missing blank or out of range q is a 400 naming q`() {
            listOf(null, "", "a", "   ", " a ", "\t\n", "x".repeat(65)).forEach { q ->
                mockMvc
                    .get(ApiPaths.SEARCH) {
                        q?.let { param("q", it) }
                        with(authentication(token(admin)))
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("invalid_parameter") }
                        jsonPath("$.violations[0].field") { value("q") }
                    }
            }
        }

        @Test
        fun `a control character in q is a 400 naming q`() {
            listOf("ab\u0000cd", "ab\u0001cd", "ab\ncd", "ab\tcd", "\u007fab").forEach { q ->
                mockMvc
                    .get(ApiPaths.SEARCH) {
                        param("q", q)
                        with(authentication(token(admin)))
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("invalid_parameter") }
                        jsonPath("$.violations[0].field") { value("q") }
                    }
            }
        }

        @Test
        fun `a percent encoded spelling of the route reaches the search handler`() {
            val tag = tag()
            val member = seedMember("Encoded $tag", "enc-$tag", "enc-$tag@seed.test")

            // Spring routes on the decoded path, so this is the same route (the access log
            // filter redacts it too, see HttpAccessLogFilterTests).
            val body =
                mockMvc
                    .get(URI.create("/api/v1/tenant/%73earch?q=$tag")) {
                        with(authentication(token(admin)))
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString

            assertEquals(
                setOf(member.userId.toString()),
                mapper.readTree(body)["users"].ids(),
            )
        }

        @Test
        fun `the bounds are inclusive`() {
            get("/api/v1/tenant/search?q=ab", admin).andExpect { status { isOk() } }
            get("/api/v1/tenant/search?q=${"x".repeat(64)}", admin).andExpect {
                status { isOk() }
            }
        }

        @Test
        fun `a malformed q is a 400 even for a caller who may view no type`() {
            val caller = seedUser("nobody-bad-q")
            fixture.grantTenantPermissionsExactly(organisationId, caller, "audit.view")

            get("/api/v1/tenant/search?q=a", caller).andExpect {
                status { isBadRequest() }
                jsonPath("$.violations[0].field") { value("q") }
            }
            get("/api/v1/tenant/search", caller).andExpect {
                status { isBadRequest() }
                jsonPath("$.violations[0].field") { value("q") }
            }
        }

        @Test
        fun `another tenant's rows are never returned`() {
            val tag = tag()
            val otherAdmin = seedUser("other-admin")
            val otherOrganisation = fixture.createActiveOrganisation("gso", otherAdmin)
            val mine = seedMember("Mine $tag", "mine-$tag", "mine-$tag@seed.test")
            val myBranch = seedBranch("MINE-$tag", "Mine $tag")
            val myRole = seedRole("MINE-$tag", "Mine $tag")
            seedMember("Theirs $tag", "theirs-$tag", "theirs-$tag@seed.test", otherOrganisation)
            seedBranch("THEIRS-$tag", "Theirs $tag", organisation = otherOrganisation)
            seedRole("THEIRS-$tag", "Theirs $tag", organisation = otherOrganisation)
            // The same text in both tenants: only the caller's is a hit.
            seedBranch("BOTH-$tag", "Shared $tag", organisation = otherOrganisation)

            val body = search(tag)

            assertEquals(setOf(mine.userId.toString()), body["users"].ids())
            assertEquals(setOf(mine.membershipId.toString()), body["memberships"].ids())
            assertEquals(setOf(myBranch.toString()), body["branches"].ids())
            assertEquals(setOf(myRole.toString()), body["roles"].ids())
        }

        @Test
        fun `an archived role is not a hit but a disabled one is`() {
            val tag = tag()
            val active = seedRole("ACT-$tag", "Active $tag")
            val disabled = seedRole("DIS-$tag", "Disabled $tag", status = "DISABLED")
            seedRole("ARC-$tag", "Archived $tag", status = "ARCHIVED")

            val body = search(tag)

            assertEquals(setOf(active.toString(), disabled.toString()), body["roles"].ids())
        }

        @Test
        fun `an answered search writes no audit row`() {
            val tag = tag()
            seedMember("Quiet $tag", "quiet-$tag", "quiet-$tag@seed.test")
            val before = auditRows()

            search(tag)

            assertEquals(before, auditRows())
        }

        @Test
        fun `the route runs under the tenant read rate limit policy`() {
            val request = MockHttpServletRequest("GET", "/api/v1/tenant/search")
            request.servletPath = "/api/v1/tenant/search"

            val resolution = RateLimitKeyResolver().resolve(request, rateLimitProperties)

            assertIs<RateLimitIdentity>(resolution)
            assertEquals(RateLimitPolicyId.TENANT_READ, resolution.policyId)
        }

        @Test
        fun `an unauthenticated request is refused`() {
            mockMvc.get("/api/v1/tenant/search?q=abc").andExpect { status { isUnauthorized() } }
        }

        private fun search(
            q: String,
            actor: UUID = admin,
        ): JsonNode = mapper.readTree(searchRaw(q, actor))

        private fun searchRaw(
            q: String,
            actor: UUID = admin,
        ): String =
            mockMvc
                .get(ApiPaths.SEARCH) {
                    param("q", q)
                    with(authentication(token(actor)))
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString

        private fun get(
            path: String,
            actor: UUID,
        ): ResultActionsDsl = mockMvc.get(path) { with(authentication(token(actor))) }

        private fun deniedRows(actor: UUID): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND actor_user_id = ? AND action = 'api.access_denied'",
                    organisationId,
                    actor,
                ).toString()
                .toInt()

        private fun auditRows(): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM audit_event WHERE organisation_id = ?",
                    organisationId,
                ).toString()
                .toInt()

        // Permissions are read from the database per request; the token carries identity only.
        private fun token(actor: UUID) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = actor,
                    keycloakSubject = "user-$actor",
                    organisationId = organisationId,
                    membershipId = uuidV7(),
                    branchId = null,
                    email = "searcher@gs.test",
                    fullName = "Global Searcher",
                    permissions = setOf("iam.profile.read"),
                ),
            )

        private fun tag(): String = "gs" + uuidV7().toString().takeLast(12)

        private data class Member(
            val userId: UUID,
            val membershipId: UUID,
        )

        private fun seedUser(label: String): UUID = insertUser(label, "$label-${uuidV7()}", null)

        private fun seedMember(
            displayName: String,
            username: String,
            email: String,
            organisation: UUID = organisationId,
        ): Member {
            val userId = insertUser(displayName, username, email)
            val now = OffsetDateTime.now()
            val membershipId =
                requireNotNull(
                    dsl
                        .insertInto(USER_ORGANISATION_MEMBERSHIP)
                        .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisation)
                        .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
                        .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
                        .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
                        .set(USER_ORGANISATION_MEMBERSHIP.CREATED_BY, SystemActor.ID)
                        .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
                        .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
                        .returning(USER_ORGANISATION_MEMBERSHIP.ID)
                        .fetchOne()
                        ?.id,
                )
            return Member(userId, membershipId)
        }

        private fun insertUser(
            displayName: String,
            username: String,
            email: String?,
        ): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.EMAIL, email ?: "$username@seed.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, displayName)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun seedBranch(
            code: String,
            name: String,
            organisation: UUID = organisationId,
        ): UUID {
            val now = OffsetDateTime.now()
            return requireNotNull(
                dsl
                    .insertInto(BRANCH)
                    .set(BRANCH.ORGANISATION_ID, organisation)
                    .set(BRANCH.BRANCH_CODE, code)
                    .set(BRANCH.BRANCH_NAME, name)
                    .set(BRANCH.BRANCH_TYPE, "BRANCH")
                    .set(BRANCH.STATUS, "ACTIVE")
                    .set(BRANCH.TIMEZONE, "Africa/Nairobi")
                    .set(BRANCH.CREATED_AT, now)
                    .set(BRANCH.UPDATED_AT, now)
                    .returning(BRANCH.ID)
                    .fetchOne()
                    ?.id,
            )
        }

        private fun seedRole(
            code: String,
            name: String,
            status: String = "ACTIVE",
            organisation: UUID = organisationId,
        ): UUID {
            val now = OffsetDateTime.now()
            return requireNotNull(
                dsl
                    .insertInto(ROLE)
                    .set(ROLE.ORGANISATION_ID, organisation)
                    .set(ROLE.ROLE_CODE, code)
                    .set(ROLE.ROLE_NAME, name)
                    .set(ROLE.SYSTEM_ROLE, false)
                    .set(ROLE.STATUS, status)
                    .set(ROLE.CREATED_AT, now)
                    .set(ROLE.CREATED_BY, SystemActor.ID)
                    .set(ROLE.UPDATED_AT, now)
                    .set(ROLE.UPDATED_BY, SystemActor.ID)
                    .returning(ROLE.ID)
                    .fetchOne()
                    ?.id,
            )
        }

        private fun JsonNode.keys(): Set<String> = properties().map { it.key }.toSet()

        private fun JsonNode.asMap(): Map<String, String> =
            properties().associate { (key, value) -> key to value.asString() }

        private fun JsonNode.ids(): Set<String> =
            (0 until size()).map { this[it]["id"].asString() }.toSet()

        private fun JsonNode.size(field: String): Int = this[field].size()
    }
