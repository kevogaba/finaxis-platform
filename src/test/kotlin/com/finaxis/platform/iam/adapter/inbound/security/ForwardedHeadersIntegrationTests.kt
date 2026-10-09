package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.web.ratelimit.RateLimitDecision
import com.finaxis.platform.common.web.ratelimit.RateLimitPolicy
import com.finaxis.platform.common.web.ratelimit.RateLimiterService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.web.filter.ForwardedHeaderFilter
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Forwarded headers through a REAL embedded Tomcat (#256): the client is a real HTTP client on
 * the loopback interface, so the peer Tomcat sees is `127.0.0.1`. What the application believes
 * is observed where it is used: the anonymous rate-limit key (`anon:<remoteAddr>`), the
 * `resource_metadata` URL of the `401` challenge (scheme, host, port, prefix), and a probe filter
 * at the front of the chain that records `getRemoteAddr()` and what [ClientIpResolver] resolves.
 *
 * Nothing is forwarded with no trusted proxy configured ([UntrustedPeerForwardedHeadersTests]);
 * with the loopback listed, `X-Forwarded-For`/`-Proto`/`-Host`/`-Port` are honoured
 * ([TrustedLoopbackForwardedHeadersTests]). The RFC 7239 `Forwarded` header and
 * `X-Forwarded-Prefix` are ignored in both. Both contexts also try to widen the trust through
 * `server.tomcat.remoteip.*` (which must have no effect) and enable HSTS and CORS as production
 * does, so the consequence of the list for HSTS and same-origin browser requests is pinned too.
 */
@Import(PostgresTestConfiguration::class, ForwardedHeadersIntegrationTests.Probe::class)
abstract class ForwardedHeadersIntegrationTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var observations: Observations

    @Autowired
    private lateinit var context: ApplicationContext

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    /** The peer's own view: what Tomcat's connector saw, `http://127.0.0.1:<port>`. */
    protected val directMetadata: String
        get() = "http://$LOOPBACK:$port$METADATA_PATH"

    @BeforeEach
    fun clearObservations() {
        observations.clear()
    }

    @Test
    fun `no ForwardedHeaderFilter is installed`() {
        assertTrue(context.getBeansOfType(ForwardedHeaderFilter::class.java).isEmpty())
        val registered =
            context
                .getBeansOfType(FilterRegistrationBean::class.java)
                .values
                .map { it.filter }
        assertTrue(registered.none { it is ForwardedHeaderFilter }, registered.toString())
    }

    @Test
    fun `the RFC 7239 Forwarded header and X-Forwarded-Prefix are ignored`() {
        val seen =
            send(
                "Forwarded" to "for=198.51.100.7;proto=https;host=forged.example.test",
                "X-Forwarded-Prefix" to "/forged",
            )

        assertEquals(LOOPBACK, seen.remoteAddress)
        assertEquals(LOOPBACK, seen.clientIp)
        assertEquals("$KEY_PREFIX$LOOPBACK", seen.rateLimitKey)
        assertEquals("http", seen.scheme)
        assertEquals(directMetadata, seen.resourceMetadata)
    }

    @Test
    fun `a malformed X-Forwarded-For never fails the request`() {
        listOf("", ",,,", "not-an-address", "1.2.3.4:99999, [::1", "%zz, ::g").forEach {
            val seen = send("X-Forwarded-For" to it)

            assertEquals(UNAUTHORIZED, seen.status, it)
        }
    }

    @Test
    fun `a long X-Forwarded-For is answered and an oversize one refused, never a 500`() {
        val long = List(LONG_CHAIN_ENTRIES) { "198.51.100.${it % OCTET}" }.joinToString(", ")
        assertEquals(UNAUTHORIZED, send("X-Forwarded-For" to long).status)

        val oversize = "198.51.100.1, ".repeat(OVERSIZE_REPEATS)
        val status = send("X-Forwarded-For" to oversize, observe = false).status
        assertNotEquals(SERVER_ERROR, status)
        assertTrue(status in CLIENT_ERRORS, "status $status")
    }

    /** Sends an anonymous rate-limited request and returns what the application saw. */
    protected fun send(
        vararg headers: Pair<String, String>,
        observe: Boolean = true,
    ): Seen {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://$LOOPBACK:$port$RATE_LIMITED_PATH"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val challenge = response.headers().firstValue("WWW-Authenticate").orElse("")
        val metadata = METADATA.find(challenge)?.groupValues?.get(1)
        val hsts = response.headers().firstValue("Strict-Transport-Security").isPresent
        if (!observe) {
            return Seen(response.statusCode(), null, null, null, null, null, null, hsts)
        }
        val probed = assertNotNull(observations.request, "the probe filter did not run")
        return Seen(
            status = response.statusCode(),
            remoteAddress = probed.remoteAddress,
            clientIp = probed.clientIp,
            scheme = probed.scheme,
            serverName = probed.serverName,
            rateLimitKey = observations.rateLimitKey,
            resourceMetadata = metadata,
            hsts = hsts,
        )
    }

    /** What the application saw for one request. */
    data class Seen(
        val status: Int,
        val remoteAddress: String?,
        val clientIp: String?,
        val scheme: String?,
        val serverName: String?,
        val rateLimitKey: String?,
        val resourceMetadata: String?,
        val hsts: Boolean,
    )

    /** What the probe filter and the recording rate limiter observed. */
    class Observations {
        /** The request as the first application filter saw it. */
        data class ProbedRequest(
            val remoteAddress: String,
            val clientIp: String?,
            val scheme: String,
            val serverName: String,
        )

        @Volatile
        var request: ProbedRequest? = null

        @Volatile
        var rateLimitKey: String? = null

        /** Forgets the previous request. */
        fun clear() {
            request = null
            rateLimitKey = null
        }
    }

    /**
     * The probe: a filter right behind whatever forward-headers strategy is configured, and a
     * rate limiter that records the key it is asked for and always allows.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class Probe {
        @Bean
        fun forwardedHeadersObservations(): Observations = Observations()

        @Bean
        @Primary
        fun recordingRateLimiter(observations: Observations): RateLimiterService =
            RateLimiterService { key, policy: RateLimitPolicy ->
                observations.rateLimitKey = key
                RateLimitDecision(
                    allowed = true,
                    limit = policy.capacity,
                    remaining = policy.capacity,
                    retryAfter = Duration.ZERO,
                    resetAfter = Duration.ZERO,
                )
            }

        @Bean
        fun forwardedHeadersProbe(
            observations: Observations,
            clientIpResolver: ClientIpResolver,
        ): FilterRegistrationBean<OncePerRequestFilter> =
            FilterRegistrationBean<OncePerRequestFilter>(
                object : OncePerRequestFilter() {
                    override fun doFilterInternal(
                        request: HttpServletRequest,
                        response: HttpServletResponse,
                        filterChain: FilterChain,
                    ) {
                        observations.request =
                            Observations.ProbedRequest(
                                remoteAddress = request.remoteAddr,
                                clientIp = clientIpResolver.resolve(request),
                                scheme = request.scheme,
                                serverName = request.serverName,
                            )
                        filterChain.doFilter(request, response)
                    }
                },
            ).apply { order = Ordered.HIGHEST_PRECEDENCE + 1 }
    }

    /** The request every case sends and the values it is judged by. */
    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val KEY_PREFIX = "rate-limit:auth-selection:anon:"
        const val METADATA_PATH = "/.well-known/oauth-protected-resource"
        const val RATE_LIMITED_PATH = "/api/v1/auth/select-organisation"
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403

        /** What a browser on the public HTTPS origin sends behind a TLS-terminating proxy. */
        val PUBLIC_HTTPS =
            arrayOf(
                "Origin" to "https://$LOOPBACK",
                "X-Forwarded-For" to "203.0.113.53",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to LOOPBACK,
                "X-Forwarded-Port" to "443",
            )

        /** Production's HSTS and CORS, and an attempt to widen the trust past the list. */
        const val HSTS = "finaxis.security.headers.hsts-enabled=true"
        const val CORS = "finaxis.security.cors.enabled=true"
        const val CORS_ORIGINS = "finaxis.security.cors.allowed-origins=https://app.example.test"
        const val WIDEN_INTERNAL = "server.tomcat.remoteip.internal-proxies=.*"
        const val WIDEN_TRUSTED = "server.tomcat.remoteip.trusted-proxies=.*"
        private const val SERVER_ERROR = 500
        private val CLIENT_ERRORS = 400..431
        private const val REQUEST_TIMEOUT_SECONDS = 30L
        private const val LONG_CHAIN_ENTRIES = 300
        private const val OVERSIZE_REPEATS = 2_000
        private const val OCTET = 256
        private val METADATA = Regex("""resource_metadata="([^"]*)"""")
    }
}

/** No trusted proxy (the default): every forwarded header is ignored. */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        ForwardedHeadersIntegrationTests.HSTS,
        ForwardedHeadersIntegrationTests.CORS,
        ForwardedHeadersIntegrationTests.CORS_ORIGINS,
        ForwardedHeadersIntegrationTests.WIDEN_INTERNAL,
        ForwardedHeadersIntegrationTests.WIDEN_TRUSTED,
    ],
)
class UntrustedPeerForwardedHeadersTests : ForwardedHeadersIntegrationTests() {
    @Test
    fun `behind an unlisted TLS proxy HSTS is not sent and a same-origin request is CORS`() {
        val seen = send(*PUBLIC_HTTPS, observe = false)

        // The request is http://127.0.0.1:<port> to the application, so the browser's own
        // https://127.0.0.1 origin is foreign to it (403, not in the allowed origins) and the
        // response is not secure, so no Strict-Transport-Security.
        assertEquals(FORBIDDEN, seen.status)
        assertFalse(seen.hsts)
        // The same request without the Origin header is the ordinary anonymous 401.
        val withoutOrigin = PUBLIC_HTTPS.filterNot { it.first == "Origin" }.toTypedArray()
        assertEquals(UNAUTHORIZED, send(*withoutOrigin, observe = false).status)
    }

    @Test
    fun `forged X-Forwarded headers change neither the client nor the scheme or host`() {
        val seen =
            send(
                "X-Forwarded-For" to "203.0.113.66",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "forged.example.test",
                "X-Forwarded-Port" to "8443",
            )

        assertEquals(UNAUTHORIZED, seen.status)
        assertEquals(LOOPBACK, seen.remoteAddress)
        assertEquals(LOOPBACK, seen.clientIp)
        assertEquals("$KEY_PREFIX$LOOPBACK", seen.rateLimitKey)
        assertEquals("http", seen.scheme)
        assertEquals(LOOPBACK, seen.serverName)
        assertEquals(directMetadata, seen.resourceMetadata)
    }

    @Test
    fun `rotating X-Forwarded-For does not give an anonymous caller a fresh rate-limit bucket`() {
        val keys = (1..3).map { send("X-Forwarded-For" to "198.51.100.$it").rateLimitKey }

        assertEquals(List(3) { "$KEY_PREFIX$LOOPBACK" }, keys)
    }
}

/** The loopback is a trusted proxy: its X-Forwarded headers are honoured. */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "finaxis.security.client-ip.trusted-proxies=127.0.0.1",
        ForwardedHeadersIntegrationTests.HSTS,
        ForwardedHeadersIntegrationTests.CORS,
        ForwardedHeadersIntegrationTests.CORS_ORIGINS,
        ForwardedHeadersIntegrationTests.WIDEN_INTERNAL,
        ForwardedHeadersIntegrationTests.WIDEN_TRUSTED,
    ],
)
class TrustedLoopbackForwardedHeadersTests : ForwardedHeadersIntegrationTests() {
    @Test
    fun `behind a listed TLS proxy HSTS is sent and a same-origin request is not CORS`() {
        val seen = send(*PUBLIC_HTTPS)

        assertEquals(UNAUTHORIZED, seen.status)
        assertTrue(seen.hsts)
        assertEquals("https", seen.scheme)
        assertEquals("https://$LOOPBACK$METADATA_PATH", seen.resourceMetadata)
    }

    @Test
    fun `server tomcat remoteip settings do not widen the list to the client's own entries`() {
        // internal-proxies=.* and trusted-proxies=.* would skip every entry and pick the
        // left-most, the client's choice; the list still only skips the loopback.
        val seen = send("X-Forwarded-For" to "6.6.6.6, 203.0.113.54")

        assertEquals("203.0.113.54", seen.remoteAddress)
    }

    @Test
    fun `the client is the right-most X-Forwarded-For entry that is not a trusted proxy`() {
        val seen = send("X-Forwarded-For" to "6.6.6.6, 203.0.113.50")

        assertEquals(UNAUTHORIZED, seen.status)
        assertEquals("203.0.113.50", seen.remoteAddress)
        assertEquals("203.0.113.50", seen.clientIp)
        assertEquals("${KEY_PREFIX}203.0.113.50", seen.rateLimitKey)
        assertEquals(directMetadata, seen.resourceMetadata)
    }

    @Test
    fun `trusted hops inside X-Forwarded-For are skipped`() {
        val seen = send("X-Forwarded-For" to "203.0.113.51, 127.0.0.1, 127.0.0.1")

        assertEquals("203.0.113.51", seen.remoteAddress)
        assertEquals("203.0.113.51", seen.clientIp)
        assertEquals("${KEY_PREFIX}203.0.113.51", seen.rateLimitKey)
    }

    @Test
    fun `X-Forwarded-Proto, -Host and -Port set the scheme, host and port`() {
        val seen =
            send(
                "X-Forwarded-For" to "203.0.113.52",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "api.example.test",
                "X-Forwarded-Port" to "8443",
            )

        assertEquals("https", seen.scheme)
        assertEquals("api.example.test", seen.serverName)
        assertEquals("https://api.example.test:8443$METADATA_PATH", seen.resourceMetadata)
    }
}
