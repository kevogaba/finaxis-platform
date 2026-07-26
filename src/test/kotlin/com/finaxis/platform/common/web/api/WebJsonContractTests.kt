package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Verifies the shared MVC JSON contract used by every REST adapter. */
@WebMvcTest(
    controllers = [WebJsonContractTests.ContractController::class],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc(addFilters = false)
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    WebJsonContractTests.ContractController::class,
    WebJsonContractTests.InternalMapperConfiguration::class,
)
class WebJsonContractTests
    @Autowired
    constructor(
        private val apiJsonCodec: ApiJsonCodec,
        private val internalObjectMapper: com.fasterxml.jackson.databind.ObjectMapper,
        private val mockMvc: MockMvc,
    ) {
        @Test
        fun `MVC uses the snake case API mapper while internal events retain camel case`() {
            mockMvc
                .get("/contract")
                .andExpect {
                    jsonPath("$.tenant_code") { value("ACME") }
                    jsonPath("$.occurred_at") { value("2026-07-18T23:59:58+03:00") }
                }

            val apiJson = apiJsonCodec.mapper.writeValueAsString(contractPayload())
            val internalJson = internalObjectMapper.writeValueAsString(externalizedEvent())

            assertEquals(true, apiJson.contains("\"tenant_code\""))
            assertEquals(true, internalJson.contains("\"aggregateType\""))
            assertEquals(true, internalJson.contains("\"occurredAt\""))
            assertEquals(false, internalJson.contains("\"aggregate_type\""))
            assertEquals(false, internalJson.contains("\"occurred_at\""))
        }

        @Test
        fun `serializes web values with snake case and approved time formats`() {
            val json =
                apiJsonCodec.mapper.writeValueAsString(
                    contractPayload(),
                )

            assertEquals(
                listOf(
                    "\"tenant_code\":\"ACME\"",
                    "\"business_date\":\"18-07-2026\"",
                    "\"cutoff_time\":\"23:59:58\"",
                    "\"occurred_at\":\"2026-07-18T23:59:58+03:00\"",
                ).joinToString(separator = ",", prefix = "{", postfix = "}"),
                json,
            )
        }

        @Test
        fun `deserializes only snake case and approved date format`() {
            val payload =
                apiJsonCodec.mapper.readValue(
                    validPayloadJson(),
                    ContractPayload::class.java,
                )

            assertEquals("ACME", payload.tenantCode)
            assertEquals(LocalDate.of(2026, 7, 18), payload.businessDate)
            assertEquals(LocalTime.of(23, 59, 58), payload.cutoffTime)
            assertEquals(OffsetDateTime.parse("2026-07-18T23:59:58+03:00"), payload.occurredAt)
        }

        @Test
        fun `rejects camel case and ISO local date input`() {
            assertFailsWith<JacksonException> {
                apiJsonCodec.mapper.readValue(
                    """{"tenantCode":"ACME"}""",
                    ContractPayload::class.java,
                )
            }
            assertFailsWith<JacksonException> {
                apiJsonCodec.mapper.readValue(
                    """
                    {
                      "tenant_code": "ACME",
                      "business_date": "2026-07-18",
                      "cutoff_time": "23:59:58",
                      "occurred_at": "2026-07-18T23:59:58+03:00"
                    }
                    """.trimIndent(),
                    ContractPayload::class.java,
                )
            }
        }

        @Test
        fun `converts pageable results and rejects invalid page bounds`() {
            val page = apiPageOf(items = listOf("one", "two"), number = 1, size = 2, totalItems = 5)
            val extremePage =
                apiPageOf(
                    items = emptyList<String>(),
                    number = Int.MAX_VALUE,
                    size = 100,
                    totalItems = 1,
                )

            assertEquals(
                ApiPage(
                    listOf("one", "two"),
                    ApiPageMetadata(1, 2, 5, 3, true, true),
                ),
                page,
            )
            assertFalse(extremePage.page.hasNext)
            assertFailsWith<InvalidPageRequestException> {
                apiPageOf(
                    items = emptyList<String>(),
                    number = -1,
                    size = 1,
                    totalItems = 0,
                )
            }
            assertFailsWith<InvalidPageRequestException> {
                apiPageOf(
                    items = emptyList<String>(),
                    number = 0,
                    size = 101,
                    totalItems = 0,
                )
            }
        }

        data class ContractPayload(
            val tenantCode: String,
            val businessDate: LocalDate,
            val cutoffTime: LocalTime,
            val occurredAt: OffsetDateTime,
        )

        @RestController
        class ContractController {
            @GetMapping("/contract")
            fun contract(): ContractPayload =
                ContractPayload(
                    tenantCode = "ACME",
                    businessDate = LocalDate.of(2026, 7, 18),
                    cutoffTime = LocalTime.of(23, 59, 58),
                    occurredAt = OffsetDateTime.parse("2026-07-18T23:59:58+03:00"),
                )
        }

        private fun externalizedEvent(): ExternalizedTransitionEvent =
            ExternalizedTransitionEvent(
                target = "finaxis.lifecycle.membership.activated",
                aggregateType = "MEMBERSHIP",
                aggregateId = "membership-1",
                transition = "ACTIVATE",
                fromState = "PENDING_APPROVAL",
                toState = "ACTIVE",
                actor = TransitionActor("USER", "user-1", "Example User"),
                occurredAt = OffsetDateTime.parse("2026-07-18T23:59:58+03:00").toInstant(),
            )

        private fun contractPayload(): ContractPayload =
            ContractPayload(
                tenantCode = "ACME",
                businessDate = LocalDate.of(2026, 7, 18),
                cutoffTime = LocalTime.of(23, 59, 58),
                occurredAt = OffsetDateTime.parse("2026-07-18T23:59:58+03:00"),
            )

        private fun validPayloadJson(): String =
            """
            {
              "tenant_code": "ACME",
              "business_date": "18-07-2026",
              "cutoff_time": "23:59:58",
              "occurred_at": "2026-07-18T23:59:58+03:00"
            }
            """.trimIndent()

        @TestConfiguration(proxyBeanMethods = false)
        class InternalMapperConfiguration {
            @Bean
            fun internalObjectMapper(): com.fasterxml.jackson.databind.ObjectMapper =
                com.fasterxml.jackson.databind
                    .ObjectMapper()
                    .findAndRegisterModules()
        }
    }
