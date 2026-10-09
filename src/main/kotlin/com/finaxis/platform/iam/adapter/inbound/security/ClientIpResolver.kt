package com.finaxis.platform.iam.adapter.inbound.security

import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletRequestWrapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Resolves the client address recorded on audit rows (#185), believing `X-Forwarded-For` only
 * when the direct peer is a configured trusted proxy.
 *
 * - The peer is the **container's** remote address, read from the innermost request rather than
 *   through any wrapper, so no filter's view of the address is ever used.
 * - Under `server.forward-headers-strategy: native` (#256) Tomcat's `RemoteIpValve` has already
 *   applied the same trusted list ([TrustedProxyValveCustomizer]) before any filter runs: from a
 *   trusted peer it walked `X-Forwarded-For` from the right past the trusted hops, made the entry
 *   it stopped at the remote address and left only the entries to the left of it in the header.
 *   This resolver then continues that one walk with the same list, never a second, wider trust:
 *   a peer that is not trusted is the client and the header is ignored. Kept as defence in depth
 *   and for what the valve does not do: it parses strictly, normalises, caps the walk, and reads
 *   an entry with a port or brackets, where the valve stops and leaves that text as the address.
 *   An entry that is no address at all, written by a trusted hop as its own peer, is the remote
 *   address the valve leaves and resolves to `null`: the hop that forwarded it is no longer known.
 * - With no trusted proxy configured (the default), or a peer outside the list, the peer address
 *   is the client and every forwarded header is ignored.
 * - Behind a trusted peer `X-Forwarded-For` is walked lazily from the right (several header lines
 *   are one list, in order): the client is the first entry that is not itself a trusted proxy;
 *   if every entry is trusted, the left-most. Entries to the left of the client are never read,
 *   so a client cannot veto its own address with junk or padding at the left. Reaching a blank or
 *   malformed entry (not an IP literal, optionally with a port) during the walk, or more than
 *   [MAX_HOPS] entries, stops it and the trusted peer is the client: never an error. Only
 *   `X-Forwarded-For` is read, never `Forwarded`.
 * - A peer address with an IPv6 zone id (`fe80::1%eth0`) is read without the zone; a peer that is
 *   an entry the valve stopped at may carry a port or brackets, which are dropped.
 *
 * The result is normalised: IPv4 dotted quad, IPv4-mapped IPv6 as IPv4, other IPv6 in RFC 5952
 * form (at most 39 characters), no zone or port. It is personal data: it goes on
 * `RequestContext.clientIp` for the audit row only, never into MDC, logs or metric tags.
 */
@Component
class ClientIpResolver(
    properties: ClientIpProperties,
) {
    private val trustedProxies = properties.trustedRanges

    /** Returns the normalised client address, or `null` when the peer is not an IP literal. */
    fun resolve(request: HttpServletRequest): String? {
        val container = containerRequest(request)
        val peerText = container.remoteAddr.orEmpty().substringBefore(ZONE_SEPARATOR)
        val peer = parseEntry(peerText) ?: return null
        val client = if (isTrusted(peer)) forwardedClient(container) ?: peer else peer
        return IpLiterals.format(client)
    }

    private fun forwardedClient(request: HttpServletRequest): InetAddress? {
        val header =
            request
                .getHeaders(X_FORWARDED_FOR)
                ?.toList()
                .orEmpty()
                .joinToString(SEPARATOR.toString())
        var client: InetAddress? = null
        for ((hop, entry) in entriesFromRight(header).withIndex()) {
            client = parseEntry(entry.trim())?.takeIf { hop < MAX_HOPS }
            if (client == null || !isTrusted(client)) {
                break
            }
        }
        return client
    }

    private fun isTrusted(address: InetAddress): Boolean =
        trustedProxies.any { it.contains(address) }

    /** Header names and bounds of the walk. */
    companion object {
        /** The most `X-Forwarded-For` entries examined; a longer trusted chain is not real. */
        const val MAX_HOPS = 16
        private const val X_FORWARDED_FOR = "X-Forwarded-For"
        private const val SEPARATOR = ','
        private const val ZONE_SEPARATOR = '%'
        private const val MAX_PORT = 65_535

        /** `[2001:db8::1]` or `[2001:db8::1]:443`; the brackets must hold an IPv6 literal. */
        private val BRACKETED_IPV6 = Regex("""\[([0-9A-Fa-f.]*:[0-9A-Fa-f:.]*)](?::(\d{1,5}))?""")

        /** `203.0.113.9:5555`. */
        private val IPV4_WITH_PORT = Regex("""([0-9.]+):(\d{1,5})""")

        private fun containerRequest(request: HttpServletRequest): HttpServletRequest =
            generateSequence<ServletRequest>(request) { (it as? ServletRequestWrapper)?.request }
                .last() as? HttpServletRequest ?: request

        /** The comma-separated entries, right-most first, cut only as far as they are read. */
        private fun entriesFromRight(header: String): Sequence<String> =
            generateSequence(header.length to header.lastIndexOf(SEPARATOR)) { (_, comma) ->
                if (comma < 0) null else comma to header.lastIndexOf(SEPARATOR, comma - 1)
            }.map { (end, comma) -> header.substring(comma + 1, end) }

        /** An entry is an IP literal, optionally with a port; the port is dropped. */
        private fun parseEntry(entry: String): InetAddress? {
            val match = BRACKETED_IPV6.matchEntire(entry) ?: IPV4_WITH_PORT.matchEntire(entry)
            val literal =
                match
                    ?.takeIf { validPort(it.groupValues[2]) }
                    ?.groupValues
                    ?.get(1)
                    ?: entry
            return IpLiterals.parse(literal)
        }

        private fun validPort(port: String): Boolean = port.isEmpty() || port.toInt() <= MAX_PORT
    }
}

/**
 * One trusted-proxy entry: an address and a prefix length (the whole address when no `/n` is
 * given). Host bits beyond the prefix are ignored, so `10.1.2.3/8` means `10.0.0.0/8`.
 */
class IpRange private constructor(
    internal val network: ByteArray,
    internal val prefixLength: Int,
) {
    /** True when [address] is of the same family and shares the first prefix bits. */
    fun contains(address: InetAddress): Boolean {
        val candidate = address.address
        return candidate.size == network.size &&
            (0 until prefixLength).all { bit -> bitAt(candidate, bit) == bitAt(network, bit) }
    }

    /** Parses configured entries; a bad entry fails startup naming the property. */
    companion object {
        private const val BYTE_MASK = 0xFF
        private const val TOP_BIT = 7

        /** `::ffff:a.b.c.d/n` is the IPv4 range `a.b.c.d/(n - 96)`. */
        private const val MAPPED_PREFIX = 96
        private const val PROPERTY = "finaxis.security.client-ip.trusted-proxies"
        private val PREFIX = Regex("""0|[1-9]\d{0,2}""")

        /**
         * Parses `address` or `address/prefix`, IPv4 or IPv6, or throws. A range of every address
         * (`0.0.0.0/0`, `::/0`, `::ffff:0:0/96`) is refused: it would let any client choose the
         * address recorded for it.
         */
        fun parse(entry: String): IpRange {
            val range =
                requireNotNull(parseOrNull(entry.trim())) {
                    "$PROPERTY: '$entry' is not an IP address or a CIDR range"
                }
            require(range.prefixLength > 0) {
                "$PROPERTY: '$entry' would trust every address, so any client could choose the " +
                    "address recorded for it; list the reverse proxy's own addresses"
            }
            return range
        }

        private fun parseOrNull(entry: String): IpRange? {
            val parts = entry.split('/')
            val text = parts.first()
            val address = IpLiterals.parse(text)?.takeIf { parts.size <= 2 } ?: return null
            val width = address.address.size * Byte.SIZE_BITS
            val mappedOffset = if (address is Inet4Address && ':' in text) MAPPED_PREFIX else 0
            val prefix =
                if (parts.size == 1) {
                    width
                } else {
                    parts
                        .last()
                        .takeIf(PREFIX::matches)
                        ?.toInt()
                        ?.minus(mappedOffset)
                }
            return prefix?.takeIf { it in 0..width }?.let { IpRange(address.address, it) }
        }

        private fun bitAt(
            bytes: ByteArray,
            bit: Int,
        ): Int {
            val byte = bytes[bit / Byte.SIZE_BITS].toInt() and BYTE_MASK
            return (byte shr (TOP_BIT - bit % Byte.SIZE_BITS)) and 1
        }
    }
}

/** Strict IP-literal parsing and canonical text; never a DNS lookup. */
internal object IpLiterals {
    /** The longest IPv6 text with an embedded IPv4 tail (`INET6_ADDRSTRLEN` minus the NUL). */
    private const val MAX_LITERAL_LENGTH = 45
    private val IPV4 =
        Regex("""((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)""")
    private val IPV6_CHARACTERS = Regex("""[0-9A-Fa-f:.]+""")
    private val ZERO_RUN = Regex("""(?<![0-9a-f])0(:0)+(?![0-9a-f])""")

    /** Parses a dotted-quad IPv4 or an IPv6 literal (no zone); anything else is `null`. */
    fun parse(text: String): InetAddress? =
        when {
            text.length > MAX_LITERAL_LENGTH -> null
            IPV4.matches(text) -> InetAddress.ofLiteral(text)
            text.contains(':') && IPV6_CHARACTERS.matches(text) -> parseIpv6(text)
            else -> null
        }

    /** IPv4 as a dotted quad; IPv6 lower-case with the longest zero run compressed (RFC 5952). */
    fun format(address: InetAddress): String {
        val text = address.hostAddress
        if (address !is Inet6Address) {
            return text
        }
        val run = ZERO_RUN.findAll(text).maxByOrNull { it.value.length } ?: return text
        return text.substring(0, run.range.first).removeSuffix(":") + "::" +
            text.substring(run.range.last + 1).removePrefix(":")
    }

    private fun parseIpv6(text: String): InetAddress? =
        try {
            InetAddress.ofLiteral(text)
        } catch (_: IllegalArgumentException) {
            null
        }
}
