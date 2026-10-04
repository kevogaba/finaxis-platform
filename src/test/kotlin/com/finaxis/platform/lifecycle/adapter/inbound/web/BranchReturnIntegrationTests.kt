package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full-stack proof of returning or withdrawing a pending branch (issue #180, ADR 0029 3b): one
 * transition, two intents told apart by who the actor is, the permission each intent needs, the
 * check order, the platform checker window, the audit rows, and the loop back through the
 * maker's amendment (#165) to activation.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("LargeClass", "TooManyFunctions")
class BranchReturnIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val maker = seedUser("maker")
        private val checker = seedUser("checker")
        private val organisationId = fixture.createActiveOrganisation("branch-return", maker)
        private val platformMaker by lazy {
            seedUser("pmaker").also(fixture::grantPlatformSuperAdmin)
        }
        private val platformChecker by lazy {
            seedUser("pchecker").also(fixture::grantPlatformSuperAdmin)
        }

        init {
            fixture.grantTenantAdmin(organisationId, checker)
        }

        @Test
        fun `a checker returns a branch and the maker amends and resubmits it to activation`() {
            val branchId = createBranch()
            submit(branchId, makerToken())

            returnTenant(branchId, tenantToken(checker, "branch.activate")).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(branchId.toString()) }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.status_reason") { value(REASON) }
            }

            assertEquals("DRAFT", branchColumn(branchId, "status"))
            assertEquals(REASON, branchColumn(branchId, "status_reason"))
            assertEquals(maker.toString(), branchColumn(branchId, "created_by"))
            // The FSM row, with the reason and the checker as actor; no withdrawal row.
            val transition = transitionRows(branchId, "RETURN_FOR_CHANGES").single()
            assertEquals(Triple("PENDING_APPROVAL", "DRAFT", REASON), transition.first)
            assertEquals(checker, transition.second)
            val audit = auditRows(branchId, "branch.return_for_changes").single()
            assertEquals(REASON, audit.reason)
            assertEquals(checker, audit.actor)
            assertTrue(auditRows(branchId, "branch.withdraw").isEmpty())
            assertTrue(auditRows(branchId, AS_CHECKER).isEmpty())
            // Internal event only: nothing reaches the outbox for the return.
            assertEquals(0, outboxRecords("RETURN_FOR_CHANGES", branchId))

            // The returned draft is the maker's to amend and resubmit; the creator rule keeps
            // applying however many times it loops.
            patch(branchId, """{"branch_name":"Corrected Branch"}""").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.branch_name") { value("Corrected Branch") }
            }
            submit(branchId, makerToken())
            assertEquals("PENDING_APPROVAL", branchColumn(branchId, "status"))
            activate(branchId, tenantToken(maker, "branch.activate"))
                .andExpect { status { isForbidden() } }
            activate(branchId, tenantToken(checker, "branch.activate"))
                .andExpect { status { isOk() } }
            assertEquals("ACTIVE", branchColumn(branchId, "status"))
        }

        @Test
        fun `the maker withdraws their own pending branch and the code stays taken`() {
            val branchId = createBranch()
            submit(branchId, makerToken())
            val code = branchColumn(branchId, "branch_code")

            returnTenant(branchId, makerToken()).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.status_reason") { value(REASON) }
            }

            assertEquals("DRAFT", branchColumn(branchId, "status"))
            assertEquals(1, transitionRows(branchId, "RETURN_FOR_CHANGES").size)
            assertEquals(1, auditRows(branchId, "branch.return_for_changes").size)
            val withdrawal = auditRows(branchId, "branch.withdraw").single()
            assertEquals(maker, withdrawal.actor)
            assertEquals(REASON, withdrawal.reason)
            assertFalse(withdrawal.metadata.contains("checkerScope"), withdrawal.metadata)
            assertTrue(auditRows(branchId, AS_CHECKER).isEmpty())
            // Withdrawing frees nothing: the draft keeps its code (documented in ADR 0029).
            post(
                ApiPaths.BRANCHES,
                makerToken(),
                apiJsonCodec.mapper.writeValueAsString(
                    CreateBranchRequest(
                        branchCode = code!!,
                        branchName = "Duplicate",
                        branchType = "OPERATIONAL",
                        timezone = "Africa/Nairobi",
                    ),
                ),
            ).andExpect { status { isConflict() } }
        }

        @Test
        fun `the latest submitter withdraws even when someone else created the branch`() {
            val branchId = createBranch()
            submit(branchId, tenantToken(checker, "branch.create"))

            returnTenant(branchId, tenantToken(checker, "branch.create"))
                .andExpect { status { isOk() } }

            assertEquals(checker, auditRows(branchId, "branch.withdraw").single().actor)
            assertEquals("DRAFT", branchColumn(branchId, "status"))
        }

        @Test
        fun `a maker without branch create cannot withdraw but a checker can still return`() {
            val branchId = createBranch()
            submit(branchId, makerToken())
            // The maker's account now holds branch.activate and nothing else.
            val formerMaker = seedUser("former-maker")
            fixture.grantTenantPermissionsOnly(organisationId, formerMaker, "branch.activate")
            dsl
                .update(BRANCH)
                .set(BRANCH.CREATED_BY, formerMaker)
                .where(BRANCH.ID.eq(branchId))
                .execute()

            // A creator is classified as a maker, so branch.activate cannot make this a return.
            returnTenant(branchId, tenantToken(formerMaker, "branch.activate"))
                .andExpect { status { isForbidden() } }
            assertEquals("PENDING_APPROVAL", branchColumn(branchId, "status"))
            assertTrue(auditRows(branchId, "branch.return_for_changes").isEmpty())

            returnTenant(branchId, tenantToken(checker, "branch.activate"))
                .andExpect { status { isOk() } }
            assertEquals("DRAFT", branchColumn(branchId, "status"))
        }

        @Test
        fun `a role holding only the mutation permission returns or withdraws and reads it back`() {
            val checkerOnly = seedUser("checker-only")
            fixture.grantTenantPermissionsOnly(organisationId, checkerOnly, "branch.activate")
            val makerOnly = seedUser("maker-only")
            fixture.grantTenantPermissionsOnly(organisationId, makerOnly, "branch.create")
            val branchScoped = seedUser("branch-scoped")
            val first = createBranch()
            fixture.grantBranchPermissionsOnly(
                organisationId,
                first,
                branchScoped,
                "branch.activate",
            )

            // None of them holds branch.view anywhere: the response must not need it, or the
            // return would be rolled back with a 403 after the mutation.
            submit(first, makerToken())
            returnTenant(first, tenantToken(branchScoped, "branch.activate", headOfficeId()))
                .andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("DRAFT") }
                }
            assertEquals("DRAFT", branchColumn(first, "status"))

            val second = createBranch()
            submit(second, makerToken())
            returnTenant(second, tenantToken(checkerOnly, "branch.activate")).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(second.toString()) }
                jsonPath("$.status") { value("DRAFT") }
            }
            assertEquals("DRAFT", branchColumn(second, "status"))

            val third = createBranchAs(makerOnly)
            // Submitted by another maker: the existing submit route reads the result back with
            // branch.view, which this role does not hold. makerOnly is still the creator.
            submit(third, makerToken())
            returnTenant(third, tenantToken(makerOnly, "branch.create")).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
            }
            assertEquals("DRAFT", branchColumn(third, "status"))
        }

        @Test
        fun `only a pending branch can be returned and anything else is a conflict`() {
            val draft = createBranch()
            val active = createBranch()
            submit(active, makerToken())
            activate(active, checkerToken()).andExpect { status { isOk() } }
            val suspended = createBranch()
            submit(suspended, makerToken())
            activate(suspended, checkerToken()).andExpect { status { isOk() } }
            post(
                "${ApiPaths.BRANCHES}/$suspended/suspend",
                tenantToken(checker, "branch.suspend"),
                """{"reason":"Audit hold"}""",
            ).andExpect { status { isOk() } }
            val closed = createBranch()
            submit(closed, makerToken())
            activate(closed, checkerToken()).andExpect { status { isOk() } }
            post(
                "${ApiPaths.BRANCHES}/$closed/close",
                tenantToken(checker, "branch.close"),
                """{"reason":"Consolidated"}""",
            ).andExpect { status { isOk() } }

            mapOf(
                draft to "DRAFT",
                active to "ACTIVE",
                suspended to "SUSPENDED",
                closed to "CLOSED",
            ).forEach { (branchId, status) ->
                listOf(makerToken(), tenantToken(checker, "branch.activate")).forEach { token ->
                    returnTenant(branchId, token).andExpect { status { isConflict() } }
                }
                assertEquals(status, branchColumn(branchId, "status"), "unchanged")
                assertTrue(transitionRows(branchId, "RETURN_FOR_CHANGES").isEmpty())
                assertTrue(auditRows(branchId, "branch.withdraw").isEmpty())
            }
        }

        @Test
        fun `a missing permission is 403 before any existence signal and foreign is 404`() {
            val branchId = createBranch()
            submit(branchId, makerToken())
            val otherOwner = seedUser("other-owner")
            val otherOrganisation = fixture.createActiveOrganisation("branch-oth", otherOwner)
            val foreign = createBranchIn(otherOrganisation, otherOwner)
            val reader = seedUser("reader")
            fixture.grantTenantPermissionsOnly(organisationId, reader, "branch.view")

            // Holds the coarse authority on the token but not the grant: 403 for a real branch,
            // a branch of another tenant and a branch that does not exist alike.
            listOf(branchId, foreign, uuidV7()).forEach { target ->
                returnTenant(target, tenantToken(reader, "branch.activate"))
                    .andExpect { status { isForbidden() } }
            }
            // Authorised in this tenant: another tenant's branch and an unknown id are 404.
            listOf(foreign, uuidV7()).forEach { target ->
                returnTenant(target, tenantToken(checker, "branch.activate"))
                    .andExpect { status { isNotFound() } }
                returnTenant(target, makerToken()).andExpect { status { isNotFound() } }
            }
            // And the other tenant's owner cannot reach this tenant's branch either.
            val foreignOwner = tenantToken(otherOwner, "branch.activate", org = otherOrganisation)
            returnTenant(branchId, foreignOwner)
                .andExpect { status { isNotFound() } }

            assertEquals("PENDING_APPROVAL", branchColumn(branchId, "status"))
            assertEquals("DRAFT", branchColumn(foreign, "status"))
        }

        @Test
        fun `a tenant that is not active or provisioning freezes its pending branches`() {
            // A tenant caller is refused by permission resolution outside ACTIVE, so the
            // organisation-state rule is the platform route's: both intents, same 409.
            val branchId = createPlatformBranch()
            submitPlatform(branchId, platformMaker)

            listOf("SUSPENDED", "DEPROVISIONING", "DRAFT").forEach { state ->
                setTenantStatus(state)
                listOf(platformMaker, platformChecker).forEach { actor ->
                    returnPlatform(branchId, actor).andExpect { status { isConflict() } }
                }
                assertEquals("PENDING_APPROVAL", branchColumn(branchId, "status"), state)
                assertTrue(transitionRows(branchId, "RETURN_FOR_CHANGES").isEmpty())
            }
            setTenantStatus("PROVISIONING")
            returnPlatform(branchId, platformChecker).andExpect { status { isOk() } }
            assertEquals("DRAFT", branchColumn(branchId, "status"))
            setTenantStatus("ACTIVE")
        }

        @Test
        fun `a missing blank or out of range reason is a 400 and changes nothing`() {
            val branchId = createBranch()
            submit(branchId, makerToken())

            listOf(null, "{}", """{"reason":null}""").forEach { body ->
                returnTenant(branchId, makerToken(), body).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("invalid_json") }
                }
            }
            listOf("", "  ", "ab", "x".repeat(501)).forEach { reason ->
                returnTenant(
                    branchId,
                    makerToken(),
                    apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason)),
                ).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }
            }

            assertEquals("PENDING_APPROVAL", branchColumn(branchId, "status"))
            assertTrue(transitionRows(branchId, "RETURN_FOR_CHANGES").isEmpty())
        }

        @Test
        fun `a replayed key returns the stored response and returns the branch once`() {
            val branchId = createBranch()
            submit(branchId, makerToken())
            val key = uuidV7().toString()

            val first = returnRaw(branchId, key)
            val second = returnRaw(branchId, key)

            assertEquals(200, first.status)
            assertEquals(first.status, second.status)
            assertEquals(first.contentAsString, second.contentAsString)
            assertEquals(1, transitionRows(branchId, "RETURN_FOR_CHANGES").size)
            assertEquals(1, auditRows(branchId, "branch.withdraw").size)
            // A fresh key against the now-draft branch is a conflict, not a second return.
            returnTenant(branchId, makerToken()).andExpect { status { isConflict() } }
        }

        @Test
        fun `a platform checker returns inside the bound and is audited with the platform scope`() {
            val branchId = createBranch()
            submit(branchId, makerToken())

            returnPlatform(branchId, platformChecker).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.status_reason") { value(REASON) }
            }

            assertEquals("DRAFT", branchColumn(branchId, "status"))
            assertEquals(1, auditRows(branchId, "branch.return_for_changes").size)
            val checkerRow =
                auditRows(branchId, "branch.return_for_changes_as_platform_checker").single()
            assertEquals(platformChecker, checkerRow.actor)
            assertEquals(REASON, checkerRow.reason)
            val marker = "\"checkerScope\": \"PLATFORM\""
            assertTrue(checkerRow.metadata.contains(marker), checkerRow.metadata)
            assertTrue(auditRows(branchId, "branch.withdraw").isEmpty())
        }

        @Test
        fun `a platform checker return is closed once the tenant has its own active branch`() {
            val own = createBranch()
            submit(own, makerToken())
            activate(own, tenantToken(checker, "branch.activate")).andExpect { status { isOk() } }
            val pending = createBranch()
            submit(pending, makerToken())

            val body =
                returnPlatform(pending, platformChecker)
                    .andExpect { status { isConflict() } }
                    .andReturn()
                    .response.contentAsString
            assertTrue(body.contains("lifecycle.platform_checker_closed"), body)

            assertEquals("PENDING_APPROVAL", branchColumn(pending, "status"))
            assertTrue(transitionRows(pending, "RETURN_FOR_CHANGES").isEmpty())
            // The tenant's own checker is not bounded.
            returnTenant(pending, tenantToken(checker, "branch.activate"))
                .andExpect { status { isOk() } }
        }

        @Test
        fun `a platform withdrawal is not bounded and carries no checker marker`() {
            val branchId = createPlatformBranch()
            submitPlatform(branchId, platformMaker)
            // The tenant now has an active branch of its own, closing the platform checker.
            val own = createBranch()
            submit(own, makerToken())
            activate(own, tenantToken(checker, "branch.activate")).andExpect { status { isOk() } }
            returnPlatform(branchId, platformChecker).andExpect { status { isConflict() } }

            returnPlatform(branchId, platformMaker).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
            }

            val withdrawal = auditRows(branchId, "branch.withdraw").single()
            assertEquals(platformMaker, withdrawal.actor)
            assertFalse(withdrawal.metadata.contains("checkerScope"), withdrawal.metadata)
            assertTrue(auditRows(branchId, AS_CHECKER).isEmpty())
        }

        @Test
        fun `a platform submitter cannot return as a checker or activate what it submitted`() {
            val branchId = createPlatformBranch()
            submitPlatform(branchId, platformMaker)

            // Their own return is a withdrawal, never a checker step.
            returnPlatform(branchId, platformMaker).andExpect { status { isOk() } }
            assertEquals(1, auditRows(branchId, "branch.withdraw").size)
            assertTrue(auditRows(branchId, AS_CHECKER).isEmpty())

            // A different platform administrator returns the resubmitted draft as the checker.
            submitPlatform(branchId, platformMaker)
            returnPlatform(branchId, platformChecker).andExpect { status { isOk() } }
            assertEquals(1, auditRows(branchId, AS_CHECKER).size)

            // The maker still cannot approve it, however many times it loops.
            submitPlatform(branchId, platformMaker)
            platformPost("activate", branchId, platformMaker).andExpect { status { isForbidden() } }
            platformPost("activate", branchId, platformChecker).andExpect { status { isOk() } }
            assertEquals("ACTIVE", branchColumn(branchId, "status"))
        }

        @Test
        fun `a platform role holding only the mutation permission returns and withdraws`() {
            val onlyActivate = seedUser("p-activate")
            fixture.grantPlatformPermissionsOnly(onlyActivate, "branch.activate")
            val onlyCreate = seedUser("p-create")
            fixture.grantPlatformPermissionsOnly(onlyCreate, "branch.create")
            val pending = createBranch()
            submit(pending, makerToken())

            // Neither holds branch.view: the response must not need it.
            returnPlatformAs(pending, onlyActivate, setOf("branch.activate")).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
            }
            // The maker permission does not make a non-maker a checker.
            submit(pending, makerToken())
            returnPlatformAs(pending, onlyCreate, setOf("branch.create"))
                .andExpect { status { isForbidden() } }
            assertEquals("PENDING_APPROVAL", branchColumn(pending, "status"))

            // The latest submitter withdraws with branch.create alone, outside the checker role.
            val platformDraft = createPlatformBranch()
            submitPlatform(platformDraft, onlyCreate)
            returnPlatformAs(platformDraft, onlyCreate, setOf("branch.create")).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
            }
            assertEquals(onlyCreate, auditRows(platformDraft, "branch.withdraw").single().actor)
        }

        @Test
        fun `the platform route refuses foreign branches the platform organisation and tenants`() {
            val branchId = createBranch()
            submit(branchId, makerToken())
            val otherOwner = seedUser("p-other-owner")
            val otherOrganisation = fixture.createActiveOrganisation("branch-p-other", otherOwner)
            val foreign = createBranchIn(otherOrganisation, otherOwner)
            val unauthorised = seedUser("p-nobody")
            fixture.grantPlatformSupport(unauthorised)

            // A branch of another tenant under this tenant's path, and an unknown id, are 404.
            returnPlatform(foreign, platformChecker).andExpect { status { isNotFound() } }
            returnPlatform(uuidV7(), platformChecker).andExpect { status { isNotFound() } }
            // The platform organisation is never a tenant.
            mockMvc
                .post(platformPath(PlatformOrganisation.ID, branchId)) {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = REASON_BODY
                    with(authentication(platformToken(platformChecker, COARSE)))
                }.andExpect { status { isNotFound() } }
            // PLATFORM_SUPPORT holds neither permission: 403 before any existence signal.
            listOf(branchId, foreign, uuidV7()).forEach { target ->
                returnPlatformAs(target, unauthorised, COARSE)
                    .andExpect { status { isForbidden() } }
            }
            // A tenant context never reaches the platform route.
            mockMvc
                .post(platformPath(organisationId, branchId)) {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = REASON_BODY
                    with(authentication(tenantToken(checker, "branch.activate")))
                }.andExpect { status { isForbidden() } }

            assertEquals("PENDING_APPROVAL", branchColumn(branchId, "status"))
        }

        private fun createBranch(): UUID = createBranchIn(organisationId, maker)

        private fun createBranchAs(actor: UUID): UUID = createBranchIn(organisationId, actor)

        private fun createBranchIn(
            tenantId: UUID,
            actor: UUID,
        ): UUID {
            val body =
                post(
                    ApiPaths.BRANCHES,
                    tenantToken(actor, "branch.create", org = tenantId),
                    branchBody(),
                ).andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return idOf(body)
        }

        private fun createPlatformBranch(): UUID {
            val body =
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$organisationId/branches") {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                        contentType = MediaType.APPLICATION_JSON
                        content = branchBody()
                        with(authentication(platformToken(platformMaker, COARSE)))
                    }.andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return idOf(body)
        }

        private fun branchBody() =
            apiJsonCodec.mapper.writeValueAsString(
                CreateBranchRequest(
                    branchCode = "BR-${uuidV7().toString().takeLast(8).uppercase()}",
                    branchName = "Riverside Branch",
                    branchType = "OPERATIONAL",
                    timezone = "Africa/Nairobi",
                ),
            )

        private fun submit(
            branchId: UUID,
            token: AppPrincipalAuthenticationToken,
        ) {
            post("${ApiPaths.BRANCHES}/$branchId/submit", token, "{}")
                .andExpect { status { isOk() } }
        }

        private fun submitPlatform(
            branchId: UUID,
            actor: UUID,
        ) {
            val permissions = if (actor == platformMaker) COARSE else setOf("branch.create")
            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$organisationId/branches/$branchId/submit") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    with(authentication(platformToken(actor, permissions)))
                }.andExpect { status { isOk() } }
        }

        private fun activate(
            branchId: UUID,
            token: AppPrincipalAuthenticationToken,
        ) = post("${ApiPaths.BRANCHES}/$branchId/activate", token, "{}")

        private fun platformPost(
            action: String,
            branchId: UUID,
            actor: UUID,
        ) = mockMvc.post(platformPath(organisationId, branchId, action)) {
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
            with(authentication(platformToken(actor, COARSE)))
        }

        private fun returnTenant(
            branchId: UUID,
            token: AppPrincipalAuthenticationToken,
            body: String? = REASON_BODY,
        ): ResultActionsDsl =
            mockMvc.post("${ApiPaths.BRANCHES}/$branchId/return") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                with(authentication(token))
            }

        private fun returnRaw(
            branchId: UUID,
            key: String,
        ) = mockMvc
            .post("${ApiPaths.BRANCHES}/$branchId/return") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = REASON_BODY
                with(authentication(makerToken()))
            }.andReturn()
            .response

        private fun returnPlatform(
            branchId: UUID,
            actor: UUID,
        ) = returnPlatformAs(branchId, actor, COARSE)

        private fun returnPlatformAs(
            branchId: UUID,
            actor: UUID,
            authorities: Set<String>,
        ): ResultActionsDsl =
            mockMvc.post(platformPath(organisationId, branchId)) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = REASON_BODY
                with(authentication(platformToken(actor, authorities)))
            }

        private fun patch(
            branchId: UUID,
            body: String,
        ): ResultActionsDsl =
            mockMvc.patch("${ApiPaths.BRANCHES}/$branchId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                // Amending takes branch.update, which branch.create alone no longer gives (#203).
                with(authentication(tenantToken(maker, "branch.update")))
            }

        private fun post(
            path: String,
            token: AppPrincipalAuthenticationToken,
            body: String,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token))
            }

        private fun idOf(body: String): UUID =
            UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("branch_id")
                    .asString(),
            )

        private fun branchColumn(
            branchId: UUID,
            column: String,
        ): String? =
            dsl
                .fetchOne("SELECT $column::text FROM branch WHERE id = ?", branchId)!!
                .get(0, String::class.java)

        private fun headOfficeId(): UUID =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.BRANCH_TYPE.eq("HEAD_OFFICE"))
                    .fetchOne(BRANCH.ID),
            )

        private fun setTenantStatus(status: String) {
            dsl
                .update(ORGANISATION)
                .set(ORGANISATION.STATUS, status)
                .where(ORGANISATION.ID.eq(organisationId))
                .execute()
        }

        /** (from, to, reason) and the actor of each log row of [transition]. */
        private fun transitionRows(
            branchId: UUID,
            transition: String,
        ): List<Pair<Triple<String, String, String?>, UUID?>> =
            dsl
                .fetch(
                    "SELECT status_from, status_to, reason, created_by " +
                        "FROM branch_transition_log " +
                        "WHERE branch_id = ? AND transition_name = ? ORDER BY created_at",
                    branchId,
                    transition,
                ).map {
                    Triple(
                        it.get(0, String::class.java),
                        it.get(1, String::class.java),
                        it.get(2, String::class.java),
                    ) to it.get(3, UUID::class.java)
                }

        private data class AuditRow(
            val actor: UUID?,
            val reason: String?,
            val metadata: String,
        )

        private fun auditRows(
            branchId: UUID,
            action: String,
        ): List<AuditRow> =
            dsl
                .fetch(
                    "SELECT actor_user_id, reason, metadata_jsonb::text FROM audit_event " +
                        "WHERE entity_id = ? AND action = ? AND outcome = 'SUCCESS' " +
                        "ORDER BY event_time",
                    branchId,
                    action,
                ).map {
                    AuditRow(
                        it.get(0, UUID::class.java),
                        it.get(1, String::class.java),
                        it.get(2, String::class.java),
                    )
                }

        private fun outboxRecords(
            transition: String,
            aggregateId: UUID,
        ): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM outbox_record WHERE payload LIKE ? AND payload LIKE ?",
                    "%$transition%",
                    "%$aggregateId%",
                )!!
                .get(0, Int::class.java)

        private fun makerToken() = tenantToken(maker, "branch.create")

        private fun checkerToken() = tenantToken(checker, "branch.activate")

        private fun platformPath(
            tenantId: UUID,
            branchId: UUID,
            action: String = "return",
        ) = "${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/$action"

        private fun tenantToken(
            userId: UUID,
            permission: String,
            pinnedTo: UUID? = null,
            org: UUID = organisationId,
        ) = token(userId, org, setOf(permission), pinnedTo)

        private fun platformToken(
            userId: UUID,
            permissions: Set<String>,
        ) = token(userId, PlatformOrganisation.ID, permissions)

        private fun token(
            userId: UUID,
            tenantId: UUID,
            permissions: Set<String>,
            pinnedTo: UUID? = null,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = pinnedTo,
                email = "user@branch-return.test",
                fullName = "Branch Return User",
                permissions = permissions,
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@branch-return.test")
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
            const val AS_CHECKER = "branch.return_for_changes_as_platform_checker"
            const val REASON = "Branch name has a typo."
            const val REASON_BODY = """{"reason":"$REASON"}"""
            val COARSE =
                setOf("branch.create", "branch.activate", "branch.view", "audit.view")
        }
    }
