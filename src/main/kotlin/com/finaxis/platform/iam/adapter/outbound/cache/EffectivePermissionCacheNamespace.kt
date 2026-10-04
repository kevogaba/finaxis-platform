package com.finaxis.platform.iam.adapter.outbound.cache

import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import org.flywaydb.core.Flyway
import org.springframework.data.redis.cache.RedisCacheConfiguration

/**
 * How the Redis `iam.effective-permissions` cache is namespaced by schema version.
 *
 * A Redis key for this cache is `iam.effective-permissions:v<N>::<membership>:<branch>`, where
 * `N` is the highest Flyway version applied when the instance started. An instance of the
 * previous release (older `N`, or the unversioned `iam.effective-permissions::` of a release that
 * predates this scheme) reads and writes a different namespace from a new one, so a permission set
 * an old instance resolved before a migration committed can never be read by an instance that
 * started after it. The cache has no time-to-live, so without this separation such an entry would
 * stay live indefinitely.
 */
object EffectivePermissionCacheNamespace {
    /** The version segment used when no migration is known (Flyway absent or nothing applied). */
    const val UNVERSIONED = "none"

    /** Redis glob matching every schema version's namespace of this cache, and the legacy one. */
    const val ALL_VERSIONS_PATTERN = "${EffectivePermissionResolver.CACHE_NAME}:*"

    /** The key prefix of the permission cache for [schemaVersion]. */
    fun prefix(schemaVersion: String): String =
        "${EffectivePermissionResolver.CACHE_NAME}:v$schemaVersion::"

    /** Returns [base] with this cache's key prefix computed from [schemaVersion]. */
    fun namespaced(
        base: RedisCacheConfiguration,
        schemaVersion: String,
    ): RedisCacheConfiguration = base.computePrefixWith { prefix(schemaVersion) }

    /**
     * The highest migration version Flyway has applied, or [UNVERSIONED]. Only meaningful once
     * migrations have run, which the caller must ensure.
     */
    fun appliedSchemaVersion(flyway: Flyway?): String =
        flyway
            ?.info()
            ?.current()
            ?.version
            ?.version ?: UNVERSIONED
}
