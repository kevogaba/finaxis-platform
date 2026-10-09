package com.finaxis.platform.iam.adapter.inbound.security

import org.apache.catalina.valves.RemoteIpValve
import org.slf4j.LoggerFactory
import org.springframework.boot.tomcat.TomcatWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/**
 * Makes Tomcat's `RemoteIpValve` believe forwarded headers only from the reverse proxies listed in
 * `finaxis.security.client-ip.trusted-proxies` (#256), the same list [ClientIpResolver] uses.
 *
 * `server.forward-headers-strategy: native` installs the valve with Tomcat's default internal
 * proxies, which trust every private range. This customizer runs after Spring Boot's and replaces
 * that with [TrustedProxyPattern] built from the list, clears any other trusted-proxy setting and
 * pins the four header names, so no `server.tomcat.remoteip.*` setting widens or redirects it
 * (`protocol-header-https-value` aside). With the default empty list the pattern matches nothing:
 * no forwarded header is believed, `getRemoteAddr()` is the peer and scheme, host and port are
 * what the connector saw. From a listed peer the valve honours `X-Forwarded-For`, `-Proto`,
 * `-Host` and `-Port`; it never reads the RFC 7239 `Forwarded` header or `X-Forwarded-Prefix`.
 *
 * Under the `production` profile an empty list is logged once as a `WARN` when the server is
 * built: behind a TLS-terminating proxy the client address, scheme, host and port are then the
 * proxy connection's, so HSTS is not sent (the request is not secure) and a same-origin browser
 * request is judged cross-origin by CORS. A direct deployment stays legal, hence no failure.
 *
 * A pattern, not Tomcat's CIDR form: the valve matches a CIDR list by passing each header entry to
 * `InetAddress.getByName`, a DNS lookup of client-supplied text. A regular expression never
 * resolves anything.
 *
 * Every strategy but `native` is refused at startup, unset included: `framework` installs Spring's
 * `ForwardedHeaderFilter`, which has no trusted-proxy list and believes those headers from any
 * peer; `none` (and unset, outside a detected cloud platform) installs no valve, so the list would
 * govern only the audit address and not `getRemoteAddr()`, scheme, host, port, HSTS or the
 * anonymous rate-limit key. That check runs whenever the Tomcat factory is customized (a MOCK test
 * context creates the factory bean too, so it also notices). As a second guard, building the
 * server fails when no `RemoteIpValve` is on the engine after Spring Boot's customizer ran; that
 * one runs only when an embedded Tomcat is actually built, which every deployment does.
 */
@Component
class TrustedProxyValveCustomizer(
    private val clientIpProperties: ClientIpProperties,
    private val serverProperties: ServerProperties,
    private val environment: Environment,
) : WebServerFactoryCustomizer<TomcatWebServerFactory>,
    Ordered {
    override fun customize(factory: TomcatWebServerFactory) {
        val strategy = serverProperties.forwardHeadersStrategy
        check(strategy == ServerProperties.ForwardHeadersStrategy.NATIVE) {
            "server.forward-headers-strategy must be native, was ${strategy ?: "unset"}: " +
                "only native applies finaxis.security.client-ip.trusted-proxies to forwarded " +
                "headers (framework believes them from any peer, none ignores the list)"
        }
        // Checked when the server is built, not here: a MOCK test context also creates and
        // customizes the factory bean, without the valve, but never starts a server from it.
        factory.addContextCustomizers({ _ ->
            check(factory.engineValves.any { it is RemoteIpValve }) {
                "server.forward-headers-strategy is native but Tomcat has no RemoteIpValve, so " +
                    "finaxis.security.client-ip.trusted-proxies would not govern forwarded headers"
            }
        })
        if (clientIpProperties.trustedRanges.isEmpty() &&
            environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))
        ) {
            log.warn(EMPTY_LIST_IN_PRODUCTION)
        }
        val pattern = TrustedProxyPattern.of(clientIpProperties.trustedRanges)
        factory.engineValves.filterIsInstance<RemoteIpValve>().forEach { valve ->
            valve.setInternalProxies(pattern)
            valve.setTrustedProxies(null)
            valve.remoteIpHeader = X_FORWARDED_FOR
            valve.protocolHeader = X_FORWARDED_PROTO
            valve.hostHeader = X_FORWARDED_HOST
            valve.portHeader = X_FORWARDED_PORT
        }
    }

    /** After Spring Boot's own Tomcat customizer, which adds the valve. */
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    /** Header names and the production warning. */
    companion object {
        /** Logged once when the production profile builds the server with no trusted proxy. */
        const val EMPTY_LIST_IN_PRODUCTION =
            "finaxis.security.client-ip.trusted-proxies is empty under the production profile: " +
                "no forwarded header is believed, so behind a reverse proxy the client " +
                "address, scheme, host, port and HSTS are those of the proxy connection, " +
                "not the client's; " +
                "set FINAXIS_CLIENT_IP_TRUSTED_PROXIES to the pinned Traefik address"
        private const val PRODUCTION_PROFILE = "production"
        private val log = LoggerFactory.getLogger(TrustedProxyValveCustomizer::class.java)
        private const val X_FORWARDED_FOR = "X-Forwarded-For"
        private const val X_FORWARDED_PROTO = "X-Forwarded-Proto"
        private const val X_FORWARDED_HOST = "X-Forwarded-Host"
        private const val X_FORWARDED_PORT = "X-Forwarded-Port"
    }
}

/**
 * The `RemoteIpValve` internal-proxies regular expression for a list of [IpRange]s: it matches
 * every textual form of every address inside them and nothing else.
 *
 * - IPv4: the dotted quad without leading zeros (Tomcat's own form of a peer, and a proxy's).
 * - IPv6: the full form Tomcat gives a peer (`2001:db8:0:0:0:0:0:1`) and every `::`-compressed
 *   form a proxy may write, any case, leading zeros allowed, an optional `%zone` (Tomcat writes
 *   one on a link-local peer; inside `X-Forwarded-For` the audit resolver alone would stop at
 *   such an entry, but its address is listed either way).
 * - Never matched, so never trusted: a port, brackets, an embedded IPv4 tail (`::ffff:a.b.c.d`)
 *   or any other text. The valve then stops at that entry; it never trusts more than the list.
 */
internal object TrustedProxyPattern {
    /** Matches no string at all: the pattern of an empty list. */
    const val NOTHING = "(?!)"

    private const val IPV4_BYTES = 4
    private const val BITS_PER_OCTET = 8
    private const val OCTET_MASK = 0xFF
    private const val HEXTETS = 8
    private const val NIBBLES_PER_HEXTET = 4
    private const val BITS_PER_NIBBLE = 4
    private const val NIBBLE_VALUES = 16
    private const val ANY_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
    private const val ANY_HEXTET = "[0-9a-fA-F]{1,4}"
    private const val ZONE = "(?:%[0-9A-Za-z_.~-]+)?"

    /** The alternation of every range's pattern, or [NOTHING] for an empty list. */
    fun of(ranges: List<IpRange>): String =
        if (ranges.isEmpty()) {
            NOTHING
        } else {
            ranges.joinToString("|") { range ->
                if (range.network.size == IPV4_BYTES) ipv4(range) else ipv6(range)
            }
        }

    private fun ipv4(range: IpRange): String =
        (0 until IPV4_BYTES).joinToString("""\.""", "(?:", ")") { octet ->
            val fixedBits =
                (range.prefixLength - octet * BITS_PER_OCTET).coerceIn(0, BITS_PER_OCTET)
            if (fixedBits == 0) {
                ANY_OCTET
            } else {
                val freeBits = BITS_PER_OCTET - fixedBits
                val value = range.network[octet].toInt() and OCTET_MASK
                val low = value shr freeBits shl freeBits
                (low until low + (1 shl freeBits)).joinToString("|", "(?:", ")")
            }
        }

    private fun ipv6(range: IpRange): String {
        val hextets = (0 until HEXTETS).map { Hextet.of(range, it) }
        val forms =
            buildList {
                add(hextets.joinToString(":") { it.pattern })
                for (head in 0 until HEXTETS) {
                    for (tail in 0 until HEXTETS - head) {
                        val elided = hextets.subList(head, HEXTETS - tail)
                        if (elided.all(Hextet::mayBeZero)) {
                            add(
                                hextets.take(head).joinToString(":") { it.pattern } + "::" +
                                    hextets.takeLast(tail).joinToString(":") { it.pattern },
                            )
                        }
                    }
                }
            }
        return forms.joinToString("|", "(?:(?:", ")$ZONE)")
    }

    /** One group of an IPv6 range: the hex digits each of its four positions may hold. */
    private class Hextet(
        private val digits: List<Set<Int>>,
    ) {
        /** Whether the group may be zero, so `::` may stand for it. */
        val mayBeZero: Boolean = digits.all { 0 in it }

        /** The group in 1 to 4 digits: leading digits that may be zero may be left out. */
        val pattern: String =
            if (digits.all { it.size == NIBBLE_VALUES }) {
                ANY_HEXTET
            } else {
                (0 until NIBBLES_PER_HEXTET)
                    .takeWhile { dropped -> digits.take(dropped).all { 0 in it } }
                    .joinToString("|", "(?:", ")") { dropped ->
                        digits.drop(dropped).joinToString("", transform = ::characterClass)
                    }
            }

        companion object {
            private const val LOW_NIBBLE = 0x0F

            fun of(
                range: IpRange,
                hextet: Int,
            ): Hextet =
                Hextet(
                    (0 until NIBBLES_PER_HEXTET).map { position ->
                        val nibble = hextet * NIBBLES_PER_HEXTET + position
                        val fixedBits =
                            (range.prefixLength - nibble * BITS_PER_NIBBLE)
                                .coerceIn(0, BITS_PER_NIBBLE)
                        val freeBits = BITS_PER_NIBBLE - fixedBits
                        val value = nibbleAt(range.network, nibble) shr freeBits
                        (0 until NIBBLE_VALUES).filter { it shr freeBits == value }.toSet()
                    },
                )

            private fun nibbleAt(
                bytes: ByteArray,
                nibble: Int,
            ): Int {
                val byte = bytes[nibble / 2].toInt() and OCTET_MASK
                return if (nibble % 2 == 0) byte shr BITS_PER_NIBBLE else byte and LOW_NIBBLE
            }

            private fun characterClass(values: Set<Int>): String =
                if (values.size == NIBBLE_VALUES) {
                    "[0-9a-fA-F]"
                } else {
                    values.joinToString("", "[", "]") { value ->
                        val digit = value.toString(NIBBLE_VALUES)
                        if (digit == digit.uppercase()) digit else digit + digit.uppercase()
                    }
                }
        }
    }
}
