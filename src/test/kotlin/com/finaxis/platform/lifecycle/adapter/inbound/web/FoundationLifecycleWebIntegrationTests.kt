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
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.InitialAdminDto
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class FoundationLifecycleWebIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        @MockitoBean
        private lateinit var identityProvisioningGateway: IdentityProvisioningGateway

        @Test
        @Suppress("LongMethod")
        fun `full platform and tenant REST lifecycle end to end`() {
            whenever(identityProvisioningGateway.findOrCreateUser(any()))
                .thenReturn(KeycloakUserRef(subject = UUID.randomUUID().toString(), created = true))

            val makerId = uuidV7()
            val checkerId = uuidV7()
            val tenantCode =
                "web-tenant-" +
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(8)
                        .lowercase()

            // Seed user accounts for maker and checker
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, makerId)
                .set(USER_ACCOUNT.USERNAME, "maker-$tenantCode")
                .set(USER_ACCOUNT.EMAIL, "maker@$tenantCode.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Maker")
                .set(USER_ACCOUNT.STATUS, "DRAFT")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()

            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, checkerId)
                .set(USER_ACCOUNT.USERNAME, "checker-$tenantCode")
                .set(USER_ACCOUNT.EMAIL, "checker@$tenantCode.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Checker")
                .set(USER_ACCOUNT.STATUS, "DRAFT")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()

            val organisationFixture =
                TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
            organisationFixture.grantPlatformSuperAdmin(makerId)
            organisationFixture.grantPlatformSuperAdmin(checkerId)

            // 1. Create tenant draft via POST /api/v1/platform/tenants
            val createRequest =
                CreateTenantDraftRequest(
                    tenantCode = tenantCode,
                    displayName = "Web Integration Tenant",
                    legalName = "Web Integration Tenant Ltd",
                    registrationNumber = "REG-WEB-001",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    admin =
                        InitialAdminDto(
                            email = "admin@$tenantCode.test",
                            username = "admin-$tenantCode",
                            displayName = "Initial Admin",
                            phoneE164 = "+254700000000",
                            sendApplicationInvite = true,
                        ),
                )

            val idempotencyKey = UUID.randomUUID().toString()

            val createResult =
                mockMvc
                    .post(ApiPaths.PLATFORM_TENANTS) {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        contentType = MediaType.APPLICATION_JSON
                        content = apiJsonCodec.mapper.writeValueAsString(createRequest)
                        with(authentication(platformToken(makerId, setOf("tenant.create"))))
                    }.andExpect {
                        status { isCreated() }
                        header { exists("Location") }
                        jsonPath("$.organisation_id") { exists() }
                        jsonPath("$.status") { value("DRAFT") }
                    }.andReturn()

            val responseBody = createResult.response.contentAsString
            val orgId =
                UUID.fromString(
                    apiJsonCodec.mapper
                        .readTree(responseBody)
                        .get("organisation_id")
                        .asString(),
                )

            // 2. Submit tenant draft via POST /api/v1/platform/tenants/{tenant_id}/submit
            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/submit") {
                    header(
                        IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                        UUID.randomUUID().toString(),
                    )
                    with(
                        authentication(platformToken(makerId, setOf("tenant.submit_for_approval"))),
                    )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("PENDING_APPROVAL") }
                }

            // 3. Approve tenant draft via POST /api/v1/platform/tenants/{tenant_id}/approve
            //    as distinct checker
            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/approve") {
                    header(
                        IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                        UUID.randomUUID().toString(),
                    )
                    with(authentication(platformToken(checkerId, setOf("tenant.approve"))))
                }.andExpect {
                    status { isAccepted() }
                    jsonPath("$.status") { value("ACTIVE") }
                }

            // 4. Get tenant details via GET /api/v1/platform/tenants/{tenant_id}
            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/$orgId") {
                    with(authentication(platformToken(checkerId, setOf("tenant.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(orgId.toString()) }
                    jsonPath("$.tenant_code") { value(tenantCode) }
                }

            organisationFixture.grantTenantAdmin(orgId, makerId)

            // 5. Create branch draft under tenant via POST /api/v1/branches
            val createBranchReq =
                CreateBranchRequest(
                    branchCode = "BR-01",
                    branchName = "Westlands Branch",
                    branchType = "OPERATIONAL",
                    timezone = "Africa/Nairobi",
                )

            val branchCreateResult =
                mockMvc
                    .post(ApiPaths.BRANCHES) {
                        header(
                            IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                            UUID.randomUUID().toString(),
                        )
                        contentType = MediaType.APPLICATION_JSON
                        content = apiJsonCodec.mapper.writeValueAsString(createBranchReq)
                        with(authentication(tenantToken(makerId, orgId, setOf("branch.create"))))
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.branch_id") { exists() }
                        jsonPath("$.status") { value("DRAFT") }
                    }.andReturn()

            val branchId =
                UUID.fromString(
                    apiJsonCodec.mapper
                        .readTree(
                            branchCreateResult.response.contentAsString,
                        ).get("branch_id")
                        .asString(),
                )

            // 6. Search branches via GET /api/v1/branches
            mockMvc
                .get(ApiPaths.BRANCHES) {
                    with(authentication(tenantToken(makerId, orgId, setOf("branch.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].branch_code") { value("BR-01") }
                }
        }

        @Test
        fun `a tenant cannot be provisioned with a currency the ledger cannot post in`() {
            val makerId = uuidV7()
            val organisationFixture =
                TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
            val tenantCode = "currency-tenant-" + shortSuffix()

            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, makerId)
                .set(USER_ACCOUNT.USERNAME, "maker-$tenantCode")
                .set(USER_ACCOUNT.EMAIL, "maker@$tenantCode.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Maker")
                .set(USER_ACCOUNT.STATUS, "DRAFT")
                .set(USER_ACCOUNT.CREATED_AT, OffsetDateTime.now())
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, OffsetDateTime.now())
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            organisationFixture.grantPlatformSuperAdmin(makerId)

            // ZZZ passes the DTO's ^[A-Z]{3}$ pattern and the column's CHECK constraint, so nothing
            // below the application layer would have stopped it: the draft would have been stored
            // and the tenant activated with a functional currency it can never post in.
            listOf("ZZZ", "XXX").forEach { code ->
                mockMvc
                    .post(ApiPaths.PLATFORM_TENANTS) {
                        header(
                            IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                            UUID.randomUUID().toString(),
                        )
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                draftRequest("$tenantCode-${code.lowercase()}", code),
                            )
                        with(authentication(platformToken(makerId, setOf("tenant.create"))))
                    }.andExpect {
                        status { isUnprocessableContent() }
                        jsonPath("$.code") { value("accounting.currency_invalid") }
                    }
            }

            assertEquals(
                0,
                dsl.fetchCount(
                    ORGANISATION,
                    ORGANISATION.TENANT_CODE.like("$tenantCode%"),
                ),
                "a refused base currency must leave no organisation row behind",
            )
        }

        private fun shortSuffix(): String =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
                .lowercase()

        @Test
        fun `an initial base_currency setting the ledger cannot post in is refused`() {
            val makerId = seedPlatformMaker("settings-tenant-" + shortSuffix())

            // The column was already guarded; `initial_settings` was the door left open. It went
            // to the store verbatim, which wrote every key as a STRING row and never consulted
            // TenantSettingCatalog - so the same request that could not name XAU as its base
            // currency could still declare XAU as its base currency setting.
            listOf("XAU", "ZZZ").forEach { code ->
                val tenantCode = "settings-tenant-" + shortSuffix()
                mockMvc
                    .post(ApiPaths.PLATFORM_TENANTS) {
                        header(
                            IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                            UUID.randomUUID().toString(),
                        )
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                draftRequest(
                                    tenantCode,
                                    "KES",
                                    initialSettings = mapOf("base_currency" to code),
                                ),
                            )
                        with(authentication(platformToken(makerId, setOf("tenant.create"))))
                    }.andExpect {
                        status { isUnprocessableContent() }
                        jsonPath("$.code") { value("accounting.currency_invalid") }
                    }
                assertEquals(
                    0,
                    dsl.fetchCount(ORGANISATION, ORGANISATION.TENANT_CODE.eq(tenantCode)),
                    "a refused initial setting must leave no organisation row behind",
                )
            }
        }

        @Test
        fun `an accepted initial setting is stored under its catalog value type`() {
            val tenantCode = "settings-tenant-" + shortSuffix()
            val makerId = seedPlatformMaker(tenantCode)

            val created =
                mockMvc
                    .post(ApiPaths.PLATFORM_TENANTS) {
                        header(
                            IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                            UUID.randomUUID().toString(),
                        )
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                draftRequest(
                                    tenantCode,
                                    "KES",
                                    initialSettings = mapOf("base_currency" to "KES"),
                                ),
                            )
                        with(authentication(platformToken(makerId, setOf("tenant.create"))))
                    }.andExpect {
                        status { isCreated() }
                    }.andReturn()
            val organisationId =
                UUID.fromString(
                    apiJsonCodec.mapper
                        .readTree(created.response.contentAsString)
                        .get("organisation_id")
                        .asString(),
                )

            // The row must be indistinguishable from one the settings endpoint would have written:
            // the catalog's declared value type, not the hard-coded STRING the store used to stamp.
            assertEquals(
                "CURRENCY",
                dsl
                    .select(ORGANISATION_SETTING.VALUE_TYPE)
                    .from(ORGANISATION_SETTING)
                    .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
                    .and(ORGANISATION_SETTING.SETTING_KEY.eq("base_currency"))
                    .fetchOne(ORGANISATION_SETTING.VALUE_TYPE),
            )
        }

        @Test
        fun `an initial setting key outside the catalog is refused`() {
            val tenantCode = "unknown-setting-" + shortSuffix()
            val makerId = seedPlatformMaker(tenantCode)

            mockMvc
                .post(ApiPaths.PLATFORM_TENANTS) {
                    header(
                        IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                        UUID.randomUUID().toString(),
                    )
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            draftRequest(
                                tenantCode,
                                "KES",
                                initialSettings = mapOf("base_currancy" to "KES"),
                            ),
                        )
                    with(authentication(platformToken(makerId, setOf("tenant.create"))))
                }.andExpect {
                    status { isUnprocessableContent() }
                    jsonPath("$.code") { value("invalid_operation") }
                }

            assertEquals(
                0,
                dsl.fetchCount(ORGANISATION, ORGANISATION.TENANT_CODE.eq(tenantCode)),
                "a refused initial setting must leave no organisation row behind",
            )
        }

        private fun seedPlatformMaker(tenantCode: String): UUID {
            val makerId = uuidV7()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, makerId)
                .set(USER_ACCOUNT.USERNAME, "maker-$tenantCode")
                .set(USER_ACCOUNT.EMAIL, "maker@$tenantCode.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Maker")
                .set(USER_ACCOUNT.STATUS, "DRAFT")
                .set(USER_ACCOUNT.CREATED_AT, OffsetDateTime.now())
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, OffsetDateTime.now())
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
                .grantPlatformSuperAdmin(makerId)
            return makerId
        }

        private fun draftRequest(
            tenantCode: String,
            baseCurrencyCode: String,
            initialSettings: Map<String, String> = emptyMap(),
        ): CreateTenantDraftRequest =
            CreateTenantDraftRequest(
                tenantCode = tenantCode,
                displayName = "Currency Integration Tenant",
                legalName = "Currency Integration Tenant Ltd",
                registrationNumber = "REG-WEB-002",
                countryCode = "KE",
                baseCurrencyCode = baseCurrencyCode,
                timezone = "Africa/Nairobi",
                initialSettings = initialSettings,
                admin =
                    InitialAdminDto(
                        email = "admin@$tenantCode.test",
                        username = "admin-$tenantCode",
                        displayName = "Initial Admin",
                        phoneE164 = "+254700000000",
                        sendApplicationInvite = true,
                    ),
            )

        private fun platformToken(
            userId: UUID,
            permissions: Set<String>,
        ): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = userId,
                    keycloakSubject = "platform-user-$userId",
                    organisationId = PlatformOrganisation.ID,
                    membershipId = uuidV7(),
                    email = "admin@platform.test",
                    fullName = "Platform Admin",
                    permissions = permissions,
                ),
            )

        private fun tenantToken(
            userId: UUID,
            tenantId: UUID,
            permissions: Set<String>,
        ): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = userId,
                    keycloakSubject = "tenant-user-$userId",
                    organisationId = tenantId,
                    membershipId = uuidV7(),
                    email = "admin@tenant.test",
                    fullName = "Tenant Admin",
                    permissions = permissions,
                ),
            )
    }
