package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.iam.adapter.inbound.security.SessionActiveOrganisationContextResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.jooq.tables.references.API_IDEMPOTENCY_RECORD
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import jakarta.servlet.ServletContext
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post as requestPost

@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = ["finaxis.api.idempotency.max-request-body-bytes=512"],
)
@AutoConfigureMockMvc
class IdempotencyWebIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    @Qualifier("idempotencyKeyFilterRegistration")
    private lateinit var filterRegistration: FilterRegistrationBean<IdempotencyKeyFilter>

    @BeforeEach
    fun clearRecords() {
        dsl.deleteFrom(API_IDEMPOTENCY_RECORD).execute()
        dsl
            .update(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(UUID.fromString(LOCAL_MEMBERSHIP_ID)))
            .execute()
        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.STATUS, "ACTIVE")
            .where(ORGANISATION.ID.eq(UUID.fromString(LOCAL_ORGANISATION_ID)))
            .execute()
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
            .where(USER_BRANCH_ASSIGNMENT.USER_ID.eq(UUID.fromString(LOCAL_USER_SUBJECT)))
            .execute()
    }

    @Test
    fun `client key is returned and an identical request replays once`() {
        val key = UUID.randomUUID()
        val firstSession = MockHttpSession()
        val replaySession = MockHttpSession()

        val first = selectOrganisation(key, firstSession)
        val replay = selectOrganisation(key, replaySession)

        assertThat(first.response.getHeader(IDEMPOTENCY_KEY_HEADER)).isEqualTo(key.toString())
        assertThat(first.response.getHeader(IDEMPOTENCY_REPLAYED_HEADER)).isNull()
        assertThat(replay.response.getHeader(IDEMPOTENCY_KEY_HEADER)).isEqualTo(key.toString())
        assertThat(replay.response.getHeader(IDEMPOTENCY_REPLAYED_HEADER)).isEqualTo("true")
        assertThat(stableSelectionFields(replay)).isEqualTo(stableSelectionFields(first))
        assertThat(dsl.fetchCount(API_IDEMPOTENCY_RECORD)).isEqualTo(1)
    }

    @Test
    fun `same browser session replay keeps keycloak subject fingerprint after enrichment`() {
        val key = UUID.randomUUID()
        val session = MockHttpSession()

        selectOrganisation(key, session)
        val replay = selectOrganisation(key, session)

        assertThat(replay.response.getHeader(IDEMPOTENCY_REPLAYED_HEADER)).isEqualTo("true")
        assertThat(dsl.fetchCount(API_IDEMPOTENCY_RECORD)).isEqualTo(1)
    }

    @Test
    fun `missing key is generated even when request validation fails without retaining a record`() {
        val result =
            mockMvc
                .post(SELECT_ORGANISATION_PATH) {
                    with(localJwt())
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }.andReturn()

        val effectiveKey = requireNotNull(result.response.getHeader(IDEMPOTENCY_KEY_HEADER))
        assertThat(UUID.fromString(effectiveKey)).isNotNull()
        assertThat(dsl.fetchCount(API_IDEMPOTENCY_RECORD)).isZero()
    }

    @Test
    fun `registered mutation filter is concrete and initializes in MockMvc`() {
        assertThat(AopUtils.isAopProxy(filterRegistration.filter)).isFalse()
    }

    @Test
    fun `openapi publishes concrete selection responses and idempotency headers`() {
        mockMvc
            .get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                jsonPath(
                    "$.paths['/api/v1/auth/select-organisation'].post.responses['200']" +
                        ".content['application/json'].schema['\$ref']",
                ) { value("#/components/schemas/SelectOrganisationResponse") }
                jsonPath(
                    "$.paths['/api/v1/auth/select-organisation'].post.responses['200']" +
                        ".headers['Idempotency-Key']",
                ) { exists() }
                jsonPath(
                    "$.components.schemas.SelectOrganisationResponse.properties.durable_body",
                ) {
                    doesNotExist()
                }
                jsonPath(
                    "$.components.schemas.SelectOrganisationResponse.properties.organisation_id",
                ) { exists() }
                jsonPath(
                    "$.components.schemas.SelectOrganisationResponse.properties.organisationId",
                ) { doesNotExist() }
            }
    }

    @Test
    fun `unauthenticated mutation error still returns an effective key`() {
        val result =
            mockMvc
                .post(SELECT_ORGANISATION_PATH) {
                    contentType = MediaType.APPLICATION_JSON
                    content = organisationBody()
                }.andExpect {
                    status { isUnauthorized() }
                }.andReturn()

        assertThat(
            UUID.fromString(requireNotNull(result.response.getHeader(IDEMPOTENCY_KEY_HEADER))),
        ).isNotNull()
        assertThat(dsl.fetchCount(API_IDEMPOTENCY_RECORD)).isZero()
    }

    @Test
    fun `malformed client key returns a safe problem and does not execute mutation`() {
        val result =
            mockMvc
                .post(SELECT_ORGANISATION_PATH) {
                    with(localJwt())
                    header(IDEMPOTENCY_KEY_HEADER, "not-a-uuid")
                    contentType = MediaType.APPLICATION_JSON
                    content = organisationBody()
                }.andExpect {
                    status { isBadRequest() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                    jsonPath("$.code") { value("INVALID_IDEMPOTENCY_KEY") }
                    jsonPath("$.detail") { value("Idempotency-Key must be a valid UUID.") }
                }.andReturn()

        assertThat(
            UUID.fromString(requireNotNull(result.response.getHeader(IDEMPOTENCY_KEY_HEADER))),
        ).isNotNull()

        assertThat(dsl.fetchCount(API_IDEMPOTENCY_RECORD)).isZero()
    }

    @Test
    fun `multiple idempotency key values are rejected safely`() {
        val result =
            mockMvc
                .post(SELECT_ORGANISATION_PATH) {
                    with(localJwt())
                    header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID(), UUID.randomUUID())
                    contentType = MediaType.APPLICATION_JSON
                    content = organisationBody()
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("INVALID_IDEMPOTENCY_KEY") }
                }.andReturn()

        assertThat(UUID.fromString(result.response.getHeader(IDEMPOTENCY_KEY_HEADER))).isNotNull()
        assertThat(dsl.fetchCount(API_IDEMPOTENCY_RECORD)).isZero()
    }

    @Test
    fun `oversized mutation body returns a safe problem and generated key`() {
        val result =
            mockMvc
                .post(SELECT_ORGANISATION_PATH) {
                    with(localJwt())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"padding":"${"x".repeat(600)}"}"""
                }.andExpect {
                    status { isContentTooLarge() }
                    jsonPath("$.code") { value("REQUEST_BODY_TOO_LARGE") }
                }.andReturn()

        assertThat(
            UUID.fromString(requireNotNull(result.response.getHeader(IDEMPOTENCY_KEY_HEADER))),
        ).isNotNull()
        assertThat(dsl.fetchCount(API_IDEMPOTENCY_RECORD)).isZero()
    }

    @Test
    fun `unknown length oversized mutation is rejected before mvc reads it`() {
        val result =
            mockMvc
                .perform(
                    UnknownLengthRequestBuilder()
                        .uri(SELECT_ORGANISATION_PATH)
                        .with(localJwt())
                        .header("Transfer-Encoding", "chunked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"padding":"${"x".repeat(600)}"}"""),
                ).andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status()
                        .isContentTooLarge,
                ).andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code")
                        .value("REQUEST_BODY_TOO_LARGE"),
                ).andReturn()

        assertThat(UUID.fromString(result.response.getHeader(IDEMPOTENCY_KEY_HEADER))).isNotNull()
        assertThat(dsl.fetchCount(API_IDEMPOTENCY_RECORD)).isZero()
    }

    @Test
    fun `same key with changed branch payload conflicts inside active tenant scope`() {
        val organisationToken = selectOrganisation(UUID.randomUUID(), MockHttpSession()).token()
        val key = UUID.randomUUID()

        selectBranch(key, HEAD_OFFICE_BRANCH_ID, organisationToken)
        mockMvc
            .post(SELECT_BRANCH_PATH) {
                with(localJwt())
                header(IDEMPOTENCY_KEY_HEADER, key)
                header(ActiveOrganisationContextService.HEADER, organisationToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"branch_id":"$OPERATIONS_BRANCH_ID"}"""
            }.andExpect {
                status { isConflict() }
                header { string(IDEMPOTENCY_KEY_HEADER, key.toString()) }
                jsonPath("$.code") { value("IDEMPOTENCY_KEY_REUSED") }
            }
    }

    @Test
    fun `concurrent identical requests serialize and create one completed record`() {
        val key = UUID.randomUUID()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val results =
                (1..2).map {
                    executor.submit<MvcResult> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        selectOrganisation(key, MockHttpSession())
                    }
                }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val responses = results.map { it.get(20, TimeUnit.SECONDS).response }

            assertThat(responses).allMatch { it.status == 200 }
            assertThat(responses.count { it.getHeader(IDEMPOTENCY_REPLAYED_HEADER) == "true" })
                .isEqualTo(1)
        }
        assertThat(dsl.fetchCount(API_IDEMPOTENCY_RECORD)).isEqualTo(1)
    }

    @Test
    fun `selection replay restores the current session and never stores a signed token`() {
        val key = UUID.randomUUID()
        val firstSession = MockHttpSession()
        val replaySession = MockHttpSession()

        val first = selectOrganisation(key, firstSession)
        Thread.sleep(1_100)
        val replay = selectOrganisation(key, replaySession)
        val storedBody =
            dsl
                .select(API_IDEMPOTENCY_RECORD.RESPONSE_BODY)
                .from(API_IDEMPOTENCY_RECORD)
                .where(API_IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
                .fetchSingle(API_IDEMPOTENCY_RECORD.RESPONSE_BODY)

        assertThat(firstSession.activeContext()).isNotNull()
        assertThat(replaySession.activeContext()).isEqualTo(firstSession.activeContext())
        assertThat(replay.token()).isNotEqualTo(first.token())
        assertThat(
            activeContextService().verify(replay.token()),
        ).isEqualTo(replaySession.activeContext())
        assertThat(storedBody)
            .doesNotContain(first.token())
            .doesNotContain(replay.token())
            .doesNotContainIgnoringCase("context_token")
            .doesNotContainIgnoringCase("authorization")
            .doesNotContainIgnoringCase("cookie")
            .doesNotContainIgnoringCase("secret")
    }

    @Test
    fun `selection replay denies suspended and revoked memberships before restoring context`() {
        listOf("SUSPENDED", "REVOKED").forEach { status ->
            val key = UUID.randomUUID()
            selectOrganisation(key, MockHttpSession())
            dsl
                .update(USER_ORGANISATION_MEMBERSHIP)
                .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, status)
                .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(UUID.fromString(LOCAL_MEMBERSHIP_ID)))
                .execute()
            val replaySession = MockHttpSession()

            replayOrganisationForbidden(key, replaySession)
            assertThat(replaySession.activeContext()).isNull()

            dsl
                .update(USER_ORGANISATION_MEMBERSHIP)
                .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
                .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(UUID.fromString(LOCAL_MEMBERSHIP_ID)))
                .execute()
        }
    }

    @Test
    fun `selection replay denies inactive organisation before restoring context`() {
        val key = UUID.randomUUID()
        selectOrganisation(key, MockHttpSession())
        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.STATUS, "SUSPENDED")
            .where(ORGANISATION.ID.eq(UUID.fromString(LOCAL_ORGANISATION_ID)))
            .execute()
        val replaySession = MockHttpSession()

        replayOrganisationForbidden(key, replaySession)

        assertThat(replaySession.activeContext()).isNull()
    }

    @Test
    fun `selection replay denies changed branch assignments before restoring context`() {
        val key = UUID.randomUUID()
        selectOrganisation(key, MockHttpSession())
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "INACTIVE")
            .where(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(UUID.fromString(HEAD_OFFICE_BRANCH_ID)))
            .execute()
        val replaySession = MockHttpSession()

        replayOrganisationForbidden(key, replaySession)

        assertThat(replaySession.activeContext()).isNull()
    }

    @Test
    fun `branch replay supports multiple assignments and denies removed selected assignment`() {
        val organisationToken = selectOrganisation(UUID.randomUUID(), MockHttpSession()).token()
        val key = UUID.randomUUID()
        selectBranch(key, HEAD_OFFICE_BRANCH_ID, organisationToken)
        val replay = selectBranch(key, HEAD_OFFICE_BRANCH_ID, organisationToken)
        assertThat(replay.response.getHeader(IDEMPOTENCY_REPLAYED_HEADER)).isEqualTo("true")
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "INACTIVE")
            .where(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(UUID.fromString(HEAD_OFFICE_BRANCH_ID)))
            .execute()

        mockMvc
            .post(SELECT_BRANCH_PATH) {
                with(localJwt())
                header(IDEMPOTENCY_KEY_HEADER, key)
                header(ActiveOrganisationContextService.HEADER, organisationToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"branch_id":"$HEAD_OFFICE_BRANCH_ID"}"""
            }.andExpect { status { isForbidden() } }
    }

    private fun selectOrganisation(
        key: UUID,
        session: MockHttpSession,
    ): MvcResult =
        mockMvc
            .perform(
                requestPost(SELECT_ORGANISATION_PATH)
                    .with(localJwt())
                    .session(session)
                    .header(IDEMPOTENCY_KEY_HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(organisationBody()),
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .status()
                    .isOk,
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.organisation_id")
                    .value(LOCAL_ORGANISATION_ID),
            ).andReturn()

    private fun selectBranch(
        key: UUID,
        branchId: String,
        organisationToken: String,
    ): MvcResult =
        mockMvc
            .post(SELECT_BRANCH_PATH) {
                with(localJwt())
                header(IDEMPOTENCY_KEY_HEADER, key)
                header(ActiveOrganisationContextService.HEADER, organisationToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"branch_id":"$branchId"}"""
            }.andExpect {
                status { isOk() }
            }.andReturn()

    private fun replayOrganisationForbidden(
        key: UUID,
        session: MockHttpSession,
    ) {
        mockMvc
            .post(SELECT_ORGANISATION_PATH) {
                with(localJwt())
                this.session = session
                header(IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = organisationBody()
            }.andExpect { status { isForbidden() } }
    }

    private fun organisationBody(): String = """{"organisation_id":"$LOCAL_ORGANISATION_ID"}"""

    private fun localJwt() = jwt().jwt { token -> token.subject(LOCAL_USER_SUBJECT) }

    @Autowired
    private lateinit var contextService: ActiveOrganisationContextService

    private fun activeContextService(): ActiveOrganisationContextService = contextService

    private fun org.springframework.test.web.servlet.MvcResult.token(): String =
        com.jayway.jsonpath.JsonPath
            .read(response.contentAsString, "$.context_token")

    private fun stableSelectionFields(result: MvcResult): List<Any> =
        listOf(
            com.jayway.jsonpath.JsonPath.read<String>(
                result.response.contentAsString,
                "$.organisation_id",
            ),
            com.jayway.jsonpath.JsonPath.read<String>(
                result.response.contentAsString,
                "$.membership_id",
            ),
            com.jayway.jsonpath.JsonPath.read<Boolean>(
                result.response.contentAsString,
                "$.requires_branch_selection",
            ),
            com.jayway.jsonpath.JsonPath
                .read<List<String>>(
                    result.response.contentAsString,
                    "$.assigned_branch_ids",
                ).sorted(),
        )

    private fun MockHttpSession.activeContext(): ActiveOrganisationContext? =
        getAttribute(SessionActiveOrganisationContextResolver.ATTRIBUTE)
            as? ActiveOrganisationContext

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
        const val SELECT_ORGANISATION_PATH = "/api/v1/auth/select-organisation"
        const val SELECT_BRANCH_PATH = "/api/v1/auth/select-branch"
        const val LOCAL_USER_SUBJECT = "11111111-1111-1111-1111-111111111111"
        const val LOCAL_ORGANISATION_ID = "22222222-2222-2222-2222-222222222222"
        const val LOCAL_MEMBERSHIP_ID = "55555555-5555-5555-5555-555555555555"
        const val HEAD_OFFICE_BRANCH_ID = "33333333-3333-3333-3333-333333333333"
        const val OPERATIONS_BRANCH_ID = "44444444-4444-4444-4444-444444444444"
    }
}

private class UnknownLengthRequestBuilder :
    AbstractMockHttpServletRequestBuilder<UnknownLengthRequestBuilder>(HttpMethod.POST) {
    override fun createServletRequest(servletContext: ServletContext): MockHttpServletRequest =
        object : MockHttpServletRequest(servletContext) {
            override fun getContentLength(): Int = -1

            override fun getContentLengthLong(): Long = -1
        }
}
