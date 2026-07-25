package com.finaxis.platform.common.web.idempotency

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Validated successful response representation that is safe for durable replay persistence. */
sealed interface SafeReplayResponse {
    val status: Int
    val headers: Map<String, String>
    val storedBody: String?

    /** Converts validated replay data back to the live response path. */
    fun toLive(): IdempotencyResponse
}

private class ValidatedSafeReplayResponse(
    override val status: Int,
    override val headers: Map<String, String>,
    override val storedBody: String?,
    private val hasBody: Boolean,
) : SafeReplayResponse {
    override fun toLive(): IdempotencyResponse =
        IdempotencyResponse(
            status = status,
            headers = headers,
            body = storedBody.takeIf { hasBody },
        )
}

/** Builds safe replay values from unrestricted live responses after strict validation. */
@Component
class SafeReplayResponseFactory(
    private val objectMapper: ObjectMapper,
    properties: IdempotencyProperties,
) {
    private val maxResponseBodyBytes = properties.maxResponseBodyBytes
    private val allowedHeaders =
        (STANDARD_SAFE_HEADERS + properties.safeDomainHeaders)
            .associateBy { it.lowercase(Locale.ROOT) }

    /** Validates one live successful response for durable persistence and exact replay. */
    fun fromLive(response: IdempotencyResponse): SafeReplayResponse {
        require(response.status in SUCCESS_STATUS_MINIMUM..SUCCESS_STATUS_MAXIMUM) {
            "Only successful responses can be replayed"
        }
        val body = response.body
        if (response.status == NO_CONTENT_STATUS) {
            require(body.isNullOrEmpty()) {
                "204 responses cannot contain a replay body"
            }
            return ValidatedSafeReplayResponse(
                response.status,
                sanitizeHeaders(response.headers),
                storedBody = null,
                hasBody = false,
            )
        }
        if (body.isNullOrEmpty()) {
            return ValidatedSafeReplayResponse(
                response.status,
                sanitizeHeaders(response.headers),
                storedBody = "",
                hasBody = false,
            )
        }
        require(body.toByteArray(StandardCharsets.UTF_8).size <= maxResponseBodyBytes) {
            "Replay response body exceeds the configured maximum size"
        }
        val parsed = parseJson(body)
        rejectProhibitedFields(parsed)
        return ValidatedSafeReplayResponse(
            response.status,
            sanitizeHeaders(response.headers),
            storedBody = body,
            hasBody = true,
        )
    }

    /** Revalidates a stored response before exposing it to the replay path. */
    internal fun fromStored(
        status: Int,
        headers: Map<String, String>,
        storedBody: String?,
    ): SafeReplayResponse {
        require((status == NO_CONTENT_STATUS) == (storedBody == null)) {
            "Stored replay body does not match its response status"
        }
        return fromLive(IdempotencyResponse(status, headers, storedBody))
    }

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> =
        headers
            .mapNotNull { (name, value) ->
                allowedHeaders[name.lowercase(Locale.ROOT)]?.let { canonicalName ->
                    canonicalName to value
                }
            }.toMap()

    private fun parseJson(body: String): JsonNode =
        try {
            requireNotNull(objectMapper.readTree(body)) {
                "Replay response body must be valid JSON"
            }
        } catch (failure: JsonProcessingException) {
            throw IllegalArgumentException("Replay response body must be valid JSON", failure)
        }

    private fun rejectProhibitedFields(node: JsonNode) {
        when {
            node.isObject -> {
                node.properties().forEach { (fieldName, value) ->
                    require(!containsProhibitedFieldName(fieldName)) {
                        "Replay response body contains a prohibited field"
                    }
                    rejectProhibitedFields(value)
                }
            }

            node.isArray -> {
                node.forEach(::rejectProhibitedFields)
            }
        }
    }

    private fun containsProhibitedFieldName(fieldName: String): Boolean {
        val tokens = fieldNameTokens(fieldName)
        return tokens.any(::isSensitiveToken) ||
            PROHIBITED_TOKEN_SEQUENCES.any { sequence ->
                tokens.windowed(sequence.size).any { it == sequence }
            }
    }

    private fun fieldNameTokens(fieldName: String): List<String> =
        fieldName
            .split(NON_ALPHANUMERIC)
            .filter(String::isNotBlank)
            .flatMap { segment ->
                listOf(segment) +
                    segment
                        .replace(CAMEL_CASE_BOUNDARY, "$1 $2")
                        .replace(ACRONYM_BOUNDARY, "$1 $2")
                        .split(' ')
            }.distinct()
            .map { it.lowercase(Locale.ROOT) }

    private fun isSensitiveToken(token: String): Boolean =
        SENSITIVE_TOKENS.any { sensitiveToken ->
            token == sensitiveToken || token == "${sensitiveToken}s"
        }

    private companion object {
        const val SUCCESS_STATUS_MINIMUM: Int = 200
        const val SUCCESS_STATUS_MAXIMUM: Int = 299
        const val NO_CONTENT_STATUS: Int = 204
        val STANDARD_SAFE_HEADERS: Set<String> = setOf("Location", "ETag")

        val CAMEL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        val ACRONYM_BOUNDARY = Regex("([A-Z]+)([A-Z][a-z])")
        val NON_ALPHANUMERIC = Regex("[^A-Za-z0-9]+")
        val SENSITIVE_TOKENS: Set<String> =
            setOf(
                "authorization",
                "auth",
                "cookie",
                "token",
                "password",
                "secret",
                "credential",
                "otp",
                "pin",
                "key",
                "session",
                "sessionid",
            )
        val PROHIBITED_TOKEN_SEQUENCES: Set<List<String>> =
            setOf(
                listOf("recovery", "code"),
                listOf("active", "org", "context"),
                listOf("active", "organisation", "context"),
            )
    }
}
