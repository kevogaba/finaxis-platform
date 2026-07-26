package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.jooq.tables.references.API_IDEMPOTENCY_RECORD
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.web.bind.annotation.RequestMapping as SpringRequestMapping

@Import(
    PostgresTestConfiguration::class,
    ExactResponseTestController::class,
    UnorderedAuditFixtureAspect::class,
)
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyExactResponseIntegrationTests {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var dsl: DSLContext

    @BeforeEach
    fun reset() {
        dsl.deleteFrom(API_IDEMPOTENCY_RECORD).execute()
        ExactResponseTestController.reset()
        UnorderedAuditFixtureAspect.executions.set(0)
    }

    @Test
    fun `exact dto response replays with its public type and executes once`() {
        val key = UUID.randomUUID()

        repeat(2) {
            mockMvc
                .post("/api/v1/idempotency-test/dto") {
                    with(allowedJwt())
                    header("Idempotency-Key", key)
                    contentType = org.springframework.http.MediaType.APPLICATION_JSON
                    content = body()
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.value") { value("created") }
                }
        }

        assertThat(ExactResponseTestController.dtoExecutions.get()).isEqualTo(1)
    }

    @Test
    fun `response entity status and safe headers replay exactly`() {
        val key = UUID.randomUUID()

        repeat(2) {
            mockMvc
                .post("/api/v1/idempotency-test/entity") {
                    with(allowedJwt())
                    header("Idempotency-Key", key)
                    contentType = org.springframework.http.MediaType.APPLICATION_JSON
                    content = body()
                }.andExpect {
                    status { isCreated() }
                    header { string("X-Domain-Reference", "ref-1") }
                    jsonPath("$.value") { value("entity") }
                }
        }

        assertThat(ExactResponseTestController.entityExecutions.get()).isEqualTo(1)
    }

    @Test
    fun `no content unit response replays without a body`() {
        val key = UUID.randomUUID()

        repeat(2) {
            mockMvc
                .post("/api/v1/idempotency-test/no-content") {
                    with(allowedJwt())
                    header("Idempotency-Key", key)
                    contentType = org.springframework.http.MediaType.APPLICATION_JSON
                    content = body()
                }.andExpect {
                    status { isNoContent() }
                    content { string("") }
                }
        }

        assertThat(ExactResponseTestController.noContentExecutions.get()).isEqualTo(1)
    }

    @Test
    fun `method security runs before an exact replay is returned`() {
        val key = UUID.randomUUID()
        mockMvc
            .post("/api/v1/idempotency-test/dto") {
                with(allowedJwt())
                header("Idempotency-Key", key)
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = body()
            }.andExpect { status { isOk() } }

        mockMvc
            .post("/api/v1/idempotency-test/dto") {
                with(jwt().jwt { it.subject("actor") })
                header("Idempotency-Key", key)
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = body()
            }.andExpect { status { isForbidden() } }

        assertThat(ExactResponseTestController.dtoExecutions.get()).isEqualTo(1)
    }

    @Test
    fun `idempotency replay does not execute unordered audit advice twice`() {
        val key = UUID.randomUUID()

        repeat(2) {
            mockMvc
                .post("/api/v1/idempotency-test/dto") {
                    with(allowedJwt())
                    header("Idempotency-Key", key)
                    contentType = org.springframework.http.MediaType.APPLICATION_JSON
                    content = body()
                }.andExpect { status { isOk() } }
        }

        assertThat(UnorderedAuditFixtureAspect.executions.get()).isEqualTo(1)
    }

    @Test
    fun `composed and inherited mutation contracts are intercepted at runtime`() {
        listOf("composed", "inherited").forEach { endpoint ->
            val key = UUID.randomUUID()
            repeat(2) {
                mockMvc
                    .post("/api/v1/idempotency-test/$endpoint") {
                        with(allowedJwt())
                        header("Idempotency-Key", key)
                        contentType = org.springframework.http.MediaType.APPLICATION_JSON
                        content = body()
                    }.andExpect { status { isOk() } }
            }
        }

        assertThat(ExactResponseTestController.composedExecutions.get()).isEqualTo(1)
        assertThat(ExactResponseTestController.inheritedExecutions.get()).isEqualTo(1)
    }

    @Test
    fun `malformed query encoding returns a safe client problem`() {
        mockMvc
            .post("/api/v1/idempotency-test/dto") {
                with(allowedJwt())
                with { request ->
                    request.queryString = "broken=%GG"
                    request
                }
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = body()
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_QUERY_ENCODING") }
            }

        assertThat(ExactResponseTestController.dtoExecutions.get()).isZero()
    }

    private fun allowedJwt() =
        jwt().jwt { it.subject("actor") }.authorities(
            SimpleGrantedAuthority("test.write"),
        )

    private fun body(): String = """{"organisation_id":"$ORGANISATION_ID"}"""

    private companion object {
        val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}

data class ExactMutationRequest(
    @field:NotNull val organisationId: UUID?,
)

data class ExactMutationResponse(
    val value: String,
)

@RestController
@RequestMapping("/api/v1/idempotency-test")
class ExactResponseTestController : InheritedExactMutation {
    @PostMapping("/dto")
    @PreAuthorize("hasAuthority('test.write')")
    @IdempotentMutation(scope = IdempotencyScopeKind.ORGANISATION_SELECTION)
    fun dto(
        @Valid @RequestBody request: ExactMutationRequest,
    ): ExactMutationResponse {
        requireNotNull(request.organisationId)
        dtoExecutions.incrementAndGet()
        return ExactMutationResponse("created")
    }

    @PostMapping("/entity")
    @PreAuthorize("hasAuthority('test.write')")
    @IdempotentMutation(scope = IdempotencyScopeKind.ORGANISATION_SELECTION)
    fun entity(
        @Valid @RequestBody request: ExactMutationRequest,
    ): ResponseEntity<ExactMutationResponse> {
        requireNotNull(request.organisationId)
        entityExecutions.incrementAndGet()
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .header("X-Domain-Reference", "ref-1")
            .body(ExactMutationResponse("entity"))
    }

    @PostMapping("/no-content")
    @PreAuthorize("hasAuthority('test.write')")
    @IdempotentMutation(scope = IdempotencyScopeKind.ORGANISATION_SELECTION)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun noContent(
        @Valid @RequestBody request: ExactMutationRequest,
    ) {
        requireNotNull(request.organisationId)
        noContentExecutions.incrementAndGet()
    }

    @ComposedMutation
    fun composed(
        @Valid @RequestBody request: ExactMutationRequest,
    ): ExactMutationResponse {
        requireNotNull(request.organisationId)
        composedExecutions.incrementAndGet()
        return ExactMutationResponse("composed")
    }

    override fun inherited(request: ExactMutationRequest): ExactMutationResponse {
        requireNotNull(request.organisationId)
        inheritedExecutions.incrementAndGet()
        return ExactMutationResponse("inherited")
    }

    companion object {
        val dtoExecutions = AtomicInteger()
        val entityExecutions = AtomicInteger()
        val noContentExecutions = AtomicInteger()
        val composedExecutions = AtomicInteger()
        val inheritedExecutions = AtomicInteger()

        fun reset() {
            dtoExecutions.set(0)
            entityExecutions.set(0)
            noContentExecutions.set(0)
            composedExecutions.set(0)
            inheritedExecutions.set(0)
        }
    }
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@SpringRequestMapping(method = [RequestMethod.POST], path = ["/composed"])
@IdempotentMutation(scope = IdempotencyScopeKind.ORGANISATION_SELECTION)
private annotation class ComposedMutation

interface InheritedExactMutation {
    @SpringRequestMapping(method = [RequestMethod.POST], path = ["/inherited"])
    @IdempotentMutation(scope = IdempotencyScopeKind.ORGANISATION_SELECTION)
    fun inherited(
        @Valid @RequestBody request: ExactMutationRequest,
    ): ExactMutationResponse
}

@Aspect
class UnorderedAuditFixtureAspect {
    @Around(
        "execution(* com.finaxis.platform.common.web.idempotency." +
            "ExactResponseTestController.dto(..))",
    )
    fun around(joinPoint: ProceedingJoinPoint): Any? {
        executions.incrementAndGet()
        return joinPoint.proceed()
    }

    companion object {
        val executions = AtomicInteger()
    }
}
