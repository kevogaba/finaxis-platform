package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The administrator draft on the tenant detail (#271): in full for a platform caller who may amend
 * the draft, only while the tenant is a draft, and never for anyone else.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantAdministratorDraftVisibilityIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisations: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisations, dsl)

        @Test
        fun `a draft amender sees the administrator in full and a viewer does not`() {
            val amender = fixture.createPlatformOperator("draft-amender")
            val viewer = seedUser("draft-viewer")
            fixture.grantPlatformPermissionsExactly(viewer, "tenant.view")
            val tenant = createDraft(amender)

            detail(tenant, amender).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.admin.email") { value("draft-admin@visibility.test") }
                jsonPath("$.admin.username") { value("draft-admin") }
                jsonPath("$.admin.display_name") { value("Draft Administrator") }
                jsonPath("$.admin.phone_e164") { value("+254700000001") }
                jsonPath("$.admin.send_application_invite") { value(true) }
                jsonPath("$.status_reason") { value(null as Any?) }
            }
            detail(tenant, viewer).andExpect {
                status { isOk() }
                jsonPath("$.admin") { value(null as Any?) }
            }
        }

        @Test
        fun `the administrator is not shown once the draft is submitted`() {
            val amender = fixture.createPlatformOperator("submitted-amender")
            val tenant = createDraft(amender)
            withRequestContext {
                organisations.submitForApproval(
                    SubmitOrganisationForApprovalCommand(tenant, actorId = amender),
                )
            }

            detail(tenant, amender).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("PENDING_APPROVAL") }
                jsonPath("$.admin") { value(null as Any?) }
            }
        }

        /** A plain user: no role, so the narrow grant below is all they hold. */
        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@visibility.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun createDraft(operator: UUID): UUID =
            withRequestContext {
                organisations
                    .createDraft(
                        CreateOrganisationDraftCommand(
                            tenantCode = "vis-${uuidV7()}",
                            displayName = "Visibility Tenant",
                            legalName = "Visibility Tenant Limited",
                            registrationNumber = "VIS-${uuidV7()}",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin =
                                InitialAdministratorDraft(
                                    email = "draft-admin@visibility.test",
                                    username = "draft-admin",
                                    displayName = "Draft Administrator",
                                    phoneE164 = "+254700000001",
                                    sendApplicationInvite = true,
                                ),
                            requestedBy = operator,
                        ),
                    ).organisationId
            }

        private fun detail(
            tenant: UUID,
            operator: UUID,
        ): ResultActionsDsl =
            mockMvc.get("${ApiPaths.PLATFORM_TENANTS}/$tenant") {
                with(
                    authentication(
                        AppPrincipalAuthenticationToken(
                            AppPrincipal(
                                userId = operator,
                                keycloakSubject = "user-$operator",
                                organisationId = PlatformOrganisation.ID,
                                membershipId = uuidV7(),
                                email = "operator@visibility.test",
                                fullName = "Operator",
                                permissions = setOf("tenant.view"),
                            ),
                        ),
                    ),
                )
            }
    }
