package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AmendTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.InitialAdminDto
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.hamcrest.Matchers.containsString
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full-stack proof of returning a pending tenant to draft (issue #181, ADR 0029 3c): the checker's
 * return with its reason, the maker's amendment and resubmission, the bootstrap record that stays
 * consistent across the loop, maker-checker on every later approval, the exposed `status_reason`,
 * the permission and check order, and replay.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantReturnIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        private fun admin() = seedUser("padmin").also { fixture.grantPlatformSuperAdmin(it) }

        @Test
        @Suppress("LongMethod") // One sequential story: return, amend, resubmit, approve.
        fun `a checker returns a tenant and the maker amends and resubmits it to approval`() {
            val maker = admin()
            val firstSubmitter = admin()
            val returner = admin()
            val resubmitter = admin()
            val tenantId = draftTenant(maker, "first-admin-${shortId()}@tenant.test")
            submit(tenantId, firstSubmitter).andExpect { status { isOk() } }
            val requested = bootstrapRow(tenantId)
            assertEquals(firstSubmitter.toString(), requested["submitted_by"])
            assertEquals(1, approvalRequestedEvents(tenantId))

            returnTenant(tenantId, returner).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(tenantId.toString()) }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.status_reason") { value(REASON) }
            }

            assertEquals("DRAFT", tenantColumn(tenantId, "status"))
            assertEquals(REASON, tenantColumn(tenantId, "status_reason"))
            // The record is a draft again: no submitter, no approver, the maker and the
            // administrator block untouched.
            val returned = bootstrapRow(tenantId)
            assertEquals("DRAFT", returned["status"])
            assertNull(returned["submitted_by"])
            assertNull(returned["submitted_at"])
            assertNull(returned["approved_by"])
            assertNull(returned["approved_at"])
            assertEquals(maker.toString(), returned["requested_by"])
            assertEquals(requested["admin_email"], returned["admin_email"])
            assertEquals(requested["admin_username"], returned["admin_username"])
            assertEquals(requested["admin_display_name"], returned["admin_display_name"])
            assertEquals(requested["admin_phone_e164"], returned["admin_phone_e164"])
            // The FSM row and the audit row carry the reason and the returning checker.
            val transition = transitionRows(tenantId, "RETURN_FOR_CHANGES").single()
            assertEquals(Triple("PENDING_APPROVAL", "DRAFT", REASON), transition.first)
            assertEquals(returner, transition.second)
            val audit = auditRows(tenantId, "organisation.return_for_changes").single()
            assertEquals(REASON, audit.reason)
            assertEquals(returner, audit.actor)
            // Internal event only: the return reaches neither the outbox nor the broker.
            assertEquals(0, outboxRecords("RETURN_FOR_CHANGES", tenantId))
            assertEquals(1, approvalRequestedEvents(tenantId))

            // The maker reads why it was returned on the tenant detail.
            getTenant(tenantId, maker).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.status_reason") { value(REASON) }
            }

            // The returned draft is the maker's to amend, which replaces the administrator block.
            val amendedEmail = "amended-admin-${shortId()}@tenant.test"
            amend(tenantId, maker, amendedEmail).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.status_reason") { value(REASON) }
            }
            val amended = bootstrapRow(tenantId)
            assertEquals(amendedEmail, amended["admin_email"])
            assertEquals("DRAFT", amended["status"])
            assertEquals(maker.toString(), amended["requested_by"])

            // A returned tenant cannot be approved without being resubmitted.
            approve(tenantId, admin()).andExpect { status { isConflict() } }
            assertEquals("DRAFT", tenantColumn(tenantId, "status"))

            // The resubmitter is the submitter of the new request.
            submit(tenantId, resubmitter).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("PENDING_APPROVAL") }
            }
            val resubmitted = bootstrapRow(tenantId)
            assertEquals("PENDING_ACTIVATION", resubmitted["status"])
            assertEquals(resubmitter.toString(), resubmitted["submitted_by"])
            assertEquals(2, approvalRequestedEvents(tenantId))

            // Maker-checker holds across the loop: neither the requester nor the new submitter.
            approve(tenantId, maker).andExpect { status { isForbidden() } }
            approve(tenantId, resubmitter).andExpect { status { isForbidden() } }
            assertEquals("PENDING_APPROVAL", tenantColumn(tenantId, "status"))
            // The checker who returned it is neither maker nor submitter, so may approve it
            // (ADR 0029 3c: returners and amenders are not makers).
            approve(tenantId, returner).andExpect {
                status { isAccepted() }
                jsonPath("$.status") { value("ACTIVE") }
            }

            assertEquals("ACTIVE", tenantColumn(tenantId, "status"))
            val approved = bootstrapRow(tenantId)
            assertEquals("QUEUED", approved["status"])
            assertEquals(returner.toString(), approved["approved_by"])
            assertEquals(resubmitter.toString(), approved["submitted_by"])
            assertEquals(maker.toString(), approved["requested_by"])
            assertEquals(amendedEmail, approved["admin_email"])
        }

        @Test
        fun `an approver who is the amended initial administrator is still refused`() {
            val maker = admin()
            val submitter = admin()
            val returner = admin()
            val other = admin()
            val tenantId = draftTenant(maker, "before-${shortId()}@tenant.test")
            submit(tenantId, submitter).andExpect { status { isOk() } }
            returnTenant(tenantId, returner).andExpect { status { isOk() } }

            // The amendment names the returning checker's own account as the administrator.
            amend(tenantId, maker, emailOf(returner)).andExpect { status { isOk() } }
            submit(tenantId, submitter).andExpect { status { isOk() } }

            approve(tenantId, returner).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("lifecycle.approver_is_initial_administrator") }
            }
            assertEquals("PENDING_APPROVAL", tenantColumn(tenantId, "status"))
            assertEquals("PENDING_ACTIVATION", bootstrapRow(tenantId)["status"])
            approve(tenantId, other).andExpect { status { isAccepted() } }
            assertEquals("QUEUED", bootstrapRow(tenantId)["status"])
        }

        @Test
        fun `neither the requester nor the submitter can return a tenant`() {
            val maker = admin()
            val submitter = admin()
            val checker = admin()
            val tenantId = draftTenant(maker, "adm-${shortId()}@tenant.test")
            submit(tenantId, submitter).andExpect { status { isOk() } }

            listOf(maker, submitter).forEach { actor ->
                returnTenant(tenantId, actor).andExpect { status { isForbidden() } }
            }

            assertEquals("PENDING_APPROVAL", tenantColumn(tenantId, "status"))
            assertEquals(submitter.toString(), bootstrapRow(tenantId)["submitted_by"])
            assertTrue(transitionRows(tenantId, "RETURN_FOR_CHANGES").isEmpty())
            assertTrue(auditRows(tenantId, "organisation.return_for_changes").isEmpty())
            // A third party still can.
            returnTenant(tenantId, checker).andExpect { status { isOk() } }
            // After a resubmission by the maker the maker is the submitter and still cannot.
            submit(tenantId, maker).andExpect { status { isOk() } }
            returnTenant(tenantId, maker).andExpect { status { isForbidden() } }
            assertEquals("PENDING_APPROVAL", tenantColumn(tenantId, "status"))
        }

        @Test
        fun `a role holding only tenant reject returns and reads the response back`() {
            val maker = admin()
            val narrow = seedUser("narrow")
            fixture.grantPlatformPermissionsOnly(narrow, "tenant.reject")
            val tenantId = draftTenant(maker, "adm-${shortId()}@tenant.test")
            submit(tenantId, admin()).andExpect { status { isOk() } }

            // The role holds no tenant.view: the response must not need it, or the return would
            // commit and still answer 403.
            returnTenant(tenantId, narrow, setOf("tenant.reject")).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(tenantId.toString()) }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.status_reason") { value(REASON) }
            }
            assertEquals("DRAFT", tenantColumn(tenantId, "status"))
        }

        @Test
        fun `a missing permission is 403 before any existence signal`() {
            val maker = admin()
            val approverOnly = seedUser("approver-only")
            fixture.grantPlatformPermissionsOnly(approverOnly, "tenant.approve", "tenant.view")
            val tenantId = draftTenant(maker, "adm-${shortId()}@tenant.test")
            submit(tenantId, admin()).andExpect { status { isOk() } }

            // The coarse authority on the token is claimed but not granted: a real tenant, the
            // platform organisation and an unknown id answer alike.
            listOf(tenantId, PlatformOrganisation.ID, uuidV7()).forEach { target ->
                returnTenant(target, approverOnly, setOf("tenant.reject"))
                    .andExpect { status { isForbidden() } }
            }
            // And without the authority at all, the coarse gate refuses.
            returnTenant(tenantId, approverOnly, setOf("tenant.approve"))
                .andExpect { status { isForbidden() } }
            assertEquals("PENDING_APPROVAL", tenantColumn(tenantId, "status"))
        }

        @Test
        fun `an unknown tenant and the platform organisation are not found for a checker`() {
            val checker = admin()

            listOf(uuidV7(), PlatformOrganisation.ID).forEach { target ->
                returnTenant(target, checker).andExpect { status { isNotFound() } }
            }
            // A tenant context never reaches the platform route.
            val owner = seedUser("owner")
            val tenantId = fixture.createActiveOrganisation("tenant-return-ctx", owner)
            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/return") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = REASON_BODY
                    with(authentication(token(owner, tenantId, COARSE)))
                }.andExpect { status { isForbidden() } }
        }

        @Test
        fun `only a pending tenant can be returned and a rejected one stays rejected`() {
            val maker = admin()
            val checker = admin()
            val draft = draftTenant(maker, "d-${shortId()}@tenant.test")
            val active = draftTenant(maker, "a-${shortId()}@tenant.test")
            submit(active, admin()).andExpect { status { isOk() } }
            approve(active, checker).andExpect { status { isAccepted() } }
            val rejected = draftTenant(maker, "r-${shortId()}@tenant.test")
            submit(rejected, admin()).andExpect { status { isOk() } }
            post(
                "${ApiPaths.PLATFORM_TENANTS}/$rejected/reject",
                checker,
                """{"reason":"Documents incomplete."}""",
            ).andExpect { status { isOk() } }
            val suspended = draftTenant(maker, "s-${shortId()}@tenant.test")
            submit(suspended, admin()).andExpect { status { isOk() } }
            approve(suspended, checker).andExpect { status { isAccepted() } }
            post(
                "${ApiPaths.PLATFORM_TENANTS}/$suspended/suspend",
                checker,
                """{"reason":"Regulatory review."}""",
            ).andExpect { status { isOk() } }

            mapOf(
                draft to "DRAFT",
                active to "ACTIVE",
                rejected to "REJECTED",
                suspended to "SUSPENDED",
            ).forEach { (tenantId, status) ->
                returnTenant(tenantId, checker).andExpect { status { isConflict() } }
                assertEquals(status, tenantColumn(tenantId, "status"), "unchanged")
                assertTrue(transitionRows(tenantId, "RETURN_FOR_CHANGES").isEmpty())
                assertTrue(auditRows(tenantId, "organisation.return_for_changes").isEmpty())
            }
            // The rejected tenant's record still describes a draft, as reject always left it.
            assertEquals("DRAFT", bootstrapRow(rejected)["status"])
        }

        @Test
        fun `a missing blank or out of range reason is a 400 and changes nothing`() {
            val maker = admin()
            val checker = admin()
            val tenantId = draftTenant(maker, "adm-${shortId()}@tenant.test")
            submit(tenantId, admin()).andExpect { status { isOk() } }

            listOf(null, "{}", """{"reason":null}""").forEach { body ->
                returnTenant(tenantId, checker, body = body).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("invalid_json") }
                }
            }
            listOf("", "  ", "ab", "x".repeat(501)).forEach { reason ->
                returnTenant(
                    tenantId,
                    checker,
                    body = apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason)),
                ).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }
            }

            assertEquals("PENDING_APPROVAL", tenantColumn(tenantId, "status"))
            assertTrue(transitionRows(tenantId, "RETURN_FOR_CHANGES").isEmpty())
        }

        @Test
        fun `a replayed key returns the stored response and returns the tenant once`() {
            val maker = admin()
            val checker = admin()
            val tenantId = draftTenant(maker, "adm-${shortId()}@tenant.test")
            submit(tenantId, admin()).andExpect { status { isOk() } }
            val key = uuidV7().toString()

            val first = returnRaw(tenantId, checker, key)
            val second = returnRaw(tenantId, checker, key)

            assertEquals(200, first.status)
            assertEquals(first.status, second.status)
            assertEquals(first.contentAsString, second.contentAsString)
            assertEquals(1, transitionRows(tenantId, "RETURN_FOR_CHANGES").size)
            assertEquals(1, auditRows(tenantId, "organisation.return_for_changes").size)
            // A fresh key against the now-draft tenant is a conflict, not a second return.
            returnTenant(tenantId, checker).andExpect { status { isConflict() } }
        }

        @Test
        fun `the tenant's own detail exposes its status reason, null when there is none`() {
            val maker = admin()
            val checker = admin()
            val tenantId = draftTenant(maker, "adm-${shortId()}@tenant.test")
            submit(tenantId, admin()).andExpect { status { isOk() } }
            post(
                "${ApiPaths.PLATFORM_TENANTS}/$tenantId/approve",
                checker,
                """{"reason":"KYC pack reviewed."}""",
            ).andExpect { status { isAccepted() } }
            val tenantAdmin = seedUser("tenant-admin")
            fixture.grantTenantAdmin(tenantId, tenantAdmin)

            getTenant(tenantId, checker).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ACTIVE") }
                jsonPath("$.status_reason") { value("KYC pack reviewed.") }
            }
            mockMvc
                .get(ApiPaths.TENANT) {
                    with(authentication(token(tenantAdmin, tenantId, setOf("tenant.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("ACTIVE") }
                    jsonPath("$.status_reason") { value("KYC pack reviewed.") }
                }
        }

        @Test
        fun `a tenant with no status reason carries an explicit null on both detail routes`() {
            val maker = admin()
            val tenantId = draftTenant(maker, "adm-${shortId()}@tenant.test")
            // A fresh draft has had no transition, so no reason: present and null, not omitted.
            // `doesNotExist` would also pass for null, so assert the key on the wire.
            getTenant(tenantId, maker).andExpect {
                status { isOk() }
                content { string(containsString("\"status_reason\":null")) }
            }

            submit(tenantId, admin()).andExpect { status { isOk() } }
            post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/approve", admin(), null)
                .andExpect { status { isAccepted() } }
            val tenantAdmin = seedUser("tenant-admin")
            fixture.grantTenantAdmin(tenantId, tenantAdmin)

            mockMvc
                .get(ApiPaths.TENANT) {
                    with(authentication(token(tenantAdmin, tenantId, setOf("tenant.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("ACTIVE") }
                    content { string(containsString("\"status_reason\":null")) }
                }
        }

        // -- helpers -----------------------------------------------------------------------

        /** Creates a tenant draft naming [adminEmail] as its administrator, made by [maker]. */
        private fun draftTenant(
            maker: UUID,
            adminEmail: String,
        ): UUID {
            val code = "ret-${shortId()}"
            val body =
                post(
                    ApiPaths.PLATFORM_TENANTS,
                    maker,
                    apiJsonCodec.mapper.writeValueAsString(
                        CreateTenantDraftRequest(
                            tenantCode = code,
                            displayName = "Return Tenant",
                            legalName = "Return Tenant Ltd",
                            registrationNumber = "REG-${shortId()}",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin = adminDto(adminEmail, code),
                        ),
                    ),
                ).andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("organisation_id")
                    .asString(),
            )
        }

        private fun adminDto(
            email: String,
            code: String,
        ) = InitialAdminDto(
            email = email,
            username = "admin-$code",
            displayName = "Initial Admin",
            phoneE164 = "+254700000000",
            sendApplicationInvite = false,
        )

        private fun amend(
            tenantId: UUID,
            actor: UUID,
            adminEmail: String,
        ): ResultActionsDsl =
            mockMvc.patch("${ApiPaths.PLATFORM_TENANTS}/$tenantId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content =
                    apiJsonCodec.mapper.writeValueAsString(
                        AmendTenantDraftRequest(
                            tenantCode = tenantColumn(tenantId, "tenant_code")!!,
                            displayName = "Return Tenant Amended",
                            legalName = "Return Tenant Ltd",
                            registrationNumber = "REG-${shortId()}",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin = adminDto(adminEmail, "amended-${shortId()}"),
                        ),
                    )
                with(authentication(token(actor, PlatformOrganisation.ID, COARSE)))
            }

        private fun submit(
            tenantId: UUID,
            actor: UUID,
        ) = post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/submit", actor, null)

        private fun approve(
            tenantId: UUID,
            actor: UUID,
        ) = post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/approve", actor, null)

        private fun returnTenant(
            tenantId: UUID,
            actor: UUID,
            authorities: Set<String> = COARSE,
            body: String? = REASON_BODY,
        ): ResultActionsDsl =
            mockMvc.post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/return") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                with(authentication(token(actor, PlatformOrganisation.ID, authorities)))
            }

        private fun returnRaw(
            tenantId: UUID,
            actor: UUID,
            key: String,
        ) = mockMvc
            .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/return") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = REASON_BODY
                with(authentication(token(actor, PlatformOrganisation.ID, COARSE)))
            }.andReturn()
            .response

        private fun getTenant(
            tenantId: UUID,
            actor: UUID,
        ): ResultActionsDsl =
            mockMvc.get("${ApiPaths.PLATFORM_TENANTS}/$tenantId") {
                with(authentication(token(actor, PlatformOrganisation.ID, COARSE)))
            }

        private fun post(
            path: String,
            actor: UUID,
            body: String?,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = it
                }
                with(authentication(token(actor, PlatformOrganisation.ID, COARSE)))
            }

        private fun tenantColumn(
            tenantId: UUID,
            column: String,
        ): String? =
            dsl
                .fetchOne("SELECT $column::text FROM organisation WHERE id = ?", tenantId)!!
                .get(0, String::class.java)

        private fun bootstrapRow(tenantId: UUID): Map<String, String?> {
            val record =
                dsl.fetchOne(
                    "SELECT * FROM organisation_initial_administrator_bootstrap " +
                        "WHERE organisation_id = ?",
                    tenantId,
                )!!
            return record.fields().associate { it.name to record.get(it)?.toString() }
        }

        /** (from, to, reason) and the actor of each log row of [transition]. */
        private fun transitionRows(
            tenantId: UUID,
            transition: String,
        ): List<Pair<Triple<String, String, String?>, UUID?>> =
            dsl
                .fetch(
                    "SELECT status_from, status_to, reason, created_by " +
                        "FROM organisation_transition_log " +
                        "WHERE entity_id = ? AND transition_name = ? ORDER BY created_at",
                    tenantId,
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
            tenantId: UUID,
            action: String,
        ): List<AuditRow> =
            dsl
                .fetch(
                    "SELECT actor_user_id, reason FROM audit_event " +
                        "WHERE entity_id = ? AND action = ? AND outcome = 'SUCCESS' " +
                        "ORDER BY event_time",
                    tenantId,
                    action,
                ).map { AuditRow(it.get(0, UUID::class.java), it.get(1, String::class.java)) }

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

        private fun approvalRequestedEvents(tenantId: UUID): Int =
            outboxRecords("finaxis.lifecycle.organisation.approval-requested", tenantId)

        private fun emailOf(userId: UUID): String =
            requireNotNull(
                dsl
                    .select(USER_ACCOUNT.EMAIL)
                    .from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.ID.eq(userId))
                    .fetchOne(USER_ACCOUNT.EMAIL),
            )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun shortId(): String = uuidV7().toString().takeLast(8)

        private fun token(
            userId: UUID,
            organisationId: UUID,
            permissions: Set<String>,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "it-user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "it@example.test",
                fullName = "Integration User",
                permissions = permissions,
            ),
        )

        private companion object {
            const val REASON = "Registration number has a typo."
            const val REASON_BODY = """{"reason":"$REASON"}"""

            /**
             * Every coarse authority any route here needs. The coarse gate only filters; the
             * application layer re-checks the caller's real permissions in the database.
             */
            val COARSE =
                setOf(
                    "tenant.create",
                    "tenant.update_draft",
                    "tenant.submit_for_approval",
                    "tenant.approve",
                    "tenant.reject",
                    "tenant.suspend",
                    "tenant.view",
                    "audit.view",
                )
        }
    }
