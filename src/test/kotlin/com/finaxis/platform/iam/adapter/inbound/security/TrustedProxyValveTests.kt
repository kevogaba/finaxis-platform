package com.finaxis.platform.iam.adapter.inbound.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.apache.catalina.connector.Connector
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardContext
import org.apache.catalina.valves.RemoteIpValve
import org.apache.catalina.valves.ValveBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import java.math.BigInteger
import java.net.InetAddress
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.coyote.Request as CoyoteRequest

/**
 * The #256 trusted-proxy rule for Tomcat's `RemoteIpValve`: the pattern built from
 * `finaxis.security.client-ip.trusted-proxies`, the customizer that installs it, and the real
 * valve followed by [ClientIpResolver], which must never trust more than the resolver alone.
 */
class TrustedProxyValveTests {
    @Test
    fun `the empty list matches nothing`() {
        val pattern = pattern()

        assertEquals(TrustedProxyPattern.NOTHING, TrustedProxyPattern.of(emptyList()))
        listOf("", "127.0.0.1", "10.0.0.1", "::1", "0:0:0:0:0:0:0:1", "(?!)").forEach {
            assertFalse(pattern.matches(it), it)
        }
    }

    @Test
    fun `a single IPv4 host matches only itself, in its canonical form`() {
        val pattern = pattern("192.0.2.10")

        assertTrue(pattern.matches("192.0.2.10"))
        listOf(
            "192.0.2.1",
            "192.0.2.100",
            "192.0.2.11",
            "192.0.2.10:80",
            "0192.0.2.10",
            "192.0.2.010",
            "::ffff:192.0.2.10",
            " 192.0.2.10",
            "192.0.2.10.1",
            "192x0x2x10",
        ).forEach { assertFalse(pattern.matches(it), it) }
    }

    @Test
    fun `IPv4 ranges match exactly their boundaries`() {
        val octetAligned = pattern("10.20.0.0/16")
        assertTrue(octetAligned.matches("10.20.0.0"))
        assertTrue(octetAligned.matches("10.20.255.255"))
        assertFalse(octetAligned.matches("10.19.255.255"))
        assertFalse(octetAligned.matches("10.21.0.0"))

        val unaligned = pattern("172.16.16.0/20")
        assertTrue(unaligned.matches("172.16.16.0"))
        assertTrue(unaligned.matches("172.16.31.255"))
        assertFalse(unaligned.matches("172.16.15.255"))
        assertFalse(unaligned.matches("172.16.32.0"))

        val hostBitsMasked = pattern("10.1.2.3/8")
        assertTrue(hostBitsMasked.matches("10.255.0.1"))
        assertFalse(hostBitsMasked.matches("11.0.0.0"))
    }

    @Test
    fun `IPv6 ranges match the full, compressed and any-case forms of their addresses`() {
        val loopback = pattern("::1")
        listOf("::1", "0:0:0:0:0:0:0:1", "0000:0000:0000:0000:0000:0000:0000:0001", "::0:1", "0::1")
            .forEach { assertTrue(loopback.matches(it), it) }
        listOf("::2", "::1:0", "1::", "::", "[::1]", "[::1]:8081", "::1/128", "::ffff:127.0.0.1")
            .forEach { assertFalse(loopback.matches(it), it) }

        val documentation = pattern("2001:db8::/32")
        listOf("2001:db8::1", "2001:DB8:0:0:0:0:0:1", "2001:0db8:ffff::", "2001:db8:ffff:ffff::7")
            .forEach { assertTrue(documentation.matches(it), it) }
        listOf("2001:db9::1", "2001:db7:ffff::1", "2001::db8:1", "[2001:db8::1]:443")
            .forEach { assertFalse(documentation.matches(it), it) }

        val linkLocal = pattern("fe80::/10")
        assertTrue(linkLocal.matches("fe80:0:0:0:0:0:0:1%eth0"))
        assertTrue(linkLocal.matches("febf::1"))
        assertFalse(linkLocal.matches("fec0::1"))
    }

    @Test
    fun `the pattern agrees with the range on every textual form of many addresses`() {
        val random = Random(SEED)
        val ranges =
            listOf(
                "10.0.0.0/8",
                "172.16.16.0/20",
                "192.0.2.10",
                "203.0.113.128/25",
                "0.0.0.0/1",
                "2001:db8::/32",
                "2001:db8:8000::/33",
                "::1",
                "fe80::/10",
                "2001:db8:0:1::/64",
                "2001:db8::abcd:0:0:0/100",
                "8000::/1",
            )
        ranges.forEach { entry ->
            val range = IpRange.parse(entry)
            val pattern = pattern(entry)
            candidates(range, random).forEach { address ->
                val expected = range.contains(address)
                textForms(address).forEach { text ->
                    assertEquals(expected, pattern.matches(text), "$entry vs $text")
                }
            }
        }
    }

    @Test
    fun `the pattern is a regular expression to Tomcat, never its DNS-resolving CIDR form`() {
        val pattern = TrustedProxyPattern.of(ranges("10.0.0.0/8", "2001:db8::/32", "192.0.2.10"))
        val valve = RemoteIpValve().apply { setInternalProxies(pattern) }

        assertFalse('/' in pattern)
        assertEquals(pattern, valve.internalProxies)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.0.0.0/0", "::/0", "::ffff:0:0/96"])
    fun `a list that would trust every address is still refused before any pattern exists`(
        entry: String,
    ) {
        assertFailsWith<IllegalArgumentException> { ClientIpProperties(listOf(entry)) }
    }

    @Test
    fun `the customizer replaces Tomcat's private-range default with the configured list`() {
        val factory = TomcatServletWebServerFactory()
        val valve = RemoteIpValve().apply { setTrustedProxies("192\\.168\\..*") }
        factory.addEngineValves(valve)

        customizer("192.0.2.10").customize(factory)

        assertEquals(TrustedProxyPattern.of(ranges("192.0.2.10")), valve.internalProxies)
        assertNull(valve.trustedProxies)
        assertEquals(1, factory.engineValves.size)

        customizer().customize(factory)
        assertEquals(TrustedProxyPattern.NOTHING, valve.internalProxies)
    }

    @Test
    fun `an empty list under the production profile is warned about once, naming no address`() {
        val production = MockEnvironment().apply { setActiveProfiles("production") }

        val warnings =
            captureWarnings { customizer(environment = production).customize(factory()) }

        assertEquals(listOf(TrustedProxyValveCustomizer.EMPTY_LIST_IN_PRODUCTION), warnings)
        assertTrue(warnings.single().contains("scheme, host, port and HSTS"))
        assertTrue(warnings.single().contains("FINAXIS_CLIENT_IP_TRUSTED_PROXIES"))
    }

    @Test
    fun `a listed proxy, or no production profile, is not warned about`() {
        val production = MockEnvironment().apply { setActiveProfiles("production") }

        assertEquals(
            emptyList(),
            captureWarnings {
                customizer("192.0.2.10", environment = production).customize(factory())
                customizer(environment = MockEnvironment()).customize(factory())
            },
        )
    }

    @ParameterizedTest
    @EnumSource(ForwardHeadersStrategy::class, names = ["FRAMEWORK", "NONE"])
    fun `every strategy but native is refused at startup`(strategy: ForwardHeadersStrategy) {
        val failure =
            assertFailsWith<IllegalStateException> {
                customizer(strategy = strategy).customize(factory())
            }

        val message = requireNotNull(failure.message)
        assertTrue(message.contains("server.forward-headers-strategy must be native"), message)
        assertTrue(message.contains("was $strategy"), message)
    }

    @Test
    fun `an unset strategy is refused at startup`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                customizer(strategy = null).customize(factory())
            }

        assertTrue(requireNotNull(failure.message).contains("must be native, was unset"))
    }

    @Test
    fun `native builds a server only with the valve Spring Boot adds`() {
        val withValve = factory()
        customizer(strategy = ForwardHeadersStrategy.NATIVE).customize(withValve)
        buildContext(withValve)

        // Customizing a factory without the valve is allowed (a MOCK context does it and never
        // starts a server); building a server from it is not.
        val withoutValve = TomcatServletWebServerFactory()
        customizer().customize(withoutValve)
        val failure = assertFailsWith<IllegalStateException> { buildContext(withoutValve) }
        assertTrue(requireNotNull(failure.message).contains("no RemoteIpValve"))
    }

    /** What building the server does with the context customizers. */
    private fun buildContext(factory: TomcatServletWebServerFactory) =
        factory.contextCustomizers.forEach { it.customize(StandardContext()) }

    @Test
    fun `with no trusted proxy the valve changes nothing`() {
        val seen =
            throughValve(
                trusted = emptyList(),
                peer = "10.0.0.2",
                "X-Forwarded-For" to "203.0.113.66",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "forged.example.test",
            )

        assertEquals(Seen("10.0.0.2", "10.0.0.2", "http", "app.internal"), seen)
    }

    @Test
    fun `behind a trusted proxy the valve and the resolver agree on the client`() {
        val seen =
            throughValve(
                trusted = TRUSTED,
                peer = "10.0.0.2",
                "X-Forwarded-For" to "6.6.6.6, 203.0.113.9, 192.0.2.10, 10.9.9.9",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "api.example.test",
            )

        assertEquals(Seen("203.0.113.9", "203.0.113.9", "https", "api.example.test"), seen)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "203.0.113.9", "1.1.1.1, 203.0.113.9", "10.0.0.9, 192.0.2.10", "203.0.113.9, 10.0.0.7",
            "2001:db8::2", "2001:db8::2, 10.0.0.3", "203.0.113.9:5555", "[2001:db8::2]:443",
            "10.0.0.5:443, 10.0.0.6", "6.6.6.6, 10.0.0.5:443", "",
        ],
    )
    fun `the valve then the resolver resolve as the resolver alone does`(header: String) {
        val alone = ClientIpResolver(ClientIpProperties(TRUSTED)).resolve(servletRequest(header))
        val seen = throughValve(TRUSTED, "10.0.0.2", "X-Forwarded-For" to header)

        assertEquals(alone, seen.clientIp)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "junk", "203.0.113.9, junk", "203.0.113.9, junk, 192.0.2.10", "203.0.113.999",
            "1.1.1.1;drop", "x,y,,z", "[::1", "host.example", ",,,", " ", " , ",
        ],
    )
    fun `a malformed entry the valve stops at is never trusted, at worst no address`(
        header: String,
    ) {
        val alone = ClientIpResolver(ClientIpProperties(TRUSTED)).resolve(servletRequest(header))
        val seen = throughValve(TRUSTED, "10.0.0.2", "X-Forwarded-For" to header)

        assertTrue(seen.clientIp == null || seen.clientIp == alone, "$header: $seen vs $alone")
    }

    @Test
    fun `a listed IPv6 hop with a zone is skipped by the valve, its address being listed`() {
        // The pattern accepts a zone because Tomcat writes one on a link-local peer; inside the
        // header the resolver alone would stop at it instead. Either way only listed addresses
        // are passed over, so this is not a wider trust than the list.
        val trusted = listOf("10.0.0.0/8", "fe80::/10")
        val seen = throughValve(trusted, "10.0.0.2", "X-Forwarded-For" to "6.6.6.6, fe80::1%eth0")

        assertEquals("6.6.6.6", seen.clientIp)
        assertEquals(
            "10.0.0.2",
            ClientIpResolver(ClientIpProperties(trusted))
                .resolve(servletRequest("6.6.6.6, fe80::1%eth0")),
        )
    }

    /** What the next valve saw. */
    private data class Seen(
        val remoteAddress: String?,
        val clientIp: String?,
        val scheme: String?,
        val serverName: String?,
    )

    private fun throughValve(
        trusted: List<String>,
        peer: String,
        vararg headers: Pair<String, String>,
    ): Seen {
        val factory = TomcatServletWebServerFactory()
        val valve = RemoteIpValve()
        factory.addEngineValves(valve)
        customizer(*trusted.toTypedArray()).customize(factory)
        val resolver = ClientIpResolver(ClientIpProperties(trusted))
        var seen: Seen? = null
        valve.next =
            object : ValveBase() {
                override fun invoke(
                    request: Request,
                    response: Response?,
                ) {
                    seen =
                        Seen(
                            request.remoteAddr,
                            resolver.resolve(request.request),
                            request.scheme,
                            request.serverName,
                        )
                }
            }
        val coyote =
            CoyoteRequest().apply {
                scheme().setString("http")
                serverName().setString("app.internal")
                serverPort = APP_PORT
                headers.forEach { (name, value) -> mimeHeaders.addValue(name).setString(value) }
            }
        val request = Request(Connector(), coyote).apply { remoteAddr = peer }
        valve.invoke(request, null)
        return requireNotNull(seen)
    }

    private fun servletRequest(forwardedFor: String) =
        MockHttpServletRequest().apply {
            remoteAddr = "10.0.0.2"
            addHeader("X-Forwarded-For", forwardedFor)
        }

    private fun customizer(
        vararg trusted: String,
        strategy: ForwardHeadersStrategy? = ForwardHeadersStrategy.NATIVE,
        environment: MockEnvironment = MockEnvironment(),
    ) = TrustedProxyValveCustomizer(
        ClientIpProperties(trusted.toList()),
        ServerProperties().apply { forwardHeadersStrategy = strategy },
        environment,
    )

    private fun factory() =
        TomcatServletWebServerFactory().apply { addEngineValves(RemoteIpValve()) }

    private fun captureWarnings(action: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(TrustedProxyValveCustomizer::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            action()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    private fun pattern(vararg entries: String) = Regex(TrustedProxyPattern.of(ranges(*entries)))

    private fun ranges(vararg entries: String) = entries.map(IpRange::parse)

    /** The range's own edges, their neighbours, and random addresses near and far. */
    private fun candidates(
        range: IpRange,
        random: Random,
    ): List<InetAddress> {
        val width = range.network.size
        val low = range.network.copyOf().also { maskHostBits(it, range.prefixLength, set = false) }
        val high = range.network.copyOf().also { maskHostBits(it, range.prefixLength, set = true) }
        val edges = listOf(low, high, step(low, -1), step(high, 1))
        val near =
            List(RANDOM_NEAR) {
                range.network.copyOf().also { bytes ->
                    val flip = random.nextInt(width * Byte.SIZE_BITS)
                    bytes[flip / Byte.SIZE_BITS] =
                        (bytes[flip / Byte.SIZE_BITS].toInt() xor (TOP_BIT shr (flip % 8))).toByte()
                }
            }
        val far = List(RANDOM_FAR) { random.nextBytes(width) }
        val zeroRich =
            List(RANDOM_FAR) {
                range.network.copyOf().also { bytes ->
                    repeat(width / 2) { if (random.nextBoolean()) bytes[it * 2 + 1] = 0 }
                    repeat(width / 2) { if (random.nextBoolean()) bytes[it * 2] = 0 }
                }
            }
        return (edges + near + far + zeroRich).map(InetAddress::getByAddress)
    }

    private fun maskHostBits(
        bytes: ByteArray,
        prefixLength: Int,
        set: Boolean,
    ) {
        for (bit in prefixLength until bytes.size * Byte.SIZE_BITS) {
            val mask = TOP_BIT shr (bit % Byte.SIZE_BITS)
            val index = bit / Byte.SIZE_BITS
            val value = bytes[index].toInt()
            bytes[index] = (if (set) value or mask else value and mask.inv()).toByte()
        }
    }

    /** The address one above or below, wrapping at the ends. */
    private fun step(
        bytes: ByteArray,
        delta: Int,
    ): ByteArray {
        val number = BigInteger(1, bytes).add(BigInteger.valueOf(delta.toLong()))
        val modulus = BigInteger.ONE.shiftLeft(bytes.size * Byte.SIZE_BITS)
        val raw = number.mod(modulus).toByteArray()
        return ByteArray(bytes.size) { index ->
            val source = raw.size - bytes.size + index
            if (source >= 0) raw[source] else 0
        }
    }

    /**
     * Every form a peer or a proxy writes: Java's own (Tomcat's peer form), RFC 5952, upper case,
     * and fully zero-padded. Mapped IPv4 addresses come back from `getByAddress` as IPv4.
     */
    private fun textForms(address: InetAddress): Set<String> {
        val java = address.hostAddress
        if (address.address.size == IPV4_BYTES) {
            return setOf(java)
        }
        val padded =
            address.address
                .toList()
                .chunked(2)
                .joinToString(":") { (high, low) ->
                    "%02x%02x".format(high.toInt() and BYTE_MASK, low.toInt() and BYTE_MASK)
                }
        val compressed = IpLiterals.format(address)
        return setOf(java, compressed, compressed.uppercase(), padded)
    }

    private companion object {
        val TRUSTED = listOf("10.0.0.0/8", "192.0.2.10", "2001:db8:ffff::/48")
        const val APP_PORT = 8081
        const val SEED = 256
        const val RANDOM_NEAR = 64
        const val RANDOM_FAR = 32
        const val TOP_BIT = 0x80
        const val BYTE_MASK = 0xFF
        const val IPV4_BYTES = 4
    }
}
