package com.finaxis.platform.iam.application.port.outbound

/**
 * Outbound application port reading the view requirements of the permission catalogue
 * (`permission_view_requirement`, ADR 0030, decision 8): which view codes a mutation code
 * implies. The pairing is reference data changed only by forward migration.
 */
interface PermissionViewRequirementQueries {
    /**
     * Reads every pairing as a map from a mutation code to the codes of the views it requires,
     * sorted. A view or context code has no entry, so a code absent from the map needs nothing.
     */
    fun requiredViewCodesByPermission(): Map<String, List<String>>
}
