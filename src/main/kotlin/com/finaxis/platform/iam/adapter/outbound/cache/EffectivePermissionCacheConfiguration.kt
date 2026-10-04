package com.finaxis.platform.iam.adapter.outbound.cache

import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Versions the Redis namespace of the `iam.effective-permissions` cache by the applied schema
 * version; see [EffectivePermissionCacheNamespace]. Applies only to a [RedisCacheManager][
 * org.springframework.data.redis.cache.RedisCacheManager] built by Spring Boot: a context that
 * supplies its own cache manager (Caffeine, a concurrent map, no-op) never calls the customizer
 * and is unaffected.
 */
@Configuration(proxyBeanMethods = false)
class EffectivePermissionCacheConfiguration {
    /**
     * Gives the permission cache a key prefix that carries the applied schema version. The
     * version is read when the cache manager is built, after [migrationInitializer] has forced
     * Flyway to finish, so it is the version this instance will serve, not the one before the
     * migration.
     */
    @Bean
    fun effectivePermissionCacheNamespaceCustomizer(
        flyway: ObjectProvider<Flyway>,
        migrationInitializer: ObjectProvider<FlywayMigrationInitializer>,
    ): RedisCacheManagerBuilderCustomizer =
        RedisCacheManagerBuilderCustomizer { builder ->
            migrationInitializer.ifAvailable
            val schemaVersion =
                EffectivePermissionCacheNamespace.appliedSchemaVersion(flyway.ifAvailable)
            builder.withCacheConfiguration(
                EffectivePermissionResolver.CACHE_NAME,
                EffectivePermissionCacheNamespace.namespaced(
                    builder.cacheDefaults(),
                    schemaVersion,
                ),
            )
            logger.info(
                "The iam.effective-permissions cache uses the namespace {}.",
                EffectivePermissionCacheNamespace.prefix(schemaVersion),
            )
        }

    private companion object {
        private val logger =
            LoggerFactory.getLogger(EffectivePermissionCacheConfiguration::class.java)
    }
}
