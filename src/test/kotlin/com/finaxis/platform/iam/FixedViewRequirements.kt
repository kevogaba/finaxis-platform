package com.finaxis.platform.iam

import com.finaxis.platform.iam.application.port.outbound.PermissionViewRequirementQueries

/**
 * In-memory [PermissionViewRequirementQueries] for unit tests: the pairing it is built with, or
 * none at all, so a test that is not about ADR 0030 keeps checking one code at a time. [reads]
 * counts the reads, to prove the pairing is read once per request.
 */
internal class FixedViewRequirements(
    private val pairing: Map<String, List<String>> = emptyMap(),
) : PermissionViewRequirementQueries {
    var reads: Int = 0
        private set

    override fun requiredViewCodesByPermission(): Map<String, List<String>> {
        reads++
        return pairing
    }
}
