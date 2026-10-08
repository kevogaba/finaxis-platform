package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.query.BranchDetail
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The tenant and platform branch detail mappers return the stored address and the business dates
 * (issue #165); a stored address that cannot be read back is answered as empty, never a 500.
 */
@WebMvcTest(
    controllers = [BranchController::class, PlatformTenantBranchController::class],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    BranchControllerTests.TestSecurityConfiguration::class,
    BranchController::class,
    PlatformTenantBranchController::class,
)
class BranchDetailMappingControllerTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
    ) {
        @MockitoBean
        private lateinit var branchProvisioningService: BranchProvisioningService

        @MockitoBean
        private lateinit var foundationQueryService: FoundationQueryService

        private val tenantId = uuidV7()
        private val branchId = uuidV7()

        @Test
        fun `tenant get returns the stored address and the business dates`() {
            stubBranch(
                addressJson =
                    """{"line1":"12 Riverside Drive","city":"Nairobi","postalCode":"00100"}""",
                openedOn = LocalDate.of(2026, 3, 9),
                closedOn = LocalDate.of(2026, 4, 21),
            )

            mockMvc
                .get("/api/v1/branches/$branchId") {
                    with(authentication(token(tenantId, branchId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.address.line1") { value("12 Riverside Drive") }
                    jsonPath("$.address.city") { value("Nairobi") }
                    jsonPath("$.address.postalCode") { value("00100") }
                    jsonPath("$.opened_on") { value("09-03-2026") }
                    jsonPath("$.closed_on") { value("21-04-2026") }
                }
        }

        @Test
        fun `platform get returns the stored address and the business dates`() {
            stubBranch(
                addressJson = """{"line1":"12 Riverside Drive","city":"Nairobi"}""",
                openedOn = LocalDate.of(2026, 3, 9),
            )

            mockMvc
                .get("/api/v1/platform/tenants/$tenantId/branches/$branchId") {
                    with(authentication(token(PlatformOrganisation.ID, null)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.address.line1") { value("12 Riverside Drive") }
                    jsonPath("$.address.city") { value("Nairobi") }
                    jsonPath("$.opened_on") { value("09-03-2026") }
                    jsonPath("$.closed_on") { doesNotExist() }
                }
        }

        @Test
        fun `a blank or malformed stored address reads as an empty address on both controllers`() {
            listOf("", "   ", "{}", "null", "not json", "[1,2]", """{"line1":{"x":1}}""")
                .forEach { stored ->
                    stubBranch(addressJson = stored)

                    mockMvc
                        .get("/api/v1/branches/$branchId") {
                            with(authentication(token(tenantId, branchId)))
                        }.andExpect {
                            status { isOk() }
                            jsonPath("$.address") { isEmpty() }
                        }
                    mockMvc
                        .get("/api/v1/platform/tenants/$tenantId/branches/$branchId") {
                            with(authentication(token(PlatformOrganisation.ID, null)))
                        }.andExpect {
                            status { isOk() }
                            jsonPath("$.address") { isEmpty() }
                        }
                }
        }

        private fun stubBranch(
            addressJson: String,
            openedOn: LocalDate? = null,
            closedOn: LocalDate? = null,
        ) {
            whenever(foundationQueryService.getBranch(eq(tenantId), eq(branchId), any()))
                .thenReturn(
                    BranchDetail(
                        id = branchId,
                        organisationId = tenantId,
                        branchCode = "HQ-01",
                        branchName = "Headquarters",
                        branchType = "HEAD_OFFICE",
                        parentBranchId = null,
                        status = "ACTIVE",
                        timezone = "Africa/Nairobi",
                        addressJson = addressJson,
                        openedOn = openedOn,
                        closedOn = closedOn,
                        statusReason = null,
                        createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                        updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                    ),
                )
        }

        private fun token(
            organisationId: UUID,
            branchId: UUID?,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = uuidV7(),
                keycloakSubject = "user",
                organisationId = organisationId,
                membershipId = uuidV7(),
                branchId = branchId,
                email = "user@branch.test",
                fullName = "Branch Viewer",
                permissions = setOf("branch.view"),
            ),
        )
    }
