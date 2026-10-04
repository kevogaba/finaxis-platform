package com.finaxis.platform.iam.adapter.outbound.cache

import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationInfoService
import org.flywaydb.core.api.MigrationVersion
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EffectivePermissionCacheNamespaceTests {
    private val cacheName = EffectivePermissionResolver.CACHE_NAME

    @Test
    fun `the prefix names the cache and the applied schema version`() {
        assertEquals(
            "iam.effective-permissions:v19::",
            EffectivePermissionCacheNamespace.prefix("19"),
        )
    }

    @Test
    fun `different schema versions never share a prefix, including 9 and 10`() {
        assertNotEquals(
            EffectivePermissionCacheNamespace.prefix("9"),
            EffectivePermissionCacheNamespace.prefix("10"),
        )
        assertNotEquals(
            EffectivePermissionCacheNamespace.prefix("19"),
            EffectivePermissionCacheNamespace.prefix("20"),
        )
    }

    @Test
    fun `the all-versions pattern matches every version's prefix and nothing else`() {
        val pattern =
            Regex(EffectivePermissionCacheNamespace.ALL_VERSIONS_PATTERN.replace("*", ".*"))

        assertEquals(true, pattern.matches(EffectivePermissionCacheNamespace.prefix("19") + "k"))
        assertEquals(true, pattern.matches("$cacheName::k"))
        assertEquals(false, pattern.matches("iam.other:v19::k"))
        assertEquals(false, pattern.matches("finaxis:platform:sessions:abc"))
    }

    @Test
    fun `the schema version is the highest applied Flyway version`() {
        assertEquals("19", EffectivePermissionCacheNamespace.appliedSchemaVersion(flywayAt("19")))
        assertEquals("20", EffectivePermissionCacheNamespace.appliedSchemaVersion(flywayAt("20")))
    }

    @Test
    fun `no Flyway or nothing applied falls back to a fixed unversioned namespace`() {
        assertEquals("none", EffectivePermissionCacheNamespace.appliedSchemaVersion(null))
        assertEquals("none", EffectivePermissionCacheNamespace.appliedSchemaVersion(flywayAt(null)))
    }

    @Test
    fun `the customizer namespaces only the permission cache`() {
        val builder = RedisCacheManager.builder(mock<RedisConnectionFactory>())
        val initializer = mock<ObjectProvider<FlywayMigrationInitializer>>()

        EffectivePermissionCacheConfiguration()
            .effectivePermissionCacheNamespaceCustomizer(provider(flywayAt("19")), initializer)
            .customize(builder)

        val configured = builder.getCacheConfigurationFor(cacheName).get()
        assertEquals("iam.effective-permissions:v19::", configured.getKeyPrefixFor(cacheName))
        assertEquals("other::", builder.cacheDefaults().getKeyPrefixFor("other"))
        verify(initializer).ifAvailable
    }

    @Test
    fun `the customizer keeps the application defaults it was handed`() {
        val builder = RedisCacheManager.builder(mock<RedisConnectionFactory>())
        builder.cacheDefaults(builder.cacheDefaults().disableCachingNullValues())

        EffectivePermissionCacheConfiguration()
            .effectivePermissionCacheNamespaceCustomizer(
                provider(flywayAt("19")),
                mock<ObjectProvider<FlywayMigrationInitializer>>(),
            ).customize(builder)

        val configured = builder.getCacheConfigurationFor(cacheName).get()
        assertEquals(false, configured.allowCacheNullValues)
        assertEquals(
            builder.cacheDefaults().valueSerializationPair,
            configured.valueSerializationPair,
        )
    }

    private fun provider(flyway: Flyway): ObjectProvider<Flyway> {
        val provider = mock<ObjectProvider<Flyway>>()
        whenever(provider.ifAvailable).thenReturn(flyway)
        return provider
    }

    private fun flywayAt(version: String?): Flyway {
        val info = mock<MigrationInfoService>()
        if (version != null) {
            val current = mock<MigrationInfo>()
            whenever(current.version).thenReturn(MigrationVersion.fromVersion(version))
            whenever(info.current()).thenReturn(current)
        }
        val flyway = mock<Flyway>()
        whenever(flyway.info()).thenReturn(info)
        return flyway
    }
}
