package com.finaxis.platform.iam.adapter.inbound.security

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadataClaimNames.AUTHORIZATION_SERVERS
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadataClaimNames.BEARER_METHODS_SUPPORTED
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadataClaimNames.RESOURCE
import java.net.URI
import java.net.URISyntaxException
import java.util.function.Consumer

/**
 * The application's own identity as an OAuth 2.0 protected resource (RFC 9728, #253).
 *
 * `resource` is the canonical origin clients call, such as `https://api.finaxis.example`. It is
 * bound as written and checked by [ProtectedResourceMetadataCustomizer.from], outside binding, so
 * a refused value (which may carry user info) is never printed by Boot's bind-failure report.
 * Blank or unset means "not configured" (see [ProtectedResourceMetadataCustomizer]).
 */
@ConfigurationProperties(prefix = "finaxis.security.protected-resource")
data class ProtectedResourceProperties(
    val resource: String? = null,
)

/**
 * Fills the metadata document Spring Security serves, unauthenticated, at
 * `GET /.well-known/oauth-protected-resource` (#253).
 *
 * Spring's filter prefills the builder with a `resource` derived from the request (its scheme,
 * host and port, believed from a listed trusted proxy only since #256, plus the path after the
 * well-known segment) and `tls_client_certificate_bound_access_tokens: true`, which this
 * application does not honour. This customizer discards all of it and sets exactly: `resource`,
 * [authorizationServers] (the JWT decoder's issuer, omitted when none is configured) and
 * `bearer_methods_supported: ["header"]` (the only place the token resolver reads a token from).
 * No `scopes_supported`: authorization is by application permission codes, not OAuth scopes.
 *
 * `resource` must be identical to the identifier the client derived the metadata URL from (RFC
 * 9728 section 3.3): the root document names the origin, and
 * `/.well-known/oauth-protected-resource/api/v1` names `<origin>/api/v1`. With [resource]
 * configured, only the path is kept from Spring's value and scheme, host and port are always the
 * configured ones; unset, Spring's value is used unchanged.
 */
class ProtectedResourceMetadataCustomizer(
    val resource: String?,
    val authorizationServers: List<String>,
) : Consumer<OAuth2ProtectedResourceMetadata.Builder> {
    init {
        // Spring validates the URLs only when a request builds the document: do it once here, so
        // a bad issuer fails startup instead of every request.
        OAuth2ProtectedResourceMetadata
            .builder()
            .resource(STARTUP_CHECK_RESOURCE)
            .also(::accept)
            .build()
    }

    override fun accept(builder: OAuth2ProtectedResourceMetadata.Builder) {
        builder.claims { claims ->
            val requestDerived = claims[RESOURCE]
            claims.clear()
            (resource?.let { it + pathOf(requestDerived) } ?: requestDerived)
                ?.let { claims[RESOURCE] = it }
            if (authorizationServers.isNotEmpty()) {
                claims[AUTHORIZATION_SERVERS] = authorizationServers
            }
            claims[BEARER_METHODS_SUPPORTED] = listOf(HEADER_BEARER_METHOD)
        }
    }

    /** Builds the customizer from the application's configuration. */
    companion object {
        /** The fixed reason a configured `resource` is refused; it never repeats the value. */
        const val INVALID_RESOURCE =
            "finaxis.security.protected-resource.resource (FINAXIS_PROTECTED_RESOURCE_URL) must " +
                "be an http or https origin with no path, query, fragment or user info, such as " +
                "https://api.finaxis.example"

        /** Logged once at startup when no `resource` is configured. */
        const val RESOURCE_NOT_CONFIGURED =
            "finaxis.security.protected-resource.resource (FINAXIS_PROTECTED_RESOURCE_URL) is " +
                "not set: the protected-resource metadata names the origin each request arrived " +
                "with (as believed through the trusted-proxy list) instead of the canonical one. " +
                "Set it to the public https origin."

        private const val HEADER_BEARER_METHOD = "header"
        private const val STARTUP_CHECK_RESOURCE = "https://startup-check.invalid"
        private val SCHEMES = setOf("http", "https")
        private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
        private val log = LoggerFactory.getLogger(ProtectedResourceMetadataCustomizer::class.java)

        /**
         * The configured resource, canonicalised (or `null`, warned about once, when blank or
         * unset), and the issuer the JWT decoder is configured with. A configured resource that
         * is not a bare origin fails with [INVALID_RESOURCE].
         */
        fun from(
            properties: ProtectedResourceProperties,
            resourceServer: OAuth2ResourceServerProperties,
        ): ProtectedResourceMetadataCustomizer {
            val resource = properties.resource?.trim()?.takeIf(String::isNotEmpty)
            if (resource == null) {
                log.warn(RESOURCE_NOT_CONFIGURED)
            }
            return ProtectedResourceMetadataCustomizer(
                resource?.let(::canonicalOrigin),
                listOfNotNull(
                    resourceServer.jwt.issuerUri
                        ?.trim()
                        ?.takeIf(String::isNotEmpty),
                ),
            )
        }

        /**
         * The raw path of Spring's request-derived identifier (`""` for the root document), so it
         * can follow the configured origin. A value that does not parse as a URI with a host, or
         * whose path is not already normalised (dot segments) or carries a query or fragment,
         * gives `""`: the document then names the origin, which a client that asked for that path
         * rejects as RFC 9728 requires.
         */
        fun pathOf(requestDerived: Any?): String {
            val uri =
                try {
                    (requestDerived as? String)?.let { URI(it) }
                } catch (_: URISyntaxException) {
                    null
                }
            val path = uri?.takeIf(::hasCanonicalPath)?.rawPath.orEmpty()
            return if (path == "/") "" else path
        }

        /** A URI with a host, no query or fragment, and an absolute, already normalised path. */
        private fun hasCanonicalPath(uri: URI): Boolean =
            !uri.host.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath.orEmpty().startsWith("/") &&
                uri.normalize().rawPath == uri.rawPath

        /**
         * The origin lower-cased, without a trailing slash and without the scheme's default
         * port, so it reads exactly as the `401` challenge's URL (which omits a default port).
         */
        fun canonicalOrigin(value: String): String {
            val uri =
                try {
                    URI(value)
                } catch (_: URISyntaxException) {
                    // Not chained: its message repeats the refused value, which may hold a secret.
                    null
                }
            require(uri != null && isBareOrigin(uri)) { INVALID_RESOURCE }
            val scheme = uri.scheme.lowercase()
            val port = uri.port.takeUnless { it == -1 || it == DEFAULT_PORTS[scheme] }
            return "$scheme://${uri.host.lowercase()}${port?.let { ":$it" }.orEmpty()}"
        }

        /** An http(s) URL with a host and nothing but an optional `/` after its authority. */
        private fun isBareOrigin(uri: URI): Boolean =
            uri.scheme?.lowercase() in SCHEMES &&
                !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath.orEmpty() in setOf("", "/")
    }
}
