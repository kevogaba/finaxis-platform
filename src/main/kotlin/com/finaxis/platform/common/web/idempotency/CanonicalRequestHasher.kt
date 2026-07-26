package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.common.web.api.ApiJsonCodec
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Creates stable request fingerprints without persisting raw request bodies. */
@Component
class CanonicalRequestHasher(
    private val apiJsonCodec: ApiJsonCodec,
    private val properties: IdempotencyProperties,
) {
    /** Hashes authenticated actor, method, normalized path, sorted query, and canonical JSON. */
    fun fingerprint(request: HttpServletRequest): IdempotencyRequestFingerprint {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?: error("Authenticated actor is required for idempotent mutations")
        val actorIdentity =
            (
                (authentication.principal as? IdempotencyActorPrincipal)?.idempotencySubject
                    ?: authentication.name
            ).takeIf(String::isNotBlank)
                ?: error("Authenticated actor is required for idempotent mutations")
        val method = request.method.uppercase()
        val normalizedPath = normalizePath(request.requestURI)
        val canonical =
            listOf(
                actorIdentity,
                method,
                normalizedPath,
                canonicalQuery(request.queryString),
                canonicalBody(request),
            ).joinToString("\n")
        return IdempotencyRequestFingerprint(
            actorFingerprint = sha256(actorIdentity),
            method = method,
            normalizedPath = normalizedPath,
            requestHash = sha256(canonical),
        )
    }

    private fun canonicalBody(request: HttpServletRequest): String {
        val wrapper =
            request as? BoundedContentCachingRequestWrapper
                ?: error("Mutation request was not prepared for idempotency")
        val content = wrapper.contentAsByteArray
        check(content.size <= properties.maxRequestBodyBytes)
        if (content.isEmpty()) return ""
        val node = requireNotNull(apiJsonCodec.mapper.readTree(content))
        return canonicalJson(node)
    }

    private fun canonicalJson(node: tools.jackson.databind.JsonNode): String =
        when {
            node.isObject -> {
                node
                    .properties()
                    .asSequence()
                    .sortedBy(Map.Entry<String, tools.jackson.databind.JsonNode>::key)
                    .joinToString(prefix = "{", postfix = "}") { (name, value) ->
                        "${apiJsonCodec.mapper.writeValueAsString(name)}:${canonicalJson(value)}"
                    }
            }

            node.isArray -> {
                node.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
            }

            else -> {
                node.toString()
            }
        }

    private fun canonicalQuery(query: String?): String {
        if (query == null) return ""
        val pairs =
            try {
                query
                    .split('&')
                    .map { pair ->
                        val parts = pair.split('=', limit = 2)
                        decode(parts[0]) to decode(parts.getOrElse(1) { "" })
                    }.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            } catch (_: IllegalArgumentException) {
                throw InvalidRequestException(
                    code = "INVALID_QUERY_ENCODING",
                    safeDetail = "The query string contains invalid percent encoding.",
                )
            }
        return pairs.joinToString(prefix = "[", postfix = "]") { (name, value) ->
            val encodedName = apiJsonCodec.mapper.writeValueAsString(name)
            val encodedValue = apiJsonCodec.mapper.writeValueAsString(value)
            "[$encodedName,$encodedValue]"
        }
    }

    private fun normalizePath(path: String): String {
        val normalized = path.replace(REPEATED_SLASHES, "/")
        return normalized.removeSuffix("/").ifEmpty { "/" }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val REPEATED_SLASHES = Regex("/{2,}")
    }
}
