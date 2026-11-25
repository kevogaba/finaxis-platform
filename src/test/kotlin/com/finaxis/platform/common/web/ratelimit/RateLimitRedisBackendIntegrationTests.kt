package com.finaxis.platform.common.web.ratelimit

import com.finaxis.platform.TestContainerImages
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The rate limiter's Redis backend on a real Redis, on a client built from the configuration
 * Spring Boot derives from `spring.data.redis.*`: buckets live in Redis, the command timeout is
 * the limiter's own `finaxis.rate-limit.redis-timeout` while Boot's clients keep
 * `spring.data.redis.timeout`, the limiter outlives the context being stopped and started (which
 * disposes and recreates Boot's own client), and closing it leaves Boot's client running.
 */
class RateLimitRedisBackendIntegrationTests {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration::class.java))
            .withUserConfiguration(Backend::class.java)
            .withPropertyValues(
                "spring.data.redis.host=${redis.host}",
                "spring.data.redis.port=${redis.getMappedPort(REDIS_PORT)}",
                "spring.data.redis.timeout=7s",
                "finaxis.rate-limit.redis-timeout=3s",
            )

    @Test
    fun `buckets are shared through Redis`() {
        runner.run { context ->
            val backend = context.getBean(RateLimitConfiguration.RateLimitRedisBackend::class.java)
            val policy = RateLimitPolicy(capacity = 2, refillPeriod = Duration.ofHours(1))
            val key = "rate-limit-backend-test"

            val first = Bucket4jRateLimiterService(backend.proxyManager)
            val second = Bucket4jRateLimiterService(backend.proxyManager)
            assertTrue(first.tryConsume(key, policy).allowed)
            assertTrue(second.tryConsume(key, policy).allowed)
            assertFalse(first.tryConsume(key, policy).allowed)

            val factory = context.getBean(LettuceConnectionFactory::class.java)
            factory.connection.use { connection ->
                assertTrue(connection.keyCommands().exists(key.toByteArray()) == true)
            }
        }
    }

    @Test
    fun `the limiter has its own command timeout and Boot's clients keep theirs`() {
        runner.run { context ->
            val backend =
                context.getBean(RateLimitConfiguration.RateLimitRedisBackend::class.java)
                    as RateLimitConfiguration.LettuceRateLimitRedisBackend
            val boot = context.getBean(LettuceConnectionFactory::class.java)

            assertEquals(Duration.ofSeconds(3), backend.commandTimeout)
            assertEquals(Duration.ofSeconds(7), boot.clientConfiguration.commandTimeout)
        }
    }

    @Test
    fun `the limiter keeps working after the context is stopped and started`() {
        runner.run { context ->
            val backend = context.getBean(RateLimitConfiguration.RateLimitRedisBackend::class.java)
            val limiter = Bucket4jRateLimiterService(backend.proxyManager)
            val policy = RateLimitPolicy(capacity = 5, refillPeriod = Duration.ofHours(1))
            val boot = context.getBean(LettuceConnectionFactory::class.java)
            val clientBefore = boot.nativeClient

            // What Spring's test context cache does to an idle context, and what disposed the
            // connection the limiter used to hold on Boot's own client.
            context.getSourceApplicationContext(ConfigurableApplicationContext::class.java).apply {
                stop()
                start()
            }

            assertNotSame(clientBefore, boot.nativeClient)
            assertTrue(limiter.tryConsume("rate-limit-restart-test", policy).allowed)
        }
    }

    @Test
    fun `closing the backend leaves Boot's client running`() {
        runner.run { context ->
            context.getBean(RateLimitConfiguration.RateLimitRedisBackend::class.java).destroy()

            context.getBean(LettuceConnectionFactory::class.java).connection.use { connection ->
                assertEquals("PONG", connection.ping())
            }
        }
    }

    /** The backend bean alone, without the filter and its collaborators. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties::class)
    class Backend {
        @Bean
        fun rateLimitRedisBackend(
            connectionFactory: LettuceConnectionFactory,
            properties: RateLimitProperties,
        ) = RateLimitConfiguration().rateLimitRedisBackend(connectionFactory, properties)
    }

    companion object {
        private const val REDIS_PORT = 6379

        private val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(TestContainerImages.REDIS))
                .withExposedPorts(REDIS_PORT)
                .apply { start() }

        @JvmStatic
        @AfterAll
        fun stopRedis() = redis.stop()
    }
}
