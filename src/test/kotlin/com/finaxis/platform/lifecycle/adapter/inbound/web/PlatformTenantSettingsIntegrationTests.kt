package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
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
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * `/platform/tenants/{id}/settings` (#273): a platform administrator who is no member of the
 * tenant reads and changes its platform-only settings, with the platform permission checked first.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PlatformTenantSettingsIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        @Test
        fun `a platform administrator reads updates and clears a platform-only setting`() {
            val maker = seedUser("maker")
            val admin = seedUser("platform")
            val tenant = fixture.createActiveOrganisation("platform-settings", maker)
            fixture.grantPlatformSuperAdmin(admin)
            val token = platformToken(admin)

            mockMvc
                .get("$base/$tenant/settings/audit_retention_days") {
                    with(authentication(token))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.value") { value(null as Any?) }
                    jsonPath("$.is_default") { value(true) }
                    jsonPath("$.platform_admin_only") { value(true) }
                }

            put(tenant, "audit_retention_days", """{"value":"365","reason":"Policy"}""", token)
                .andExpect {
                    status { isOk() }
                    jsonPath("$.value") { value("365") }
                    jsonPath("$.is_default") { value(false) }
                }

            mockMvc
                .get("$base/$tenant/settings") { with(authentication(token)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.items.length()") { value(1) }
                    jsonPath("$.items[0].key") { value("audit_retention_days") }
                    jsonPath("$.items[0].value") { value("365") }
                    jsonPath("$.page.total_items") { value(1) }
                }
            assertEquals(1, settingsAudit(tenant, admin))

            mockMvc
                .delete("$base/$tenant/settings/audit_retention_days") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    with(authentication(token))
                }.andExpect { status { isNoContent() } }
            mockMvc
                .get("$base/$tenant/settings/audit_retention_days") {
                    with(authentication(token))
                }.andExpect {
                    jsonPath("$.value") { value(null as Any?) }
                    jsonPath("$.is_default") { value(true) }
                }
        }

        @Test
        fun `a tenant setting is refused here and an unknown tenant is not found`() {
            val admin = seedUser("platform")
            val tenant = fixture.createActiveOrganisation("platform-settings-b", seedUser("maker"))
            fixture.grantPlatformSuperAdmin(admin)
            val token = platformToken(admin)

            put(tenant, "default_timezone", """{"value":"Africa/Nairobi"}""", token)
                .andExpect { status { isUnprocessableContent() } }
            mockMvc
                .get("$base/$tenant/settings/default_timezone") { with(authentication(token)) }
                .andExpect { status { isUnprocessableContent() } }
            mockMvc
                .get("$base/${uuidV7()}/settings") { with(authentication(token)) }
                .andExpect { status { isNotFound() } }
            put(uuidV7(), "audit_retention_days", """{"value":"30"}""", token)
                .andExpect { status { isNotFound() } }
        }

        @Test
        fun `the reserved platform organisation is refused like every other tenant route`() {
            val admin = seedUser("platform-target")
            fixture.grantPlatformSuperAdmin(admin)
            val token = platformToken(admin)
            val platform = PlatformOrganisation.ID

            mockMvc
                .get("$base/$platform/settings") { with(authentication(token)) }
                .andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("lifecycle.platform_organisation_protected") }
                }
            mockMvc
                .get("$base/$platform/settings/audit_retention_days") {
                    with(authentication(token))
                }.andExpect { status { isConflict() } }
            put(platform, "audit_retention_days", """{"value":"30"}""", token)
                .andExpect { status { isConflict() } }
            mockMvc
                .delete("$base/$platform/settings/audit_retention_days") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    with(authentication(token))
                }.andExpect { status { isConflict() } }
            assertEquals(
                0,
                dsl
                    .selectCount()
                    .from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ORGANISATION_ID.eq(platform))
                    .and(AUDIT_EVENT.ACTOR_USER_ID.eq(admin))
                    .and(AUDIT_EVENT.ACTION.eq("settings.update"))
                    .and(AUDIT_EVENT.OUTCOME.eq("SUCCESS"))
                    .fetchOne(0, Int::class.java),
                "nothing is written for the platform organisation",
            )
        }

        @Test
        fun `the platform permission is checked first, in the route and in the service`() {
            val tenant = fixture.createActiveOrganisation("platform-settings-c", seedUser("maker"))
            val holder = seedUser("no-grant")

            // The authority is missing: the route refuses before anything about the tenant is read.
            put(tenant, "audit_retention_days", """{"value":"30"}""", platformToken(holder, null))
                .andExpect { status { isForbidden() } }
            mockMvc
                .get("$base/${uuidV7()}/settings") {
                    with(authentication(platformToken(holder, null)))
                }.andExpect { status { isForbidden() } }
            // The authority is claimed but the user holds no grant: the service refuses it, 403
            // and never 404 for an unknown tenant.
            put(tenant, "audit_retention_days", """{"value":"30"}""", platformToken(holder))
                .andExpect { status { isForbidden() } }
            mockMvc
                .get("$base/${uuidV7()}/settings") {
                    with(authentication(platformToken(holder)))
                }.andExpect { status { isForbidden() } }
            // A tenant-context caller never reaches it.
            put(tenant, "audit_retention_days", """{"value":"30"}""", tenantToken(holder, tenant))
                .andExpect { status { isForbidden() } }
        }

        private fun put(
            tenant: UUID,
            key: String,
            body: String,
            token: AppPrincipalAuthenticationToken,
        ) = mockMvc.put("$base/$tenant/settings/$key") {
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
            contentType = MediaType.APPLICATION_JSON
            content = body
            with(authentication(token))
        }

        private fun settingsAudit(
            tenant: UUID,
            actor: UUID,
        ): Int =
            dsl
                .selectCount()
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(tenant))
                .and(AUDIT_EVENT.ACTOR_USER_ID.eq(actor))
                .and(AUDIT_EVENT.ACTION.eq("settings.update"))
                .and(AUDIT_EVENT.OUTCOME.eq("SUCCESS"))
                .fetchOne(0, Int::class.java) ?: 0

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@platform-settings.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun platformToken(
            userId: UUID,
            permission: String? = "tenant_setting.manage_platform",
        ) = principal(userId, PlatformOrganisation.ID, permission)

        private fun tenantToken(
            userId: UUID,
            tenant: UUID,
        ) = principal(userId, tenant, "settings.update")

        private fun principal(
            userId: UUID,
            organisationId: UUID,
            permission: String?,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "user@platform-settings.test",
                fullName = "Platform Settings User",
                permissions = setOfNotNull(permission),
            ),
        )

        private val base = ApiPaths.PLATFORM_TENANTS
    }
