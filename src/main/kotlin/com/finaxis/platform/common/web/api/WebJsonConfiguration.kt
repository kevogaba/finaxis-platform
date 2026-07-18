package com.finaxis.platform.common.web.api

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Configures the Jackson 3 mapper used by Spring MVC without changing persistence mappers. */
@Configuration(proxyBeanMethods = false)
class WebJsonConfiguration {
    /** Registers strict public date and time representations on the MVC mapper. */
    @Bean
    fun webJsonMapperCustomizer(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(
                    SimpleModule("finaxis-web-json")
                        .addSerializer(LocalDate::class.java, DateSerializer(ApiDateFormats.DATE))
                        .addDeserializer(
                            LocalDate::class.java,
                            DateDeserializer(ApiDateFormats.DATE),
                        ).addSerializer(LocalTime::class.java, TimeSerializer(ApiDateFormats.TIME))
                        .addDeserializer(
                            LocalTime::class.java,
                            TimeDeserializer(ApiDateFormats.TIME),
                        ).addSerializer(OffsetDateTime::class.java, OffsetDateTimeSerializer())
                        .addDeserializer(OffsetDateTime::class.java, OffsetDateTimeDeserializer()),
                )
        }

    private class DateSerializer(
        private val formatter: java.time.format.DateTimeFormatter,
    ) : ValueSerializer<LocalDate>() {
        override fun serialize(
            value: LocalDate,
            generator: JsonGenerator,
            context: SerializationContext,
        ) {
            generator.writeString(formatter.format(value))
        }
    }

    private class DateDeserializer(
        private val formatter: java.time.format.DateTimeFormatter,
    ) : ValueDeserializer<LocalDate>() {
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext,
        ): LocalDate = parseValue(parser, context, "dd-MM-yyyy") { LocalDate.parse(it, formatter) }
    }

    private class TimeSerializer(
        private val formatter: java.time.format.DateTimeFormatter,
    ) : ValueSerializer<LocalTime>() {
        override fun serialize(
            value: LocalTime,
            generator: JsonGenerator,
            context: SerializationContext,
        ) {
            generator.writeString(formatter.format(value))
        }
    }

    private class TimeDeserializer(
        private val formatter: java.time.format.DateTimeFormatter,
    ) : ValueDeserializer<LocalTime>() {
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext,
        ): LocalTime = parseValue(parser, context, "HH:mm:ss") { LocalTime.parse(it, formatter) }
    }

    private class OffsetDateTimeSerializer : ValueSerializer<OffsetDateTime>() {
        override fun serialize(
            value: OffsetDateTime,
            generator: JsonGenerator,
            context: SerializationContext,
        ) {
            generator.writeString(ApiDateFormats.OFFSET_DATE_TIME.format(value))
        }
    }

    private class OffsetDateTimeDeserializer : ValueDeserializer<OffsetDateTime>() {
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext,
        ): OffsetDateTime =
            parseValue(parser, context, "an ISO 8601 datetime with an explicit offset") {
                OffsetDateTime.parse(it, ApiDateFormats.OFFSET_DATE_TIME)
            }

        override fun getNullValue(context: DeserializationContext): OffsetDateTime? = null
    }
}

private fun <T> parseValue(
    parser: JsonParser,
    context: DeserializationContext,
    expectedFormat: String,
    parse: (String) -> T,
): T =
    try {
        parse(parser.valueAsString)
    } catch (exception: DateTimeParseException) {
        context.reportInputMismatch(Any::class.java, "Expected $expectedFormat")
    }
