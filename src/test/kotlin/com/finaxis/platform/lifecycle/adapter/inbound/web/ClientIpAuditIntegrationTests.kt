package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.web.filter.OncePerRequestFilter
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full-stack proof of #185 through the real filter chain: the audit row a mutation writes stores
 * the client address resolved from the peer, or from `X-Forwarded-For` only when the peer is a
 * configured trusted proxy. The tenant audit read returns it; a platform page withholds it (owner
 * ruling, #247). MockMvc has no Tomcat valve, so this is the resolver on its own; the valve in
 * front of it is proven through a real server by `ForwardedHeadersIntegrationTests` (#256).
 */
@Import(PostgresTestConfiguration::class, ClientIpAuditIntegrationTests.RemoteAddressProbe::class)
@SpringBootTest(properties = [ClientIpAuditIntegrationTests.TRUSTED_PROXIES])
@AutoConfigureMockMvc
class ClientIpAuditIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val apiJsonCodec: ApiJsonCodec,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val tenantAdmin = seedUser()
        private val tenant = fixture.createActiveOrganisation("client-ip", tenantAdmin)
        private var probedRemoteAddress: Any? = null

        @Test
        fun `a direct request stores the peer address`() {
            val rows = createBranch(peer = "198.51.100.23")

            rows.forEach { assertEquals("198.51.100.23", it.ipAddress) }
        }

        @Test
        fun `behind a trusted proxy the forwarded client is stored`() {
            val chain = "6.6.6.6, 203.0.113.50, 192.0.2.10"
            val rows = createBranch(peer = "10.20.0.5", forwardedFor = chain)

            rows.forEach { assertEquals("203.0.113.50", it.ipAddress) }
        }

        @Test
        fun `a forwarded header from an untrusted peer is ignored`() {
            val rows = createBranch(peer = "198.51.100.23", forwardedFor = "203.0.113.66")

            // No filter rewrites the address any more (#256: no ForwardedHeaderFilter): the first
            // filter already sees the peer, and the resolver did not believe the forged entry.
            assertEquals("198.51.100.23", probedRemoteAddress)
            rows.forEach { assertEquals("198.51.100.23", it.ipAddress) }
        }

        @Test
        fun `an IPv6 peer is stored in its compressed form`() {
            val rows = createBranch(peer = "2001:db8:0:0:0:0:0:7")

            rows.forEach { assertEquals("2001:db8::7", it.ipAddress) }
        }

        @Test
        fun `the tenant read returns the address and a platform page withholds it`() {
            val row = createBranch(peer = "10.20.0.5", forwardedFor = "203.0.113.51").first()
            val platformAdmin = seedUser().also { fixture.grantPlatformSuperAdmin(it) }

            mockMvc
                .get("${ApiPaths.AUDIT_EVENTS}/${row.id}") {
                    with(authentication(token(tenantAdmin, tenant)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.ip_address") { value("203.0.113.51") }
                }

            val body =
                mockMvc
                    .get("${ApiPaths.PLATFORM_TENANTS}/$tenant/audit-events") {
                        param("action", row.action)
                        param("size", "100")
                        with(authentication(token(platformAdmin, PlatformOrganisation.ID)))
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
            val item =
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("items")
                    .single { it.get("id").asString() == row.id.toString() }
            assertTrue(item.has("ip_address"))
            assertTrue(item.get("ip_address").isNull)
        }

        private data class AuditRow(
            val id: UUID,
            val action: String,
            val ipAddress: String?,
        )

        /** Creates a branch as [peer] and returns the audit rows that request wrote. */
        private fun createBranch(
            peer: String,
            forwardedFor: String? = null,
        ): List<AuditRow> {
            val requestId = "ip185-${uuidV7()}"
            mockMvc
                .post(ApiPaths.BRANCHES) {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    header("X-Request-Id", requestId)
                    forwardedFor?.let { header("X-Forwarded-For", it) }
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"branch_code":"IP-${uuidV7().toString().takeLast(8).uppercase()}",""" +
                        """"branch_name":"Client IP Branch","branch_type":"OPERATIONAL",""" +
                        """"timezone":"Africa/Nairobi"}"""
                    with(authentication(branchToken()))
                    with(RequestPostProcessor { request -> request.apply { remoteAddr = peer } })
                }.andExpect { status { isCreated() } }
                .andReturn()
                .also { probedRemoteAddress = it.request.getAttribute(PROBE_ATTRIBUTE) }
            val rows =
                dsl
                    .select(AUDIT_EVENT.ID, AUDIT_EVENT.ACTION, AUDIT_EVENT.IP_ADDRESS)
                    .from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.REQUEST_ID.eq(requestId))
                    .fetch { AuditRow(it.value1()!!, it.value2()!!, it.value3()) }
            assertTrue(rows.isNotEmpty(), "the branch creation wrote no audit row")
            return rows
        }

        private fun branchToken() =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = tenantAdmin,
                    keycloakSubject = "kc-$tenantAdmin",
                    organisationId = tenant,
                    membershipId = uuidV7(),
                    email = "client-ip@example.test",
                    fullName = "Client Ip User",
                    permissions = setOf("branch.create"),
                ),
            )

        private fun token(
            userId: UUID,
            organisationId: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "kc-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "client-ip@example.test",
                fullName = "Client Ip User",
                permissions = setOf("audit.view"),
            ),
        )

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "client-ip-$id")
                .set(USER_ACCOUNT.EMAIL, "client-ip-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Client Ip User")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        /**
         * Records the `getRemoteAddr()` the first application filter sees, so the test proves no
         * forward-headers filter rewrote it on the request.
         */
        @TestConfiguration(proxyBeanMethods = false)
        class RemoteAddressProbe {
            @Bean
            fun remoteAddressProbe(): FilterRegistrationBean<OncePerRequestFilter> =
                FilterRegistrationBean<OncePerRequestFilter>(
                    object : OncePerRequestFilter() {
                        override fun doFilterInternal(
                            request: HttpServletRequest,
                            response: HttpServletResponse,
                            filterChain: FilterChain,
                        ) {
                            request.setAttribute(PROBE_ATTRIBUTE, request.remoteAddr)
                            filterChain.doFilter(request, response)
                        }
                    },
                ).apply { order = Ordered.HIGHEST_PRECEDENCE + 1 }
        }

        /** The proxies this context trusts: one range and one single address. */
        companion object {
            const val PROBE_ATTRIBUTE = "client-ip-test.probed-remote-address"

            const val TRUSTED_PROXIES =
                "finaxis.security.client-ip.trusted-proxies=10.20.0.0/16,192.0.2.10"
        }
    }
