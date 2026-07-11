package com.finaxis.platform.common.web.ratelimit

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.BucketExceptions.BucketExecutionException
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.lettuce.core.RedisException
import java.time.Duration

/**
 * Bucket4j implementation backed by a Redis proxy manager.
 */
open class Bucket4jRateLimiterService(
    private val proxyManager: ProxyManager<String>,
) : RateLimiterService {
    override fun tryConsume(
        key: String,
        policy: RateLimitPolicy,
    ): RateLimitDecision =
        try {
            consume(key, policy)
        } catch (ex: BucketExecutionException) {
            throw RateLimitUnavailableException(ex)
        } catch (ex: RedisException) {
            throw RateLimitUnavailableException(ex)
        }

    private fun consume(
        key: String,
        policy: RateLimitPolicy,
    ): RateLimitDecision {
        val bucket =
            proxyManager.getProxy(key) {
                BucketConfiguration
                    .builder()
                    .addLimit(
                        Bandwidth
                            .builder()
                            .capacity(policy.capacity)
                            .refillGreedy(policy.refillTokens, policy.refillPeriod)
                            .build(),
                    ).build()
            }
        val probe = bucket.tryConsumeAndReturnRemaining(REQUEST_TOKEN)
        return RateLimitDecision(
            allowed = probe.isConsumed,
            limit = policy.capacity,
            remaining = probe.remainingTokens.coerceAtLeast(0),
            retryAfter = Duration.ofNanos(probe.nanosToWaitForRefill.coerceAtLeast(0)),
            resetAfter = Duration.ofNanos(probe.nanosToWaitForReset.coerceAtLeast(0)),
        )
    }

    private companion object {
        private const val REQUEST_TOKEN = 1L
    }
}
