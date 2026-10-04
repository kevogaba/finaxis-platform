package com.finaxis.platform.iam.adapter.outbound.cache

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import org.awaitility.Awaitility.await
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestConstructor
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The permission cache is namespaced by the applied schema version, proved against a real Redis.
 *
 * An instance of the previous release can resolve a permission set before a migration commits and
 * write it after a newer instance started. With one shared namespace that stale set would be live
 * for a cache with no time-to-live; with a namespace per schema version the newer instance never
 * reads it.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class EffectivePermissionCacheNamespaceIntegrationTests(
    private val cacheManager: CacheManager,
    private val flyway: Flyway,
    private val redis: StringRedisTemplate,
    private val connectionFactory: RedisConnectionFactory,
    private val sweeper: EffectivePermissionCacheNamespaceSweeper,
) {
    private val cacheName = EffectivePermissionResolver.CACHE_NAME

    @AfterEach
    fun removeEverythingThisTestWrote() {
        sweeper.sweepAllVersions()
    }

    @Test
    fun `the application cache is namespaced by the highest applied migration version`() {
        val latest =
            flyway
                .info()
                .current()
                .version.version
        cacheManager.putAndAwait("membership-1:none", setOf("branch.create"))

        val keys = redis.keys(EffectivePermissionCacheNamespace.ALL_VERSIONS_PATTERN)

        assertTrue(
            "iam.effective-permissions:v$latest::membership-1:none" in keys,
            "expected a v$latest key, found $keys",
        )
    }

    @Test
    fun `an entry written under the old schema version is never read by the new one`() {
        val oldInstance = managerAt("18")
        val newInstance = managerAt("19")
        val key = "membership-2:none"

        // The previous release resolved before the migration committed and writes afterwards.
        oldInstance.putAndAwait(key, setOf("branch.create"))

        assertNull(newInstance.getCache(cacheName)!!.get(key), "the new instance must miss")
        assertNotNull(oldInstance.getCache(cacheName)!!.get(key), "the old one keeps its own")

        newInstance.putAndAwait(key, setOf("branch.create", "branch.update"))
        assertEquals(
            setOf("branch.create"),
            oldInstance.getCache(cacheName)!!.get(key)?.get(),
            "the new instance's entry does not leak back into the old namespace",
        )
        assertEquals(
            setOf("branch.create", "branch.update"),
            newInstance.getCache(cacheName)!!.get(key)?.get(),
        )
    }

    @Test
    fun `invalidating a namespace removes only that namespace's entries`() {
        val oldInstance = managerAt("18")
        val newInstance = managerAt("19")
        oldInstance.putAndAwait("a:none", setOf("x"))
        newInstance.putAndAwait("a:none", setOf("y"))

        newInstance.getCache(cacheName)!!.invalidate()

        assertNull(newInstance.getCache(cacheName)!!.get("a:none"))
        assertNotNull(oldInstance.getCache(cacheName)!!.get("a:none"))
    }

    @Test
    fun `the start-up sweep removes every schema version's namespace and the unversioned one`() {
        managerAt("18").putAndAwait("a:none", setOf("x"))
        managerAt("19").putAndAwait("a:none", setOf("y"))
        redis.opsForValue().set("$cacheName::legacy", "pre-versioning entry")
        redis.opsForValue().set("iam.unrelated:v19::keep", "other cache")

        val removed = sweeper.sweepAllVersions()

        assertTrue(removed >= 3, "removed $removed")
        assertEquals(emptySet(), redis.keys(EffectivePermissionCacheNamespace.ALL_VERSIONS_PATTERN))
        assertEquals("other cache", redis.opsForValue().get("iam.unrelated:v19::keep"))
        redis.delete("iam.unrelated:v19::keep")
    }

    /**
     * The Redis cache writes asynchronously, so a `put` can land after the next statement; wait
     * until the entry is readable so a following clear or read is not racing the write.
     */
    private fun CacheManager.putAndAwait(
        key: String,
        value: Set<String>,
    ) {
        val cache = getCache(cacheName)!!
        cache.put(key, value)
        await().atMost(Duration.ofSeconds(AWAIT_SECONDS)).until { cache.get(key) != null }
    }

    private fun managerAt(schemaVersion: String): RedisCacheManager {
        val configured =
            EffectivePermissionCacheNamespace.namespaced(
                RedisCacheConfiguration.defaultCacheConfig(javaClass.classLoader),
                schemaVersion,
            )
        return RedisCacheManager
            .builder(connectionFactory)
            .withCacheConfiguration(cacheName, configured)
            .build()
            .also { it.initializeCaches() }
    }

    private companion object {
        const val AWAIT_SECONDS = 5L
    }
}
