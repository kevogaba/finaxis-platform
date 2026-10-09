package com.finaxis.platform.iam.adapter.inbound.security

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.filter.ForwardedHeaderFilter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The #185 client address rule: forwarded headers count only behind a configured proxy. */
class ClientIpResolverTests {
    private val behindProxy = resolver("10.0.0.0/8", "192.0.2.10")

    @Test
    fun `with no trusted proxy the peer address is the client and the header is ignored`() {
        val resolver = resolver()

        assertEquals("198.51.100.7", resolver.resolve(request("198.51.100.7", "203.0.113.9")))
        assertEquals("198.51.100.7", resolver.resolve(request("198.51.100.7")))
    }

    @Test
    fun `a forwarded header from a peer that is not a trusted proxy is ignored`() {
        assertEquals(
            "198.51.100.7",
            behindProxy.resolve(request("198.51.100.7", "203.0.113.9, 10.0.0.5")),
        )
    }

    @Test
    fun `behind a trusted proxy the right-most untrusted entry is the client`() {
        assertEquals("203.0.113.9", behindProxy.resolve(request("10.0.0.2", "203.0.113.9")))
        // A client-supplied left-most entry is never believed over the proxy's own entry.
        assertEquals(
            "203.0.113.9",
            behindProxy.resolve(request("10.0.0.2", "1.1.1.1, 203.0.113.9")),
        )
    }

    @Test
    fun `a chain of trusted proxies is walked from the right`() {
        assertEquals(
            "203.0.113.9",
            behindProxy.resolve(request("10.0.0.2", "6.6.6.6, 203.0.113.9, 192.0.2.10, 10.9.9.9")),
        )
        // Several header lines are one list, in order.
        val split = request("10.0.0.2", "203.0.113.9")
        split.addHeader(X_FORWARDED_FOR, "192.0.2.10")
        assertEquals("203.0.113.9", behindProxy.resolve(split))
    }

    @Test
    fun `a header naming only trusted proxies resolves to its left-most entry`() {
        assertEquals("10.0.0.9", behindProxy.resolve(request("10.0.0.2", "10.0.0.9, 192.0.2.10")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " ", "not-an-ip", "unknown", "203.0.113.9,,10.0.0.1", "203.0.113.9,",
            "203.0.113.999", "010.0.0.1", "1.2.3", "fe80::1%eth0", "203.0.113.9; rm",
            "2001:db8::g", "[203.0.113.9]", "203.0.113.9:99999", "host.example",
        ],
    )
    fun `a malformed entry reached by the walk falls back to the trusted peer`(header: String) {
        assertEquals("10.0.0.2", behindProxy.resolve(request("10.0.0.2", header)))
    }

    @Test
    fun `a malformed entry inside the trusted hops falls back to the trusted peer`() {
        assertEquals(
            "10.0.0.2",
            behindProxy.resolve(request("10.0.0.2", "203.0.113.9, junk, 192.0.2.10")),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "junk", "", " ", "unknown", "fe80::1%eth0", "203.0.113.999", "1.1.1.1;drop",
            "x,y,,z", "[::1", ",,,",
        ],
    )
    fun `junk to the left of the client cannot veto the client`(junk: String) {
        assertEquals(
            "203.0.113.9",
            behindProxy.resolve(request("10.0.0.2", "$junk, 203.0.113.9, 192.0.2.10")),
        )
    }

    @Test
    fun `padding of any length to the left of the client is never read`() {
        val padding = "a".repeat(PADDING_LENGTH)
        val manyEntries = List(PADDING_LENGTH) { "6.6.6.6" }.joinToString(", ")

        assertEquals(
            "203.0.113.9",
            behindProxy.resolve(request("10.0.0.2", "$padding, 203.0.113.9")),
        )
        assertEquals(
            "203.0.113.9",
            behindProxy.resolve(request("10.0.0.2", "$manyEntries, 203.0.113.9")),
        )
        val split = request("10.0.0.2", padding)
        split.addHeader(X_FORWARDED_FOR, "203.0.113.9")
        assertEquals("203.0.113.9", behindProxy.resolve(split))
    }

    @Test
    fun `the walk examines at most the hop cap`() {
        val hops = ClientIpResolver.MAX_HOPS
        val trustedHops = { count: Int -> List(count) { "10.0.0.${it + 1}" } }

        // The client is the last entry the cap lets the walk examine ...
        val atCap = (listOf("203.0.113.9") + trustedHops(hops - 1)).joinToString(", ")
        assertEquals("203.0.113.9", behindProxy.resolve(request("10.0.0.200", atCap)))
        // ... one trusted hop more and it is never reached: the trusted peer is the client.
        val pastCap = (listOf("203.0.113.9") + trustedHops(hops)).joinToString(", ")
        assertEquals("10.0.0.200", behindProxy.resolve(request("10.0.0.200", pastCap)))
        // A chain made only of trusted hops beyond the cap does not resolve to its left-most.
        val allTrusted = trustedHops(hops + 1).joinToString(", ")
        assertEquals("10.0.0.200", behindProxy.resolve(request("10.0.0.200", allTrusted)))
    }

    @Test
    fun `an IPv6 peer with a zone id is read without the zone`() {
        assertEquals("fe80::1", resolver().resolve(request("fe80::1%eth0")))
        assertEquals(
            "203.0.113.9",
            resolver("fe80::/10").resolve(request("fe80:0:0:0:0:0:0:1%3", "203.0.113.9")),
        )
    }

    @Test
    fun `IPv6 addresses are normalised and matched`() {
        val resolver = resolver("2001:db8:ffff::/48")

        assertEquals(
            "2001:db8::1",
            resolver.resolve(request("2001:db8:ffff::1", "2001:DB8:0:0:0:0:0:1")),
        )
        assertEquals("2001:db8::2", resolver.resolve(request("2001:db8:ffff::1", "[2001:db8::2]")))
        assertEquals(
            "2001:db8::2",
            resolver.resolve(request("2001:db8:ffff::1", "[2001:db8::2]:443")),
        )
        assertEquals("::1", resolver().resolve(request("0:0:0:0:0:0:0:1")))
        assertEquals(
            "2001:db8::1:0:0:1",
            resolver().resolve(request("2001:0db8:0000:0000:0001:0000:0000:0001")),
        )
        assertEquals("2001:0:0:1::1", resolver().resolve(request("2001:0:0:1:0:0:0:1")))
        assertEquals(
            "2001:db8:0:1:1:1:1:1",
            resolver().resolve(request("2001:db8:0:1:1:1:1:1")),
        )
    }

    @Test
    fun `IPv4-mapped IPv6 addresses read as IPv4 and match IPv4 ranges`() {
        assertEquals("198.51.100.7", resolver().resolve(request("::ffff:198.51.100.7")))
        assertEquals(
            "203.0.113.9",
            behindProxy.resolve(request("::ffff:10.0.0.2", "::ffff:203.0.113.9")),
        )
    }

    @Test
    fun `an IPv4 entry with a port keeps only the address`() {
        assertEquals("203.0.113.9", behindProxy.resolve(request("10.0.0.2", "203.0.113.9:5555")))
    }

    @Test
    fun `a peer the valve left as an entry with a port continues the same walk`() {
        // Tomcat's RemoteIpValve stops at an entry it cannot match, port included, and makes
        // that text the remote address, leaving only the entries to its left in the header.
        assertEquals("203.0.113.9", behindProxy.resolve(request("10.0.0.5:443", "203.0.113.9")))
        assertEquals("10.0.0.5", behindProxy.resolve(request("10.0.0.5:443")))
        assertEquals("2001:db8::2", resolver().resolve(request("[2001:db8::2]:443", "6.6.6.6")))
    }

    @Test
    fun `a peer address that is not an IP literal resolves to nothing`() {
        assertNull(resolver().resolve(request("localhost")))
    }

    @Test
    fun `the address is read from the container request, not from a wrapper's view`() {
        val forwardedView = forwardedHeaderFilterView(request("198.51.100.7", "203.0.113.66"))

        // Spring's ForwardedHeaderFilter believes the left-most entry from any peer ...
        assertEquals("203.0.113.66", forwardedView.remoteAddr)
        // ... the resolver does not: the peer is not a trusted proxy.
        assertEquals("198.51.100.7", behindProxy.resolve(forwardedView))
        assertEquals(
            "203.0.113.9",
            behindProxy.resolve(
                forwardedHeaderFilterView(request("10.0.0.2", "6.6.6.6, 203.0.113.9")),
            ),
        )
    }

    @Test
    fun `trusted proxy entries accept addresses and CIDR ranges and mask host bits`() {
        val properties =
            ClientIpProperties(listOf(" 10.1.2.3/8 ", "192.0.2.10", "::1", "2001:db8::/32"))
        val ranges = properties.trustedRanges

        assertTrue(ranges.any { it.contains(literal("10.200.0.1")) })
        assertTrue(ranges.any { it.contains(literal("192.0.2.10")) })
        assertFalse(ranges.any { it.contains(literal("192.0.2.11")) })
        assertTrue(ranges.any { it.contains(literal("::1")) })
        assertTrue(ranges.any { it.contains(literal("2001:db8:1::5")) })
        assertFalse(ranges.any { it.contains(literal("2001:db9::5")) })
        val mapped = ClientIpProperties(listOf("::ffff:172.16.0.0/108")).trustedRanges.single()
        assertTrue(mapped.contains(literal("172.31.255.1")))
        assertFalse(mapped.contains(literal("172.32.0.1")))
        val everyOtherIpv4 = ClientIpProperties(listOf("0.0.0.0/1")).trustedRanges.single()
        assertTrue(everyOtherIpv4.contains(literal("1.2.3.4")))
        assertFalse(everyOtherIpv4.contains(literal("::2")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.0.0.0/0", "::/0", "::ffff:0:0/96", "10.0.0.0/0", " 2001:db8::/0"])
    fun `a range of every address is refused`(entry: String) {
        val failure =
            assertFailsWith<IllegalArgumentException> { ClientIpProperties(listOf(entry)) }

        assertTrue(requireNotNull(failure.message).contains("would trust every address"))
        assertTrue(requireNotNull(failure.message).contains("finaxis.security.client-ip"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "not-a-cidr", "10.0.0.0/33", "10.0.0.0/", "10.0.0.0/-1", "10.0.0.0/a", "::/129",
            "host.example", "10.0.0.0/8/8", "fe80::1%eth0", "010.0.0.0/8", "10.0.0.0/+8",
            "10.0.0.0/08", "::ffff:10.0.0.0/64", "::ffff:10.0.0.0/129",
        ],
    )
    fun `an entry that is not an address or a CIDR range is refused`(entry: String) {
        val failure =
            assertFailsWith<IllegalArgumentException> { ClientIpProperties(listOf(entry)) }

        assertTrue(requireNotNull(failure.message).contains("finaxis.security.client-ip"))
    }

    private fun resolver(vararg trusted: String) =
        ClientIpResolver(ClientIpProperties(trusted.toList()))

    private fun request(
        peer: String,
        forwardedFor: String? = null,
    ): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            remoteAddr = peer
            forwardedFor?.let { addHeader(X_FORWARDED_FOR, it) }
        }

    /** What a filter behind Spring's ForwardedHeaderFilter (`framework` strategy) is given. */
    private fun forwardedHeaderFilterView(request: MockHttpServletRequest): HttpServletRequest {
        val chain = MockFilterChain()
        ForwardedHeaderFilter().doFilter(request, MockHttpServletResponse(), chain)
        return chain.request as HttpServletRequest
    }

    private fun literal(text: String) = requireNotNull(IpLiterals.parse(text))

    private companion object {
        const val X_FORWARDED_FOR = "X-Forwarded-For"
        const val PADDING_LENGTH = 600
    }
}
