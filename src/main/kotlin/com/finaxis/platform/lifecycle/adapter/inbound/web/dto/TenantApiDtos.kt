package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Request payload for drafting a new tenant organisation. */
data class CreateTenantDraftRequest(
    @field:NotBlank
    @field:Pattern(
        regexp = "^[a-z0-9-]{3,32}$",
        message = "Tenant code must be 3-32 lowercase alphanumeric characters or hyphens.",
    )
    @field:Schema(example = "acme-corp")
    val tenantCode: String,
    @field:NotBlank
    @field:Size(min = 2, max = 100)
    @field:Schema(example = "Acme Financial Services")
    val displayName: String,
    @field:Size(max = 100)
    @field:Schema(example = "Acme Financial Services Limited")
    val legalName: String? = null,
    @field:Size(max = 50)
    @field:Schema(example = "REG-123456")
    val registrationNumber: String? = null,
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z]{2}$", message = "Country code must be 2 uppercase ISO letters.")
    @field:Schema(example = "KE")
    val countryCode: String,
    @field:NotBlank
    @field:Pattern(
        regexp = "^[A-Z]{3}$",
        message = "Currency code must be 3 uppercase ISO letters.",
    )
    @field:Schema(example = "KES")
    val baseCurrencyCode: String,
    @field:NotBlank
    @field:Schema(example = "Africa/Nairobi")
    val timezone: String,
    @field:NotNull
    @field:Valid
    val admin: InitialAdminDto,
    @field:Schema(
        description =
            "Tenant settings to seed, validated against the same catalogue as " +
                "PUT /api/v1/tenant/settings/{key}: an unknown key or a value that fails its " +
                "type rule is rejected with 422.",
        example = """{"base_currency":"KES"}""",
    )
    val initialSettings: Map<String, String> = emptyMap(),
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val businessDate: LocalDate? = null,
)

/** Request payload for amending an un-submitted tenant draft. */
data class AmendTenantDraftRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[a-z0-9-]{3,32}$")
    @field:Schema(example = "acme-corp")
    val tenantCode: String,
    @field:NotBlank
    @field:Size(min = 2, max = 100)
    @field:Schema(example = "Acme Financial Services")
    val displayName: String,
    @field:Size(max = 100)
    @field:Schema(example = "Acme Financial Services Limited")
    val legalName: String? = null,
    @field:Size(max = 50)
    @field:Schema(example = "REG-123456")
    val registrationNumber: String? = null,
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z]{2}$", message = "Country code must be 2 uppercase ISO letters.")
    @field:Schema(example = "KE")
    val countryCode: String,
    @field:NotBlank
    @field:Pattern(
        regexp = "^[A-Z]{3}$",
        message = "Currency code must be 3 uppercase ISO letters.",
    )
    @field:Schema(example = "KES")
    val baseCurrencyCode: String,
    @field:NotBlank
    @field:Schema(example = "Africa/Nairobi")
    val timezone: String,
    @field:NotNull
    @field:Valid
    val admin: InitialAdminDto,
    val initialSettings: Map<String, String> = emptyMap(),
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val businessDate: LocalDate? = null,
)

/** Initial administrator draft details. */
data class InitialAdminDto(
    @field:NotBlank
    @field:Email
    @field:Schema(example = "admin@acme.test")
    val email: String,
    @field:NotBlank
    @field:Pattern(regexp = "^[a-zA-Z0-9._-]{3,50}$")
    @field:Schema(example = "admin")
    val username: String,
    @field:NotBlank
    @field:Size(min = 2, max = 100)
    @field:Schema(example = "Initial Administrator")
    val displayName: String,
    @field:NotBlank
    @field:Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Phone must be in E.164 format.")
    @field:Schema(example = "+254700000000")
    val phoneE164: String? = null,
    val sendApplicationInvite: Boolean = false,
) {
    internal fun toDomain(): InitialAdministratorDraft =
        InitialAdministratorDraft(
            email = email,
            username = username,
            displayName = displayName,
            phoneE164 = phoneE164,
            sendApplicationInvite = sendApplicationInvite,
        )
}

/** Request payload for rejecting a submitted tenant draft. */
data class RejectTenantRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    @field:Schema(example = "Incomplete registration documents.")
    val reason: String,
)

/** Request payload for suspending an active tenant. */
data class SuspendTenantRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    @field:Schema(example = "Regulatory compliance review.")
    val reason: String,
)

/** Request payload for reactivating a suspended tenant. */
data class ReactivateTenantRequest(
    @field:Size(max = 500)
    @field:Schema(example = "Compliance review completed successfully.")
    val reason: String? = null,
)

/** Request payload for metadata-only deprovisioning of a tenant. */
data class DeprovisionTenantRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    @field:Schema(example = "Offboarding requested by client.")
    val reason: String,
)

/** Result returned when a tenant draft is created. */
data class TenantDraftResultResponse(
    val organisationId: UUID,
    val status: String,
)

/** Response representing tenant details. */
data class TenantDetailResponse(
    val id: UUID,
    val tenantCode: String,
    val displayName: String,
    val countryCode: String,
    val baseCurrencyCode: String,
    val timezone: String,
    val status: String,
    val bootstrapStatus: String? = null,
    val bootstrapFailureCode: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Compact response representing a tenant in list queries. */
data class TenantSummaryResponse(
    val id: UUID,
    val tenantCode: String,
    val displayName: String,
    val countryCode: String,
    val status: String,
    val createdAt: Instant,
)
