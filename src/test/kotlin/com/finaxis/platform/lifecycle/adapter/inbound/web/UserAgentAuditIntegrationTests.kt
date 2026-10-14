package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
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
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full-stack proof of the user agent (#185) through the real filter chain and PostgreSQL: the audit
 * row a mutation writes stores the `User-Agent` the request sent, and an agent longer than the
 * stored cap is truncated silently while the request still succeeds. Each row is read back by the
 * branch id the response returned and its action, never as "the latest row". Client addresses ride
 * on the same request, so the test also shows the forwarded client is stored beside the agent.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest(properties = [UserAgentAuditIntegrationTests.TRUSTED_PROXIES])
@AutoConfigureMockMvc
class UserAgentAuditIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val apiJsonCodec: ApiJsonCodec,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val tenantAdmin = seedUser()
        private val tenant = fixture.createActiveOrganisation("user-agent", tenantAdmin)

        @Test
        fun `a mutation stores the user agent and the forwarded client the request sent`() {
            val branchId = createBranch(userAgent = BROWSER_AGENT)

            val row = auditRowOf(branchId)
            assertEquals(BROWSER_AGENT, row.userAgent)
            assertEquals(FORWARDED_CLIENT, row.ipAddress)
        }

        @Test
        fun `an agent over 512 characters is stored truncated and the request still succeeds`() {
            val longAgent = LONG_AGENT_PREFIX + "x".repeat(600)

            val branchId = createBranch(userAgent = longAgent)

            val stored = requireNotNull(auditRowOf(branchId).userAgent)
            assertEquals(512, stored.length)
            assertTrue(stored.startsWith(LONG_AGENT_PREFIX))
            assertEquals(longAgent.take(512), stored)
        }

        private data class AuditRow(
            val userAgent: String?,
            val ipAddress: String?,
        )

        /** Creates a branch with [userAgent] and returns the id the response reports. */
        private fun createBranch(userAgent: String): UUID {
            val code = "UA-${uuidV7().toString().takeLast(8).uppercase()}"
            val body =
                mockMvc
                    .post(ApiPaths.BRANCHES) {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                        header("User-Agent", userAgent)
                        header("X-Forwarded-For", FORWARDED_CLIENT)
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            """{"branch_code":"$code","branch_name":"User Agent Branch",""" +
                            """"branch_type":"OPERATIONAL","timezone":"Africa/Nairobi"}"""
                        with(authentication(branchToken()))
                        with(
                            RequestPostProcessor { request ->
                                request.apply { remoteAddr = PEER }
                            },
                        )
                    }.andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("branch_id")
                    .asString(),
            )
        }

        /** The one audit row the create wrote for [branchId]: its own entity id and action. */
        private fun auditRowOf(branchId: UUID): AuditRow {
            val rows =
                dsl
                    .select(AUDIT_EVENT.USER_AGENT, AUDIT_EVENT.IP_ADDRESS)
                    .from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ENTITY_ID.eq(branchId))
                    .and(AUDIT_EVENT.ACTION.eq(BRANCH_CREATE_ACTION))
                    .fetch { AuditRow(it.value1(), it.value2()) }
            assertEquals(1, rows.size, "expected one $BRANCH_CREATE_ACTION row for $branchId")
            return rows.single()
        }

        private fun branchToken() =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = tenantAdmin,
                    keycloakSubject = "kc-$tenantAdmin",
                    organisationId = tenant,
                    membershipId = uuidV7(),
                    email = "user-agent@example.test",
                    fullName = "User Agent User",
                    permissions = setOf("branch.create"),
                ),
            )

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "user-agent-$id")
                .set(USER_ACCOUNT.EMAIL, "user-agent-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "User Agent User")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        /** The trusted proxy this context uses, and the client it forwards for. */
        companion object {
            const val TRUSTED_PROXIES = "finaxis.security.client-ip.trusted-proxies=10.20.0.0/16"
            const val PEER = "10.20.0.5"
            const val FORWARDED_CLIENT = "203.0.113.50"
            const val BROWSER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) FinaxisProbe/1.0"
            const val LONG_AGENT_PREFIX = "finaxis-probe/"
            const val BRANCH_CREATE_ACTION = "branch.create_draft"
        }
    }
