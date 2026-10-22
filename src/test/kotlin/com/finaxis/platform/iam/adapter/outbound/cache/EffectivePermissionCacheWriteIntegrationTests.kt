package com.finaxis.platform.iam.adapter.outbound.cache

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestConstructor
import java.time.Duration
import kotlin.test.assertTrue

/**
 * How the permission cache is written, proved against a real Redis.
 *
 * A write must be visible, and ordered before whatever follows it, once `put` returns: otherwise a
 * revoke that evicts the cache can be undone by an earlier resolve's write still in flight, and the
 * revoked permission set stays readable. Every entry must also expire, so the one race no write
 * ordering can close (a resolve that read the database before a revoke and writes after the
 * eviction) is bounded in time.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class EffectivePermissionCacheWriteIntegrationTests(
    private val cacheManager: CacheManager,
    private val redis: StringRedisTemplate,
    private val environment: Environment,
) {
    private val cache = cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)!!

    @AfterEach
    fun removeEverythingThisTestWrote() {
        cache.invalidate()
    }

    @Test
    fun `a write that precedes an invalidation does not reappear after it`() {
        val run = uuidV7()

        repeat(WRITES) { index ->
            cache.put("$run-$index:none", setOf("branch.view"))
            cache.invalidate()
        }

        // An asynchronous write that was still in flight lands after the invalidation, so the
        // entries have to stay absent for a while before the cache can be called empty.
        await()
            .during(Duration.ofMillis(SETTLE_MILLIS))
            .atMost(Duration.ofSeconds(AWAIT_SECONDS))
            .until { redis.keys("*$run-*").isEmpty() }
    }

    @Test
    fun `an entry expires after the configured time-to-live`() {
        val timeToLive =
            environment.getRequiredProperty(TIME_TO_LIVE_PROPERTY, Duration::class.java)
        val id = uuidV7()

        cache.put("$id:none", setOf("branch.view"))

        val redisKey = checkNotNull(redis.keys("*$id:none").singleOrNull())
        val remaining = checkNotNull(redis.getExpire(redisKey))
        assertTrue(remaining > 0, "the entry has no expiry (Redis reports $remaining)")
        assertTrue(
            remaining <= timeToLive.seconds,
            "the entry outlives the configured $timeToLive (Redis reports ${remaining}s)",
        )
    }

    private companion object {
        const val WRITES = 300
        const val SETTLE_MILLIS = 300L
        const val AWAIT_SECONDS = 5L
        const val TIME_TO_LIVE_PROPERTY = "spring.cache.redis.time-to-live"
    }
}
