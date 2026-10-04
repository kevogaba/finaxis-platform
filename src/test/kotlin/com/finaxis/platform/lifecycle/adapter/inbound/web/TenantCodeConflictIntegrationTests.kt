package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AmendTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.InitialAdminDto
import com.finaxis.platform.lifecycle.application.AmendOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.FoundationLifecycleService
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapService
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.application.OrganisationAccessStore
import com.finaxis.platform.lifecycle.application.OrganisationBootstrapStore
import com.finaxis.platform.lifecycle.application.OrganisationLifecycleProvisioningStore
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.OrganisationQueryStore
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Full-stack proof that a duplicate tenant code is a 409, not the 500 a violation of
 * `uq_organisation_tenant_code` used to be, on both the create and the amend route, with no new
 * row and the amended draft untouched. The lost-race cases bypass the courtesy pre-check so the
 * unique index itself is what refuses the second write.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantCodeConflictIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
        private val lifecycleService: FoundationLifecycleService,
        private val lifecycleStore: OrganisationLifecycleProvisioningStore,
        private val bootstrapStore: OrganisationBootstrapStore,
        private val accessStore: OrganisationAccessStore,
        private val queryStore: OrganisationQueryStore,
        private val auditService: AuditService,
        private val adminBootstrapStore: InitialAdministratorBootstrapStore,
        private val bootstrapService: InitialAdministratorBootstrapService,
        private val permissionGuard: PermissionGuard,
        private val clock: Clock,
        private val transactionManager: PlatformTransactionManager,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val maker = seedUser("maker").also(fixture::grantPlatformSuperAdmin)

        @Test
        fun `creating a draft with an existing tenant code is a 409 and adds no row`() {
            val code = "dup-${shortId()}"
            create(code).andExpect { status { isCreated() } }
            val before = organisationCount()

            create(code).andExpect {
                status { isConflict() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.code") { value("conflict") }
                jsonPath("$.detail") { value("A tenant with that tenant code already exists.") }
                jsonPath("$.request_id") { isNotEmpty() }
            }

            assertEquals(before, organisationCount())
            assertEquals(1, organisationCount(code))
        }

        @Test
        fun `amending a draft to a code another tenant holds is a 409 and changes nothing`() {
            val taken = "taken-${shortId()}"
            val mine = "mine-${shortId()}"
            create(taken).andExpect { status { isCreated() } }
            val draft = createdId(create(mine))
            val rowVersion = column(draft, "row_version")

            amend(draft, taken).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("conflict") }
                jsonPath("$.detail") { value("A tenant with that tenant code already exists.") }
                jsonPath("$.request_id") { isNotEmpty() }
            }

            assertEquals(mine, column(draft, "tenant_code"))
            assertNotEquals("Amended Tenant", column(draft, "display_name"))
            assertEquals(rowVersion, column(draft, "row_version"))

            // Keeping its own code is not a clash.
            amend(draft, mine).andExpect { status { isOk() } }
            assertEquals("Amended Tenant", column(draft, "display_name"))
        }

        @Test
        fun `a create that loses the race at the unique index is the same 409`() {
            val code = "race-${shortId()}"
            create(code).andExpect { status { isCreated() } }
            val before = organisationCount()

            val failure =
                assertFailsWith<ConflictException> {
                    inTransaction {
                        withoutPrecheck().createDraft(createCommand(code))
                    }
                }

            assertEquals("A tenant with that tenant code already exists.", failure.safeDetail)
            assertIs<DuplicateKeyException>(failure.cause)
            assertEquals(before, organisationCount())
        }

        @Test
        fun `an amend that loses the race at the unique index is the same 409`() {
            val taken = "taken-${shortId()}"
            create(taken).andExpect { status { isCreated() } }
            val draft = createdId(create("mine-${shortId()}"))
            val rowVersion = column(draft, "row_version")

            val failure =
                assertFailsWith<ConflictException> {
                    inTransaction {
                        withoutPrecheck().amendDraft(amendCommand(draft, taken))
                    }
                }

            assertIs<DuplicateKeyException>(failure.cause)
            assertEquals(rowVersion, column(draft, "row_version"))
            assertEquals(1, organisationCount(taken))
        }

        // The same service, but its code lookup always answers "free", which is what a request
        // that lost a race sees: the insert or update then collides with the unique index.
        private fun withoutPrecheck() =
            OrganisationProvisioningService(
                lifecycleService,
                lifecycleStore,
                bootstrapStore,
                accessStore,
                object : OrganisationQueryStore by queryStore {
                    override fun findByCode(tenantCode: String) = null
                },
                auditService,
                adminBootstrapStore,
                bootstrapService,
                permissionGuard,
                clock,
            )

        private fun <T> inTransaction(block: () -> T): T =
            TransactionTemplate(transactionManager).execute { block() }!!

        private fun createCommand(code: String) =
            CreateOrganisationDraftCommand(
                tenantCode = code,
                displayName = "Race Tenant",
                legalName = null,
                registrationNumber = null,
                countryCode = "KE",
                baseCurrencyCode = "KES",
                timezone = "Africa/Nairobi",
                requestedBy = maker,
                admin = admin(),
            )

        private fun amendCommand(
            organisationId: UUID,
            code: String,
        ) = AmendOrganisationDraftCommand(
            organisationId = organisationId,
            tenantCode = code,
            displayName = "Amended Tenant",
            legalName = null,
            registrationNumber = null,
            countryCode = "KE",
            baseCurrencyCode = "KES",
            timezone = "Africa/Nairobi",
            actorId = maker,
            requestId = uuidV7(),
            admin = admin(),
        )

        private fun admin() =
            InitialAdministratorDraft(
                email = "admin-${shortId()}@tenant-code.test",
                username = "admin-${shortId()}",
                displayName = "Initial Admin",
                phoneE164 = "+254700000000",
                sendApplicationInvite = false,
            )

        private fun create(code: String): ResultActionsDsl =
            mockMvc.post(ApiPaths.PLATFORM_TENANTS) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content =
                    apiJsonCodec.mapper.writeValueAsString(
                        CreateTenantDraftRequest(
                            tenantCode = code,
                            displayName = "Code Tenant",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin = adminDto(),
                        ),
                    )
                with(authentication(token("tenant.create")))
            }

        private fun amend(
            tenantId: UUID,
            code: String,
        ): ResultActionsDsl =
            mockMvc.patch("${ApiPaths.PLATFORM_TENANTS}/$tenantId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content =
                    apiJsonCodec.mapper.writeValueAsString(
                        AmendTenantDraftRequest(
                            tenantCode = code,
                            displayName = "Amended Tenant",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin = adminDto(),
                        ),
                    )
                with(authentication(token("tenant.update_draft", "tenant.view")))
            }

        private fun adminDto() =
            InitialAdminDto(
                email = "admin-${shortId()}@tenant-code.test",
                username = "admin-${shortId()}",
                displayName = "Initial Admin",
                phoneE164 = "+254700000000",
                sendApplicationInvite = false,
            )

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

        private fun organisationCount(code: String? = null): Int =
            if (code == null) {
                dsl.fetchOne("SELECT COUNT(*) FROM organisation")!!.get(0, Int::class.java)
            } else {
                dsl
                    .fetchOne("SELECT COUNT(*) FROM organisation WHERE tenant_code = ?", code)!!
                    .get(0, Int::class.java)
            }

        private fun column(
            organisationId: UUID,
            column: String,
        ): String =
            dsl
                .fetchOne("SELECT $column::text FROM organisation WHERE id = ?", organisationId)!!
                .get(0, String::class.java)

        private fun token(vararg permissions: String) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = maker,
                    keycloakSubject = "user-$maker",
                    organisationId = PlatformOrganisation.ID,
                    membershipId = uuidV7(),
                    branchId = null,
                    email = "maker@tenant-code.test",
                    fullName = "Tenant Code Maker",
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
                .set(USER_ACCOUNT.EMAIL, "$label-$id@tenant-code.test")
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
