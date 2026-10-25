package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
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
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full-stack proof of discarding a draft branch (issue #222, ADR 0036): `DRAFT` to `CLOSED` through
 * a transition of its own, the one FSM audit row, no externalized closed event, the code that
 * stays taken, the guards (head office, children, organisation state, source state) and the
 * check order (named 403 before any lookup).
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("LargeClass", "TooManyFunctions")
class BranchDiscardIntegrationTests
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
        private val organisationId = fixture.createActiveOrganisation("branch-discard", maker)

        init {
            fixture.grantTenantAdmin(organisationId, checker)
        }

        @Test
        fun `a draft is discarded to closed with a closing date and one audit row`() {
            val branchId = createBranch()

            discard(branchId, makerToken()).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(branchId.toString()) }
                jsonPath("$.status") { value("CLOSED") }
                jsonPath("$.status_reason") { value(REASON) }
            }

            assertEquals("CLOSED", branchColumn(branchId, "status"))
            assertEquals(REASON, branchColumn(branchId, "status_reason"))
            // Stamped as every other close is: the tenant's current business date.
            assertEquals(businessDate().toString(), branchColumn(branchId, "closed_on"))
            assertNull(branchColumn(branchId, "opened_on"), "a branch that never opened")
            // The transition log tells a discard from a close.
            val transition = transitionRows(branchId, "DISCARD").single()
            assertEquals(Triple("DRAFT", "CLOSED", REASON), transition.first)
            assertEquals(maker, transition.second)
            assertTrue(transitionRows(branchId, "CLOSE").isEmpty())
            assertTrue(transitionRows(branchId, "CLOSE_SUSPENDED").isEmpty())
            // ADR 0031: the FSM row is the whole audit; no service row repeats it.
            val discard = auditRows(branchId, "branch.discard").single()
            assertEquals(maker, discard.actor)
            assertEquals(REASON, discard.reason)
            assertEquals(
                listOf("branch.create_draft", "branch.discard"),
                auditActions(branchId),
            )
        }

        @Test
        fun `a discard announces nothing while closing an active branch still does`() {
            val discarded = createBranch()
            val closed = createBranch()
            submit(closed)
            activate(closed)

            discard(discarded, makerToken()).andExpect { status { isOk() } }
            post(
                "${ApiPaths.BRANCHES}/$closed/close",
                tenantToken(checker, "branch.close"),
                """{"reason":"Consolidated"}""",
            ).andExpect { status { isOk() } }

            // The control: a close does reach the outbox, so the zero below is not vacuous.
            assertTrue(outboxRecords(CLOSED_TARGET, closed) > 0, "closing announces")
            assertEquals(0, outboxRecords(CLOSED_TARGET, discarded))
            assertEquals(0, outboxRecords("DISCARD", discarded))
        }

        @Test
        fun `the code of a discarded branch stays taken`() {
            val branchId = createBranch()
            val code = requireNotNull(branchColumn(branchId, "branch_code"))

            discard(branchId, makerToken()).andExpect { status { isOk() } }

            post(
                ApiPaths.BRANCHES,
                makerToken(),
                apiJsonCodec.mapper.writeValueAsString(
                    CreateBranchRequest(
                        branchCode = code,
                        branchName = "Duplicate",
                        branchType = "OPERATIONAL",
                        timezone = "Africa/Nairobi",
                    ),
                ),
            ).andExpect { status { isConflict() } }
            assertEquals(
                1,
                dsl.fetchCount(
                    BRANCH,
                    BRANCH.ORGANISATION_ID.eq(organisationId).and(BRANCH.BRANCH_CODE.eq(code)),
                ),
            )
        }

        @Test
        fun `only a draft can be discarded and anything else is a conflict`() {
            val pending = createBranch()
            submit(pending)
            val active = createBranch()
            submit(active)
            activate(active)
            val suspended = createBranch()
            submit(suspended)
            activate(suspended)
            post(
                "${ApiPaths.BRANCHES}/$suspended/suspend",
                tenantToken(checker, "branch.suspend"),
                """{"reason":"Audit hold"}""",
            ).andExpect { status { isOk() } }
            val closed = createBranch()
            submit(closed)
            activate(closed)
            post(
                "${ApiPaths.BRANCHES}/$closed/close",
                tenantToken(checker, "branch.close"),
                """{"reason":"Consolidated"}""",
            ).andExpect { status { isOk() } }

            mapOf(
                pending to "PENDING_APPROVAL",
                active to "ACTIVE",
                suspended to "SUSPENDED",
                closed to "CLOSED",
            ).forEach { (branchId, status) ->
                discard(branchId, makerToken()).andExpect { status { isConflict() } }
                assertEquals(status, branchColumn(branchId, "status"), "unchanged")
                assertTrue(transitionRows(branchId, "DISCARD").isEmpty())
                assertTrue(auditRows(branchId, "branch.discard").isEmpty())
            }
        }

        @Test
        fun `the head office draft is refused and stays a draft`() {
            val headOffice = headOfficeId()
            // Provisioning leaves the head office ACTIVE; put it back to the draft it starts as.
            dsl
                .update(BRANCH)
                .set(BRANCH.STATUS, "DRAFT")
                .where(BRANCH.ID.eq(headOffice))
                .execute()
            try {
                discard(headOffice, makerToken()).andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("lifecycle.head_office_branch_protected") }
                }

                assertEquals("DRAFT", branchColumn(headOffice, "status"))
                assertTrue(transitionRows(headOffice, "DISCARD").isEmpty())
                assertTrue(auditRows(headOffice, "branch.discard").isEmpty())
            } finally {
                dsl
                    .update(BRANCH)
                    .set(BRANCH.STATUS, "ACTIVE")
                    .where(BRANCH.ID.eq(headOffice))
                    .execute()
            }
        }

        @Test
        fun `a draft with an active child is refused until the child is closed`() {
            val parent = createBranch()
            val child = createBranch(parent)
            setBranchStatus(child, "ACTIVE")

            val body =
                discard(parent, makerToken())
                    .andExpect { status { isConflict() } }
                    .andReturn()
                    .response.contentAsString
            assertTrue(body.contains("child branches before discarding"), body)
            assertEquals("DRAFT", branchColumn(parent, "status"))
            assertTrue(transitionRows(parent, "DISCARD").isEmpty())

            setBranchStatus(child, "CLOSED")
            discard(parent, makerToken()).andExpect { status { isOk() } }
            assertEquals("CLOSED", branchColumn(parent, "status"))
        }

        @Test
        fun `a draft child under a draft parent is discarded on its own`() {
            val parent = createBranch()
            val child = createBranch(parent)

            discard(child, makerToken()).andExpect { status { isOk() } }

            assertEquals("CLOSED", branchColumn(child, "status"))
            assertEquals("DRAFT", branchColumn(parent, "status"))
        }

        @Test
        fun `a tenant that is not active refuses the discard and the draft is untouched`() {
            val branchId = createBranch()
            try {
                listOf("SUSPENDED", "DEPROVISIONING").forEach { state ->
                    setTenantStatus(state)
                    // A tenant caller is refused by permission resolution outside ACTIVE, so this
                    // route never reaches the organisation-state conflict (the service test pins
                    // that rule); what matters here is that nothing is discarded.
                    discard(branchId, makerToken()).andExpect { status { isForbidden() } }
                    assertEquals("DRAFT", branchColumn(branchId, "status"), state)
                    assertTrue(transitionRows(branchId, "DISCARD").isEmpty())
                }
            } finally {
                setTenantStatus("ACTIVE")
            }
        }

        @Test
        fun `a missing permission is a named 403 before any existence signal`() {
            val branchId = createBranch()
            val otherOwner = seedUser("other-owner")
            val otherOrganisation = fixture.createActiveOrganisation("branch-disc-oth", otherOwner)
            val foreign = createBranchIn(otherOrganisation, otherOwner)
            val reader = seedUser("reader")
            fixture.grantTenantPermissionsWithViews(organisationId, reader, "branch.view")

            // The coarse authority is on the token but the grant is not: 403 naming the code for a
            // real draft, another tenant's branch and an id that does not exist alike.
            listOf(branchId, foreign, uuidV7()).forEach { target ->
                discard(target, tenantToken(reader, "branch.create")).andExpect {
                    status { isForbidden() }
                    jsonPath("$.detail") { value("Missing permission: branch.create.") }
                }
            }
            // Authorised: another tenant's branch and an unknown id are 404.
            listOf(foreign, uuidV7()).forEach { target ->
                discard(target, makerToken()).andExpect { status { isNotFound() } }
            }
            assertEquals("DRAFT", branchColumn(branchId, "status"))
            assertEquals("DRAFT", branchColumn(foreign, "status"))
            assertTrue(auditRows(branchId, "branch.discard").isEmpty())
        }

        @Test
        fun `branch create without its view is refused naming the view`() {
            val branchId = createBranch()
            val noView = seedUser("create-no-view")
            fixture.grantTenantPermissionsExactly(organisationId, noView, "branch.create")

            discard(branchId, tenantToken(noView, "branch.create")).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
                jsonPath("$.detail") { value("Missing permission: branch.view.") }
            }

            assertEquals("DRAFT", branchColumn(branchId, "status"))
            assertTrue(transitionRows(branchId, "DISCARD").isEmpty())
        }

        @Test
        fun `a role holding branch create and its view discards, a branch scoped one cannot`() {
            val makerOnly = seedUser("maker-only")
            fixture.grantTenantPermissionsWithViews(organisationId, makerOnly, "branch.create")
            val branchScoped = seedUser("branch-scoped")
            val draft = createBranch()
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                draft,
                branchScoped,
                "branch.create",
            )

            // A grant scoped to a branch counts only while it is ACTIVE (issue #242), so it
            // confers nothing on a draft: a drafted branch is discarded by a tenant-wide holder.
            discard(draft, tenantToken(branchScoped, "branch.create", headOfficeId()))
                .andExpect {
                    status { isForbidden() }
                    jsonPath("$.detail") { value("Missing permission: branch.create.") }
                }
            assertEquals("DRAFT", branchColumn(draft, "status"))

            discard(draft, tenantToken(makerOnly, "branch.create")).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("CLOSED") }
            }
            assertEquals("CLOSED", branchColumn(draft, "status"))
        }

        @Test
        fun `a missing blank or out of range reason is a 400 and changes nothing`() {
            val branchId = createBranch()

            listOf(null, "{}", """{"reason":null}""").forEach { body ->
                discard(branchId, makerToken(), body).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("invalid_json") }
                }
            }
            listOf("", "  ", "ab", "x".repeat(501)).forEach { reason ->
                discard(
                    branchId,
                    makerToken(),
                    apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason)),
                ).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }
            }

            assertEquals("DRAFT", branchColumn(branchId, "status"))
            assertTrue(transitionRows(branchId, "DISCARD").isEmpty())
        }

        @Test
        fun `a replayed key returns the stored response and discards the branch once`() {
            val branchId = createBranch()
            val key = uuidV7().toString()

            val first = discardRaw(branchId, key)
            val second = discardRaw(branchId, key)

            assertEquals(200, first.status)
            assertEquals(first.status, second.status)
            assertEquals(first.contentAsString, second.contentAsString)
            assertEquals(1, transitionRows(branchId, "DISCARD").size)
            assertEquals(1, auditRows(branchId, "branch.discard").size)
            // A fresh key against the now-closed branch is a conflict, not a second discard.
            discard(branchId, makerToken()).andExpect { status { isConflict() } }
        }

        private fun createBranch(parent: UUID? = null): UUID =
            createBranchIn(organisationId, maker, parent)

        private fun createBranchIn(
            tenantId: UUID,
            actor: UUID,
            parent: UUID? = null,
        ): UUID {
            val body =
                post(
                    ApiPaths.BRANCHES,
                    tenantToken(actor, "branch.create", org = tenantId),
                    branchBody(parent),
                ).andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("branch_id")
                    .asString(),
            )
        }

        private fun branchBody(parent: UUID?) =
            apiJsonCodec.mapper.writeValueAsString(
                CreateBranchRequest(
                    branchCode = "BR-${uuidV7().toString().takeLast(8).uppercase()}",
                    branchName = "Riverside Branch",
                    branchType = "OPERATIONAL",
                    parentBranchId = parent,
                    timezone = "Africa/Nairobi",
                ),
            )

        private fun submit(branchId: UUID) {
            post("${ApiPaths.BRANCHES}/$branchId/submit", makerToken(), "{}")
                .andExpect { status { isOk() } }
        }

        private fun activate(branchId: UUID) {
            post("${ApiPaths.BRANCHES}/$branchId/activate", checkerToken(), "{}")
                .andExpect { status { isOk() } }
        }

        private fun discard(
            branchId: UUID,
            token: AppPrincipalAuthenticationToken,
            body: String? = REASON_BODY,
        ): ResultActionsDsl =
            mockMvc.post("${ApiPaths.BRANCHES}/$branchId/discard") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                with(authentication(token))
            }

        private fun discardRaw(
            branchId: UUID,
            key: String,
        ) = mockMvc
            .post("${ApiPaths.BRANCHES}/$branchId/discard") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = REASON_BODY
                with(authentication(makerToken()))
            }.andReturn()
            .response

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

        private fun branchColumn(
            branchId: UUID,
            column: String,
        ): String? =
            dsl
                .fetchOne("SELECT $column::text FROM branch WHERE id = ?", branchId)!!
                .get(0, String::class.java)

        private fun setBranchStatus(
            branchId: UUID,
            status: String,
        ) {
            dsl
                .update(BRANCH)
                .set(BRANCH.STATUS, status)
                .where(BRANCH.ID.eq(branchId))
                .execute()
        }

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

        private fun businessDate(): LocalDate =
            assertNotNull(
                dsl
                    .fetchOne(
                        "SELECT current_business_date FROM business_date " +
                            "WHERE organisation_id = ?",
                        organisationId,
                    )?.get(0, LocalDate::class.java),
            )

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
        )

        private fun auditRows(
            branchId: UUID,
            action: String,
        ): List<AuditRow> =
            dsl
                .fetch(
                    "SELECT actor_user_id, reason FROM audit_event " +
                        "WHERE entity_id = ? AND action = ? AND outcome = 'SUCCESS' " +
                        "ORDER BY event_time",
                    branchId,
                    action,
                ).map { AuditRow(it.get(0, UUID::class.java), it.get(1, String::class.java)) }

        /** Every action audited against the branch, in order, whatever its outcome. */
        private fun auditActions(branchId: UUID): List<String> =
            dsl
                .fetch(
                    "SELECT action FROM audit_event WHERE entity_id = ? ORDER BY event_time",
                    branchId,
                ).map { it.get(0, String::class.java) }

        private fun outboxRecords(
            marker: String,
            aggregateId: UUID,
        ): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM outbox_record WHERE payload LIKE ? AND payload LIKE ?",
                    "%$marker%",
                    "%$aggregateId%",
                )!!
                .get(0, Int::class.java)

        private fun makerToken() = tenantToken(maker, "branch.create")

        private fun checkerToken() = tenantToken(checker, "branch.approve")

        private fun tenantToken(
            userId: UUID,
            permission: String,
            pinnedTo: UUID? = null,
            org: UUID = organisationId,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = org,
                membershipId = uuidV7(),
                branchId = pinnedTo,
                email = "user@branch-discard.test",
                fullName = "Branch Discard User",
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
                .set(USER_ACCOUNT.EMAIL, "$label-$id@branch-discard.test")
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
            const val CLOSED_TARGET = "finaxis.lifecycle.branch.closed"
            const val REASON = "Opened against the wrong organisation."
            const val REASON_BODY = """{"reason":"$REASON"}"""
        }
    }
