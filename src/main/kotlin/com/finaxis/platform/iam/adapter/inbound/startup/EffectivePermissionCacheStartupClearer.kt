package com.finaxis.platform.iam.adapter.inbound.startup

import com.finaxis.platform.iam.adapter.outbound.cache.EffectivePermissionCacheNamespaceSweeper
import com.finaxis.platform.iam.application.authorization.PermissionCacheInvalidator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

/**
 * Clears the `iam.effective-permissions` cache on every application start.
 *
 * The cache has no time-to-live and lives in Redis, so it outlives a deployment. A migration that
 * changes who holds a permission (`V19` copies `branch.create` grants to `branch.update`) writes
 * straight to the tables and bypasses [PermissionCacheInvalidator], so an entry cached before the
 * deploy would keep answering with the old set until something evicted it. Two things prevent
 * that, and this is the second: the cache key is namespaced by the applied schema version (see
 * `EffectivePermissionCacheNamespace`), so a new instance never reads what an older release
 * wrote; and it clears here, after every singleton exists, which covers a restart on the *same*
 * schema version after an out-of-band SQL change. Flyway has finished by then (the migration runs
 * when the data source is initialised, before the singletons it feeds), and the web server only
 * starts accepting traffic after singleton initialisation, so no request can read an entry this
 * call is about to drop.
 *
 * The clear drops this instance's namespace through the cache API and then sweeps the namespaces
 * of every other schema version, so earlier versions' entries do not accumulate.
 *
 * If Redis is unreachable the clear fails with a [DataAccessException]; that is logged as a
 * warning and the boot continues, because the cache is an optimisation and an unreachable cache
 * cannot serve a stale entry either. The residual case is a Redis that comes back later still
 * holding entries of this very schema version written before an out-of-band SQL change, which the
 * next start (or a role or assignment change) clears.
 */
@Component
class EffectivePermissionCacheStartupClearer(
    private val invalidator: PermissionCacheInvalidator,
    private val sweeper: EffectivePermissionCacheNamespaceSweeper,
) : SmartInitializingSingleton {
    /** Drops every cached effective-permission set, in every schema version's namespace. */
    override fun afterSingletonsInstantiated() {
        try {
            invalidator.clearAll()
            val swept = sweeper.sweepAllVersions()
            logger.info(
                "Cleared the iam.effective-permissions cache after startup migrations " +
                    "({} keys swept across all schema versions).",
                swept,
            )
        } catch (failure: DataAccessException) {
            logger.warn(
                "Could not clear the iam.effective-permissions cache at startup; entries cached " +
                    "before this deployment may still be served: {}",
                failure.message,
            )
        }
    }

    private companion object {
        private val logger =
            LoggerFactory.getLogger(EffectivePermissionCacheStartupClearer::class.java)
    }
}
