package com.finaxis.platform.accounting.application.rules

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.application.GlAccountPostingPolicy
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.ledger.PostingLegResolver
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingRule
import com.finaxis.platform.accounting.domain.PostingRuleLeg
import com.finaxis.platform.accounting.domain.PostingRulePolicy
import com.finaxis.platform.accounting.domain.PostingRuleSelector
import com.finaxis.platform.accounting.domain.PostingRuleVersion
import com.finaxis.platform.accounting.domain.PostingRuleVersionAggregate
import com.finaxis.platform.accounting.domain.PostingRuleVersionLifecycle
import com.finaxis.platform.accounting.domain.PostingRuleVersionStatus
import com.finaxis.platform.accounting.domain.PostingRuleVersionTransition
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.transitions.TransitionException
import com.finaxis.platform.common.transitions.TransitionExecution
import com.finaxis.platform.common.transitions.TransitionExecutor
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Posting rules and their versions: creation, drafting, maker-checker lifecycle, and dry run.
 *
 * The lifecycle follows the chart-of-accounts service exactly, because it is the same control:
 * approval requires `posting_rule.approve` and an actor who is not the version's most recent
 * submitter, resolved from `posting_rule_version_transition_log` under the **rule's** row lock -
 * every version transition of a rule takes that lock, so the answer cannot change underneath the
 * check. Activation is where a rule's head changes, so it is also where the previous head is
 * superseded: its window is closed the day before the successor's `effective_from`, in the same
 * transaction, and `ex_posting_rule_version_no_overlap` backs the arithmetic up.
 *
 * A version's legs and `effective_from` are editable in `DRAFT` only. Once submitted they are what
 * the checker approves; once approved they are what historical postings cite. The store does not
 * know that rule; this service does, under the lock.
 *
 * Dry run resolves an intent through the same [PostingLegResolver] the engine uses and returns the
 * legs it would post, in a read-only transaction that writes nothing - which is the whole point.
 */
@Service
@Suppress("TooManyFunctions")
class PostingRuleService(
    private val rules: PostingRuleStore,
    private val accounts: GlAccountStore,
    private val makers: PostingRuleVersionMakerResolver,
    private val permissions: AccountingPermissionGuard,
    private val transitions: TransitionExecutor,
    private val auditService: AuditService,
    private val resolver: PostingLegResolver,
) {
    /** Creates a rule with no versions yet. */
    @Transactional
    fun createRule(command: CreatePostingRuleCommand): PostingRule {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.POSTING_RULE_CREATE,
        )
        val rule =
            translatingDuplicate {
                rules.createRule(
                    NewPostingRule(
                        organisationId = command.organisationId,
                        code = command.code,
                        name = command.name,
                        description = command.description,
                        selector = command.selector,
                        actorId = command.actorId,
                    ),
                )
            }
        audit(
            command.organisationId,
            command.actorId,
            AccountingAuditActions.POSTING_RULE_CREATE,
            rule.id,
            null,
        )
        return rule
    }

    /** Creates the next `DRAFT` version of a rule with the supplied legs. */
    @Transactional
    fun createVersion(command: CreatePostingRuleVersionCommand): PostingRuleVersion {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.POSTING_RULE_UPDATE,
        )
        val rule = lockRule(command.organisationId, command.ruleId)
        val existing = rules.findVersions(command.organisationId, rule.id)
        val inProgress =
            setOf(PostingRuleVersionStatus.DRAFT, PostingRuleVersionStatus.PENDING_APPROVAL)
        if (existing.any { it.status in inProgress }) {
            throw ConflictException(
                code = PostingErrorCodes.POSTING_RULE_VERSION_IN_PROGRESS,
                safeDetail = "The rule already has a draft or pending version; finish it first.",
            )
        }
        val version =
            rules.createVersion(
                NewPostingRuleVersion(
                    organisationId = command.organisationId,
                    ruleId = rule.id,
                    versionNumber = (existing.maxOfOrNull { it.versionNumber } ?: 0) + 1,
                    effectiveFrom = command.effectiveFrom,
                    description = command.description,
                    actorId = command.actorId,
                ),
            )
        validateLegs(command.organisationId, command.legs)
        rules.replaceLegs(command.organisationId, version.id, command.legs, command.actorId)
        audit(
            command.organisationId,
            command.actorId,
            AccountingAuditActions.POSTING_RULE_CREATE_VERSION,
            version.id,
            null,
        )
        return version
    }

    /** Replaces a draft version's legs, effective-from date and description. */
    @Transactional
    fun amendDraft(command: AmendPostingRuleVersionCommand): PostingRuleVersion {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.POSTING_RULE_UPDATE,
        )
        val version = lockedVersion(command.organisationId, command.versionId)
        if (version.status != PostingRuleVersionStatus.DRAFT) {
            throw ConflictException(
                code = PostingErrorCodes.POSTING_RULE_VERSION_NOT_EDITABLE,
                safeDetail =
                    "Only a DRAFT version can be amended; a ${version.status} version is frozen.",
            )
        }
        validateLegs(command.organisationId, command.legs)
        rules.replaceLegs(command.organisationId, version.id, command.legs, command.actorId)
        if (!rules.updateDraftVersion(
                command.organisationId,
                version.id,
                command.effectiveFrom,
                command.description,
                command.actorId,
            )
        ) {
            throw ConflictException(
                code = PostingErrorCodes.POSTING_RULE_VERSION_STALE,
                safeDetail = "The version changed while it was being amended; reload and retry.",
            )
        }
        return requireNotNull(rules.findVersion(command.organisationId, version.id))
    }

    /** Submits a draft for approval. The maker's act. */
    @Transactional
    fun submit(command: PostingRuleVersionTransitionCommand): PostingRuleVersion =
        move(
            command,
            PostingRuleVersionTransition.SUBMIT,
            AccountingPermissions.POSTING_RULE_SUBMIT,
        ) { version ->
            PostingRulePolicy.requireWellFormed(rules.findLegs(command.organisationId, version.id))
        }

    /**
     * Approves and activates a submitted version, superseding the rule's current head.
     *
     * Refuses the actor who submitted it. Supersession happens here and nowhere else: the previous
     * `ACTIVE` version's window is closed the day before the new one's `effective_from`, so the two
     * never govern the same date. A successor that would start before the current head began is
     * refused, because closing the head's window would leave dates it already governed with no
     * version.
     */
    @Transactional
    fun approve(command: PostingRuleVersionTransitionCommand): PostingRuleVersion {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.POSTING_RULE_APPROVE,
        )
        return move(
            command,
            PostingRuleVersionTransition.APPROVE,
            permissionCode = null,
        ) { version ->
            requireDifferentActorFromTheSubmitter(command, version)
            val legs = rules.findLegs(command.organisationId, version.id)
            PostingRulePolicy.requireWellFormed(legs)
            requirePostableAccounts(command.organisationId, legs)
            supersedeCurrentHead(command, version)
        }
    }

    /** Rejects a submitted version back to `DRAFT`, with a mandatory reason. */
    @Transactional
    fun reject(command: PostingRuleVersionTransitionCommand): PostingRuleVersion {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.POSTING_RULE_APPROVE,
        )
        requireReason(command)
        return move(command, PostingRuleVersionTransition.REJECT, permissionCode = null)
    }

    /** Retires the rule's active head on [PostingRuleVersionTransitionCommand.effectiveTo]. */
    @Transactional
    fun retire(command: PostingRuleVersionTransitionCommand): PostingRuleVersion {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.POSTING_RULE_APPROVE,
        )
        requireReason(command)
        val closeOn =
            command.effectiveTo
                ?: throw InvalidOperationException(
                    code = PostingErrorCodes.POSTING_RULE_EFFECTIVE_TO_REQUIRED,
                    safeDetail = "Retiring a version needs the last date it governs.",
                )
        return move(
            command,
            PostingRuleVersionTransition.RETIRE,
            permissionCode = null,
            closeOn = closeOn,
        ) { version ->
            requireDifferentActorFromTheApprover(command, version)
            if (closeOn.isBefore(version.effectiveFrom)) {
                throw InvalidOperationException(
                    code = PostingErrorCodes.POSTING_RULE_WINDOW_INVALID,
                    safeDetail = "A version cannot be retired before the date it took effect.",
                )
            }
        }
    }

    /**
     * Resolves an intent exactly as a posting would, without posting.
     *
     * Read-only by declaration and by construction: the resolver reads rules and accounts and
     * writes nothing, so an administrator can test a configuration against real facts and see the
     * legs, the amounts and the version that would answer, with no journal anywhere.
     */
    @Transactional(readOnly = true)
    fun dryRun(command: PostingRuleDryRunCommand): PostingRuleDryRunResult {
        permissions.requireTenantPermission(
            command.context.actorId,
            command.context.organisationId,
            AccountingPermissions.POSTING_RULE_VIEW,
        )
        val resolved = resolver.resolve(command.context, command.intent, command.postingDate)
        return PostingRuleDryRunResult(
            postingRuleVersionId = requireNotNull(resolved.postingRuleVersionId),
            legs = resolved.legs,
        )
    }

    /** Reads one version, permission-gated and tenant-scoped. */
    @Transactional(readOnly = true)
    fun getVersion(
        organisationId: UUID,
        versionId: UUID,
        actorId: UUID,
    ): PostingRuleVersion {
        permissions.requireTenantPermission(
            actorId,
            organisationId,
            AccountingPermissions.POSTING_RULE_VIEW,
        )
        return rules.findVersion(organisationId, versionId) ?: throw notFound()
    }

    /** Reads the legs of one version, permission-gated and tenant-scoped. */
    @Transactional(readOnly = true)
    fun getLegs(
        organisationId: UUID,
        versionId: UUID,
        actorId: UUID,
    ): List<PostingRuleLeg> {
        permissions.requireTenantPermission(
            actorId,
            organisationId,
            AccountingPermissions.POSTING_RULE_VIEW,
        )
        rules.findVersion(organisationId, versionId) ?: throw notFound()
        return rules.findLegs(organisationId, versionId)
    }

    // ---- lifecycle mechanics ----------------------------------------------------------------

    /**
     * Locks the rule, re-reads the version under it, validates against that snapshot, moves the
     * status and runs the transition. Same shape, same reasons, as the GL-account lifecycle.
     */
    @Suppress("LongParameterList")
    private fun move(
        command: PostingRuleVersionTransitionCommand,
        transition: PostingRuleVersionTransition,
        permissionCode: String?,
        closeOn: LocalDate? = null,
        underLock: (PostingRuleVersion) -> Unit = {},
    ): PostingRuleVersion {
        if (permissionCode != null) {
            permissions.requireTenantPermission(
                command.actorId,
                command.organisationId,
                permissionCode,
            )
        }
        val current = lockedVersion(command.organisationId, command.versionId)
        val definition = requireLegal(current, transition)
        underLock(current)

        if (!rules.updateStatus(
                command.organisationId,
                current.id,
                current.status,
                definition.to,
                closeOn,
                command.reason,
                command.actorId,
            )
        ) {
            throw ConflictException(
                code = PostingErrorCodes.POSTING_RULE_VERSION_STALE,
                safeDetail = "The version's status changed while it was being moved.",
            )
        }
        transitions.execute(
            TransitionExecution(
                aggregate = PostingRuleVersionAggregate(current.id, current.status),
                graph = PostingRuleVersionLifecycle.GRAPH,
                transition = transition,
                actor = TransitionActor(type = ACTOR_TYPE_USER, id = command.actorId.toString()),
                command =
                    TransitionCommand(
                        reason = command.reason,
                        metadata = mapOf(ORGANISATION_ID to command.organisationId.toString()),
                    ),
                persist = { it },
            ),
        )
        auditActionFor(transition)?.let { action ->
            audit(command.organisationId, command.actorId, action, current.id, command.reason)
        }
        return requireNotNull(rules.findVersion(command.organisationId, current.id))
    }

    /**
     * The audit action a transition records, or null when the transition log is the whole record.
     *
     * Approval and retirement both change what a product module will post and are `CRITICAL`;
     * submission and rejection move a draft that has never governed anything.
     */
    private fun auditActionFor(transition: PostingRuleVersionTransition): String? =
        when (transition) {
            PostingRuleVersionTransition.APPROVE -> AccountingAuditActions.POSTING_RULE_APPROVE
            PostingRuleVersionTransition.RETIRE -> AccountingAuditActions.POSTING_RULE_RETIRE
            else -> null
        }

    /** Locks the owning rule, then re-reads the version so the decision uses the locked state. */
    private fun lockedVersion(
        organisationId: UUID,
        versionId: UUID,
    ): PostingRuleVersion {
        val version = rules.findVersion(organisationId, versionId) ?: throw notFound()
        lockRule(organisationId, version.ruleId)
        return rules.findVersion(organisationId, versionId) ?: throw notFound()
    }

    private fun lockRule(
        organisationId: UUID,
        ruleId: UUID,
    ): PostingRule =
        rules.lockRule(organisationId, ruleId)
            ?: throw ResourceNotFoundException(
                code = PostingErrorCodes.POSTING_RULE_NOT_FOUND,
                safeDetail = "The posting rule does not exist.",
            )

    private fun requireLegal(
        current: PostingRuleVersion,
        transition: PostingRuleVersionTransition,
    ) = try {
        PostingRuleVersionLifecycle.GRAPH.requireDefinition(current.status, transition)
    } catch (ex: TransitionException) {
        throw ConflictException(
            code = PostingErrorCodes.POSTING_RULE_TRANSITION_NOT_ALLOWED,
            safeDetail =
                "A ${current.status} posting-rule version cannot undergo ${transition.name}.",
            cause = ex,
        )
    }

    private fun requireDifferentActorFromTheSubmitter(
        command: PostingRuleVersionTransitionCommand,
        version: PostingRuleVersion,
    ) {
        val submitter =
            makers.lastActorFor(
                command.organisationId,
                version.id,
                PostingRuleVersionTransition.SUBMIT,
            )
        if (submitter != null && submitter == command.actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.POSTING_RULE_SELF_APPROVAL,
                safeDetail = "The actor who submitted a posting-rule version cannot approve it.",
            )
        }
    }

    private fun requireDifferentActorFromTheApprover(
        command: PostingRuleVersionTransitionCommand,
        version: PostingRuleVersion,
    ) {
        val approver =
            makers.lastActorFor(
                command.organisationId,
                version.id,
                PostingRuleVersionTransition.APPROVE,
            )
        if (approver != null && approver == command.actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.POSTING_RULE_SELF_APPROVAL,
                safeDetail = "The actor who activated a posting-rule version cannot retire it.",
            )
        }
    }

    /**
     * Closes the rule's current head the day before [successor] takes effect.
     *
     * A head whose `effective_from` is not before the successor's cannot be closed without leaving
     * its own dates ungoverned, so a successor may only start after the current head started.
     */
    private fun supersedeCurrentHead(
        command: PostingRuleVersionTransitionCommand,
        successor: PostingRuleVersion,
    ) {
        val head =
            rules
                .findVersions(command.organisationId, successor.ruleId)
                .singleOrNull { it.status == PostingRuleVersionStatus.ACTIVE }
                ?: return
        if (!head.effectiveFrom.isBefore(successor.effectiveFrom)) {
            throw ConflictException(
                code = PostingErrorCodes.POSTING_RULE_WINDOW_INVALID,
                safeDetail =
                    "A successor version must take effect after ${head.effectiveFrom}, when the " +
                        "current version began.",
            )
        }
        move(
            PostingRuleVersionTransitionCommand(
                organisationId = command.organisationId,
                actorId = command.actorId,
                versionId = head.id,
                reason = "Superseded by version ${successor.versionNumber}",
            ),
            PostingRuleVersionTransition.SUPERSEDE,
            permissionCode = null,
            closeOn = successor.effectiveFrom.minusDays(1),
        )
    }

    private fun validateLegs(
        organisationId: UUID,
        legs: List<PostingRuleLeg>,
    ) {
        if (legs.isEmpty() || legs.map { it.legNumber }.toSet() != (1..legs.size).toSet()) {
            throw InvalidOperationException(
                code = PostingErrorCodes.POSTING_RULE_LEGS_INVALID,
                safeDetail = "Legs must be numbered 1..n with no gaps.",
            )
        }
        legs.forEach(::requireLegFieldsStorable)
        requirePostableAccounts(organisationId, legs)
    }

    /**
     * The field-level rules `V8` also enforces, checked here so they are named application
     * failures rather than a constraint violation arriving as a data-access exception and a 500.
     * The database stays the authority; this is the friendlier path to the same answer.
     */
    private fun requireLegFieldsStorable(leg: PostingRuleLeg) {
        val invalid =
            !FACT_CODE.matches(leg.amountSource) ||
                leg.amountPercentage <= BigDecimal.ZERO ||
                leg.amountPercentage > ONE_HUNDRED ||
                (leg.narrative?.length ?: 0) > MAX_LEG_NARRATIVE
        if (invalid) {
            throw InvalidOperationException(
                code = PostingErrorCodes.POSTING_RULE_LEGS_INVALID,
                safeDetail =
                    "A leg needs an upper-case fact code, a share in (0, 100], and a narrative " +
                        "of at most $MAX_LEG_NARRATIVE characters.",
            )
        }
    }

    private fun requirePostableAccounts(
        organisationId: UUID,
        legs: List<PostingRuleLeg>,
    ) {
        legs.map { it.accountId }.distinct().forEach { accountId ->
            val account =
                accounts.findById(organisationId, accountId)
                    ?: throw InvalidOperationException(
                        code = PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
                        safeDetail = "A leg names a general-ledger account that does not exist.",
                    )
            GlAccountPostingPolicy.requirePostable(account)
        }
    }

    private fun requireReason(command: PostingRuleVersionTransitionCommand) {
        if (command.reason.isNullOrBlank()) {
            throw InvalidOperationException(
                code = PostingErrorCodes.POSTING_RULE_REASON_REQUIRED,
                safeDetail = "This posting-rule operation requires a reason.",
            )
        }
    }

    private fun <T> translatingDuplicate(block: () -> T): T =
        try {
            block()
        } catch (ex: DuplicateKeyException) {
            throw ConflictException(
                code = PostingErrorCodes.POSTING_RULE_DUPLICATE,
                safeDetail = "A posting rule with this code or selector already exists.",
                cause = ex,
            )
        }

    private fun audit(
        organisationId: UUID,
        actorId: UUID,
        action: String,
        resourceId: UUID,
        reason: String?,
    ) {
        auditService.record(
            AuditCommand(
                actorType = ACTOR_TYPE_USER,
                actorId = actorId.toString(),
                tenantId = organisationId.toString(),
                action = action,
                resourceType = RESOURCE_TYPE,
                resourceId = resourceId.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity =
                    if (action in CRITICAL_ACTIONS) AuditSeverity.CRITICAL else AuditSeverity.HIGH,
                reason = reason,
            ),
        )
    }

    private fun notFound() =
        ResourceNotFoundException(
            code = PostingErrorCodes.POSTING_RULE_VERSION_NOT_FOUND,
            safeDetail = "The posting-rule version does not exist.",
        )

    private companion object {
        /** The transitions an auditor must be able to find without reading a transition log. */
        val CRITICAL_ACTIONS =
            setOf(
                AccountingAuditActions.POSTING_RULE_APPROVE,
                AccountingAuditActions.POSTING_RULE_RETIRE,
            )

        /** `chk_posting_rule_leg_amount_source`, `_percentage` and `_narrative`, all from `V8`. */
        val FACT_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
        val ONE_HUNDRED: BigDecimal = BigDecimal(100)
        const val MAX_LEG_NARRATIVE = 200

        const val ACTOR_TYPE_USER = "USER"
        const val RESOURCE_TYPE = "POSTING_RULE"
        const val ORGANISATION_ID = "organisationId"
    }
}

/** Creates a rule. */
data class CreatePostingRuleCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val code: String,
    val name: String,
    val selector: PostingRuleSelector,
    val description: String? = null,
)

/** Creates the next draft version of a rule, with its legs. */
data class CreatePostingRuleVersionCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val ruleId: UUID,
    val effectiveFrom: LocalDate,
    val legs: List<PostingRuleLeg>,
    val description: String? = null,
)

/** Replaces a draft version's content. */
data class AmendPostingRuleVersionCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val versionId: UUID,
    val effectiveFrom: LocalDate,
    val legs: List<PostingRuleLeg>,
    val description: String? = null,
)

/** Moves one version through the lifecycle. */
data class PostingRuleVersionTransitionCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val versionId: UUID,
    val reason: String? = null,
    val effectiveTo: LocalDate? = null,
)

/** Asks what a posting would do, without doing it. */
data class PostingRuleDryRunCommand(
    val context: AccountingContext,
    val intent: PostingIntent.Facts,
    val postingDate: LocalDate,
)

/** The version and legs a posting of the intent would produce. */
data class PostingRuleDryRunResult(
    val postingRuleVersionId: UUID,
    val legs: List<PostingLeg>,
)
