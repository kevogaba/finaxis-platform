package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID

/**
 * The idempotency scope check runs before the controller, so a wrong-context call to an
 * idempotent mutation must get the stable context code and its matching sentence from there too,
 * through the full application (aspect, filters and method security included).
 *
 * The order is method security, then the idempotency scope check, then the controller. So a
 * context code is returned only by a request that clears its route's `@PreAuthorize` gate: the
 * platform route below is gated by `branch.create`, which the bootstrap tenant administrator
 * holds. A request with no active-organisation context carries no permission authority, so on a
 * gated tenant mutation method security refuses it first with the generic `forbidden`; only an
 * ungated idempotent tenant route reaches the scope check and answers `tenant_context_required`.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class WrongContextIdempotentRouteIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun localJwt() = jwt().jwt { token -> token.subject(LOCAL_USER_SUBJECT) }

    private fun tenantContextToken(): String {
        val organisation =
            mockMvc
                .post("/api/v1/auth/select-organisation") {
                    with(localJwt())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"organisation_id":"$LOCAL_ORGANISATION_ID"}"""
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        val branch =
            mockMvc
                .post("/api/v1/auth/select-branch") {
                    with(localJwt())
                    header(
                        ActiveOrganisationContextService.HEADER,
                        JsonPath.read<String>(organisation, "$.context_token"),
                    )
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"branch_id":"$HEAD_OFFICE_BRANCH_ID"}"""
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        return JsonPath.read(branch, "$.context_token")
    }

    @Test
    fun `a tenant context posting to a platform scoped route gets the platform context code`() {
        val tenantId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
        mockMvc
            .post("/api/v1/platform/tenants/$tenantId/branches/$branchId/submit") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, tenantContextToken())
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("platform_context_required") }
                jsonPath("$.detail") {
                    value("Reserved platform organisation context is required for this route.")
                }
            }
    }

    @Test
    fun `no context on a gated tenant mutation is refused by method security first`() {
        mockMvc
            .post("/api/v1/branches") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"branch_code":"NOCTX","branch_name":"No Context",""" +
                    """"branch_type":"BRANCH","timezone":"Africa/Nairobi"}"""
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
                jsonPath("$.detail") { value("You are not permitted to perform this action.") }
            }
    }

    @Test
    fun `no context on an ungated tenant mutation reaches the scope check`() {
        val selectBranch =
            mockMvc.post("/api/v1/auth/select-branch") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"branch_id":"$HEAD_OFFICE_BRANCH_ID"}"""
            }
        val putSetting =
            mockMvc.put("/api/v1/tenant/settings/no-context-probe") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"value":"x"}"""
            }
        listOf(selectBranch, putSetting).forEach { response ->
            response.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("tenant_context_required") }
                jsonPath("$.detail") { value("Active tenant context is required for this route.") }
            }
        }
    }

    private companion object {
        const val LOCAL_USER_SUBJECT = "11111111-1111-1111-1111-111111111111"
        const val HEAD_OFFICE_BRANCH_ID = "33333333-3333-3333-3333-333333333333"
        const val LOCAL_ORGANISATION_ID = "22222222-2222-2222-2222-222222222222"
    }
}
