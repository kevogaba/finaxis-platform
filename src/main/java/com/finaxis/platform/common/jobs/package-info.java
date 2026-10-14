/**
 * Shared JobRunr job-execution helpers, exposed for cross-module reuse: per-job step idempotency
 * across retries, the requeue port for a FAILED job ({@code FailedJobRequeuer}) with its JobRunr
 * adapter, and the deterministic job id of a dispatch key ({@code deterministicJobId}).
 */
@org.springframework.modulith.NamedInterface("jobs")
package com.finaxis.platform.common.jobs;
