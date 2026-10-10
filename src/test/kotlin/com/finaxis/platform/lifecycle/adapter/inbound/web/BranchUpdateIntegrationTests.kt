package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full-stack proof of `PATCH /api/v1/branches/{id}` (issue #165): the change reads back through
 * GET, only draft and active branches can change, a tenant cannot reach another tenant's branch,
 * the parent hierarchy stays a tree, and the change is audited by field name and idempotent.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BranchUpdateIntegrationTests
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
        private val organisationId = fixture.createActiveOrganisation("branch-update", maker)

        init {
            fixture.grantTenantAdmin(organisationId, checker)
        }

        @Test
        fun `a draft and an active branch can be updated and read back`() {
            val branchId = createBranch()

            patch(
                branchId,
                """{"branch_name":"Lakeside Branch","timezone":"Africa/Kampala",""" +
                    """"address":{"city":"Kampala","line1":"3 Lake Road"}}""",
            ).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.branch_name") { value("Lakeside Branch") }
                jsonPath("$.branch_code") { value(branchCode(branchId)) }
                jsonPath("$.branch_type") { value("OPERATIONAL") }
            }
            assertDetail(branchId, "DRAFT", "Lakeside Branch", "Africa/Kampala", "Kampala")

            post("submit", branchId, makerToken())
            post("activate", branchId, checkerToken("branch.approve"))

            // A partial update on an active branch: only the address changes.
            patch(branchId, """{"address":{"city":"Entebbe"}}""").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ACTIVE") }
                jsonPath("$.address.city") { value("Entebbe") }
                jsonPath("$.address.line1") { doesNotExist() }
                jsonPath("$.branch_name") { value("Lakeside Branch") }
                jsonPath("$.timezone") { value("Africa/Kampala") }
                jsonPath("$.opened_on") { isNotEmpty() }
            }
            assertDetail(branchId, "ACTIVE", "Lakeside Branch", "Africa/Kampala", "Entebbe")
        }

        @Test
        fun `a branch that is pending approval suspended or closed cannot be updated`() {
            val branchId = createBranch()
            val body = """{"branch_name":"Never Applied"}"""

            post("submit", branchId, makerToken())
            patch(branchId, body).andExpect { status { isConflict() } }

            post("activate", branchId, checkerToken("branch.approve"))
            post("suspend", branchId, checkerToken("branch.suspend"), """{"reason":"Audit hold"}""")
            patch(branchId, body).andExpect { status { isConflict() } }

            post("close", branchId, checkerToken("branch.close"), """{"reason":"Consolidated"}""")
            patch(branchId, body).andExpect { status { isConflict() } }

            assertEquals("Riverside Branch", branchColumn(branchId, BRANCH.BRANCH_NAME))
        }

        @Test
        fun `the parent can be set cleared and never forms a cycle`() {
            val headOffice = headOfficeId()
            val child = createBranch()
            val grandchild = createBranch()

            patch(child, """{"parent_branch_id":"$headOffice"}""").andExpect {
                status { isOk() }
                jsonPath("$.parent_branch_id") { value(headOffice.toString()) }
            }
            patch(grandchild, """{"parent_branch_id":"$child"}""").andExpect { status { isOk() } }

            // Self, a descendant, and a branch of another tenant are all refused.
            patch(child, """{"parent_branch_id":"$child"}""")
                .andExpect { status { isUnprocessableContent() } }
            patch(child, """{"parent_branch_id":"$grandchild"}""")
                .andExpect { status { isUnprocessableContent() } }
            patch(headOffice, """{"parent_branch_id":"$grandchild"}""")
                .andExpect { status { isUnprocessableContent() } }
            val foreignParent = foreignBranch()
            patch(child, """{"parent_branch_id":"$foreignParent"}""")
                .andExpect { status { isNotFound() } }
            assertEquals(headOffice, branchColumn(child, BRANCH.PARENT_BRANCH_ID))

            // An explicit null detaches; an absent field would have left it alone.
            patch(child, """{"branch_name":"Still Under HQ"}""").andExpect {
                jsonPath("$.parent_branch_id") { value(headOffice.toString()) }
            }
            patch(child, """{"parent_branch_id":null}""").andExpect {
                status { isOk() }
                jsonPath("$.parent_branch_id") { value(null) }
            }
            assertEquals(null, branchColumn(child, BRANCH.PARENT_BRANCH_ID))
        }

        @Test
        fun `a closed or archived branch cannot become a parent but an active one can`() {
            val closedParent = createActiveBranch()
            post(
                "close",
                closedParent,
                checkerToken("branch.close"),
                """{"reason":"Consolidated"}""",
            )
            val archivedParent = createActiveBranch()
            dsl
                .update(BRANCH)
                .set(BRANCH.STATUS, "ARCHIVED")
                .where(BRANCH.ID.eq(archivedParent))
                .execute()
            val openParent = createActiveBranch()
            val draft = createBranch()
            val active = createActiveBranch()

            listOf(closedParent, archivedParent).forEach { parent ->
                listOf(draft, active).forEach { child ->
                    patch(child, """{"parent_branch_id":"$parent"}""").andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("conflict") }
                    }
                    assertEquals(null, branchColumn(child, BRANCH.PARENT_BRANCH_ID))
                }
            }
            // The refused moves wrote nothing: the draft is untouched and the parent stays closed.
            assertEquals(0L, branchColumn(draft, BRANCH.ROW_VERSION))
            assertEquals("CLOSED", branchColumn(closedParent, BRANCH.STATUS))

            listOf(draft, active).forEach { child ->
                patch(child, """{"parent_branch_id":"$openParent"}""").andExpect {
                    status { isOk() }
                    jsonPath("$.parent_branch_id") { value(openParent.toString()) }
                }
            }
        }

        @Test
        fun `an invalid update changes nothing`() {
            val branchId = createBranch()

            patch(branchId, """{"timezone":"Mars/Olympus"}""")
                .andExpect { status { isUnprocessableContent() } }
            patch(branchId, "{}").andExpect { status { isBadRequest() } }
            patch(branchId, """{"branch_name":" "}""").andExpect { status { isBadRequest() } }
            patch(branchId, """{"branch_code":"OTHER"}""").andExpect { status { isBadRequest() } }

            assertEquals(0L, branchColumn(branchId, BRANCH.ROW_VERSION))
            assertEquals("Africa/Nairobi", branchColumn(branchId, BRANCH.TIMEZONE))
        }

        @Test
        fun `another tenant and a caller without the authority cannot update the branch`() {
            val branchId = createBranch()
            val otherOwner = seedUser("other-owner")
            val otherOrganisation = fixture.createActiveOrganisation("branch-other", otherOwner)
            val body = """{"branch_name":"Hijacked"}"""

            // Authorised in their own tenant, but the branch is not theirs: absent, not forbidden.
            patch(branchId, body, token(otherOwner, otherOrganisation, "branch.update"))
                .andExpect { status { isNotFound() } }

            // Holds a role but not branch.update: refused at the gate.
            patch(branchId, body, token(maker, organisationId, "branch.view"))
                .andExpect { status { isForbidden() } }

            assertEquals("Riverside Branch", branchColumn(branchId, BRANCH.BRANCH_NAME))
        }

        @Test
        fun `a member who can read branches but lacks branch update is refused by the service`() {
            val branchId = createBranch()
            val body = """{"branch_name":"Hijacked"}"""
            // A member whose role can read branches (so the controller's read-back would succeed)
            // but holds no branch.update grant, with the authority only on the token: it is the
            // service's own permission check that refuses, and nothing changes.
            val reader = seedUser("reader")
            fixture.grantTenantPermissionsWithViews(organisationId, reader, "branch.view")
            patch(branchId, body, token(reader, organisationId, "branch.update"))
                .andExpect { status { isForbidden() } }
            assertEquals(0L, branchColumn(branchId, BRANCH.ROW_VERSION))
            assertTrue(updateAudits(branchId).isEmpty())
            assertEquals("Riverside Branch", branchColumn(branchId, BRANCH.BRANCH_NAME))
        }

        @Test
        fun `an editor holding branch update and its view updates the branch and reads it back`() {
            val branchId = createBranch()
            // Each editor holds branch.view with branch.update (ADR 0030), so the read-back of
            // the committed update cannot be refused.
            val tenantMaker = seedUser("tenant-maker")
            fixture.grantTenantPermissionsWithViews(organisationId, tenantMaker, "branch.update")
            val branchMaker = seedUser("branch-maker")
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                branchId,
                branchMaker,
                "branch.update",
            )
            // A grant scoped to a branch counts only while it is ACTIVE (issue #242): on the
            // draft the branch-scoped editor is refused, so the branch is activated first.
            patch(
                branchId,
                """{"branch_name":"Draft Edit"}""",
                token(branchMaker, organisationId, "branch.update"),
            ).andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Missing permission: branch.update.") }
            }
            post("submit", branchId, makerToken())
            post("activate", branchId, checkerToken("branch.approve"))
            val activatedVersion = requireNotNull(branchColumn(branchId, BRANCH.ROW_VERSION))

            listOf(
                "Tenant Maker Edit" to tenantMaker,
                "Branch Maker Edit" to branchMaker,
            ).forEachIndexed { index, (name, actor) ->
                patch(
                    branchId,
                    """{"branch_name":"$name"}""",
                    token(actor, organisationId, "branch.update"),
                ).andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(branchId.toString()) }
                    jsonPath("$.branch_name") { value(name) }
                }
                assertEquals(name, branchColumn(branchId, BRANCH.BRANCH_NAME))
                assertEquals(
                    activatedVersion + index + 1L,
                    branchColumn(branchId, BRANCH.ROW_VERSION),
                )
                assertEquals(index + 1, updateAudits(branchId).size)
            }
        }

        @Test
        fun `an editor holding branch update without branch view is refused and nothing changes`() {
            val branchId = createBranch()
            val noView = seedUser("editor-no-view")
            fixture.grantTenantPermissionsExactly(organisationId, noView, "branch.update")

            patch(
                branchId,
                """{"branch_name":"Blind Edit"}""",
                token(noView, organisationId, "branch.update"),
            ).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
                jsonPath("$.detail") { value("Missing permission: branch.view.") }
            }

            assertEquals(0L, branchColumn(branchId, BRANCH.ROW_VERSION))
            assertTrue(updateAudits(branchId).isEmpty())
            assertEquals("Riverside Branch", branchColumn(branchId, BRANCH.BRANCH_NAME))
        }

        @Test
        fun `a maker holding only branch create can no longer update the branch`() {
            val branchId = createBranch()
            val body = """{"branch_name":"Maker Edit"}"""
            // A maker-only role: it may draft and submit, but is no longer a branch editor, so it
            // cannot change a live branch with no checker (#203). The branch.create token reaches
            // the controller gate and is refused there; the branch.update token passes the gate
            // with no matching grant behind it, so the service's own check refuses it.
            val tenantMaker = seedUser("create-only-maker")
            fixture.grantTenantPermissionsWithViews(organisationId, tenantMaker, "branch.create")
            val branchMaker = seedUser("create-only-branch-maker")
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                branchId,
                branchMaker,
                "branch.create",
            )

            listOf(tenantMaker, branchMaker).forEach { actor ->
                patch(branchId, body, token(actor, organisationId, "branch.create"))
                    .andExpect { status { isForbidden() } }
                patch(branchId, body, token(actor, organisationId, "branch.update"))
                    .andExpect { status { isForbidden() } }
            }

            assertEquals(0L, branchColumn(branchId, BRANCH.ROW_VERSION))
            assertTrue(updateAudits(branchId).isEmpty())
        }

        @Test
        fun `an unknown branch is not found for an editor and forbidden for a non-editor`() {
            val unknown = uuidV7()
            val body = """{"branch_name":"Ghost"}"""
            val createOnly = seedUser("ghost-create-only")
            fixture.grantTenantPermissionsWithViews(organisationId, createOnly, "branch.create")
            val editor = seedUser("ghost-editor")
            fixture.grantTenantPermissionsWithViews(organisationId, editor, "branch.update")

            // The permission is checked before existence: a caller who may not edit branches learns
            // nothing about which ids exist.
            patch(unknown, body, token(createOnly, organisationId, "branch.update"))
                .andExpect { status { isForbidden() } }
            patch(unknown, body, token(editor, organisationId, "branch.update"))
                .andExpect { status { isNotFound() } }
        }

        @Test
        fun `a caller pinned to another branch updates the target branch`() {
            val branchId = createBranch()
            val pinned = token(maker, organisationId, "branch.update", pinnedTo = headOfficeId())

            patch(branchId, """{"branch_name":"Pinned Edit"}""", pinned).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(branchId.toString()) }
                jsonPath("$.branch_name") { value("Pinned Edit") }
            }
        }

        @Test
        fun `a replayed key returns the stored response and applies the update once`() {
            val branchId = createBranch()
            val key = uuidV7().toString()
            val body = """{"branch_name":"Replayed","address":{"city":"Mombasa"}}"""

            val first = patchRaw(branchId, body, key)
            val second = patchRaw(branchId, body, key)

            assertEquals(200, first.status)
            assertEquals(first.status, second.status)
            assertEquals(first.contentAsString, second.contentAsString)
            assertEquals(1L, branchColumn(branchId, BRANCH.ROW_VERSION))
            assertEquals(1, updateAudits(branchId).size)

            // The same key with a different body is a misuse, not a second update.
            val reused = patchRaw(branchId, """{"branch_name":"Different"}""", key)
            assertTrue(reused.status in 400..499, "key reuse answered ${reused.status}")
            assertEquals("Replayed", branchColumn(branchId, BRANCH.BRANCH_NAME))
        }

        @Test
        fun `the update is audited by field name and never by address content`() {
            val branchId = createBranch()

            patch(
                branchId,
                """{"branch_name":"Audited","address":{"line1":"7 Confidential Close"}}""",
            ).andExpect { status { isOk() } }

            val audit = updateAudits(branchId).single()
            assertEquals("BRANCH", audit.entityType)
            assertEquals(maker, audit.actorUserId)
            assertEquals("SUCCESS", audit.outcome)
            assertTrue(audit.metadata.contains("branch_name,address"), audit.metadata)
            assertFalse(audit.metadata.contains("Confidential"), audit.metadata)
            assertFalse(audit.metadata.contains("Audited"), audit.metadata)
        }

        private fun assertDetail(
            branchId: UUID,
            status: String,
            name: String,
            timezone: String,
            city: String,
        ) {
            mockMvc
                .get("${ApiPaths.BRANCHES}/$branchId") {
                    with(authentication(token(maker, organisationId, "branch.view")))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value(status) }
                    jsonPath("$.branch_name") { value(name) }
                    jsonPath("$.timezone") { value(timezone) }
                    jsonPath("$.address.city") { value(city) }
                }
        }

        private fun createBranch(): UUID = createBranchIn(organisationId, maker)

        private fun createActiveBranch(): UUID =
            createBranch().also {
                post("submit", it, makerToken())
                post("activate", it, checkerToken("branch.approve"))
            }

        private fun createBranchIn(
            tenantId: UUID,
            actor: UUID,
        ): UUID {
            val body =
                mockMvc
                    .post(ApiPaths.BRANCHES) {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                CreateBranchRequest(
                                    branchCode = "BR-${uuidV7().toString().takeLast(
                                        8,
                                    ).uppercase()}",
                                    branchName = "Riverside Branch",
                                    branchType = "OPERATIONAL",
                                    timezone = "Africa/Nairobi",
                                    address = mapOf("city" to "Nairobi"),
                                ),
                            )
                        with(authentication(token(actor, tenantId, "branch.create")))
                    }.andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("branch_id")
                    .asString(),
            )
        }

        private fun foreignBranch(): UUID {
            val owner = seedUser("foreign-owner")
            val foreignOrganisation = fixture.createActiveOrganisation("branch-foreign", owner)
            return createBranchIn(foreignOrganisation, owner)
        }

        private fun patch(
            branchId: UUID,
            body: String,
            token: AppPrincipalAuthenticationToken = editorToken(),
        ): ResultActionsDsl =
            mockMvc.patch("${ApiPaths.BRANCHES}/$branchId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token))
            }

        private fun patchRaw(
            branchId: UUID,
            body: String,
            key: String,
        ) = mockMvc
            .patch("${ApiPaths.BRANCHES}/$branchId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(editorToken()))
            }.andReturn()
            .response

        private fun post(
            action: String,
            branchId: UUID,
            token: AppPrincipalAuthenticationToken,
            body: String = "{}",
        ) {
            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/$action") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                    with(authentication(token))
                }.andExpect { status { isOk() } }
        }

        private fun <T> branchColumn(
            branchId: UUID,
            field: org.jooq.Field<T>,
        ): T? =
            dsl
                .select(field)
                .from(BRANCH)
                .where(BRANCH.ID.eq(branchId))
                .fetchOne(field)

        private fun branchCode(branchId: UUID): String =
            branchColumn(branchId, BRANCH.BRANCH_CODE)!!

        private fun headOfficeId(): UUID =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.BRANCH_TYPE.eq("HEAD_OFFICE"))
                    .fetchOne(BRANCH.ID),
            )

        private data class UpdateAudit(
            val entityType: String,
            val actorUserId: UUID?,
            val outcome: String,
            val metadata: String,
        )

        private fun updateAudits(branchId: UUID): List<UpdateAudit> =
            dsl
                .select(
                    AUDIT_EVENT.ENTITY_TYPE,
                    AUDIT_EVENT.ACTOR_USER_ID,
                    AUDIT_EVENT.OUTCOME,
                    AUDIT_EVENT.METADATA_JSONB,
                ).from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ENTITY_ID.eq(branchId))
                .and(AUDIT_EVENT.ACTION.eq("branch.update"))
                .fetch {
                    UpdateAudit(
                        it.value1()!!,
                        it.value2(),
                        it.value3()!!,
                        it.value4()!!.data(),
                    )
                }

        private fun makerToken() = token(maker, organisationId, "branch.create")

        private fun editorToken() = token(maker, organisationId, "branch.update")

        private fun checkerToken(
            permission: String,
            actor: UUID = checker,
        ) = token(actor, organisationId, permission)

        private fun token(
            userId: UUID,
            tenantId: UUID,
            permission: String,
            pinnedTo: UUID? = null,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = pinnedTo,
                email = "user@branch-update.test",
                fullName = "Branch Update User",
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
                .set(USER_ACCOUNT.EMAIL, "$label-$id@branch-update.test")
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
