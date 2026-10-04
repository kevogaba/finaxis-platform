package com.finaxis.platform.iam.adapter.outbound.cache

import org.springframework.beans.factory.ObjectProvider
import org.springframework.cache.CacheManager
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Deletes the Redis keys of every schema version's `iam.effective-permissions` namespace.
 *
 * Eviction through the Spring cache API reaches only the namespace of the running instance. The
 * start-up clear also needs to remove the namespaces of earlier schema versions, which no
 * instance reads once its release is replaced and which, with no time-to-live, would otherwise
 * accumulate one per migration. Deleting all of them is safe: an entry is only ever a cache of
 * rows, so an instance of an older release still running during a rolling deploy merely
 * repopulates its own namespace from the database. Does nothing unless the application cache is a
 * [RedisCacheManager], so it never touches Redis for a Caffeine, concurrent-map or no-op cache.
 */
@Component
class EffectivePermissionCacheNamespaceSweeper(
    private val cacheManager: CacheManager,
    private val redis: ObjectProvider<StringRedisTemplate>,
) {
    /**
     * Deletes every key matching [EffectivePermissionCacheNamespace.ALL_VERSIONS_PATTERN] and
     * returns how many were removed. Propagates Redis access failures to the caller.
     */
    fun sweepAllVersions(): Int {
        val template = redis.ifAvailable
        if (cacheManager !is RedisCacheManager || template == null) {
            return 0
        }
        val options =
            ScanOptions
                .scanOptions()
                .match(EffectivePermissionCacheNamespace.ALL_VERSIONS_PATTERN)
                .count(SCAN_BATCH)
                .build()
        // Collected before deleting so the cursor is never advanced over a mutating keyspace.
        val keys = template.scan(options).use { cursor -> cursor.asSequence().toList() }
        keys.chunked(SCAN_BATCH.toInt()).forEach { template.unlink(it) }
        return keys.size
    }

    private companion object {
        private const val SCAN_BATCH = 500L
    }
}
