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
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AmendTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.InitialAdminDto
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
import kotlin.test.assertTrue

/**
 * Full-stack proof of the tenant checker rule (#221) on the real platform tenant routes
 * (`/approve`, `/reject`, `/return`): the maker (requester or current submitter) and anyone with a
 * successful `organisation.amend_draft` on the tenant can neither approve nor reject it, each
 * refusal is a named `403` that changes nothing, and its `DENIED` audit row survives the
 * refusal's rollback. A tenant is decided only on the platform routes: no tenant-context route
 * approves, rejects or returns a tenant.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantCheckerRuleIntegrationTests
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
        fun `the requester and the submitter cannot approve, and the refusal is audited`() {
            val maker = admin()
            val submitter = admin()
            val tenantId = draftTenant(maker)
            submit(tenantId, submitter).andExpect { status { isOk() } }

            listOf(maker, submitter).forEach { actor ->
                approve(tenantId, actor).andExpect { refusedAs(MAKER, MAKER_DETAIL) }
            }

            assertEquals("PENDING_APPROVAL", tenantStatus(tenantId))
            assertEquals(listOf(MAKER, MAKER), deniedReasons(tenantId, "organisation.approve"))
            assertEquals(setOf(maker, submitter), deniedActors(tenantId, "organisation.approve"))
            approve(tenantId, admin()).andExpect { status { isAccepted() } }
        }

        @Test
        fun `the requester and the submitter cannot reject their own tenant`() {
            val maker = admin()
            val submitter = admin()
            val tenantId = draftTenant(maker)
            submit(tenantId, submitter).andExpect { status { isOk() } }

            listOf(maker, submitter).forEach { actor ->
                reject(tenantId, actor).andExpect { refusedAs(MAKER, MAKER_DETAIL) }
            }

            assertEquals("PENDING_APPROVAL", tenantStatus(tenantId))
            assertTrue(transitions(tenantId, "REJECT") == 0)
            assertEquals(listOf(MAKER, MAKER), deniedReasons(tenantId, "organisation.reject"))
            // A third party may reject it.
            reject(tenantId, admin()).andExpect { status { isOk() } }
            assertEquals("REJECTED", tenantStatus(tenantId))
        }

        @Test
        fun `the maker refused on return is named and audited`() {
            val maker = admin()
            val tenantId = draftTenant(maker)
            submit(tenantId, admin()).andExpect { status { isOk() } }

            returnTenant(tenantId, maker).andExpect { refusedAs(MAKER, MAKER_DETAIL) }

            assertEquals("PENDING_APPROVAL", tenantStatus(tenantId))
            assertEquals(
                listOf(MAKER),
                deniedReasons(tenantId, "organisation.return_for_changes"),
            )
        }

        @Test
        @Suppress("LongMethod") // One sequential story: return, amend, resubmit, decide.
        fun `an amender stays barred across the loop and a different checker decides`() {
            val maker = admin()
            val returner = admin()
            val amender = admin()
            val resubmitter = admin()
            val checker = admin()
            val tenantId = draftTenant(maker)
            submit(tenantId, admin()).andExpect { status { isOk() } }
            returnTenant(tenantId, returner).andExpect { status { isOk() } }
            amend(tenantId, amender).andExpect { status { isOk() } }
            submit(tenantId, resubmitter).andExpect { status { isOk() } }

            approve(tenantId, amender).andExpect { refusedAs(MODIFIER, MODIFIER_DETAIL) }
            reject(tenantId, amender).andExpect { refusedAs(MODIFIER, MODIFIER_DETAIL) }
            assertEquals("PENDING_APPROVAL", tenantStatus(tenantId))
            assertEquals(listOf(MODIFIER), deniedReasons(tenantId, "organisation.approve"))
            assertEquals(listOf(MODIFIER), deniedReasons(tenantId, "organisation.reject"))
            assertEquals(setOf(amender), deniedActors(tenantId, "organisation.approve"))

            // A second loop, amended by the maker this time: the first amender stays an amender.
            returnTenant(tenantId, amender).andExpect { status { isOk() } }
            amend(tenantId, maker).andExpect { status { isOk() } }
            submit(tenantId, resubmitter).andExpect { status { isOk() } }
            approve(tenantId, amender).andExpect { refusedAs(MODIFIER, MODIFIER_DETAIL) }
            reject(tenantId, amender).andExpect { refusedAs(MODIFIER, MODIFIER_DETAIL) }
            assertEquals("PENDING_APPROVAL", tenantStatus(tenantId))

            // A checker who neither made nor amended it approves (so may the first returner,
            // which only returned it: proved by the next test).
            approve(tenantId, checker).andExpect {
                status { isAccepted() }
                jsonPath("$.status") { value("ACTIVE") }
            }
            assertEquals("ACTIVE", tenantStatus(tenantId))
            assertEquals(
                checker,
                dsl.fetchValue(
                    "SELECT approved_by FROM organisation_initial_administrator_bootstrap " +
                        "WHERE organisation_id = ?",
                    tenantId,
                ) as UUID,
            )
        }

        @Test
        fun `the checker who only returned the tenant may approve the amended resubmission`() {
            val maker = admin()
            val returner = admin()
            val tenantId = draftTenant(maker)
            submit(tenantId, admin()).andExpect { status { isOk() } }
            returnTenant(tenantId, returner).andExpect { status { isOk() } }
            amend(tenantId, admin()).andExpect { status { isOk() } }
            submit(tenantId, admin()).andExpect { status { isOk() } }

            approve(tenantId, returner).andExpect { status { isAccepted() } }

            assertEquals("ACTIVE", tenantStatus(tenantId))
            assertTrue(deniedReasons(tenantId, "organisation.approve").isEmpty())
        }

        @Test
        fun `an amender may reject only a tenant it never amended`() {
            val amender = admin()
            val amended = draftTenant(admin())
            val other = draftTenant(admin())
            amend(amended, amender).andExpect { status { isOk() } }
            submit(amended, admin()).andExpect { status { isOk() } }
            submit(other, admin()).andExpect { status { isOk() } }

            reject(amended, amender).andExpect { refusedAs(MODIFIER, MODIFIER_DETAIL) }
            // The rule reads the amendments of the tenant decided, not the actor's history.
            reject(other, amender).andExpect { status { isOk() } }

            assertEquals("PENDING_APPROVAL", tenantStatus(amended))
            assertEquals("REJECTED", tenantStatus(other))
        }

        // -- helpers -----------------------------------------------------------------------

        private fun org.springframework.test.web.servlet.MockMvcResultMatchersDsl.refusedAs(
            code: String,
            detail: String,
        ) {
            status { isForbidden() }
            jsonPath("$.code") { value(code) }
            jsonPath("$.detail") { value(detail) }
        }

        private fun draftTenant(maker: UUID): UUID {
            val code = "chk-${shortId()}"
            val body =
                post(
                    ApiPaths.PLATFORM_TENANTS,
                    maker,
                    apiJsonCodec.mapper.writeValueAsString(
                        CreateTenantDraftRequest(
                            tenantCode = code,
                            displayName = "Checker Tenant",
                            legalName = "Checker Tenant Ltd",
                            registrationNumber = "REG-${shortId()}",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin = adminDto(code),
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

        private fun adminDto(code: String) =
            InitialAdminDto(
                email = "admin-$code@tenant.test",
                username = "admin-$code",
                displayName = "Initial Admin",
                phoneE164 = "+254700000000",
                sendApplicationInvite = false,
            )

        private fun amend(
            tenantId: UUID,
            actor: UUID,
        ): ResultActionsDsl =
            mockMvc.patch("${ApiPaths.PLATFORM_TENANTS}/$tenantId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content =
                    apiJsonCodec.mapper.writeValueAsString(
                        AmendTenantDraftRequest(
                            tenantCode = tenantColumn(tenantId, "tenant_code"),
                            displayName = "Checker Tenant Amended",
                            legalName = "Checker Tenant Ltd",
                            registrationNumber = "REG-${shortId()}",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin = adminDto("amended-${shortId()}"),
                        ),
                    )
                with(authentication(token(actor)))
            }

        private fun submit(
            tenantId: UUID,
            actor: UUID,
        ) = post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/submit", actor, null)

        private fun approve(
            tenantId: UUID,
            actor: UUID,
        ) = post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/approve", actor, null)

        private fun reject(
            tenantId: UUID,
            actor: UUID,
        ) = post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/reject", actor, REASON_BODY)

        private fun returnTenant(
            tenantId: UUID,
            actor: UUID,
        ) = post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/return", actor, REASON_BODY)

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
                with(authentication(token(actor)))
            }

        private fun tenantStatus(tenantId: UUID): String = tenantColumn(tenantId, "status")

        private fun tenantColumn(
            tenantId: UUID,
            column: String,
        ): String =
            dsl
                .fetchOne("SELECT $column::text FROM organisation WHERE id = ?", tenantId)!!
                .get(0, String::class.java)

        private fun transitions(
            tenantId: UUID,
            transition: String,
        ): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM organisation_transition_log " +
                        "WHERE entity_id = ? AND transition_name = ?",
                    tenantId,
                    transition,
                )!!
                .get(0, Int::class.java)

        /** The `reason` of each committed `DENIED` row of [action] on the tenant, oldest first. */
        private fun deniedReasons(
            tenantId: UUID,
            action: String,
        ): List<String> =
            dsl
                .fetch(
                    "SELECT reason FROM audit_event WHERE organisation_id = ? " +
                        "AND entity_type = 'ORGANISATION' AND entity_id = ? AND action = ? " +
                        "AND outcome = 'DENIED' AND severity = 'HIGH' ORDER BY event_time",
                    tenantId,
                    tenantId,
                    action,
                ).map { it.get(0, String::class.java) }

        private fun deniedActors(
            tenantId: UUID,
            action: String,
        ): Set<UUID> =
            dsl
                .fetch(
                    "SELECT actor_user_id FROM audit_event WHERE entity_id = ? " +
                        "AND action = ? AND outcome = 'DENIED'",
                    tenantId,
                    action,
                ).map { it.get(0, UUID::class.java) }
                .toSet()

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

        private fun token(userId: UUID) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = userId,
                    keycloakSubject = "it-user-$userId",
                    organisationId = PlatformOrganisation.ID,
                    membershipId = uuidV7(),
                    email = "it@example.test",
                    fullName = "Integration User",
                    permissions = COARSE,
                ),
            )

        private companion object {
            const val MAKER = "lifecycle.approver_is_tenant_maker"
            const val MAKER_DETAIL = "The checker cannot be the tenant's requester or submitter."
            const val MODIFIER = "lifecycle.approver_is_tenant_modifier"
            const val MODIFIER_DETAIL = "The checker cannot be someone who amended the tenant."
            const val REASON_BODY = """{"reason":"Documents incomplete."}"""

            /** Coarse gate only; the service re-checks the caller's real platform grants. */
            val COARSE =
                setOf(
                    "tenant.create",
                    "tenant.update_draft",
                    "tenant.submit_for_approval",
                    "tenant.approve",
                    "tenant.reject",
                    "tenant.view",
                )
        }
    }
