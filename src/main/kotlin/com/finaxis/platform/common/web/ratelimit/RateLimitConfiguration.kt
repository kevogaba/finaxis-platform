package com.finaxis.platform.common.web.ratelimit

import com.finaxis.platform.common.web.api.ApiProblemWriter
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import org.springframework.core.Ordered
import org.springframework.data.redis.connection.RedisConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
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
     * Bucket4j's distributed bucket state, on a Lettuce client of its own built from the Redis
     * configuration Spring Boot derived from `spring.data.redis.*` (standalone, Sentinel or
     * cluster, with its database, credentials, TLS and client resources), so that file is its
     * whole connection configuration. Only the command timeout is the limiter's own,
     * `finaxis.rate-limit.redis-timeout` (5s), set on its connection: every API request waits on
     * it, so a Redis that stops answering must fail open sooner than the cache's and the
     * session's `spring.data.redis.timeout`, and Spring Boot has no property for a second
     * client's timeout.
     *
     * Not Boot's client itself: Boot's [LettuceConnectionFactory] disposes its client when the
     * context stops and creates another when it starts again (Spring's test context cache does so
     * to every idle context), which would close a connection held on the old one and leave the
     * limiter failing open. Bucket4j needs a raw Lettuce connection with a byte-array codec, which
     * Spring Data Redis's own connections are not, so the limiter keeps this one open.
     */
    @Bean
    @ConditionalOnMissingBean
    fun rateLimitRedisBackend(
        connectionFactory: LettuceConnectionFactory,
        properties: RateLimitProperties,
    ): RateLimitRedisBackend {
        val factory =
            LettuceConnectionFactory(
                redisConfiguration(connectionFactory),
                connectionFactory.clientConfiguration,
            ).apply { afterPropertiesSet() }
        return when (
            val client =
                checkNotNull(factory.nativeClient) {
                    "The rate limiter's Lettuce connection factory has not created its client"
                }
        ) {
            is RedisClusterClient -> {
                val connection = client.connect(stringBytesCodec())
                connection.setTimeout(properties.redisTimeout)
                LettuceRateLimitRedisBackend(proxyManager(connection), connection, factory)
            }

            is RedisClient -> {
                val connection = client.connect(stringBytesCodec())
                connection.setTimeout(properties.redisTimeout)
                LettuceRateLimitRedisBackend(proxyManager(connection), connection, factory)
            }

            else -> {
                factory.destroy()
                error("Unsupported Lettuce client for rate limiting: ${client.javaClass.name}")
            }
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
     * The rate limiter's Redis connection and the factory whose client opened it, both closed with
     * the application context. Client resources that came with Boot's configuration stay Boot's.
     */
    open class LettuceRateLimitRedisBackend(
        override val proxyManager: ProxyManager<String>,
        private val connection: StatefulConnection<String, ByteArray>,
        private val factory: DisposableBean,
    ) : RateLimitRedisBackend {
        /** The connection's command timeout, `finaxis.rate-limit.redis-timeout`. */
        val commandTimeout: Duration
            get() = connection.timeout

        override fun destroy() {
            connection.close()
            factory.destroy()
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
     *
     * Infrastructure, so Spring Modulith's module observability leaves it unproxied: a class-based
     * proxy of a [org.springframework.web.filter.OncePerRequestFilter] cannot intercept its final
     * `doFilter` and `init`, so it observed nothing and ran `doFilter` on the proxy instance.
     */
    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun rateLimitFilter(
        properties: RateLimitProperties,
        keyResolver: RateLimitKeyResolver,
        rateLimiter: RateLimiterService,
        clock: Clock,
        problemWriter: ApiProblemWriter,
    ): RateLimitFilter =
        RateLimitFilter(
            properties = properties,
            keyResolver = keyResolver,
            rateLimiter = rateLimiter,
            clock = clock,
            problemWriter = problemWriter,
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

    private fun proxyManager(
        connection: StatefulRedisConnection<String, ByteArray>,
    ): ProxyManager<String> =
        Bucket4jLettuce
            .casBasedBuilder(connection)
            .expirationAfterWrite(expirationAfterWrite())
            .build()

    private fun proxyManager(
        connection: StatefulRedisClusterConnection<String, ByteArray>,
    ): ProxyManager<String> =
        Bucket4jLettuce
            .casBasedBuilder(connection)
            .expirationAfterWrite(expirationAfterWrite())
            .build()

    /** The topology Boot's factory connects to; a static master/replica one is not supported. */
    private fun redisConfiguration(factory: LettuceConnectionFactory): RedisConfiguration =
        when {
            factory.isClusterAware -> checkNotNull(factory.clusterConfiguration)
            factory.isRedisSentinelAware -> checkNotNull(factory.sentinelConfiguration)
            else -> factory.standaloneConfiguration
        }

    private fun stringBytesCodec(): RedisCodec<String, ByteArray> =
        RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)

    private fun expirationAfterWrite(): ExpirationAfterWriteStrategy =
        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
            BUCKET_STATE_TTL_AFTER_FULL_REFILL,
        )

    private companion object {
        private val BUCKET_STATE_TTL_AFTER_FULL_REFILL: Duration = Duration.ofMinutes(5)
    }
}
