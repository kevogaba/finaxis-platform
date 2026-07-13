package com.finaxis.platform.common.persistence

import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Explicit audit-field convention for mutable Spring Data JDBC aggregates. */
interface AuditedJdbcAggregate {
    val createdAt: Instant?
    val createdBy: UUID?
    val updatedAt: Instant?
    val updatedBy: UUID?
    val rowVersion: Long?
}

/** Spring Data JDBC row model for the organisation aggregate. */
@Table("organisation")
data class OrganisationJdbcEntity(
    @Id val id: UUID,
    val tenantCode: String,
    val displayName: String,
    val legalName: String? = null,
    val registrationNumber: String? = null,
    val countryCode: String,
    val baseCurrencyCode: String,
    val timezone: String,
    val status: String,
    val activatedAt: Instant? = null,
    val suspendedAt: Instant? = null,
    val deprovisionedAt: Instant? = null,
    val statusReason: String? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for an organisation-owned branch. */
@Table("branch")
data class BranchJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val branchCode: String,
    val branchName: String,
    val branchType: String,
    val parentBranchId: UUID? = null,
    val status: String,
    val timezone: String,
    val addressJsonb: String = "{}",
    val openedOn: LocalDate? = null,
    val closedOn: LocalDate? = null,
    val statusReason: String? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for a global application user. */
@Table("user_account")
data class UserAccountJdbcEntity(
    @Id val id: UUID,
    val username: String,
    val email: String,
    val phoneE164: String? = null,
    val displayName: String,
    val status: String,
    val preferredLocale: String? = null,
    val lastLoginAt: Instant? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for the external Keycloak identity linked to a user. */
@Table("keycloak_identity_link")
data class KeycloakIdentityLinkJdbcEntity(
    @Id val id: UUID,
    val userId: UUID,
    val provider: String = "KEYCLOAK",
    val subject: String,
    val realmName: String? = null,
    val linkedAt: Instant,
    val unlinkedAt: Instant? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for a user's organisation-scoped membership. */
@Table("user_organisation_membership")
data class UserOrganisationMembershipJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val userId: UUID,
    val membershipStatus: String,
    val membershipType: String,
    val primaryBranchId: UUID? = null,
    val joinedAt: Instant? = null,
    val suspendedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val statusReason: String? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for an organisation-scoped branch assignment. */
@Table("user_branch_assignment")
data class UserBranchAssignmentJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val userId: UUID,
    val branchId: UUID,
    val assignmentType: String,
    val status: String,
    val assignedAt: Instant,
    val assignedBy: UUID? = null,
    val revokedAt: Instant? = null,
    val revokedBy: UUID? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for a platform permission definition. */
@Table("permission")
data class PermissionJdbcEntity(
    @Id val id: UUID,
    val permissionCode: String,
    val permissionName: String,
    val moduleCode: String,
    val description: String? = null,
    val riskLevel: String,
    val systemPermission: Boolean = true,
    val status: String,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for an organisation-owned role. */
@Table("role")
data class RoleJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val roleCode: String,
    val roleName: String,
    val description: String? = null,
    val systemRole: Boolean = false,
    val status: String,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for a role-to-permission grant. */
@Table("role_permission")
data class RolePermissionJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val roleId: UUID,
    val permissionId: UUID,
    val grantedAt: Instant,
    val grantedBy: UUID? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for a scoped role assignment. */
@Table("user_role_assignment")
data class UserRoleAssignmentJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val userId: UUID,
    val roleId: UUID,
    val branchId: UUID? = null,
    val scopeType: String,
    val status: String,
    val assignedAt: Instant,
    val assignedBy: UUID? = null,
    val revokedAt: Instant? = null,
    val revokedBy: UUID? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for an effective-dated organisation setting. */
@Table("organisation_setting")
data class OrganisationSettingJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val settingKey: String,
    val settingValue: String,
    val valueType: String,
    val isSensitive: Boolean = false,
    val effectiveFrom: Instant,
    val effectiveTo: Instant? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for an organisation's controlled business date. */
@Table("business_date")
data class BusinessDateJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val currentBusinessDate: LocalDate,
    val currentCobDate: LocalDate? = null,
    val status: String,
    val lastAdvancedAt: Instant? = null,
    val advancedBy: UUID? = null,
    @Version val rowVersion: Long? = null,
)

/** Spring Data JDBC row model for an organisation lifecycle transition audit record. */
@Table("organisation_transition_log")
data class OrganisationTransitionLogJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val entityId: UUID,
    val transitionName: String,
    val statusFrom: String,
    val statusTo: String,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
    val metadataJsonb: String = "{}",
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for a branch lifecycle transition audit record. */
@Table("branch_transition_log")
data class BranchTransitionLogJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val branchId: UUID,
    val entityId: UUID,
    val transitionName: String,
    val statusFrom: String,
    val statusTo: String,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
    val metadataJsonb: String = "{}",
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for a global user lifecycle transition audit record. */
@Table("user_account_transition_log")
data class UserAccountTransitionLogJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val branchId: UUID? = null,
    val entityId: UUID,
    val transitionName: String,
    val statusFrom: String,
    val statusTo: String,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
    val metadataJsonb: String = "{}",
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for a membership lifecycle transition audit record. */
@Table("user_organisation_membership_transition_log")
data class UserOrganisationMembershipTransitionLogJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val branchId: UUID? = null,
    val entityId: UUID,
    val transitionName: String,
    val statusFrom: String,
    val statusTo: String,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
    val metadataJsonb: String = "{}",
) : AuditedJdbcAggregate

/** Spring Data JDBC row model for an append-only audit event. */
@Table("audit_event")
data class AuditEventJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val eventTime: Instant,
    val actorUserId: UUID? = null,
    val actorExternalSubject: String? = null,
    val actorType: String,
    val branchId: UUID? = null,
    val eventType: String,
    val entityType: String,
    val entityId: UUID? = null,
    val action: String,
    val outcome: String,
    val severity: String,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val correlationId: String? = null,
    val requestId: String? = null,
    val beforeJsonb: String? = null,
    val afterJsonb: String? = null,
    val metadataJsonb: String = "{}",
)

/** Spring Data JDBC row model for a transactional integration outbox record. */
@Table("outbox_event")
data class OutboxEventJdbcEntity(
    @Id val id: UUID,
    val organisationId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val routingKey: String,
    val payloadJsonb: String,
    val publishStatus: String,
    val publishAttempts: Int = 0,
    val nextRetryAt: Instant? = null,
    val publishedAt: Instant? = null,
    val lastError: String? = null,
    @CreatedDate override val createdAt: Instant? = null,
    @CreatedBy override val createdBy: UUID? = null,
    @LastModifiedDate override val updatedAt: Instant? = null,
    @LastModifiedBy override val updatedBy: UUID? = null,
    @Version override val rowVersion: Long? = null,
) : AuditedJdbcAggregate
