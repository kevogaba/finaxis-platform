package com.finaxis.platform.accounting.application.rules

import com.finaxis.platform.accounting.domain.PostingRule
import com.finaxis.platform.accounting.domain.PostingRuleLeg
import com.finaxis.platform.accounting.domain.PostingRuleSelector
import com.finaxis.platform.accounting.domain.PostingRuleVersion
import com.finaxis.platform.accounting.domain.PostingRuleVersionStatus
import com.finaxis.platform.accounting.domain.PostingRuleVersionTransition
import java.time.LocalDate
import java.util.UUID

/** A rule before the database has given it an identity. */
data class NewPostingRule(
    val organisationId: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val selector: PostingRuleSelector,
    val actorId: UUID,
)

/** A version before the database has given it an identity; always created as `DRAFT`. */
data class NewPostingRuleVersion(
    val organisationId: UUID,
    val ruleId: UUID,
    val versionNumber: Int,
    val effectiveFrom: LocalDate,
    val description: String?,
    val actorId: UUID,
)

/**
 * Read and write port over `posting_rule`, `posting_rule_version` and `posting_rule_leg`.
 *
 * Returns domain types, never generated jOOQ records, and is organisation-scoped by parameter on
 * every method. Leg writes are only legal while the owning version is `DRAFT`; the store does not
 * know that, the service does, and the store's lock is what makes the service's check hold. One
 * adapter implements both halves; they are declared apart so each stays a small, readable
 * contract - the rule catalogue on one side, the versions and their legs on the other.
 */
interface PostingRuleStore :
    PostingRuleCatalogStore,
    PostingRuleVersionStore

/** The rule half of [PostingRuleStore]: the catalogue of rules and their selectors. */
interface PostingRuleCatalogStore {
    /** Finds one rule by id within a tenant, or null. */
    fun findRule(
        organisationId: UUID,
        ruleId: UUID,
    ): PostingRule?

    /** Every rule of the tenant for [eventCode] - a handful of rows, the resolver's candidates. */
    fun findRulesForEvent(
        organisationId: UUID,
        eventCode: String,
    ): List<PostingRule>

    /** Inserts a rule and returns it with its generated id. */
    fun createRule(rule: NewPostingRule): PostingRule

    /**
     * Takes an exclusive row lock on the rule and returns it as read under that lock.
     *
     * Every version state change of a rule runs under this lock, so "the rule's current head" and
     * "the most recent submitter of this version" are stable answers for the rest of the
     * transaction - the same reason the GL-account lifecycle locks its account row.
     */
    fun lockRule(
        organisationId: UUID,
        ruleId: UUID,
    ): PostingRule?
}

/** The version half of [PostingRuleStore]: effective-dated versions and their legs. */
interface PostingRuleVersionStore {
    /** Finds one version by id within a tenant, or null. */
    fun findVersion(
        organisationId: UUID,
        versionId: UUID,
    ): PostingRuleVersion?

    /** Every version of a rule, oldest first. Bounded by construction: a rule has few versions. */
    fun findVersions(
        organisationId: UUID,
        ruleId: UUID,
    ): List<PostingRuleVersion>

    /** Inserts a `DRAFT` version and returns it with its generated id. */
    fun createVersion(version: NewPostingRuleVersion): PostingRuleVersion

    /** Replaces the editable fields of a version: effective-from and description. Draft only. */
    fun updateDraftVersion(
        organisationId: UUID,
        versionId: UUID,
        effectiveFrom: LocalDate,
        description: String?,
        actorId: UUID,
    ): Boolean

    /**
     * Moves a version's status, and optionally closes its window, as a compare-and-set on [from].
     *
     * [effectiveTo] is written only when non-null: activation leaves it alone, supersession and
     * retirement set it. Returns false when the status had already moved.
     */
    fun updateStatus(
        organisationId: UUID,
        versionId: UUID,
        from: PostingRuleVersionStatus,
        to: PostingRuleVersionStatus,
        effectiveTo: LocalDate?,
        reason: String?,
        actorId: UUID,
    ): Boolean

    /** The legs of a version in leg order. */
    fun findLegs(
        organisationId: UUID,
        versionId: UUID,
    ): List<PostingRuleLeg>

    /** Replaces every leg of a draft version in one statement pair. */
    fun replaceLegs(
        organisationId: UUID,
        versionId: UUID,
        legs: List<PostingRuleLeg>,
        actorId: UUID,
    )
}

/**
 * Who performed a given transition on a version last, read from
 * `posting_rule_version_transition_log`.
 */
interface PostingRuleVersionMakerResolver {
    /** The actor of the most recent [transition] on the version, or null when it never happened. */
    fun lastActorFor(
        organisationId: UUID,
        versionId: UUID,
        transition: PostingRuleVersionTransition,
    ): UUID?
}
