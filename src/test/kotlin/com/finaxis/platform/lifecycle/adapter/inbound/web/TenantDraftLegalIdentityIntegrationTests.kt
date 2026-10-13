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
import com.finaxis.platform.jooq.tables.references.ORGANISATION
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Full-stack proof of the presence-aware `legal_name` and `registration_number` on the tenant
 * amend route (issue #166). An amend that omits them keeps the stored values, an explicit `null`
 * clears them, a legal name over 100 characters is refused with nothing changed, and the tenant
 * detail exposes both. The request bodies are built as maps so an omitted key is truly absent on
 * the wire, not sent as the Kotlin default `null`.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantDraftLegalIdentityIntegrationTests
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
        fun `an amend that omits legal name and registration number keeps both stored values`() {
            val tenantId = createdId(create("Kept Ltd", "REG-KEPT-1"))

            amend(tenantId, emptyMap()).andExpect { status { isOk() } }

            assertEquals(Pair("Kept Ltd", "REG-KEPT-1"), storedLegalIdentity(tenantId))
        }

        @Test
        fun `an explicit null on amend clears legal name and registration number`() {
            val tenantId = createdId(create("Cleared Ltd", "REG-CLEAR-1"))

            amend(tenantId, mapOf("legal_name" to null, "registration_number" to null))
                .andExpect { status { isOk() } }

            assertEquals(Pair(null, null), storedLegalIdentity(tenantId))
        }

        @Test
        fun `a legal name over 100 characters on amend is a 400 and changes nothing`() {
            val tenantId = createdId(create("Limit Ltd", "REG-LIMIT-1"))

            amend(tenantId, mapOf("legal_name" to "L".repeat(101)))
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }

            assertEquals(Pair("Limit Ltd", "REG-LIMIT-1"), storedLegalIdentity(tenantId))
        }

        @Test
        fun `a legal name of 100 characters and a registration number of 50 are kept`() {
            val tenantId = createdId(create("Limit Ltd", "REG-LIMIT-3"))

            amend(
                tenantId,
                mapOf(
                    "legal_name" to "L".repeat(100),
                    "registration_number" to "R".repeat(50),
                ),
            ).andExpect { status { isOk() } }

            assertEquals(Pair("L".repeat(100), "R".repeat(50)), storedLegalIdentity(tenantId))
        }

        @Test
        fun `a registration number over 50 characters on amend is a 400 and changes nothing`() {
            val tenantId = createdId(create("Reg Limit Ltd", "REG-LIMIT-2"))

            amend(tenantId, mapOf("registration_number" to "R".repeat(51)))
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }

            assertEquals(Pair("Reg Limit Ltd", "REG-LIMIT-2"), storedLegalIdentity(tenantId))
        }

        @Test
        fun `the tenant detail exposes legal name and registration number`() {
            val tenantId = createdId(create("Detail Ltd", "REG-DETAIL-1"))

            detail(tenantId).andExpect {
                status { isOk() }
                jsonPath("$.legal_name") { value("Detail Ltd") }
                jsonPath("$.registration_number") { value("REG-DETAIL-1") }
            }
        }

        private fun create(
            legalName: String,
            registrationNumber: String,
        ): ResultActionsDsl =
            mockMvc.post(ApiPaths.PLATFORM_TENANTS) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content =
                    apiJsonCodec.mapper.writeValueAsString(
                        CreateTenantDraftRequest(
                            tenantCode = "legal-${shortId()}",
                            displayName = "Legal Tenant",
                            legalName = legalName,
                            registrationNumber = registrationNumber,
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin = adminDto(),
                        ),
                    )
                with(authentication(token("tenant.create")))
            }

        /** Amends the draft; [legalIdentity] holds only the keys the request sends. */
        private fun amend(
            tenantId: UUID,
            legalIdentity: Map<String, Any?>,
        ): ResultActionsDsl {
            val body =
                mapOf(
                    "tenant_code" to "amended-${shortId()}",
                    "display_name" to "Amended Tenant",
                    "country_code" to "KE",
                    "base_currency_code" to "KES",
                    "timezone" to "Africa/Nairobi",
                    "admin" to adminDto(),
                ) + legalIdentity
            return mockMvc.patch("${ApiPaths.PLATFORM_TENANTS}/$tenantId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = apiJsonCodec.mapper.writeValueAsString(body)
                with(authentication(token("tenant.update_draft", "tenant.view")))
            }
        }

        private fun detail(tenantId: UUID): ResultActionsDsl =
            mockMvc.get("${ApiPaths.PLATFORM_TENANTS}/$tenantId") {
                with(authentication(token("tenant.view")))
            }

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

        private fun storedLegalIdentity(tenantId: UUID): Pair<String?, String?> {
            val row =
                dsl
                    .select(ORGANISATION.LEGAL_NAME, ORGANISATION.REGISTRATION_NUMBER)
                    .from(ORGANISATION)
                    .where(ORGANISATION.ID.eq(tenantId))
                    .fetchSingle()
            return row.get(ORGANISATION.LEGAL_NAME) to row.get(ORGANISATION.REGISTRATION_NUMBER)
        }

        private fun adminDto() =
            InitialAdminDto(
                email = "admin-${shortId()}@legal-identity.test",
                username = "admin-${shortId()}",
                displayName = "Initial Admin",
                phoneE164 = "+254700000000",
                sendApplicationInvite = false,
            )

        private fun token(vararg permissions: String) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = maker,
                    keycloakSubject = "user-$maker",
                    organisationId = PlatformOrganisation.ID,
                    membershipId = uuidV7(),
                    branchId = null,
                    email = "maker@legal-identity.test",
                    fullName = "Legal Identity Maker",
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
                .set(USER_ACCOUNT.EMAIL, "$label-$id@legal-identity.test")
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
