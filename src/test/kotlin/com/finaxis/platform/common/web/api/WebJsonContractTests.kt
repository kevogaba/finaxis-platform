package com.finaxis.platform.common.web.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies the shared MVC JSON contract used by every REST adapter. */
@WebMvcTest(useDefaultFilters = false)
@Import(WebJsonConfiguration::class, ApiProblemFactory::class)
class WebJsonContractTests
    @Autowired
    constructor(
        private val jsonMapper: ObjectMapper,
    ) {
        @Test
        fun `serializes web values with snake case and approved time formats`() {
            val json =
                jsonMapper.writeValueAsString(
                    ContractPayload(
                        tenantCode = "ACME",
                        businessDate = LocalDate.of(2026, 7, 18),
                        cutoffTime = LocalTime.of(23, 59, 58),
                        occurredAt = OffsetDateTime.parse("2026-07-18T23:59:58+03:00"),
                    ),
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
                jsonMapper.readValue(
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
                jsonMapper.readValue("""{"tenantCode":"ACME"}""", ContractPayload::class.java)
            }
            assertFailsWith<JacksonException> {
                jsonMapper.readValue(
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

            assertEquals(
                ApiPage(
                    listOf("one", "two"),
                    ApiPageMetadata(1, 2, 5, 3, true, true),
                ),
                page,
            )
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

        private data class ContractPayload(
            val tenantCode: String,
            val businessDate: LocalDate,
            val cutoffTime: LocalTime,
            val occurredAt: OffsetDateTime,
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
    }
