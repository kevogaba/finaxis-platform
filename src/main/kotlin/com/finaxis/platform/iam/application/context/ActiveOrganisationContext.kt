package com.finaxis.platform.iam.application.context

import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service

/**
 * Configuration used to sign short-lived application context tokens.
 *
 * The token is not an authentication credential. It only carries the selected
 * application tenant context for clients that cannot use browser sessions.
 */
@ConfigurationProperties(prefix = "finaxis.iam.active-organisation-context")
data class ActiveOrganisationContextProperties(
    val secret: String,
    val ttl: Duration = Duration.ofHours(8),
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
) : Serializable

/**
 * Issues and verifies signed active-organisation context tokens.
 */
@Service
class ActiveOrganisationContextService(
    private val properties: ActiveOrganisationContextProperties,
    private val clock: Clock,
) {
    fun issue(context: ActiveOrganisationContext): String {
        val expiresAt = clock.instant().plus(properties.ttl).epochSecond
        val payload = listOf(
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

    fun verify(token: String): ActiveOrganisationContext? {
        val parts = token.split(".")
        if (parts.size != 2) {
            return null
        }
        if (!constantTimeEquals(sign(parts[0]), parts[1])) {
            return null
        }

        val payload = runCatching {
            String(decoder.decode(parts[0]), StandardCharsets.UTF_8).split(".")
        }.getOrNull() ?: return null
        if (payload.size != 5) {
            return null
        }

        val expiresAt = payload[4].toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
        if (clock.instant().isAfter(expiresAt)) {
            return null
        }

        return runCatching {
            ActiveOrganisationContext(
                userId = UUID.fromString(payload[0]),
                organisationId = UUID.fromString(payload[1]),
                membershipId = UUID.fromString(payload[2]),
                branchId = payload[3].takeUnless { it == NO_BRANCH }?.let(UUID::fromString),
            )
        }.getOrNull()
    }

    private fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        return MessageDigestIsEqual.equals(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8),
        )
    }

    companion object {
        const val HEADER = "X-Active-Organisation-Context"
        private const val NO_BRANCH = "-"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }
}

/**
 * Small indirection around JDK constant-time byte-array comparison.
 */
private object MessageDigestIsEqual {
    fun equals(left: ByteArray, right: ByteArray): Boolean {
        return java.security.MessageDigest.isEqual(left, right)
    }
}
