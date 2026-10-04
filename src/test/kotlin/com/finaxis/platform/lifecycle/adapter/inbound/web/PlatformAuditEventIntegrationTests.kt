package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Full-stack proof of the platform audit read model: a platform operator reads the PLATFORM log
 * and any tenant's log, and no tenant user can reach either through any route.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PlatformAuditEventIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        private inner class Scenario {
            val platformAdmin = seedUser().also { fixture.grantPlatformSuperAdmin(it) }
            val tenantAAdmin = seedUser()
            val tenantBAdmin = seedUser()
            val tenantA = fixture.createActiveOrganisation("audit-a", tenantAAdmin)
            val tenantB = fixture.createActiveOrganisation("audit-b", tenantBAdmin)
            val platformEvent = seedEvent(PlatformOrganisation.ID, "user.suspend")
            val tenantAEvent = seedEvent(tenantA, "tenant.approve")
            val tenantBEvent = seedEvent(tenantB, "tenant.approve")
        }

        @Test
        fun `platform admin reads the platform log and no tenant row leaks into it`() {
            val s = Scenario()

            mockMvc
                .get(ApiPaths.PLATFORM_AUDIT_EVENTS) {
                    param("action", "user.suspend")
                    with(authentication(token(s.platformAdmin, PlatformOrganisation.ID)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[?(@.id == '${s.platformEvent}')]") { exists() }
                    jsonPath("$.items[?(@.id == '${s.tenantAEvent}')]") { doesNotExist() }
                    jsonPath("$.items[?(@.id == '${s.tenantBEvent}')]") { doesNotExist() }
                }

            mockMvc
                .get("${ApiPaths.PLATFORM_AUDIT_EVENTS}/${s.platformEvent}") {
                    with(authentication(token(s.platformAdmin, PlatformOrganisation.ID)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(s.platformEvent.toString()) }
                    jsonPath("$.organisation_id") { value(PlatformOrganisation.ID.toString()) }
                }
        }

        @Test
        fun `platform audit detail refuses a tenant row`() {
            val s = Scenario()

            mockMvc
                .get("${ApiPaths.PLATFORM_AUDIT_EVENTS}/${s.tenantAEvent}") {
                    with(authentication(token(s.platformAdmin, PlatformOrganisation.ID)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("audit_event_not_found") }
                }
        }

        @Test
        fun `platform admin reads one tenant's log and only that tenant's rows`() {
            val s = Scenario()

            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/${s.tenantA}/audit-events") {
                    param("action", "tenant.approve")
                    with(authentication(token(s.platformAdmin, PlatformOrganisation.ID)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items.length()") { value(1) }
                    jsonPath("$.items[0].id") { value(s.tenantAEvent.toString()) }
                    jsonPath("$.page.total_items") { value(1) }
                }
        }

        @Test
        fun `a tenant admin cannot reach the platform audit routes`() {
            val s = Scenario()
            val tenantAAdminToken = token(s.tenantAAdmin, s.tenantA)

            listOf(
                ApiPaths.PLATFORM_AUDIT_EVENTS,
                "${ApiPaths.PLATFORM_AUDIT_EVENTS}/${s.platformEvent}",
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantA}/audit-events",
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantB}/audit-events",
                "${ApiPaths.PLATFORM_TENANTS}/${PlatformOrganisation.ID}/audit-events",
            ).forEach { path ->
                mockMvc
                    .get(path) { with(authentication(tenantAAdminToken)) }
                    .andExpect { status { isForbidden() } }
            }
        }

        @Test
        fun `a platform principal without a platform membership is refused in the application`() {
            val s = Scenario()
            // Holds the coarse authority in its token but has no PLATFORM membership or role.
            val stranger = token(seedUser(), PlatformOrganisation.ID)

            listOf(
                ApiPaths.PLATFORM_AUDIT_EVENTS,
                "${ApiPaths.PLATFORM_AUDIT_EVENTS}/${s.platformEvent}",
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantA}/audit-events",
            ).forEach { path ->
                mockMvc
                    .get(path) { with(authentication(stranger)) }
                    .andExpect { status { isForbidden() } }
            }
        }

        @Test
        fun `the tenant endpoint still shows a tenant only its own rows`() {
            val s = Scenario()

            mockMvc
                .get(ApiPaths.AUDIT_EVENTS) {
                    with(authentication(token(s.tenantAAdmin, s.tenantA)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[?(@.id == '${s.tenantAEvent}')]") { exists() }
                    jsonPath("$.items[?(@.id == '${s.tenantBEvent}')]") { doesNotExist() }
                    jsonPath("$.items[?(@.id == '${s.platformEvent}')]") { doesNotExist() }
                }
            mockMvc
                .get("${ApiPaths.AUDIT_EVENTS}/${s.platformEvent}") {
                    with(authentication(token(s.tenantAAdmin, s.tenantA)))
                }.andExpect { status { isNotFound() } }
            mockMvc
                .get("${ApiPaths.AUDIT_EVENTS}/${s.tenantBEvent}") {
                    with(authentication(token(s.tenantAAdmin, s.tenantA)))
                }.andExpect { status { isNotFound() } }
        }

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "audit-$id")
                .set(USER_ACCOUNT.EMAIL, "audit-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Audit User")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun seedEvent(
            organisationId: UUID,
            action: String,
        ): UUID {
            val id = uuidV7()
            dsl
                .insertInto(AUDIT_EVENT)
                .set(AUDIT_EVENT.ID, id)
                .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
                .set(AUDIT_EVENT.EVENT_TIME, OffsetDateTime.now())
                .set(AUDIT_EVENT.ACTOR_TYPE, "SYSTEM")
                .set(AUDIT_EVENT.EVENT_TYPE, "TEST")
                .set(AUDIT_EVENT.ENTITY_TYPE, "ORGANISATION")
                .set(AUDIT_EVENT.ACTION, action)
                .set(AUDIT_EVENT.OUTCOME, "SUCCESS")
                .set(AUDIT_EVENT.SEVERITY, "INFO")
                .execute()
            return id
        }

        private fun token(
            userId: UUID,
            organisationId: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "audit-user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "audit@example.test",
                fullName = "Audit User",
                permissions = setOf("audit.view"),
            ),
        )
    }
