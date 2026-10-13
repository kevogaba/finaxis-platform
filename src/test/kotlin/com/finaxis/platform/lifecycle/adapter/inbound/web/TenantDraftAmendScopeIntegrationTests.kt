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
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
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
 * Full-stack proof of what a tenant draft amend may and may not discard (issue #166). An amend
 * without `admin` leaves the stored administrator draft alone; with `admin` it is a full replace
 * that must state `send_application_invite`; a non-empty `initial_settings` or a `business_date`
 * is refused with 422 and nothing is written. The bodies are maps, so an omitted key is truly
 * absent on the wire.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantDraftAmendScopeIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val maker = seedUser("maker").also(fixture::grantPlatformSuperAdmin)

        @Test
        fun `an amend without an administrator keeps the stored administrator draft`() {
            val tenantId = createdId(create())
            val adminBefore = storedAdmin(tenantId)

            amend(tenantId, core()).andExpect { status { isOk() } }

            assertEquals(adminBefore, storedAdmin(tenantId))
            assertTrue(adminBefore.sendApplicationInvite)
        }

        @Test
        fun `an amend with an administrator but without send_application_invite is a 400`() {
            val tenantId = createdId(create())
            val before = writtenState(tenantId)

            amend(tenantId, core() + ("admin" to adminWithoutInvite()))
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }

            assertEquals(before, writtenState(tenantId))
        }

        @Test
        fun `an amend with an administrator replaces the stored administrator draft`() {
            val tenantId = createdId(create())

            amend(tenantId, core() + ("admin" to adminMap(invite = false)))
                .andExpect { status { isOk() } }

            val stored = storedAdmin(tenantId)
            assertEquals("replaced-admin@amend-scope.test", stored.email)
            assertEquals(false, stored.sendApplicationInvite)
        }

        @Test
        fun `a non-empty initial_settings on amend is a 422 and writes nothing`() {
            val tenantId = createdId(create())
            val before = writtenState(tenantId)

            amend(
                tenantId,
                core() + ("initial_settings" to mapOf("audit_retention_days" to "30")) +
                    ("admin" to adminMap()),
            ).andExpect {
                status { isUnprocessableContent() }
                jsonPath("$.code") { value("invalid_operation") }
                jsonPath("$.detail") {
                    value(
                        "initial_settings cannot be changed by an amend.",
                    )
                }
            }

            assertEquals(before, writtenState(tenantId))
        }

        @Test
        fun `a business_date on amend is a 422 and writes nothing`() {
            val tenantId = createdId(create())
            val before = writtenState(tenantId)

            amend(tenantId, core() + ("business_date" to "20-07-2026") + ("admin" to adminMap()))
                .andExpect {
                    status { isUnprocessableContent() }
                    jsonPath("$.code") { value("invalid_operation") }
                    jsonPath("$.detail") { value("business_date cannot be changed by an amend.") }
                }

            assertEquals(before, writtenState(tenantId))
        }

        @Test
        fun `an empty initial_settings object on amend is accepted`() {
            val tenantId = createdId(create())

            amend(
                tenantId,
                core() + ("initial_settings" to emptyMap<String, String>()) +
                    ("admin" to adminMap()),
            ).andExpect { status { isOk() } }

            assertEquals("Amended Tenant", storedTenant(tenantId).displayName)
        }

        private fun create(): ResultActionsDsl =
            mockMvc.post(ApiPaths.PLATFORM_TENANTS) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content =
                    apiJsonCodec.mapper.writeValueAsString(
                        CreateTenantDraftRequest(
                            tenantCode = "scope-${shortId()}",
                            displayName = "Scope Tenant",
                            legalName = "Scope Ltd",
                            registrationNumber = "REG-SCOPE-1",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin =
                                InitialAdminDto(
                                    email = "admin-${shortId()}@amend-scope.test",
                                    username = "admin-${shortId()}",
                                    displayName = "Initial Admin",
                                    phoneE164 = "+254700000000",
                                    sendApplicationInvite = true,
                                ),
                        ),
                    )
                with(authentication(token("tenant.create")))
            }

        private fun amend(
            tenantId: UUID,
            body: Map<String, Any?>,
        ): ResultActionsDsl =
            mockMvc.patch("${ApiPaths.PLATFORM_TENANTS}/$tenantId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = apiJsonCodec.mapper.writeValueAsString(body)
                with(authentication(token("tenant.update_draft", "tenant.view")))
            }

        /** The required tenant fields of an amend, without any administrator block. */
        private fun core(): Map<String, Any?> =
            mapOf(
                "tenant_code" to "amended-${shortId()}",
                "display_name" to "Amended Tenant",
                "country_code" to "KE",
                "base_currency_code" to "KES",
                "timezone" to "Africa/Nairobi",
            )

        private fun adminMap(invite: Boolean = true): Map<String, Any?> =
            mapOf(
                "email" to "replaced-admin@amend-scope.test",
                "username" to "replaced-${shortId()}",
                "display_name" to "Replaced Admin",
                "phone_e164" to "+254711111111",
                "send_application_invite" to invite,
            )

        private fun adminWithoutInvite(): Map<String, Any?> = adminMap() - "send_application_invite"

        private fun createdId(result: ResultActionsDsl): UUID {
            val body =
                result
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("organisation_id")
                    .asString(),
            )
        }

        private data class StoredTenant(
            val tenantCode: String,
            val displayName: String,
        )

        private fun storedTenant(tenantId: UUID): StoredTenant {
            val row =
                dsl
                    .select(ORGANISATION.TENANT_CODE, ORGANISATION.DISPLAY_NAME)
                    .from(ORGANISATION)
                    .where(ORGANISATION.ID.eq(tenantId))
                    .fetchSingle()
            return StoredTenant(
                tenantCode = requireNotNull(row.get(ORGANISATION.TENANT_CODE)),
                displayName = requireNotNull(row.get(ORGANISATION.DISPLAY_NAME)),
            )
        }

        /** The administrator draft row, including its row version so any write shows. */
        private data class StoredAdmin(
            val email: String,
            val username: String,
            val displayName: String,
            val phoneE164: String?,
            val sendApplicationInvite: Boolean,
            val rowVersion: Long,
        )

        private fun storedAdmin(tenantId: UUID): StoredAdmin {
            val t = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
            val row =
                dsl
                    .select(
                        t.ADMIN_EMAIL,
                        t.ADMIN_USERNAME,
                        t.ADMIN_DISPLAY_NAME,
                        t.ADMIN_PHONE_E164,
                        t.SEND_APPLICATION_INVITE,
                        t.ROW_VERSION,
                    ).from(t)
                    .where(t.ORGANISATION_ID.eq(tenantId))
                    .fetchSingle()
            return StoredAdmin(
                email = requireNotNull(row.get(t.ADMIN_EMAIL)),
                username = requireNotNull(row.get(t.ADMIN_USERNAME)),
                displayName = requireNotNull(row.get(t.ADMIN_DISPLAY_NAME)),
                phoneE164 = row.get(t.ADMIN_PHONE_E164),
                sendApplicationInvite = requireNotNull(row.get(t.SEND_APPLICATION_INVITE)),
                rowVersion = requireNotNull(row.get(t.ROW_VERSION)),
            )
        }

        /**
         * Everything a refused or rejected amend must leave as it was: the draft, the stored
         * administrator, the settings rows and the audit log. Counting rows, not only reading the
         * two stored records, is what proves nothing was written beside them.
         */
        private fun writtenState(tenantId: UUID): List<Any> =
            listOf(
                storedTenant(tenantId),
                storedAdmin(tenantId),
                settingRowCount(tenantId),
                auditRowCount(tenantId),
            )

        private fun settingRowCount(tenantId: UUID): Int =
            dsl
                .selectCount()
                .from(ORGANISATION_SETTING)
                .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(tenantId))
                .fetchOne(0, Int::class.java) ?: 0

        private fun auditRowCount(tenantId: UUID): Int =
            dsl
                .selectCount()
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(tenantId))
                .fetchOne(0, Int::class.java) ?: 0

        private fun token(vararg permissions: String) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = maker,
                    keycloakSubject = "user-$maker",
                    organisationId = PlatformOrganisation.ID,
                    membershipId = uuidV7(),
                    branchId = null,
                    email = "maker@amend-scope.test",
                    fullName = "Amend Scope Maker",
                    permissions = permissions.toSet(),
                ),
            )

        private fun shortId(): String = uuidV7().toString().takeLast(12)

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-${id.toString().take(12)}")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@amend-scope.test")
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
