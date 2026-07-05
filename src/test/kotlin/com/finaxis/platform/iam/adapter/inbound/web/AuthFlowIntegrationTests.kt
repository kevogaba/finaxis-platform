package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.TestContainerImages
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

private const val LOCAL_USER_SUBJECT = "11111111-1111-1111-1111-111111111111"
private const val LOCAL_ORGANISATION_ID = "22222222-2222-2222-2222-222222222222"
private const val HEAD_OFFICE_BRANCH_ID = "33333333-3333-3333-3333-333333333333"
private const val OPERATIONS_BRANCH_ID = "44444444-4444-4444-4444-444444444444"
private const val LOCAL_MEMBERSHIP_ID = "55555555-5555-5555-5555-555555555555"

@Import(PostgresTestConfiguration::class, AuthFlowIntegrationTests.RedisTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `smoke auth flow selects tenant context and returns current profile`() {
        val organisationContextToken = selectOrganisation()
        val branchContextToken = selectBranch(organisationContextToken)

        assertCurrentProfile(branchContextToken)
    }

    @Test
    fun `public controllers reject unauthenticated requests`() {
        mockMvc
            .get("/api/v1/auth/me")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `profile endpoint requires active organisation context`() {
        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `invalid active organisation context fails closed`() {
        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, "invalid-context")
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `selection endpoint returns validation errors through api exception handler`() {
        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
                jsonPath("$.fieldErrors.organisationId[0]") { value("must not be null") }
            }
    }

    @Test
    fun `selection endpoint returns invalid json errors through api exception handler`() {
        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"organisationId":"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid_json") }
                jsonPath("$.errors[0].attribute") { value("request_body") }
            }
    }

    @Test
    fun `selection endpoint rejects unknown organisation membership`() {
        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"organisationId":"${UUID.randomUUID()}"}"""
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
            }
    }

    private fun selectOrganisation(): String =
        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"organisationId":"$LOCAL_ORGANISATION_ID"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.organisationId") { value(LOCAL_ORGANISATION_ID) }
                jsonPath("$.membershipId") { value(LOCAL_MEMBERSHIP_ID) }
                jsonPath("$.contextHeader") { value(ActiveOrganisationContextService.HEADER) }
                jsonPath("$.branchId") { doesNotExist() }
                jsonPath("$.requiresBranchSelection") { value(true) }
                jsonPath("$.assignedBranchIds") {
                    value(containsInAnyOrder(HEAD_OFFICE_BRANCH_ID, OPERATIONS_BRANCH_ID))
                }
            }.andReturn()
            .response
            .jsonContextToken()

    private fun selectBranch(organisationContextToken: String): String =
        mockMvc
            .post("/api/v1/auth/select-branch") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, organisationContextToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"branchId":"$HEAD_OFFICE_BRANCH_ID"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.organisationId") { value(LOCAL_ORGANISATION_ID) }
                jsonPath("$.membershipId") { value(LOCAL_MEMBERSHIP_ID) }
                jsonPath("$.branchId") { value(HEAD_OFFICE_BRANCH_ID) }
                jsonPath("$.contextHeader") { value(ActiveOrganisationContextService.HEADER) }
            }.andReturn()
            .response
            .jsonContextToken()

    private fun assertCurrentProfile(branchContextToken: String) {
        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, branchContextToken)
            }.andExpect {
                status { isOk() }
                jsonPath("$.userId") { value(LOCAL_USER_SUBJECT) }
                jsonPath("$.keycloakSubject") { value(LOCAL_USER_SUBJECT) }
                jsonPath("$.email") { value("admin@finaxis.local") }
                jsonPath("$.organisation.id") { value(LOCAL_ORGANISATION_ID) }
                jsonPath("$.organisation.code") { value("FINAXIS-LOCAL") }
                jsonPath("$.membership.id") { value(LOCAL_MEMBERSHIP_ID) }
                jsonPath("$.selectedBranch.id") { value(HEAD_OFFICE_BRANCH_ID) }
                jsonPath("$.selectedBranch.code") { value("HQ") }
                jsonPath("$.branches[*].id") {
                    value(containsInAnyOrder(HEAD_OFFICE_BRANCH_ID, OPERATIONS_BRANCH_ID))
                }
                jsonPath("$.roles[*].code") { value(hasItem("local-admin")) }
                jsonPath("$.permissions") {
                    value(
                        containsInAnyOrder(
                            "iam.profile.read",
                            "iam.user.invite",
                            "logistics.shipment.approve",
                        ),
                    )
                }
            }
    }

    private fun localJwt() =
        jwt().jwt { token ->
            token.subject(LOCAL_USER_SUBJECT)
        }

    private fun org.springframework.mock.web.MockHttpServletResponse.jsonContextToken(): String =
        com.jayway.jsonpath.JsonPath
            .read(contentAsString, "$.contextToken")

    @TestConfiguration(proxyBeanMethods = false)
    class RedisTestConfiguration {
        @Bean
        @ServiceConnection(name = "redis")
        fun redisContainer(): GenericContainer<*> =
            GenericContainer(DockerImageName.parse(TestContainerImages.REDIS))
                .withExposedPorts(6379)
    }
}
