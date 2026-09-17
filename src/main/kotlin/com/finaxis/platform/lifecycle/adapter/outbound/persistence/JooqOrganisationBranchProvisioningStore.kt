package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.boundedPageOffset
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BRANCH_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.REFERENCE_SEQUENCE
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.application.AmendOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.AssignUserToBranchCommand
import com.finaxis.platform.lifecycle.application.BranchAssignmentStore
import com.finaxis.platform.lifecycle.application.BranchLifecycleSnapshot
import com.finaxis.platform.lifecycle.application.BranchLifecycleStore
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.DeprovisionedAssignment
import com.finaxis.platform.lifecycle.application.HeadOfficeDraftResult
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.MembershipLifecycleSnapshot
import com.finaxis.platform.lifecycle.application.MembershipSnapshot
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationAccessStore
import com.finaxis.platform.lifecycle.application.OrganisationBootstrapStore
import com.finaxis.platform.lifecycle.application.OrganisationLifecycleProvisioningStore
import com.finaxis.platform.lifecycle.application.OrganisationListFilter
import com.finaxis.platform.lifecycle.application.OrganisationPage
import com.finaxis.platform.lifecycle.application.OrganisationQueryStore
import com.finaxis.platform.lifecycle.application.OrganisationSetupRequirement
import com.finaxis.platform.lifecycle.application.OrganisationSummary
import com.finaxis.platform.lifecycle.application.RevokeUserBranchAssignmentCommand
import com.finaxis.platform.lifecycle.application.StoredSetting
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * jOOQ adapter for organisation provisioning, branch lifecycle facts, and organisation-bounded
 * branch assignments. It intentionally performs no physical deletes during deprovisioning.
 */
@Component
class JooqOrganisationBranchProvisioningStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : OrganisationLifecycleProvisioningStore,
    BranchLifecycleStore {
    override fun lifecycleState(organisationId: UUID): OrganisationLifecycleState? =
        dsl
            .select(ORGANISATION.STATUS)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne(ORGANISATION.STATUS)
            ?.let(OrganisationLifecycleState::valueOf)

    override fun createDraft(command: CreateOrganisationDraftCommand): UUID {
        val now = now()
        return dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.TENANT_CODE, command.tenantCode)
            .set(ORGANISATION.DISPLAY_NAME, command.displayName)
            .set(ORGANISATION.LEGAL_NAME, command.legalName)
            .set(ORGANISATION.REGISTRATION_NUMBER, command.registrationNumber)
            .set(ORGANISATION.COUNTRY_CODE, command.countryCode)
            .set(ORGANISATION.BASE_CURRENCY_CODE, command.baseCurrencyCode)
            .set(ORGANISATION.TIMEZONE, command.timezone)
            .set(ORGANISATION.STATUS, OrganisationLifecycleState.DRAFT.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.CREATED_BY, command.requestedBy)
            .set(ORGANISATION.UPDATED_AT, now)
            .set(ORGANISATION.UPDATED_BY, command.requestedBy)
            .returning(ORGANISATION.ID)
            .fetchOne()
            ?.id
            ?: error("Insert into organisation returned no generated identifier.")
    }

    override fun amendDraft(command: AmendOrganisationDraftCommand) {
        val now = now()
        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.TENANT_CODE, command.tenantCode)
            .set(ORGANISATION.DISPLAY_NAME, command.displayName)
            .set(ORGANISATION.LEGAL_NAME, command.legalName)
            .set(ORGANISATION.REGISTRATION_NUMBER, command.registrationNumber)
            .set(ORGANISATION.COUNTRY_CODE, command.countryCode)
            .set(ORGANISATION.BASE_CURRENCY_CODE, command.baseCurrencyCode)
            .set(ORGANISATION.TIMEZONE, command.timezone)
            .set(ORGANISATION.UPDATED_AT, now)
            .set(ORGANISATION.UPDATED_BY, command.actorId)
            .set(ORGANISATION.ROW_VERSION, ORGANISATION.ROW_VERSION.plus(1))
            .where(ORGANISATION.ID.eq(command.organisationId))
            .execute()
    }

    override fun saveSettings(
        organisationId: UUID,
        settings: List<StoredSetting>,
        actorId: UUID,
    ) {
        settings.forEach { setting ->
            dsl
                .insertInto(ORGANISATION_SETTING)
                .set(ORGANISATION_SETTING.ORGANISATION_ID, organisationId)
                .set(ORGANISATION_SETTING.SETTING_KEY, setting.key)
                .set(
                    ORGANISATION_SETTING.SETTING_VALUE,
                    JSONB.jsonb(objectMapper.writeValueAsString(setting.value)),
                ).set(ORGANISATION_SETTING.VALUE_TYPE, setting.valueType)
                .set(ORGANISATION_SETTING.IS_SENSITIVE, setting.sensitive)
                .set(ORGANISATION_SETTING.EFFECTIVE_FROM, now())
                .set(ORGANISATION_SETTING.CREATED_AT, now())
                .set(ORGANISATION_SETTING.CREATED_BY, actorId)
                .set(ORGANISATION_SETTING.UPDATED_AT, now())
                .set(ORGANISATION_SETTING.UPDATED_BY, actorId)
                .execute()
        }
    }

    override fun hasRequiredMetadata(organisationId: UUID): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .and(ORGANISATION.TENANT_CODE.ne(""))
                .and(ORGANISATION.DISPLAY_NAME.ne(""))
                .and(ORGANISATION.COUNTRY_CODE.isNotNull)
                .and(ORGANISATION.BASE_CURRENCY_CODE.isNotNull)
                .and(ORGANISATION.TIMEZONE.ne("")),
        )

    /** Finds a compact organisation projection using the stable tenant-code field. */
    fun findByCode(tenantCode: String): OrganisationSummary? {
        val b = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        return dsl
            .select(
                ORGANISATION.ID,
                ORGANISATION.TENANT_CODE,
                ORGANISATION.DISPLAY_NAME,
                ORGANISATION.COUNTRY_CODE,
                ORGANISATION.STATUS,
                ORGANISATION.CREATED_AT,
                b.STATUS,
                b.ATTEMPTS,
                b.USER_ID,
                b.MEMBERSHIP_ID,
                b.LAST_FAILURE_CODE,
            ).from(ORGANISATION)
            .leftJoin(b)
            .on(b.ORGANISATION_ID.eq(ORGANISATION.ID))
            .where(ORGANISATION.TENANT_CODE.eq(tenantCode))
            .fetchOne()
            ?.let(::organisationSummary)
    }

    /** Executes the bounded organisation administration query. */
    fun list(filter: OrganisationListFilter): OrganisationPage {
        val b = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        var condition: Condition = DSL.trueCondition()
        filter.status?.let { condition = condition.and(ORGANISATION.STATUS.eq(it.name)) }
        filter.countryCode?.let { condition = condition.and(ORGANISATION.COUNTRY_CODE.eq(it)) }
        filter.createdFrom?.let {
            condition =
                condition.and(ORGANISATION.CREATED_AT.ge(it.atOffset(ZoneOffset.UTC)))
        }
        filter.createdTo?.let {
            condition =
                condition.and(ORGANISATION.CREATED_AT.le(it.atOffset(ZoneOffset.UTC)))
        }
        val total = dsl.fetchCount(ORGANISATION, condition).toLong()
        val offset =
            boundedPageOffset(filter.page, filter.size, total)
                ?: return OrganisationPage(emptyList(), total)
        val items =
            dsl
                .select(
                    ORGANISATION.ID,
                    ORGANISATION.TENANT_CODE,
                    ORGANISATION.DISPLAY_NAME,
                    ORGANISATION.COUNTRY_CODE,
                    ORGANISATION.STATUS,
                    ORGANISATION.CREATED_AT,
                    b.STATUS,
                    b.ATTEMPTS,
                    b.USER_ID,
                    b.MEMBERSHIP_ID,
                    b.LAST_FAILURE_CODE,
                ).from(ORGANISATION)
                .leftJoin(b)
                .on(b.ORGANISATION_ID.eq(ORGANISATION.ID))
                .where(condition)
                .orderBy(ORGANISATION.CREATED_AT.desc())
                .limit(filter.size)
                .offset(offset)
                .fetch(::organisationSummary)
        return OrganisationPage(items, total)
    }

    override fun organisationState(organisationId: UUID): OrganisationLifecycleState? =
        lifecycleState(organisationId)

    override fun createDraft(command: CreateBranchCommand): UUID {
        val id =
            dsl
                .insertInto(
                    BRANCH,
                ).set(BRANCH.ORGANISATION_ID, command.organisationId)
                .set(BRANCH.BRANCH_CODE, command.branchCode)
                .set(BRANCH.BRANCH_NAME, command.branchName)
                .set(
                    BRANCH.BRANCH_TYPE,
                    command.branchType,
                ).set(BRANCH.PARENT_BRANCH_ID, command.parentBranchId)
                .set(
                    BRANCH.STATUS,
                    BranchLifecycleState.DRAFT.name,
                ).set(BRANCH.TIMEZONE, command.timezone)
                .set(
                    BRANCH.ADDRESS_JSONB,
                    JSONB.jsonb(objectMapper.writeValueAsString(command.address)),
                ).set(BRANCH.CREATED_AT, now())
                .set(BRANCH.CREATED_BY, command.requestedBy)
                .set(BRANCH.UPDATED_AT, now())
                .set(BRANCH.UPDATED_BY, command.requestedBy)
                .returning(BRANCH.ID)
                .fetchOne()
                ?.id
                ?: error("Insert into branch returned no generated identifier.")
        dsl
            .insertInto(BRANCH_TRANSITION_LOG)
            .set(BRANCH_TRANSITION_LOG.ORGANISATION_ID, command.organisationId)
            .set(BRANCH_TRANSITION_LOG.BRANCH_ID, id)
            .set(BRANCH_TRANSITION_LOG.ENTITY_ID, id)
            .set(BRANCH_TRANSITION_LOG.TRANSITION_NAME, "CREATE_DRAFT")
            .set(BRANCH_TRANSITION_LOG.STATUS_FROM, "NONE")
            .set(BRANCH_TRANSITION_LOG.STATUS_TO, BranchLifecycleState.DRAFT.name)
            .set(BRANCH_TRANSITION_LOG.CREATED_AT, now())
            .set(BRANCH_TRANSITION_LOG.CREATED_BY, command.requestedBy)
            .set(BRANCH_TRANSITION_LOG.UPDATED_AT, now())
            .set(BRANCH_TRANSITION_LOG.UPDATED_BY, command.requestedBy)
            .set(BRANCH_TRANSITION_LOG.METADATA_JSONB, JSONB.jsonb("{\"creation\":true}"))
            .execute()
        return id
    }

    override fun branchCodeExists(
        organisationId: UUID,
        branchCode: String,
        excludingBranchId: UUID?,
    ): Boolean {
        var condition =
            BRANCH.ORGANISATION_ID
                .eq(
                    organisationId,
                ).and(BRANCH.BRANCH_CODE.eq(branchCode))
        excludingBranchId?.let { condition = condition.and(BRANCH.ID.ne(it)) }
        return dsl.fetchExists(dsl.selectOne().from(BRANCH).where(condition))
    }

    override fun branchCode(
        organisationId: UUID,
        branchId: UUID,
    ): String? =
        dsl
            .select(
                BRANCH.BRANCH_CODE,
            ).from(
                BRANCH,
            ).where(
                BRANCH.ORGANISATION_ID.eq(organisationId),
            ).and(BRANCH.ID.eq(branchId))
            .fetchOne(BRANCH.BRANCH_CODE)

    override fun parentBelongsToOrganisation(
        organisationId: UUID,
        parentBranchId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(
                    BRANCH,
                ).where(BRANCH.ORGANISATION_ID.eq(organisationId))
                .and(BRANCH.ID.eq(parentBranchId)),
        )

    override fun branchState(
        organisationId: UUID,
        branchId: UUID,
    ): BranchLifecycleState? =
        dsl
            .select(
                BRANCH.STATUS,
            ).from(
                BRANCH,
            ).where(
                BRANCH.ORGANISATION_ID.eq(organisationId),
            ).and(BRANCH.ID.eq(branchId))
            .fetchOne(BRANCH.STATUS)
            ?.let(BranchLifecycleState::valueOf)

    override fun parentBranchId(
        organisationId: UUID,
        branchId: UUID,
    ): UUID? =
        dsl
            .select(BRANCH.PARENT_BRANCH_ID)
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(organisationId))
            .and(BRANCH.ID.eq(branchId))
            .fetchOne(BRANCH.PARENT_BRANCH_ID)

    override fun createdBy(
        organisationId: UUID,
        branchId: UUID,
    ): UUID? =
        dsl
            .select(BRANCH.CREATED_BY)
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(organisationId))
            .and(BRANCH.ID.eq(branchId))
            .fetchOne(BRANCH.CREATED_BY)

    private fun now() = clock.instant().atOffset(ZoneOffset.UTC)
}

/** jOOQ adapter for durable organisation setup created inside the provisioning transaction. */
@Component
class JooqOrganisationBootstrapStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : OrganisationBootstrapStore {
    override fun ensureBusinessDate(
        organisationId: UUID,
        date: LocalDate,
    ) {
        if (hasBusinessDate(organisationId)) return
        dsl
            .insertInto(BUSINESS_DATE)
            .set(BUSINESS_DATE.ORGANISATION_ID, organisationId)
            .set(BUSINESS_DATE.CURRENT_BUSINESS_DATE, date)
            .set(BUSINESS_DATE.STATUS, "OPEN")
            .execute()
    }

    override fun timezone(organisationId: UUID): String =
        requireNotNull(
            dsl
                .select(ORGANISATION.TIMEZONE)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchOne(ORGANISATION.TIMEZONE),
        ) { "Organisation was not found." }

    override fun baseCurrencyCode(organisationId: UUID): String? =
        dsl
            .select(ORGANISATION.BASE_CURRENCY_CODE)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne(ORGANISATION.BASE_CURRENCY_CODE)

    /**
     * The same select with `FOR SHARE`, so the code comes back attached to a row this transaction
     * holds rather than to a snapshot another transaction may already have superseded.
     *
     * Shared, not exclusive: several postings may read the code at once, and nothing in the
     * posting path writes the organisation row. The only writer is the draft amendment, which
     * cannot run against an organisation that is postable at all.
     *
     * Above `READ COMMITTED` - and the posting path runs at `SERIALIZABLE` - a change committed
     * after this transaction's snapshot makes this statement raise `40001` rather than return the
     * stale code. That failure is the point: it is what the caller's plain re-read could not do.
     * The active-transaction assertion comes first, as it does on every locking statement here,
     * because outside a transaction the lock would be taken and released by the same statement and
     * guarantee nothing at all.
     */
    override fun lockBaseCurrencyCode(organisationId: UUID): String? {
        requireActiveTransaction("Locking an organisation's base currency")
        return dsl
            .select(ORGANISATION.BASE_CURRENCY_CODE)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .forShare()
            .fetchOne(ORGANISATION.BASE_CURRENCY_CODE)
    }

    override fun ensureHeadOfficeDraft(organisationId: UUID): HeadOfficeDraftResult {
        dsl
            .select(BRANCH.ID)
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(organisationId))
            .and(BRANCH.BRANCH_CODE.eq(OrganisationBootstrapDefaults.HEAD_OFFICE_CODE))
            .fetchOne(BRANCH.ID)
            ?.let { return HeadOfficeDraftResult(it, false) }
        val now = now()
        val branchId =
            dsl
                .insertInto(BRANCH)
                .set(BRANCH.ORGANISATION_ID, organisationId)
                .set(BRANCH.BRANCH_CODE, OrganisationBootstrapDefaults.HEAD_OFFICE_CODE)
                .set(BRANCH.BRANCH_NAME, "Head Office")
                .set(BRANCH.BRANCH_TYPE, "HEAD_OFFICE")
                .set(BRANCH.STATUS, BranchLifecycleState.DRAFT.name)
                .set(BRANCH.TIMEZONE, timezone(organisationId))
                .set(BRANCH.CREATED_AT, now)
                .set(BRANCH.CREATED_BY, SystemActor.ID)
                .set(BRANCH.UPDATED_AT, now)
                .set(BRANCH.UPDATED_BY, SystemActor.ID)
                .returning(BRANCH.ID)
                .fetchOne()
                ?.id
                ?: error("Insert into branch returned no generated identifier.")
        dsl
            .insertInto(BRANCH_TRANSITION_LOG)
            .set(BRANCH_TRANSITION_LOG.ORGANISATION_ID, organisationId)
            .set(BRANCH_TRANSITION_LOG.BRANCH_ID, branchId)
            .set(BRANCH_TRANSITION_LOG.ENTITY_ID, branchId)
            .set(BRANCH_TRANSITION_LOG.TRANSITION_NAME, "CREATE_HEAD_OFFICE_DRAFT")
            .set(BRANCH_TRANSITION_LOG.STATUS_FROM, "NONE")
            .set(BRANCH_TRANSITION_LOG.STATUS_TO, BranchLifecycleState.DRAFT.name)
            .set(BRANCH_TRANSITION_LOG.CREATED_AT, now)
            .set(BRANCH_TRANSITION_LOG.CREATED_BY, SystemActor.ID)
            .set(BRANCH_TRANSITION_LOG.UPDATED_AT, now)
            .set(BRANCH_TRANSITION_LOG.UPDATED_BY, SystemActor.ID)
            .set(BRANCH_TRANSITION_LOG.METADATA_JSONB, JSONB.jsonb("{\"bootstrap\":true}"))
            .execute()
        return HeadOfficeDraftResult(branchId, true)
    }

    override fun createDefaultReferenceSequences(organisationId: UUID) {
        OrganisationBootstrapDefaults.SEQUENCE_CODES.forEach { code ->
            dsl
                .insertInto(REFERENCE_SEQUENCE)
                .set(REFERENCE_SEQUENCE.ORGANISATION_ID, organisationId)
                .set(REFERENCE_SEQUENCE.SEQUENCE_CODE, code)
                .set(REFERENCE_SEQUENCE.NEXT_VALUE, 1)
                .set(REFERENCE_SEQUENCE.CREATED_AT, now())
                .set(REFERENCE_SEQUENCE.CREATED_BY, SystemActor.ID)
                .set(REFERENCE_SEQUENCE.UPDATED_AT, now())
                .set(REFERENCE_SEQUENCE.UPDATED_BY, SystemActor.ID)
                .onConflict(REFERENCE_SEQUENCE.ORGANISATION_ID, REFERENCE_SEQUENCE.SEQUENCE_CODE)
                .doNothing()
                .execute()
        }
    }

    override fun createDefaultRoles(organisationId: UUID) {
        OrganisationBootstrapDefaults.ROLE_PERMISSIONS.forEach { (roleCode, permissions) ->
            dsl
                .insertInto(ROLE)
                .set(ROLE.ORGANISATION_ID, organisationId)
                .set(ROLE.ROLE_CODE, roleCode)
                .set(ROLE.ROLE_NAME, roleCode.toDisplayName())
                .set(ROLE.SYSTEM_ROLE, true)
                .set(ROLE.STATUS, "ACTIVE")
                .set(ROLE.CREATED_AT, now())
                .set(ROLE.CREATED_BY, SystemActor.ID)
                .set(ROLE.UPDATED_AT, now())
                .set(ROLE.UPDATED_BY, SystemActor.ID)
                .onConflict(ROLE.ORGANISATION_ID, ROLE.ROLE_CODE)
                .doUpdate()
                .set(ROLE.SYSTEM_ROLE, true)
                .set(ROLE.UPDATED_AT, now())
                .set(ROLE.UPDATED_BY, SystemActor.ID)
                .execute()
            dsl.grantPermissions(organisationId, roleCode, permissions, clock)
        }
    }

    override fun headOfficeState(organisationId: UUID): BranchLifecycleState? =
        dsl
            .select(BRANCH.STATUS)
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(organisationId))
            .and(BRANCH.BRANCH_CODE.eq(OrganisationBootstrapDefaults.HEAD_OFFICE_CODE))
            .fetchOne(BRANCH.STATUS)
            ?.let(BranchLifecycleState::valueOf)

    private fun hasBusinessDate(organisationId: UUID): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(BUSINESS_DATE)
                .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId)),
        )

    private fun now() = clock.instant().atOffset(ZoneOffset.UTC)

    private fun requireActiveTransaction(operation: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "$operation requires an active transaction."
        }
    }
}

private fun String.toDisplayName(): String =
    replace('_', ' ').lowercase().replaceFirstChar(Char::titlecase)

private object OrganisationBootstrapDefaults {
    const val HEAD_OFFICE_CODE = "HEAD_OFFICE"
    val SEQUENCE_CODES = listOf("MEMBER", "TRANSACTION", "JOURNAL")
    private val BASELINE_PERMISSION_CODES =
        setOf(
            // Tenant lifecycle
            "tenant.create",
            "tenant.submit_for_approval",
            "tenant.approve",
            "tenant.activate",
            "tenant.suspend",
            "tenant.deprovision",
            // Foundation API – tenant reads/writes
            "tenant.view",
            "tenant.update_draft",
            "tenant.reject",
            "tenant.reactivate",
            "tenant.bootstrap_retry",
            // Branch lifecycle
            "branch.create",
            "branch.approve",
            "branch.activate",
            "branch.suspend",
            "branch.close",
            // Foundation API – branch
            "branch.view",
            "branch.reactivate",
            // User / membership administration (no global user lifecycle – platform-only)
            "user.view",
            "user.invite",
            "user.approve",
            "user.assign_branch",
            "user.assign_role",
            "user.revoke_branch",
            "user.revoke_role",
            // Membership
            "membership.view",
            "membership.suspend",
            "membership.reactivate",
            "membership.revoke",
            // Branch assignments
            "branch_assignment.view",
            // Roles
            "role.create",
            "role.update",
            "role.assign_permission",
            "role.view",
            "role.activate",
            "role.deactivate",
            "role.remove_permission",
            // Role assignments
            "role_assignment.view",
            // Permissions catalog
            "permission.view",
            // Audit & settings
            "audit.view",
            "settings.view",
            "settings.update",
            // Business date & COB
            "business_date.view",
            "business_date.advance",
            "business_date.reopen",
            "cob.start",
            "cob.complete",
            // Auth selection
            "auth.select_organisation",
            "auth.select_branch",
            // Profile
            "iam.profile.read",
        )

    /**
     * Accounting configuration and oversight, granted to TENANT_ADMIN. Deliberately excludes the
     * operational and break-glass codes - manual journal preparation, approval, reversal,
     * prior-period posting, reconciliation resolution and period reopening - so the default
     * administrator is not also the default poster. TENANT_ADMIN holds role.assign_permission and
     * can grant itself more, so this is safe-by-default posture rather than a security boundary.
     */
    private val ACCOUNTING_ADMINISTRATION_CODES =
        setOf(
            AccountingPermissions.GL_ACCOUNT_VIEW,
            AccountingPermissions.GL_ACCOUNT_CREATE,
            AccountingPermissions.GL_ACCOUNT_UPDATE,
            AccountingPermissions.GL_ACCOUNT_SUBMIT,
            AccountingPermissions.GL_ACCOUNT_APPROVE,
            AccountingPermissions.GL_ACCOUNT_DEACTIVATE,
            AccountingPermissions.FISCAL_PERIOD_VIEW,
            AccountingPermissions.FISCAL_PERIOD_OPEN,
            AccountingPermissions.FISCAL_PERIOD_CLOSE,
            AccountingPermissions.JOURNAL_VIEW,
            AccountingPermissions.POSTING_RULE_VIEW,
            AccountingPermissions.POSTING_RULE_CREATE,
            AccountingPermissions.POSTING_RULE_UPDATE,
            AccountingPermissions.POSTING_RULE_SUBMIT,
            AccountingPermissions.POSTING_RULE_APPROVE,
            AccountingPermissions.RECONCILIATION_VIEW,
            AccountingPermissions.RECONCILIATION_RUN,
            AccountingPermissions.ACCOUNTING_REPORT_VIEW,
            AccountingPermissions.ACCOUNTING_REPORT_EXPORT,
        )

    /** Accounting maker: prepares and submits, never approves. */
    private val ACCOUNTING_OPERATOR_CODES =
        setOf(
            AccountingPermissions.GL_ACCOUNT_VIEW,
            AccountingPermissions.GL_ACCOUNT_CREATE,
            AccountingPermissions.GL_ACCOUNT_UPDATE,
            AccountingPermissions.GL_ACCOUNT_SUBMIT,
            AccountingPermissions.FISCAL_PERIOD_VIEW,
            AccountingPermissions.JOURNAL_VIEW,
            AccountingPermissions.JOURNAL_CREATE_MANUAL,
            AccountingPermissions.JOURNAL_SUBMIT,
            AccountingPermissions.POSTING_RULE_VIEW,
            AccountingPermissions.POSTING_RULE_CREATE,
            AccountingPermissions.POSTING_RULE_UPDATE,
            AccountingPermissions.POSTING_RULE_SUBMIT,
            AccountingPermissions.RECONCILIATION_VIEW,
            AccountingPermissions.RECONCILIATION_RUN,
            AccountingPermissions.ACCOUNTING_REPORT_VIEW,
            AccountingPermissions.ACCOUNTING_REPORT_EXPORT,
            "auth.select_organisation",
            "auth.select_branch",
            "iam.profile.read",
        )

    /** Accounting checker: approves and posts, never prepares. */
    private val ACCOUNTING_APPROVER_CODES =
        setOf(
            AccountingPermissions.GL_ACCOUNT_VIEW,
            AccountingPermissions.GL_ACCOUNT_APPROVE,
            AccountingPermissions.GL_ACCOUNT_DEACTIVATE,
            AccountingPermissions.FISCAL_PERIOD_VIEW,
            AccountingPermissions.FISCAL_PERIOD_OPEN,
            AccountingPermissions.FISCAL_PERIOD_CLOSE,
            AccountingPermissions.JOURNAL_VIEW,
            AccountingPermissions.JOURNAL_APPROVE,
            AccountingPermissions.JOURNAL_REVERSE,
            AccountingPermissions.POSTING_RULE_VIEW,
            AccountingPermissions.POSTING_RULE_APPROVE,
            AccountingPermissions.RECONCILIATION_VIEW,
            AccountingPermissions.RECONCILIATION_RESOLVE,
            AccountingPermissions.ACCOUNTING_REPORT_VIEW,
            "auth.select_organisation",
            "auth.select_branch",
            "iam.profile.read",
        )

    val ROLE_PERMISSIONS =
        mapOf(
            "TENANT_ADMIN" to BASELINE_PERMISSION_CODES + ACCOUNTING_ADMINISTRATION_CODES,
            "TENANT_AUDITOR" to
                setOf(
                    "audit.view",
                    "business_date.view",
                    "tenant.view",
                    "branch.view",
                    "user.view",
                    "membership.view",
                    "branch_assignment.view",
                    "role.view",
                    "role_assignment.view",
                    "permission.view",
                    "settings.view",
                    "gl_account.view",
                    "fiscal_period.view",
                    "journal.view",
                    "posting_rule.view",
                    "reconciliation.view",
                    "accounting_report.view",
                    "auth.select_organisation",
                    "auth.select_branch",
                    "iam.profile.read",
                ),
            "IAM_ADMIN" to
                setOf(
                    "user.view",
                    "user.invite",
                    "user.approve",
                    "user.assign_branch",
                    "user.assign_role",
                    "user.revoke_branch",
                    "user.revoke_role",
                    "membership.view",
                    "membership.suspend",
                    "membership.reactivate",
                    "membership.revoke",
                    "branch_assignment.view",
                    "role.create",
                    "role.update",
                    "role.assign_permission",
                    "role.view",
                    "role.activate",
                    "role.deactivate",
                    "role.remove_permission",
                    "role_assignment.view",
                    "permission.view",
                    "audit.view",
                    "auth.select_organisation",
                    "auth.select_branch",
                    "iam.profile.read",
                ),
            "BRANCH_MANAGER" to
                setOf(
                    "branch.create",
                    "branch.approve",
                    "branch.activate",
                    "branch.suspend",
                    "branch.close",
                    "branch.view",
                    "branch.reactivate",
                    "user.assign_branch",
                    "branch_assignment.view",
                    "business_date.view",
                    "accounting_report.view",
                    "auth.select_organisation",
                    "auth.select_branch",
                    "iam.profile.read",
                ),
            "BRANCH_OPERATOR" to
                setOf(
                    "business_date.view",
                    "auth.select_organisation",
                    "auth.select_branch",
                    "iam.profile.read",
                ),
            "ACCOUNTING_OPERATOR" to ACCOUNTING_OPERATOR_CODES,
            "ACCOUNTING_APPROVER" to ACCOUNTING_APPROVER_CODES,
        )
}

private fun organisationSummary(record: org.jooq.Record): OrganisationSummary {
    val b = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
    val bootstrapStatusRaw = record.get(b.STATUS)
    val statusVal =
        OrganisationLifecycleState.valueOf(
            requireNotNull(record.get(ORGANISATION.STATUS)),
        )
    return OrganisationSummary(
        organisationId = requireNotNull(record.get(ORGANISATION.ID)),
        tenantCode = requireNotNull(record.get(ORGANISATION.TENANT_CODE)),
        displayName = requireNotNull(record.get(ORGANISATION.DISPLAY_NAME)),
        countryCode = requireNotNull(record.get(ORGANISATION.COUNTRY_CODE)),
        status = statusVal,
        createdAt = requireNotNull(record.get(ORGANISATION.CREATED_AT)).toInstant(),
        bootstrapStatus =
            bootstrapStatusRaw?.let {
                InitialAdministratorBootstrapStatus.valueOf(it)
            },
        bootstrapAttempts = record.get(b.ATTEMPTS),
        bootstrapUserId = record.get(b.USER_ID),
        bootstrapMembershipId = record.get(b.MEMBERSHIP_ID),
        lastBootstrapFailureCode = record.get(b.LAST_FAILURE_CODE),
    )
}

private fun DSLContext.grantPermissions(
    organisationId: UUID,
    roleCode: String,
    permissionCodes: Set<String>,
    clock: Clock,
) {
    val roleId =
        select(ROLE.ID)
            .from(ROLE)
            .where(ROLE.ORGANISATION_ID.eq(organisationId))
            .and(ROLE.ROLE_CODE.eq(roleCode))
            .fetchOne(ROLE.ID)
            ?: error("Default role was not created.")
    select(PERMISSION.ID)
        .from(PERMISSION)
        .where(PERMISSION.PERMISSION_CODE.`in`(permissionCodes))
        .and(PERMISSION.STATUS.eq("ACTIVE"))
        .fetch(PERMISSION.ID)
        .forEach { permissionId ->
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
                .set(ROLE_PERMISSION.ROLE_ID, roleId)
                .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
                .set(ROLE_PERMISSION.GRANTED_AT, now)
                .set(ROLE_PERMISSION.GRANTED_BY, SystemActor.ID)
                .set(ROLE_PERMISSION.CREATED_AT, now)
                .set(ROLE_PERMISSION.CREATED_BY, SystemActor.ID)
                .set(ROLE_PERMISSION.UPDATED_AT, now)
                .set(ROLE_PERMISSION.UPDATED_BY, SystemActor.ID)
                .onConflict(
                    ROLE_PERMISSION.ORGANISATION_ID,
                    ROLE_PERMISSION.ROLE_ID,
                    ROLE_PERMISSION.PERMISSION_ID,
                ).doNothing()
                .execute()
        }
}

/** jOOQ adapter for organisation-bounded branch assignment persistence. */
@Component
class JooqBranchAssignmentStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : BranchAssignmentStore {
    override fun userExists(userId: UUID): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(userId)),
        )

    override fun membership(
        organisationId: UUID,
        userId: UUID,
    ): MembershipSnapshot? =
        dsl
            .select(
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
            .fetchOne()
            ?.let {
                MembershipSnapshot(
                    MembershipLifecycleState.valueOf(it.value1()!!),
                    MembershipType.valueOf(it.value2()!!),
                )
            }

    override fun assign(command: AssignUserToBranchCommand): Boolean {
        if (activeAssignmentExists(command)) return false
        val inactiveAssignmentId =
            dsl
                .select(USER_BRANCH_ASSIGNMENT.ID)
                .from(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(command.organisationId))
                .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(command.userId))
                .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(command.branchId))
                .and(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE.eq(command.assignmentType.name))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.ne("ACTIVE"))
                .orderBy(USER_BRANCH_ASSIGNMENT.UPDATED_AT.desc())
                .limit(1)
                .fetchOne(USER_BRANCH_ASSIGNMENT.ID)
        if (inactiveAssignmentId != null) {
            dsl
                .update(USER_BRANCH_ASSIGNMENT)
                .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
                .set(USER_BRANCH_ASSIGNMENT.REVOKED_AT, null as java.time.OffsetDateTime?)
                .set(USER_BRANCH_ASSIGNMENT.REVOKED_BY, null as UUID?)
                .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .set(USER_BRANCH_ASSIGNMENT.UPDATED_BY, command.assignedBy)
                .set(
                    USER_BRANCH_ASSIGNMENT.ROW_VERSION,
                    USER_BRANCH_ASSIGNMENT.ROW_VERSION.plus(1),
                ).where(USER_BRANCH_ASSIGNMENT.ID.eq(inactiveAssignmentId))
                .execute()
            return true
        }
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, command.organisationId)
            .set(USER_BRANCH_ASSIGNMENT.USER_ID, command.userId)
            .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, command.branchId)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, command.assignmentType.name)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_BY, command.assignedBy)
            .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.CREATED_BY, command.assignedBy)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_BY, command.assignedBy)
            .execute()
        return true
    }

    override fun activeAssignments(
        organisationId: UUID,
        userId: UUID,
    ): Int =
        dsl.fetchCount(
            USER_BRANCH_ASSIGNMENT,
            USER_BRANCH_ASSIGNMENT.ORGANISATION_ID
                .eq(organisationId)
                .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(userId))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE")),
        )

    override fun isActive(command: RevokeUserBranchAssignmentCommand): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(command.organisationId))
                .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(command.userId))
                .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(command.branchId))
                .and(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE.eq(command.assignmentType.name))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE")),
        )

    override fun revoke(command: RevokeUserBranchAssignmentCommand): Boolean =
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "REVOKED")
            .set(USER_BRANCH_ASSIGNMENT.REVOKED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(USER_BRANCH_ASSIGNMENT.REVOKED_BY, command.revokedBy)
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(command.organisationId))
            .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(command.userId))
            .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(command.branchId))
            .and(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE.eq(command.assignmentType.name))
            .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE"))
            .execute() > 0

    private fun activeAssignmentExists(command: AssignUserToBranchCommand): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(command.organisationId))
                .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(command.userId))
                .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(command.branchId))
                .and(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE.eq(command.assignmentType.name))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE")),
        )
}

/** Adapter exposing controlled deprovisioning cleanup without widening the lifecycle store port. */
@Component
class JooqOrganisationAccessStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : OrganisationAccessStore {
    override fun branchesForDeprovisioning(organisationId: UUID): List<BranchLifecycleSnapshot> =
        dsl
            .select(BRANCH.ID, BRANCH.STATUS)
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(organisationId))
            .and(
                BRANCH.STATUS.notIn(
                    BranchLifecycleState.CLOSED.name,
                    BranchLifecycleState.ARCHIVED.name,
                ),
            ).fetch { record ->
                BranchLifecycleSnapshot(
                    requireNotNull(record[BRANCH.ID]),
                    BranchLifecycleState.valueOf(requireNotNull(record[BRANCH.STATUS])),
                )
            }

    override fun membershipsForDeprovisioning(
        organisationId: UUID,
    ): List<MembershipLifecycleSnapshot> =
        dsl
            .select(
                USER_ORGANISATION_MEMBERSHIP.ID,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS.ne(
                    MembershipLifecycleState.REVOKED.name,
                ),
            ).fetch { record ->
                MembershipLifecycleSnapshot(
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ID]),
                    MembershipLifecycleState.valueOf(
                        requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS]),
                    ),
                )
            }

    override fun missingRequiredSetup(organisationId: UUID): Set<OrganisationSetupRequirement> =
        buildSet {
            if (!hasOperationalSettings(organisationId)) {
                add(OrganisationSetupRequirement.DEFAULT_SETTINGS)
            }
            if (!hasBusinessDate(organisationId)) add(OrganisationSetupRequirement.BUSINESS_DATE)
            if (headOfficeState(organisationId) != BranchLifecycleState.ACTIVE) {
                add(OrganisationSetupRequirement.ACTIVE_HEAD_OFFICE)
            }
            if (!hasDefaultReferenceSequences(organisationId)) {
                add(OrganisationSetupRequirement.REFERENCE_SEQUENCES)
            }
            if (!hasDefaultRolesAndPermissions(organisationId)) {
                add(OrganisationSetupRequirement.DEFAULT_ROLES_AND_PERMISSIONS)
            }
        }

    override fun revokeActiveAssignments(organisationId: UUID): List<DeprovisionedAssignment> {
        val branchAssignmentIds =
            dsl
                .select(USER_BRANCH_ASSIGNMENT.ID)
                .from(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE"))
                .fetch(USER_BRANCH_ASSIGNMENT.ID)
                .filterNotNull()
        val roleAssignmentIds =
            dsl
                .select(USER_ROLE_ASSIGNMENT.ID)
                .from(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq("ACTIVE"))
                .fetch(USER_ROLE_ASSIGNMENT.ID)
                .filterNotNull()
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "REVOKED")
            .set(USER_BRANCH_ASSIGNMENT.REVOKED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.REVOKED_BY, SystemActor.ID)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_BY, SystemActor.ID)
            .set(USER_BRANCH_ASSIGNMENT.ROW_VERSION, USER_BRANCH_ASSIGNMENT.ROW_VERSION.plus(1))
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE"))
            .execute()
        dsl
            .update(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.STATUS, "REVOKED")
            .set(USER_ROLE_ASSIGNMENT.REVOKED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.REVOKED_BY, SystemActor.ID)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_BY, SystemActor.ID)
            .set(USER_ROLE_ASSIGNMENT.ROW_VERSION, USER_ROLE_ASSIGNMENT.ROW_VERSION.plus(1))
            .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .and(USER_ROLE_ASSIGNMENT.STATUS.eq("ACTIVE"))
            .execute()
        return branchAssignmentIds.map { DeprovisionedAssignment(it, "USER_BRANCH_ASSIGNMENT") } +
            roleAssignmentIds.map { DeprovisionedAssignment(it, "USER_ROLE_ASSIGNMENT") }
    }

    private fun hasOperationalSettings(organisationId: UUID): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(ORGANISATION_SETTING)
                .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
                .and(ORGANISATION_SETTING.SETTING_KEY.eq("settings.operational")),
        )

    private fun hasBusinessDate(organisationId: UUID): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(BUSINESS_DATE)
                .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId)),
        )

    private fun headOfficeState(organisationId: UUID): BranchLifecycleState? =
        dsl
            .select(BRANCH.STATUS)
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(organisationId))
            .and(BRANCH.BRANCH_CODE.eq(OrganisationBootstrapDefaults.HEAD_OFFICE_CODE))
            .fetchOne(BRANCH.STATUS)
            ?.let(BranchLifecycleState::valueOf)

    private fun hasDefaultReferenceSequences(organisationId: UUID): Boolean =
        dsl
            .selectCount()
            .from(REFERENCE_SEQUENCE)
            .where(REFERENCE_SEQUENCE.ORGANISATION_ID.eq(organisationId))
            .and(
                REFERENCE_SEQUENCE.SEQUENCE_CODE.`in`(OrganisationBootstrapDefaults.SEQUENCE_CODES),
            ).fetchOne(0, Int::class.java) == OrganisationBootstrapDefaults.SEQUENCE_CODES.size

    private fun hasDefaultRolesAndPermissions(organisationId: UUID): Boolean =
        OrganisationBootstrapDefaults.ROLE_PERMISSIONS.all { (roleCode, permissionCodes) ->
            val roleId =
                dsl
                    .select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.ORGANISATION_ID.eq(organisationId))
                    .and(ROLE.ROLE_CODE.eq(roleCode))
                    .and(ROLE.STATUS.eq("ACTIVE"))
                    .fetchOne(ROLE.ID)
                    ?: return@all false
            dsl
                .selectCount()
                .from(ROLE_PERMISSION)
                .join(PERMISSION)
                .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
                .where(ROLE_PERMISSION.ORGANISATION_ID.eq(organisationId))
                .and(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                .and(PERMISSION.PERMISSION_CODE.`in`(permissionCodes))
                .and(PERMISSION.STATUS.eq("ACTIVE"))
                .fetchOne(0, Int::class.java) == permissionCodes.size
        }
}

/** Adapter exposing bounded organisation administration queries. */
@Component
class JooqOrganisationQueryStore(
    private val store: JooqOrganisationBranchProvisioningStore,
) : OrganisationQueryStore {
    override fun findByCode(tenantCode: String): OrganisationSummary? = store.findByCode(tenantCode)

    override fun list(filter: OrganisationListFilter): OrganisationPage = store.list(filter)
}
