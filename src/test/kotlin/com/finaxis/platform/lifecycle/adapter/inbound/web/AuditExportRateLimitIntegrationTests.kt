package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
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
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The export runs under its own `tenant-export` rate-limit policy (ADR 0039, point 18), through
 * the real filter chain and the real Redis bucket: with the capacity lowered to two, the third
 * request of a minute is a `429` that carries the headers, while an ordinary read of the same
 * caller is untouched because it draws on the `tenant-read` bucket.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.rate-limit.policies.tenant-export.capacity=2",
        "finaxis.rate-limit.policies.tenant-export.refill-tokens=2",
        "finaxis.rate-limit.policies.tenant-export.refill-period=1h",
    ],
)
@AutoConfigureMockMvc
class AuditExportRateLimitIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val apiJsonCodec: ApiJsonCodec,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        @Test
        fun `the third export of a minute is a 429 with headers, an ordinary read is not`() {
            val admin = seedUser()
            val organisationId = fixture.createActiveOrganisation("export-rl", admin)
            val token = token(admin, organisationId)

            // The window is missing, so each is a cheap 400: the limiter runs before the handler.
            repeat(2) { attempt ->
                mockMvc
                    .get(EXPORT_PATH) { with(authentication(token)) }
                    .andExpect {
                        status { isBadRequest() }
                        header { string("RateLimit-Limit", "2") }
                        header { string("RateLimit-Remaining", (1 - attempt).toString()) }
                    }
            }

            val body =
                mockMvc
                    .get(EXPORT_PATH) {
                        param("occurred_from", Instant.now().minusSeconds(60).toString())
                        with(authentication(token))
                    }.andExpect {
                        status { isTooManyRequests() }
                        header { string("RateLimit-Limit", "2") }
                        header { string("RateLimit-Remaining", "0") }
                        header { exists("Retry-After") }
                        header { exists("RateLimit-Reset") }
                    }.andReturn()
                    .response.contentAsString
            assertEquals(
                "rate_limit_exceeded",
                apiJsonCodec.mapper
                    .readTree(body)
                    .path("code")
                    .asString(),
            )

            // The search route, same caller, same tenant, has its own tenant-read bucket.
            mockMvc
                .get(ApiPaths.AUDIT_EVENTS) {
                    with(authentication(readToken(token)))
                }.andExpect {
                    status { isOk() }
                    header { string("RateLimit-Limit", "600") }
                }
        }

        @Test
        fun `another caller has its own export bucket`() {
            val first = seedUser()
            val second = seedUser()
            val organisationId = fixture.createActiveOrganisation("export-rl2", first)
            fixture.grantTenantAdmin(organisationId, second)

            repeat(3) {
                mockMvc.get(EXPORT_PATH) { with(authentication(token(first, organisationId))) }
            }

            val response =
                mockMvc
                    .get(EXPORT_PATH) { with(authentication(token(second, organisationId))) }
                    .andReturn()
                    .response
            assertEquals(400, response.status)
            assertNotNull(response.getHeader("RateLimit-Limit"))
        }

        private fun readToken(token: AppPrincipalAuthenticationToken) =
            AppPrincipalAuthenticationToken(token.principal.copy(permissions = setOf("audit.view")))

        private fun token(
            userId: UUID,
            organisationId: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "kc-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "export-rl@example.test",
                fullName = "Export RL User",
                permissions = emptySet(),
            ),
        )

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "export-rl-$id")
                .set(USER_ACCOUNT.EMAIL, "export-rl-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Export RL User")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            const val EXPORT_PATH = "${ApiPaths.AUDIT_EVENTS}/export"
        }
    }
