package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The platform audit read model as `PLATFORM_SUPPORT` and a refused tenant admin meet it (#224).
 *
 * `PlatformAuditEventIntegrationTests` seeds its rows by SQL and reads as a super administrator.
 * Here the row is a real platform action (a user suspension through its route), the reader holds
 * the read-only support role, and the refusal of a tenant admin is checked for the one durable
 * `api.access_denied` `DENIED` row it must leave (#184). The platform log read itself is not
 * audited, and nothing here changes that.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PlatformAuditSupportReadIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val objectMapper = ObjectMapper()
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        private val withheld =
            listOf(
                "before_json",
                "after_json",
                "metadata_json",
                "user_agent",
                "ip_address",
                "actor_external_subject",
            )

        @Test
        fun `support reads a real platform action with the withheld fields null`() {
            val platformAdmin = seedUser().also(fixture::grantPlatformSuperAdmin)
            val support = seedUser().also(fixture::grantPlatformSupport)
            val target = seedUser()

            mockMvc
                .post("${ApiPaths.PLATFORM_USERS}/$target/suspend") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"Regulatory compliance review."}"""
                    with(
                        authentication(
                            token(platformAdmin, PlatformOrganisation.ID, "user.suspend"),
                        ),
                    )
                }.andExpect { status { isOk() } }

            val item =
                items(
                    ApiPaths.PLATFORM_AUDIT_EVENTS,
                    support,
                    PlatformOrganisation.ID,
                    mapOf("entity_id" to target.toString()),
                ).single()

            assertEquals("user_account.suspend", item.text("action"))
            assertEquals("USER_ACCOUNT", item.text("entity_type"))
            assertEquals(PlatformOrganisation.ID.toString(), item.text("organisation_id"))
            assertEquals(platformAdmin.toString(), item.text("actor_id"))
            assertEquals(target.toString(), item.text("subject_user_id"))
            assertEquals("SUCCESS", item.text("outcome"))
            // Withheld means present in the shape with a null value, not dropped.
            assertWithheld(item)
            // The withholding is not vacuous: the same row on the platform detail route, which
            // is unchanged, carries state and request context.
            val detail = detail(item.text("id"), support)
            assertTrue(
                listOf("before_json", "after_json", "metadata_json").any { !detail[it].isNull },
                "the detail row holds state the page withholds: $detail",
            )
        }

        @Test
        fun `support lists another tenant's log and the platform log without leaving a row`() {
            val support = seedUser().also(fixture::grantPlatformSupport)
            val tenant = fixture.createActiveOrganisation("support-read", seedUser())
            val suspended = suspendUser()

            val tenantItems =
                items(
                    "${ApiPaths.PLATFORM_TENANTS}/$tenant/audit-events",
                    support,
                    PlatformOrganisation.ID,
                    mapOf("size" to "100"),
                )
            // The tenant's own provisioning rows: all of them, none from another organisation,
            // and every withheld field null on each.
            val expected =
                dsl
                    .select(AUDIT_EVENT.ID)
                    .from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ORGANISATION_ID.eq(tenant))
                    .fetch(AUDIT_EVENT.ID)
                    .map { it.toString() }
            assertTrue(expected.isNotEmpty() && expected.size <= 100, "provisioning wrote rows")
            assertEquals(expected.toSet(), tenantItems.mapNotNull { it.text("id") }.toSet())
            tenantItems.forEach {
                assertEquals(tenant.toString(), it.text("organisation_id"))
                assertWithheld(it)
            }

            val platformItems =
                items(
                    ApiPaths.PLATFORM_AUDIT_EVENTS,
                    support,
                    PlatformOrganisation.ID,
                    mapOf("size" to "100"),
                )
            assertTrue(platformItems.isNotEmpty())
            platformItems.forEach {
                assertEquals(PlatformOrganisation.ID.toString(), it.text("organisation_id"))
                assertWithheld(it)
            }
            assertTrue(
                items(
                    ApiPaths.PLATFORM_AUDIT_EVENTS,
                    support,
                    PlatformOrganisation.ID,
                    mapOf("entity_id" to suspended.toString()),
                ).isNotEmpty(),
            )

            // Reading the platform logs is not itself audited (docs/architecture/audit-logging.md).
            assertEquals(0, dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTOR_USER_ID.eq(support)))
        }

        @Test
        fun `a tenant admin refused on a platform audit route leaves exactly one denied row`() {
            val otherTenant = fixture.createActiveOrganisation("denied-other", seedUser())
            val cases =
                listOf(
                    ApiPaths.PLATFORM_AUDIT_EVENTS to "/api/v1/platform/audit-events",
                    "${ApiPaths.PLATFORM_TENANTS}/$otherTenant/audit-events" to
                        "/api/v1/platform/tenants/{tenant_id}/audit-events",
                )

            cases.forEach { (path, route) ->
                // A fresh caller per route keeps each count its own and under the flood bound.
                val tenantAdmin = seedUser()
                val tenant = fixture.createActiveOrganisation("denied-route", tenantAdmin)

                mockMvc
                    .get(path) {
                        with(authentication(token(tenantAdmin, tenant, "audit.view")))
                    }.andExpect { status { isForbidden() } }

                val row = deniedRows(tenantAdmin).single()
                assertEquals("DENIED", row.get(AUDIT_EVENT.OUTCOME))
                assertEquals("HIGH", row.get(AUDIT_EVENT.SEVERITY))
                assertEquals("platform_context_required", row.get(AUDIT_EVENT.REASON))
                // Written under the caller's own tenant, which is where the tenant's log shows it.
                assertEquals(tenant, row.get(AUDIT_EVENT.ORGANISATION_ID))
                val metadata = requireNotNull(row.get(AUDIT_EVENT.METADATA_JSONB)).data()
                assertTrue(metadata.contains(route), metadata)
                assertTrue(!metadata.contains(otherTenant.toString()), "no path value: $metadata")
            }
        }

        @Test
        fun `the denied outcome filter returns the refusal and none of the successes`() {
            val support = seedUser().also(fixture::grantPlatformSupport)
            val platformAdmin = seedUser().also(fixture::grantPlatformSuperAdmin)
            val tenantAdmin = seedUser()
            val tenant = fixture.createActiveOrganisation("denied-filter", tenantAdmin)
            mockMvc
                .get(ApiPaths.PLATFORM_AUDIT_EVENTS) {
                    with(authentication(token(tenantAdmin, tenant, "audit.view")))
                }.andExpect { status { isForbidden() } }
            val tenantDenied = deniedRows(tenantAdmin).single().get(AUDIT_EVENT.ID).toString()
            // A platform-context refusal: the support role holds no user.update.
            mockMvc
                .post("${ApiPaths.PLATFORM_USERS}/${seedUser()}/suspend") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"Regulatory compliance review."}"""
                    with(authentication(token(support, PlatformOrganisation.ID, "user.view")))
                }.andExpect { status { isForbidden() } }
            val platformDenied = deniedRows(support).single().get(AUDIT_EVENT.ID).toString()
            suspendUser(platformAdmin)

            val tenantRoute = "${ApiPaths.PLATFORM_TENANTS}/$tenant/audit-events"
            val unfiltered = items(tenantRoute, support, PlatformOrganisation.ID)
            assertTrue(unfiltered.any { it.text("outcome") == "SUCCESS" }, "successes exist")
            val tenantItems =
                items(tenantRoute, support, PlatformOrganisation.ID, mapOf("outcome" to "DENIED"))
            assertTrue(tenantItems.any { it.text("id") == tenantDenied })
            assertEquals(setOf("DENIED"), tenantItems.map { it.text("outcome") }.toSet())

            val platformItems =
                items(
                    ApiPaths.PLATFORM_AUDIT_EVENTS,
                    support,
                    PlatformOrganisation.ID,
                    mapOf("outcome" to "DENIED", "actor_id" to support.toString()),
                )
            assertEquals(listOf(platformDenied), platformItems.map { it.text("id") })
            assertEquals("api.access_denied", platformItems.single().text("action"))
            // The same actor's successes are on the log and are not returned for DENIED.
            val admin = mapOf("actor_id" to platformAdmin.toString())
            assertTrue(
                items(ApiPaths.PLATFORM_AUDIT_EVENTS, support, PlatformOrganisation.ID, admin)
                    .any { it.text("outcome") == "SUCCESS" },
            )
            assertEquals(
                emptyList(),
                items(
                    ApiPaths.PLATFORM_AUDIT_EVENTS,
                    support,
                    PlatformOrganisation.ID,
                    admin + ("outcome" to "DENIED"),
                ),
            )
        }

        private fun suspendUser(
            platformAdmin: UUID = seedUser().also(fixture::grantPlatformSuperAdmin),
        ): UUID {
            val target = seedUser()
            mockMvc
                .post("${ApiPaths.PLATFORM_USERS}/$target/suspend") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"Regulatory compliance review."}"""
                    with(
                        authentication(
                            token(platformAdmin, PlatformOrganisation.ID, "user.suspend"),
                        ),
                    )
                }.andExpect { status { isOk() } }
            return target
        }

        private fun assertWithheld(item: JsonNode) =
            withheld.forEach { field ->
                assertTrue(item.has(field) && item[field].isNull, "$field must be null: $item")
            }

        private fun items(
            path: String,
            userId: UUID,
            organisationId: UUID,
            params: Map<String, String> = emptyMap(),
        ): List<JsonNode> {
            val body =
                mockMvc
                    .get(path) {
                        params.forEach { (name, value) -> param(name, value) }
                        with(authentication(token(userId, organisationId, "audit.view")))
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response
                    .contentAsString
            return objectMapper.readTree(body)["items"].toList()
        }

        private fun deniedRows(actor: UUID) =
            dsl
                .selectFrom(AUDIT_EVENT)
                .where(AUDIT_EVENT.ACTION.eq("api.access_denied"))
                .and(AUDIT_EVENT.ACTOR_USER_ID.eq(actor))
                .fetch()

        private fun detail(
            eventId: String?,
            userId: UUID,
        ): JsonNode {
            val body =
                mockMvc
                    .get("${ApiPaths.PLATFORM_AUDIT_EVENTS}/$eventId") {
                        with(authentication(token(userId, PlatformOrganisation.ID, "audit.view")))
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response
                    .contentAsString
            return objectMapper.readTree(body)
        }

        private fun JsonNode.text(field: String): String? =
            this[field]?.takeUnless { it.isNull }?.asText()

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "support-read-$id")
                .set(USER_ACCOUNT.EMAIL, "support-read-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Support Read User")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun token(
            userId: UUID,
            organisationId: UUID,
            vararg permissions: String,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "support-read-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "support-read@example.test",
                fullName = "Support Read User",
                permissions = permissions.toSet(),
            ),
        )
    }
