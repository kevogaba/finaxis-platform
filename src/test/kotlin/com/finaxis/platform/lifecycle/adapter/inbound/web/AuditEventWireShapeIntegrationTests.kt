package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.CorrelationContext
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.core.type.TypeReference
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Full-stack proof of #187 through the real tenant and platform audit routes: an audit row written
 * by `AuditService` reads back with the same field names and values in the summary (page) and
 * detail shapes, the older names stay as aliases, and `event_type` is the row's action. Platform
 * pages keep the shape but withhold the sensitive set (owner decision).
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AuditEventWireShapeIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val auditService: AuditService,
        private val apiJsonCodec: ApiJsonCodec,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        @Test
        fun `tenant routes return the same names and values in the page and the detail`() {
            val tenantAdmin = seedUser()
            val tenant = fixture.createActiveOrganisation("audit-wire", tenantAdmin)
            val eventId = record(tenant, tenantAdmin, resourceId = tenant.toString())
            val tenantToken = token(tenantAdmin, tenant)

            val item = pageItem(ApiPaths.AUDIT_EVENTS, eventId, tenantToken)
            val detail = read("${ApiPaths.AUDIT_EVENTS}/$eventId", tenantToken)

            assertEquals(AuditEventResponseMappingTests.AUDIT_EVENT_WIRE_FIELDS, item.keys)
            assertEquals(detail, item)
            assertWrittenValues(item, eventId, tenant, tenantAdmin, tenant)
        }

        @Test
        fun `platform pages keep the shape but withhold the sensitive set`() {
            val platformAdmin = seedUser().also { fixture.grantPlatformSuperAdmin(it) }
            val tenantAdmin = seedUser()
            val tenant = fixture.createActiveOrganisation("audit-wire-p", tenantAdmin)
            val tenantEvent = record(tenant, tenantAdmin, resourceId = tenant.toString())
            val platformEvent =
                record(PlatformOrganisation.ID, platformAdmin, platformAdmin.toString())
            val platformToken = token(platformAdmin, PlatformOrganisation.ID)

            // A tenant's log read by a platform operator: the row has values, the item has nulls.
            val tenantLog = "${ApiPaths.PLATFORM_TENANTS}/$tenant/audit-events"
            val tenantItem = pageItem(tenantLog, tenantEvent, platformToken)
            val tenantDetail =
                read("${ApiPaths.AUDIT_EVENTS}/$tenantEvent", token(tenantAdmin, tenant))
            assertWrittenValues(tenantDetail, tenantEvent, tenant, tenantAdmin, tenant)
            assertWithheld(tenantItem, tenantDetail)

            // The platform's own log: the page withholds too; the existing detail route does not.
            val platformLog = ApiPaths.PLATFORM_AUDIT_EVENTS
            val platformItem = pageItem(platformLog, platformEvent, platformToken)
            val platformDetail = read("$platformLog/$platformEvent", platformToken)
            assertWrittenValues(
                platformDetail,
                platformEvent,
                PlatformOrganisation.ID,
                platformAdmin,
                platformAdmin,
            )
            assertWithheld(platformItem, platformDetail)
        }

        private fun assertWithheld(
            platformItem: Map<String, Any?>,
            fullRow: Map<String, Any?>,
        ) {
            val withheld = AuditEventResponseMappingTests.PLATFORM_WITHHELD_FIELDS
            assertEquals(AuditEventResponseMappingTests.AUDIT_EVENT_WIRE_FIELDS, platformItem.keys)
            withheld.forEach { field ->
                assertNotNull(fullRow[field], field)
                assertNull(platformItem[field], field)
            }
            assertEquals(fullRow - withheld, platformItem - withheld)
        }

        @Test
        fun `a lifecycle transition row carries its action as event_type`() {
            val tenantAdmin = seedUser()
            val tenant = fixture.createActiveOrganisation("audit-wire-fsm", tenantAdmin)

            // The provisioning saga activated the tenant through the FSM, which audited it.
            val item =
                readPage(ApiPaths.AUDIT_EVENTS, token(tenantAdmin, tenant), "organisation.activate")
                    .first { it["outcome"] == "SUCCESS" }

            assertEquals("organisation.activate", item["event_type"])
            assertEquals("organisation.activate", item["action"])
            assertEquals("ORGANISATION", item["entity_type"])
            assertEquals("ORGANISATION", item["resource_type"])
            assertEquals(tenant.toString(), item["entity_id"])
            assertEquals(tenant.toString(), item["resource_id"])
        }

        @Test
        fun `a resource id that is not a UUID reads back as null under both names`() {
            val tenantAdmin = seedUser()
            val tenant = fixture.createActiveOrganisation("audit-wire-key", tenantAdmin)
            val eventId =
                record(tenant, tenantAdmin, "audit_retention_days", "ORGANISATION_SETTING")
            val tenantToken = token(tenantAdmin, tenant)

            val item = pageItem(ApiPaths.AUDIT_EVENTS, eventId, tenantToken)
            val detail = read("${ApiPaths.AUDIT_EVENTS}/$eventId", tenantToken)

            assertEquals(detail, item)
            assertNull(item["entity_id"])
            assertNull(item["resource_id"])
            assertEquals("ORGANISATION_SETTING", item["entity_type"])
        }

        private fun assertWrittenValues(
            item: Map<String, Any?>,
            eventId: UUID,
            organisationId: UUID,
            actorId: UUID,
            entityId: UUID,
        ) {
            assertEquals(eventId.toString(), item["id"])
            assertEquals(organisationId.toString(), item["organisation_id"])
            assertEquals(actorId.toString(), item["actor_user_id"])
            assertEquals(actorId.toString(), item["actor_id"])
            assertEquals("kc-$actorId", item["actor_external_subject"])
            assertEquals("USER", item["actor_type"])
            assertEquals(ACTION, item["event_type"])
            assertEquals(ACTION, item["action"])
            assertEquals(RESOURCE_TYPE, item["entity_type"])
            assertEquals(RESOURCE_TYPE, item["resource_type"])
            assertEquals(entityId.toString(), item["entity_id"])
            assertEquals(entityId.toString(), item["resource_id"])
            assertEquals("SUCCESS", item["outcome"])
            assertEquals("HIGH", item["severity"])
            assertEquals("Wire shape check.", item["reason"])
            assertEquals("203.0.113.7", item["ip_address"])
            assertEquals("curl/8.0", item["user_agent"])
            assertEquals("correlation-187", item["correlation_id"])
            assertEquals("request-187", item["request_id"])
            assertEquals("""{"status": "DRAFT"}""", item["before_json"])
            assertEquals("""{"status": "ACTIVE"}""", item["after_json"])
            assertEquals("""{"source": "wire-shape"}""", item["metadata_json"])
        }

        private fun record(
            organisationId: UUID,
            actorId: UUID,
            resourceId: String,
            resourceType: String = RESOURCE_TYPE,
        ): UUID {
            val actor = ActorContext(actorId, "kc-$actorId", null, null)
            val context =
                RequestContext(
                    actor = actor,
                    correlation = CorrelationContext(null, "correlation-187"),
                )
            return RequestContexts.with(context) {
                auditService
                    .record(
                        AuditCommand(
                            actorType = "USER",
                            actorId = actorId.toString(),
                            tenantId = organisationId.toString(),
                            action = ACTION,
                            resourceType = resourceType,
                            resourceId = resourceId,
                            outcome = AuditOutcome.SUCCESS,
                            severity = AuditSeverity.HIGH,
                            reason = "Wire shape check.",
                            requestId = "request-187",
                            sourceIp = "203.0.113.7",
                            userAgent = "curl/8.0",
                            before = mapOf("status" to "DRAFT"),
                            after = mapOf("status" to "ACTIVE"),
                            metadata = mapOf("source" to "wire-shape"),
                        ),
                    ).id
            }
        }

        private fun pageItem(
            path: String,
            eventId: UUID,
            token: AppPrincipalAuthenticationToken,
        ): Map<String, Any?> =
            readPage(path, token, ACTION).single { it["id"] == eventId.toString() }

        private fun readPage(
            path: String,
            token: AppPrincipalAuthenticationToken,
            action: String,
        ): List<Map<String, Any?>> {
            val body =
                mockMvc
                    .get(path) {
                        param("action", action)
                        param("size", "100")
                        with(authentication(token))
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
            val mapper = apiJsonCodec.mapper
            return mapper.convertValue(
                mapper.readTree(body).get("items"),
                object : TypeReference<List<Map<String, Any?>>>() {},
            )
        }

        private fun read(
            path: String,
            token: AppPrincipalAuthenticationToken,
        ): Map<String, Any?> {
            val body =
                mockMvc
                    .get(path) { with(authentication(token)) }
                    .andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
            return apiJsonCodec.mapper.readValue(
                body,
                object : TypeReference<Map<String, Any?>>() {},
            )
        }

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "audit-wire-$id")
                .set(USER_ACCOUNT.EMAIL, "audit-wire-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Audit Wire User")
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
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "kc-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "audit-wire@example.test",
                fullName = "Audit Wire User",
                permissions = setOf("audit.view"),
            ),
        )

        private companion object {
            const val ACTION = "branch.update"
            const val RESOURCE_TYPE = "BRANCH"
        }
    }
