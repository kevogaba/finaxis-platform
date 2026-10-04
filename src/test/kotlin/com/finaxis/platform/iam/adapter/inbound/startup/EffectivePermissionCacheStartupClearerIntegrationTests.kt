package com.finaxis.platform.iam.adapter.inbound.startup

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A permission migration writes the tables directly, so a Redis entry cached before it must not
 * outlive the next start. This warms the real `iam.effective-permissions` cache with a pre-V19
 * answer, re-runs the V19 migration file to give the membership `branch.update`, and shows that
 * the start-up clear is what makes the next resolution answer from the migrated rows.
 *
 * The database work is in a transaction that is always rolled back; the one Redis entry it
 * creates is removed by the clear under test.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class EffectivePermissionCacheStartupClearerIntegrationTests(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val resolver: EffectivePermissionResolver,
    private val cacheManager: CacheManager,
    private val clearer: EffectivePermissionCacheStartupClearer,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @Test
    fun `a pre-warmed entry is gone after the clear and the next answer follows the database`() {
        transactionTemplate.execute { transaction ->
            try {
                val membershipId = membershipWithDirectBranchCreate()

                assertEquals(setOf("branch.create"), resolver.effectivePermissions(membershipId))
                // The Redis cache writes asynchronously: wait for the entry to be readable so
                // neither the stale read nor the clear below races the write.
                val cache = cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)!!
                val key = EffectivePermissionResolver.cacheKey(membershipId, null)
                await().atMost(Duration.ofSeconds(AWAIT_SECONDS)).until { cache.get(key) != null }

                // Flyway already ran V19; re-running it (idempotent) copies the override that
                // this membership gained after it, which is what a real migration does to rows
                // that were cached before it. V22 made permission.kind and grant_scope NOT NULL
                // and V19's frozen insert predates them, so they are relaxed for the re-run; the
                // transaction is rolled back below, which restores them.
                jdbcTemplate.execute(
                    "ALTER TABLE permission ALTER COLUMN kind DROP NOT NULL, " +
                        "ALTER COLUMN grant_scope DROP NOT NULL",
                )
                jdbcTemplate.execute(
                    ClassPathResource("db/migration/V19__branch_update_permission.sql")
                        .inputStream
                        .use { it.readAllBytes().decodeToString() },
                )

                assertEquals(
                    setOf("branch.create"),
                    resolver.effectivePermissions(membershipId),
                    "without the clear the cache still serves the pre-migration answer",
                )

                clearer.afterSingletonsInstantiated()

                val fresh = resolver.effectivePermissions(membershipId)
                assertTrue("branch.update" in fresh, "after the clear the answer follows the rows")
            } finally {
                clearer.afterSingletonsInstantiated()
                transaction.setRollbackOnly()
            }
        }
    }

    private fun membershipWithDirectBranchCreate(): UUID {
        val organisationId = uuidV7()
        val userId = uuidV7()
        val membershipId = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, activated_at, created_at, updated_at
            ) VALUES (?, ?, 'Clearer Test', 'KE', 'KES', 'UTC', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            organisationId,
            "CLR-" + organisationId.toString().takeLast(TENANT_CODE_SUFFIX),
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_account (id, username, email, display_name, status, created_at,
                                      updated_at)
            VALUES (?, ?, ?, 'Clearer', 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            userId,
            "clr-$userId",
            "clr-$userId@example.test",
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_organisation_membership (
                id, organisation_id, user_id, membership_status, membership_type, joined_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'ACTIVE', 'STAFF', NOW(), NOW(), NOW())
            """.trimIndent(),
            membershipId,
            organisationId,
            userId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO membership_permission (
                organisation_id, membership_id, permission_id, effect, granted_at, created_at,
                updated_at
            )
            SELECT ?, ?, id, 'ALLOW', NOW(), NOW(), NOW()
            FROM permission WHERE permission_code = 'branch.create'
            """.trimIndent(),
            organisationId,
            membershipId,
        )
        return membershipId
    }

    private companion object {
        const val TENANT_CODE_SUFFIX = 12
        const val AWAIT_SECONDS = 5L
    }
}
