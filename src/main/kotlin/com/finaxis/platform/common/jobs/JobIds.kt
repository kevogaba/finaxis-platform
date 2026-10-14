package com.finaxis.platform.common.jobs

import java.util.UUID

/**
 * The JobRunr job id of a dispatch key. Every enqueuer and requeuer of a dispatch's job derives it
 * here, so a repeated approval finds the job its first attempt enqueued and never starts a second
 * one under a new id. The formula must not change: existing jobs are stored under it.
 */
fun deterministicJobId(dispatchKey: String): UUID =
    UUID.nameUUIDFromBytes(dispatchKey.toByteArray())
