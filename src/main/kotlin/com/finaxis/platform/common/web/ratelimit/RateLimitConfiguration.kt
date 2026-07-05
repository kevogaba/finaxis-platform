package com.finaxis.platform.common.web.ratelimit

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.RedisURI.Builder
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import java.time.Clock
import java.time.Duration

/**
 * Redis-backed Bucket4j rate-limit wiring.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "finaxis.rate-limit",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RateLimitConfiguration {
    /**
     * Dedicated Lettuce resources for Bucket4j distributed bucket state.
     */
    @Bean
    @ConditionalOnMissingBean
    fun rateLimitRedisBackend(redisProperties: DataRedisProperties): RateLimitRedisBackend {
        val clusterNodes = redisProperties.cluster?.nodes.orEmpty()
        return if (clusterNodes.isEmpty()) {
            standaloneOrSentinelBackend(redisProperties)
        } else {
            clusterBackend(redisProperties, clusterNodes)
        }
    }

    /**
     * Backend contract that exposes Bucket4j while hiding Lettuce resource lifecycle details.
     */
    interface RateLimitRedisBackend : DisposableBean {
        /**
         * Bucket4j proxy manager backed by the configured Lettuce Redis topology.
         */
        val proxyManager: ProxyManager<String>
    }

    /**
     * Container for Lettuce resources that must be closed with the application context.
     */
    open class LettuceRateLimitRedisBackend(
        override val proxyManager: ProxyManager<String>,
        private val connection: AutoCloseable,
        private val client: AbstractRedisClient,
    ) : RateLimitRedisBackend {
        override fun destroy() {
            connection.close()
            client.shutdown()
        }
    }

    /**
     * Distributed rate limiter used by the API filter.
     */
    @Bean
    @ConditionalOnMissingBean
    fun rateLimiterService(backend: RateLimitRedisBackend): RateLimiterService =
        Bucket4jRateLimiterService(backend.proxyManager)

    /**
     * Resolves request identity into rate-limit keys.
     */
    @Bean
    @ConditionalOnMissingBean
    fun rateLimitKeyResolver(): RateLimitKeyResolver = RateLimitKeyResolver()

    /**
     * API-wide rate-limit servlet filter.
     */
    @Bean
    @ConditionalOnMissingBean
    fun rateLimitFilter(
        properties: RateLimitProperties,
        keyResolver: RateLimitKeyResolver,
        rateLimiter: RateLimiterService,
        clock: Clock,
    ): RateLimitFilter =
        RateLimitFilter(
            properties = properties,
            keyResolver = keyResolver,
            rateLimiter = rateLimiter,
            clock = clock,
        )

    /**
     * Lets Spring Security own placement of the rate-limit filter.
     */
    @Bean
    fun rateLimitFilterRegistration(
        filter: RateLimitFilter,
    ): FilterRegistrationBean<RateLimitFilter> =
        FilterRegistrationBean(filter).apply {
            isEnabled = false
            order = Ordered.LOWEST_PRECEDENCE
        }

    private fun standaloneOrSentinelBackend(
        properties: DataRedisProperties,
    ): RateLimitRedisBackend {
        val client = RedisClient.create(redisUri(properties))
        val connection = client.connect(stringBytesCodec())
        val manager =
            Bucket4jLettuce
                .casBasedBuilder(connection)
                .expirationAfterWrite(expirationAfterWrite())
                .build()
        return LettuceRateLimitRedisBackend(manager, connection, client)
    }

    private fun clusterBackend(
        properties: DataRedisProperties,
        nodes: List<String>,
    ): RateLimitRedisBackend {
        val client =
            RedisClusterClient.create(
                nodes.map { node -> redisUri(properties, node) },
            )
        val connection = client.connect(stringBytesCodec())
        val manager =
            Bucket4jLettuce
                .casBasedBuilder(connection)
                .expirationAfterWrite(expirationAfterWrite())
                .build()
        return LettuceRateLimitRedisBackend(manager, connection, client)
    }

    private fun redisUri(properties: DataRedisProperties): RedisURI {
        val url = properties.url
        if (!url.isNullOrBlank()) {
            return RedisURI.create(url)
        }
        val sentinel = properties.sentinel
        val sentinelMaster = sentinel?.master
        val sentinelNodes = sentinel?.nodes.orEmpty()
        if (!sentinelMaster.isNullOrBlank() && sentinelNodes.isNotEmpty()) {
            val builder = RedisURI.builder().withSentinelMasterId(sentinelMaster)
            sentinelNodes.forEach { node ->
                val endpoint = parseNode(node)
                builder.withSentinel(endpoint.host, endpoint.port)
            }
            return builder.applySharedSettings(properties).build()
        }
        val builder =
            RedisURI
                .builder()
                .withHost(properties.host)
                .withPort(properties.port)
        return builder.applySharedSettings(properties).build()
    }

    private fun redisUri(
        properties: DataRedisProperties,
        node: String,
    ): RedisURI {
        val endpoint = parseNode(node)
        return RedisURI
            .builder()
            .withHost(endpoint.host)
            .withPort(endpoint.port)
            .applySharedSettings(properties)
            .build()
    }

    private fun Builder.applySharedSettings(properties: DataRedisProperties): Builder {
        withDatabase(properties.database)
        withTimeout(properties.timeout ?: DEFAULT_REDIS_TIMEOUT)
        withSsl(properties.ssl.isEnabled)
        val password = properties.password
        if (!properties.username.isNullOrBlank() && !password.isNullOrBlank()) {
            withAuthentication(properties.username, password.toCharArray())
        } else if (!password.isNullOrBlank()) {
            withPassword(password)
        }
        return this
    }

    private fun parseNode(node: String): RedisEndpoint {
        val parts = node.split(":", limit = NODE_PARTS)
        require(parts.size == NODE_PARTS) { "Redis node must be formatted as host:port" }
        return RedisEndpoint(parts[0], parts[1].toInt())
    }

    private fun stringBytesCodec(): RedisCodec<String, ByteArray> =
        RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)

    private fun expirationAfterWrite(): ExpirationAfterWriteStrategy =
        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
            BUCKET_STATE_TTL_AFTER_FULL_REFILL,
        )

    private data class RedisEndpoint(
        val host: String,
        val port: Int,
    )

    private companion object {
        private const val NODE_PARTS = 2
        private val DEFAULT_REDIS_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val BUCKET_STATE_TTL_AFTER_FULL_REFILL: Duration = Duration.ofMinutes(5)
    }
}
