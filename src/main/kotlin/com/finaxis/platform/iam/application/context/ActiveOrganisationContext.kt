package com.finaxis.platform.iam.application.context

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val DEFAULT_CONTEXT_TTL_HOURS = 8L

/**
 * Configuration used to sign short-lived application context tokens.
 *
 * The token is not an authentication credential. It only carries the selected
 * application tenant context for clients that cannot use browser sessions.
 */
@ConfigurationProperties(prefix = "finaxis.iam.active-organisation-context")
data class ActiveOrganisationContextProperties(
    val secret: String,
    val ttl: Duration = Duration.ofHours(DEFAULT_CONTEXT_TTL_HOURS),
)

/**
 * Selected application tenant context for the authenticated Keycloak subject.
 *
 * `branchId` is nullable because users first select an organisation. If exactly
 * one branch is assigned it is auto-selected; otherwise clients must explicitly
 * select a branch before branch-scoped workflows can proceed.
 */
data class ActiveOrganisationContext(
    val userId: UUID,
    val organisationId: UUID,
    val membershipId: UUID,
    val branchId: UUID? = null,
) : Serializable {
    /**
     * Java serialization metadata for Redis-backed HTTP session storage.
     */
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Issues and verifies signed active-organisation context tokens.
 */
@Service
class ActiveOrganisationContextService(
    private val properties: ActiveOrganisationContextProperties,
    private val clock: Clock,
) {
    /**
     * Issues a signed context token for the selected organisation and optional branch.
     */
    fun issue(context: ActiveOrganisationContext): String {
        val expiresAt = clock.instant().plus(properties.ttl).epochSecond
        val payload =
            listOf(
                context.userId,
                context.organisationId,
                context.membershipId,
                context.branchId ?: NO_BRANCH,
                expiresAt,
            ).joinToString(".")
        val encodedPayload = encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = sign(encodedPayload)
        return "$encodedPayload.$signature"
    }

    /**
     * Verifies a signed context token and returns null when it is invalid or expired.
     */
    fun verify(token: String): ActiveOrganisationContext? =
        runCatching {
            val parts = token.split(".")
            require(parts.size == TOKEN_PARTS)
            require(constantTimeEquals(sign(parts[0]), parts[1]))

            val payload = String(decoder.decode(parts[0]), StandardCharsets.UTF_8).split(".")
            require(payload.size == EXPECTED_PAYLOAD_PARTS)

            val expiresAt = payload[EXPIRES_AT_INDEX].toLong().let(Instant::ofEpochSecond)
            require(!clock.instant().isAfter(expiresAt))

            ActiveOrganisationContext(
                userId = UUID.fromString(payload[0]),
                organisationId = UUID.fromString(payload[1]),
                membershipId = UUID.fromString(payload[2]),
                branchId = payload[3].takeUnless { it == NO_BRANCH }?.let(UUID::fromString),
            )
        }.getOrNull()

    private fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun constantTimeEquals(
        expected: String,
        actual: String,
    ): Boolean =
        MessageDigestIsEqual.equals(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8),
        )

    /**
     * Public token constants used by inbound adapters.
     */
    companion object {
        const val HEADER = "X-Active-Organisation-Context"
        private const val TOKEN_PARTS = 2
        private const val EXPECTED_PAYLOAD_PARTS = 5
        private const val EXPIRES_AT_INDEX = 4
        private const val NO_BRANCH = "-"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }
}

/**
 * Small indirection around JDK constant-time byte-array comparison.
 */
private object MessageDigestIsEqual {
    fun equals(
        left: ByteArray,
        right: ByteArray,
    ): Boolean = java.security.MessageDigest.isEqual(left, right)
}
