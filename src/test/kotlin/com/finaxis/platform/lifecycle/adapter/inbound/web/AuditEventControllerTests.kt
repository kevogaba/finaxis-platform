package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditEventDetail
import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.audit.AuditEventPage
import com.finaxis.platform.common.audit.AuditEventSummary
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditQueryService
import com.finaxis.platform.common.audit.AuditSeverity
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

@WebMvcTest(controllers = [AuditEventController::class], useDefaultFilters = false)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    AuditEventControllerTests.TestSecurityConfiguration::class,
    AuditEventController::class,
)
class AuditEventControllerTests
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
        fun `search audit events filters by entity`() {
            val tenantId = uuidV7()
            val callerId = uuidV7()
            val entityId = uuidV7()

            val capturedFilter =
                searchAndCapture(
                    tenantId,
                    callerId,
                    mapOf("entity_type" to "BRANCH", "entity_id" to entityId.toString()),
                )

            assertEquals(
                AuditEventFilter(
                    tenantId,
                    entityType = "BRANCH",
                    entityId = entityId,
                    page = 1,
                    size = 25,
                ),
                capturedFilter,
            )
        }

        @Test
        fun `search audit events filters by actor`() {
            val tenantId = uuidV7()
            val callerId = uuidV7()
            val targetActorId = uuidV7()

            val capturedFilter =
                searchAndCapture(
                    tenantId,
                    callerId,
                    mapOf("actor_id" to targetActorId.toString()),
                )

            assertEquals(
                AuditEventFilter(tenantId, actorId = targetActorId, page = 1, size = 25),
                capturedFilter,
            )
        }

        @Test
        fun `search audit events filters by action and occurred date range`() {
            val tenantId = uuidV7()
            val callerId = uuidV7()
            val occurredFrom = Instant.parse("2026-07-20T08:00:00Z")
            val occurredTo = Instant.parse("2026-07-21T08:00:00Z")

            val capturedFilter =
                searchAndCapture(
                    tenantId,
                    callerId,
                    mapOf(
                        "action" to "branch.updated",
                        "occurred_from" to occurredFrom.toString(),
                        "occurred_to" to occurredTo.toString(),
                    ),
                )

            assertEquals(
                AuditEventFilter(
                    tenantId,
                    action = "branch.updated",
                    occurredFrom = occurredFrom,
                    occurredTo = occurredTo,
                    page = 1,
                    size = 25,
                ),
                capturedFilter,
            )
        }

        @Test
        fun `search audit events with no filters returns the tenant scoped page`() {
            val tenantId = uuidV7()
            val callerId = uuidV7()

            val capturedFilter = searchAndCapture(tenantId, callerId, emptyMap())

            assertEquals(AuditEventFilter(tenantId, page = 1, size = 25), capturedFilter)
        }

        private fun searchAndCapture(
            tenantId: UUID,
            callerId: UUID,
            parameters: Map<String, String>,
        ): AuditEventFilter {
            val filterCaptor = argumentCaptor<AuditEventFilter>()
            whenever(auditQueryService.search(filterCaptor.capture(), eq(callerId))).thenReturn(
                AuditEventPage(items = listOf(auditEventSummary()), totalItems = 26),
            )

            mockMvc
                .get(ApiPaths.AUDIT_EVENTS) {
                    param("page", "1")
                    param("size", "25")
                    parameters.forEach { (name, value) -> param(name, value) }
                    with(authentication(tenantToken(setOf("audit.view"), tenantId, callerId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].outcome") { value("SUCCESS") }
                    jsonPath("$.items[0].severity") { value("INFO") }
                    jsonPath("$.page.number") { value(1) }
                    jsonPath("$.page.size") { value(25) }
                    jsonPath("$.page.total_items") { value(26) }
                }

            return filterCaptor.firstValue
        }

        @Test
        fun `search audit events rejects invalid page bounds`() {
            val tenantId = uuidV7()

            listOf("0", "101").forEach { size ->
                mockMvc
                    .get(ApiPaths.AUDIT_EVENTS) {
                        param("size", size)
                        with(authentication(tenantToken(setOf("audit.view"), tenantId)))
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("validation_failed") }
                    }
            }
        }

        @Test
        fun `search audit events rejects unauthenticated callers`() {
            mockMvc.get(ApiPaths.AUDIT_EVENTS).andExpect {
                status { isUnauthorized() }
            }
        }

        @Test
        fun `search audit events rejects callers without audit permission`() {
            mockMvc
                .get(ApiPaths.AUDIT_EVENTS) {
                    with(authentication(tenantToken(emptySet())))
                }.andExpect {
                    status { isForbidden() }
                }
        }

        @Test
        fun `get audit event returns every detail field`() {
            val tenantId = uuidV7()
            val callerId = uuidV7()
            val eventId = uuidV7()
            val detail = auditEventDetail(eventId, tenantId)
            whenever(auditQueryService.get(eventId, tenantId, callerId)).thenReturn(detail)

            mockMvc
                .get("${ApiPaths.AUDIT_EVENTS}/$eventId") {
                    with(authentication(tenantToken(setOf("audit.view"), tenantId, callerId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(eventId.toString()) }
                    jsonPath("$.organisation_id") { value(tenantId.toString()) }
                    jsonPath("$.occurred_at") { value("2026-07-21T10:15:30Z") }
                    jsonPath("$.actor_user_id") { value(detail.actorUserId.toString()) }
                    jsonPath("$.actor_external_subject") { value("keycloak:external-subject") }
                    jsonPath("$.actor_type") { value("USER") }
                    jsonPath("$.branch_id") { value(detail.branchId.toString()) }
                    jsonPath("$.event_type") { value("BRANCH_UPDATED") }
                    jsonPath("$.entity_type") { value("BRANCH") }
                    jsonPath("$.entity_id") { value(detail.entityId.toString()) }
                    jsonPath("$.action") { value("branch.updated") }
                    jsonPath("$.outcome") { value("FAILURE") }
                    jsonPath("$.severity") { value("HIGH") }
                    jsonPath("$.ip_address") { value("203.0.113.7") }
                    jsonPath("$.user_agent") { value("Finaxis Test Client") }
                    jsonPath("$.correlation_id") { value("correlation-123") }
                    jsonPath("$.request_id") { value("request-456") }
                    jsonPath("$.before_json") { value("{\"status\":\"DRAFT\"}") }
                    jsonPath("$.after_json") { value("{\"status\":\"ACTIVE\"}") }
                    jsonPath("$.metadata_json") { value("{\"source\":\"api\"}") }
                    jsonPath("$.reason") { value("Approval verification failed") }
                }
        }

        @Test
        fun `get audit event returns service not found problem`() {
            val tenantId = uuidV7()
            val eventId = uuidV7()
            whenever(auditQueryService.get(eq(eventId), eq(tenantId), any())).thenThrow(
                ResourceNotFoundException(
                    code = "audit_event_not_found",
                    safeDetail = "Audit event not found: $eventId",
                ),
            )

            mockMvc
                .get("${ApiPaths.AUDIT_EVENTS}/$eventId") {
                    with(authentication(tenantToken(setOf("audit.view"), tenantId)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("audit_event_not_found") }
                }
        }

        @Test
        fun `get audit event rejects unauthenticated callers`() {
            mockMvc.get("${ApiPaths.AUDIT_EVENTS}/${uuidV7()}").andExpect {
                status { isUnauthorized() }
            }
        }

        @Test
        fun `get audit event rejects callers without audit permission`() {
            mockMvc
                .get("${ApiPaths.AUDIT_EVENTS}/${uuidV7()}") {
                    with(authentication(tenantToken(emptySet())))
                }.andExpect {
                    status { isForbidden() }
                }
        }

        private fun auditEventSummary() =
            AuditEventSummary(
                id = uuidV7(),
                occurredAt = Instant.parse("2026-07-21T10:15:30Z"),
                actorType = "USER",
                actorId = uuidV7(),
                branchId = uuidV7(),
                action = "branch.updated",
                resourceType = "BRANCH",
                resourceId = "BRANCH-001",
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.INFO,
                reason = null,
            )

        private fun auditEventDetail(
            eventId: UUID,
            tenantId: UUID,
        ) = AuditEventDetail(
            id = eventId,
            organisationId = tenantId,
            occurredAt = Instant.parse("2026-07-21T10:15:30Z"),
            actorUserId = uuidV7(),
            actorExternalSubject = "keycloak:external-subject",
            actorType = "USER",
            branchId = uuidV7(),
            eventType = "BRANCH_UPDATED",
            entityType = "BRANCH",
            entityId = uuidV7(),
            action = "branch.updated",
            outcome = AuditOutcome.FAILURE,
            severity = AuditSeverity.HIGH,
            ipAddress = "203.0.113.7",
            userAgent = "Finaxis Test Client",
            correlationId = "correlation-123",
            requestId = "request-456",
            beforeJson = "{\"status\":\"DRAFT\"}",
            afterJson = "{\"status\":\"ACTIVE\"}",
            metadataJson = "{\"source\":\"api\"}",
            reason = "Approval verification failed",
        )

        private fun tenantToken(
            permissions: Set<String>,
            tenantId: UUID = uuidV7(),
            userId: UUID = uuidV7(),
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "tenant-user",
                organisationId = tenantId,
                membershipId = uuidV7(),
                email = "admin@tenant.test",
                fullName = "Tenant Admin",
                permissions = permissions,
            ),
        )
    }
