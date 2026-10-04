package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditEventDetail
import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.audit.AuditEventPage
import com.finaxis.platform.common.audit.AuditEventSummary
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditQueryService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

@WebMvcTest(controllers = [PlatformAuditEventController::class], useDefaultFilters = false)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    PlatformAuditEventControllerTests.TestSecurityConfiguration::class,
    PlatformAuditEventController::class,
)
class PlatformAuditEventControllerTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
    ) {
        /** Minimal security configuration for MVC controller tests. */
        @TestConfiguration
        @EnableMethodSecurity
        class TestSecurityConfiguration {
            /** Configures an authenticated-only test filter chain. */
            @Bean
            fun testSecurityFilterChain(http: HttpSecurity) =
                http
                    .csrf { it.disable() }
                    .authorizeHttpRequests { it.anyRequest().authenticated() }
                    .exceptionHandling {
                        it.authenticationEntryPoint(
                            org.springframework.security.web.authentication.HttpStatusEntryPoint(
                                HttpStatus.UNAUTHORIZED,
                            ),
                        )
                    }.build()
        }

        @MockitoBean
        private lateinit var auditQueryService: AuditQueryService

        @Test
        fun `platform log search is scoped to the platform organisation with every filter`() {
            val callerId = uuidV7()
            val entityId = uuidV7()
            val targetActorId = uuidV7()
            val from = Instant.parse("2026-07-20T08:00:00Z")
            val to = Instant.parse("2026-07-21T08:00:00Z")
            val filterCaptor = argumentCaptor<AuditEventFilter>()
            whenever(auditQueryService.searchForPlatform(filterCaptor.capture(), eq(callerId)))
                .thenReturn(AuditEventPage(listOf(summary()), totalItems = 26))

            mockMvc
                .get(ApiPaths.PLATFORM_AUDIT_EVENTS) {
                    param("entity_type", "USER")
                    param("entity_id", entityId.toString())
                    param("actor_id", targetActorId.toString())
                    param("action", "user.suspend")
                    param("occurred_from", from.toString())
                    param("occurred_to", to.toString())
                    param("page", "1")
                    param("size", "25")
                    with(authentication(platformToken(setOf("audit.view"), callerId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].outcome") { value("SUCCESS") }
                    jsonPath("$.page.number") { value(1) }
                    jsonPath("$.page.size") { value(25) }
                    jsonPath("$.page.total_items") { value(26) }
                }

            assertEquals(
                AuditEventFilter(
                    organisationId = PlatformOrganisation.ID,
                    entityType = "USER",
                    entityId = entityId,
                    actorId = targetActorId,
                    action = "user.suspend",
                    occurredFrom = from,
                    occurredTo = to,
                    page = 1,
                    size = 25,
                ),
                filterCaptor.firstValue,
            )
        }

        @Test
        fun `tenant log search is scoped to the path tenant`() {
            val callerId = uuidV7()
            val tenantId = uuidV7()
            val filterCaptor = argumentCaptor<AuditEventFilter>()
            whenever(auditQueryService.searchForPlatform(filterCaptor.capture(), eq(callerId)))
                .thenReturn(AuditEventPage(listOf(summary()), totalItems = 1))

            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/$tenantId/audit-events") {
                    param("action", "tenant.approve")
                    with(authentication(platformToken(setOf("audit.view"), callerId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].action") { value("branch.updated") }
                }

            assertEquals(
                AuditEventFilter(organisationId = tenantId, action = "tenant.approve"),
                filterCaptor.firstValue,
            )
        }

        @Test
        fun `get platform audit event reads from the platform organisation`() {
            val callerId = uuidV7()
            val eventId = uuidV7()
            whenever(auditQueryService.getForPlatform(eventId, PlatformOrganisation.ID, callerId))
                .thenReturn(detail(eventId))

            mockMvc
                .get("${ApiPaths.PLATFORM_AUDIT_EVENTS}/$eventId") {
                    with(authentication(platformToken(setOf("audit.view"), callerId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(eventId.toString()) }
                    jsonPath("$.organisation_id") { value(PlatformOrganisation.ID.toString()) }
                    jsonPath("$.actor_external_subject") { value("keycloak:external-subject") }
                    jsonPath("$.metadata_json") { value("{\"source\":\"api\"}") }
                }
        }

        @Test
        fun `get platform audit event returns service not found problem`() {
            val eventId = uuidV7()
            whenever(auditQueryService.getForPlatform(eq(eventId), any(), any())).thenThrow(
                ResourceNotFoundException(
                    code = "audit_event_not_found",
                    safeDetail = "Audit event not found: $eventId",
                ),
            )

            mockMvc
                .get("${ApiPaths.PLATFORM_AUDIT_EVENTS}/$eventId") {
                    with(authentication(platformToken(setOf("audit.view"))))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("audit_event_not_found") }
                }
        }

        @Test
        fun `platform audit search rejects invalid page bounds`() {
            listOf("0", "101").forEach { size ->
                mockMvc
                    .get(ApiPaths.PLATFORM_AUDIT_EVENTS) {
                        param("size", size)
                        with(authentication(platformToken(setOf("audit.view"))))
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("validation_failed") }
                    }
            }
        }

        @Test
        fun `platform audit endpoints reject unauthenticated callers`() {
            listOf(
                ApiPaths.PLATFORM_AUDIT_EVENTS,
                "${ApiPaths.PLATFORM_AUDIT_EVENTS}/${uuidV7()}",
                "${ApiPaths.PLATFORM_TENANTS}/${uuidV7()}/audit-events",
            ).forEach { path -> mockMvc.get(path).andExpect { status { isUnauthorized() } } }
        }

        @Test
        fun `platform audit endpoints reject callers without the audit permission`() {
            listOf(
                ApiPaths.PLATFORM_AUDIT_EVENTS,
                "${ApiPaths.PLATFORM_AUDIT_EVENTS}/${uuidV7()}",
                "${ApiPaths.PLATFORM_TENANTS}/${uuidV7()}/audit-events",
            ).forEach { path ->
                mockMvc
                    .get(path) { with(authentication(platformToken(emptySet()))) }
                    .andExpect { status { isForbidden() } }
            }
        }

        @Test
        fun `platform audit endpoints reject a tenant context even when it holds audit view`() {
            val tenantId = uuidV7()
            listOf(
                ApiPaths.PLATFORM_AUDIT_EVENTS,
                "${ApiPaths.PLATFORM_AUDIT_EVENTS}/${uuidV7()}",
                "${ApiPaths.PLATFORM_TENANTS}/$tenantId/audit-events",
                "${ApiPaths.PLATFORM_TENANTS}/${uuidV7()}/audit-events",
            ).forEach { path ->
                mockMvc
                    .get(path) {
                        with(authentication(tenantToken(setOf("audit.view"), tenantId)))
                    }.andExpect { status { isForbidden() } }
            }
            verify(auditQueryService, never()).searchForPlatform(any(), any())
            verify(auditQueryService, never()).getForPlatform(any(), any(), any())
        }

        private fun summary() =
            AuditEventSummary(
                id = uuidV7(),
                occurredAt = Instant.parse("2026-07-21T10:15:30Z"),
                actorType = "USER",
                actorId = uuidV7(),
                branchId = null,
                action = "branch.updated",
                resourceType = "BRANCH",
                resourceId = "BRANCH-001",
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.INFO,
                reason = null,
            )

        private fun detail(eventId: UUID) =
            AuditEventDetail(
                id = eventId,
                organisationId = PlatformOrganisation.ID,
                occurredAt = Instant.parse("2026-07-21T10:15:30Z"),
                actorUserId = uuidV7(),
                actorExternalSubject = "keycloak:external-subject",
                actorType = "USER",
                branchId = null,
                eventType = "USER_SUSPENDED",
                entityType = "USER",
                entityId = uuidV7(),
                action = "user.suspend",
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.HIGH,
                ipAddress = null,
                userAgent = null,
                correlationId = null,
                requestId = null,
                beforeJson = null,
                afterJson = null,
                metadataJson = "{\"source\":\"api\"}",
                reason = null,
            )

        private fun platformToken(
            permissions: Set<String>,
            userId: UUID = uuidV7(),
        ) = token(permissions, PlatformOrganisation.ID, userId)

        private fun tenantToken(
            permissions: Set<String>,
            tenantId: UUID,
        ) = token(permissions, tenantId, uuidV7())

        private fun token(
            permissions: Set<String>,
            organisationId: UUID,
            userId: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "user@example.test",
                fullName = "Test User",
                permissions = permissions,
            ),
        )
    }
