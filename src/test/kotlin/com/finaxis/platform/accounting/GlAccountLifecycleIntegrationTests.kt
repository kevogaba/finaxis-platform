package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ChartOfAccountsService
import com.finaxis.platform.accounting.application.CreateGlAccountCommand
import com.finaxis.platform.accounting.application.GlAccountLifecycleService
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.GlAccountTransitionCommand
import com.finaxis.platform.accounting.application.UpdateGlAccountCommand
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleCommand
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleVersionCommand
import com.finaxis.platform.accounting.application.rules.PostingRuleService
import com.finaxis.platform.accounting.application.rules.PostingRuleVersionTransitionCommand
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountResolution
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.domain.PostingRuleLeg
import com.finaxis.platform.accounting.domain.PostingRuleSelector
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The chart-of-accounts lifecycle under the FSM, and the separation of duties it enforces.
 *
 * The control being tested is **actor identity on one record**, not a partition of permissions. A
 * role holding both `gl_account.submit` and `gl_account.approve` is legitimate and `TENANT_ADMIN`
 * deliberately holds both — `AccountingSeparationOfDutiesPolicyTests` says so explicitly. What must
 * be impossible is one person performing both acts on the same account, and both actors here hold
 * both codes precisely so that a passing test cannot be explained by a missing permission.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GlAccountLifecycleIntegrationTests(
    private val lifecycle: GlAccountLifecycleService,
    private val chart: ChartOfAccountsService,
    private val accounts: GlAccountStore,
    private val rules: PostingRuleService,
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `an account is submitted, approved and deactivated, each leaving history and audit`() {
        // The positive control, plus the assertion that matters beside it: a status change with no
        // transition row is the audit gap the FSM exists to close.
        val organisationId = organisation("full")
        val account = draft(organisationId, "1010")

        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }
        val approved =
            withRequestContext { lifecycle.approve(command(organisationId, account.id, checker())) }
        withRequestContext {
            lifecycle.deactivate(command(organisationId, account.id, MAKER, "Branch closed"))
        }

        assertEquals(GlAccountStatus.ACTIVE, approved.status)
        assertEquals(
            GlAccountStatus.INACTIVE,
            accounts.findById(organisationId, account.id)?.status,
            "the row survives deactivation, because history that references it stays resolvable",
        )
        assertEquals(
            listOf("SUBMIT", "APPROVE", "DEACTIVATE"),
            transitionNames(organisationId, account.id),
        )
        assertEquals(
            2,
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ORGANISATION_ID
                    .eq(organisationId)
                    .and(AUDIT_EVENT.ACTION.`in`("gl_account.approve", "gl_account.deactivate")),
            ),
            "the two high-risk transitions must each leave an audit row",
        )
    }

    @Test
    fun `the actor who submitted an account cannot approve it`() {
        val organisationId = organisation("self")
        val account = draft(organisationId, "1010")
        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }

        val failure =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext { lifecycle.approve(command(organisationId, account.id, MAKER)) }
            }

        assertEquals("accounting.gl_account_self_approval", failure.code)
        assertEquals(
            GlAccountStatus.PENDING_APPROVAL,
            accounts.findById(organisationId, account.id)?.status,
            "a refused approval must leave the account awaiting one",
        )
    }

    @Test
    fun `the maker of the latest submission is who counts, not the account's creator`() {
        // An account created by one actor, rejected, and resubmitted by another has a different
        // maker from its creator. Reading gl_account.created_by instead of the transition log
        // would let the resubmitter approve their own submission.
        val organisationId = organisation("resubmit")
        val account = draft(organisationId, "1010")
        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }
        withRequestContext {
            lifecycle.reject(command(organisationId, account.id, checker(), "Wrong class"))
        }
        withRequestContext { lifecycle.submit(command(organisationId, account.id, checker())) }

        // MAKER created and first submitted it, so approving is now legitimate for them.
        withRequestContext { lifecycle.approve(command(organisationId, account.id, MAKER)) }

        // The mirror image on a second account, which is what distinguishes "the latest submitter"
        // from "the creator" and from "anyone who ever submitted". Here the *checker* submits first
        // and MAKER resubmits, so the rule must now block MAKER - the actor it just permitted on
        // the account above - and permit the checker, who submitted but no longer most recently.
        // An earlier revision only submitted this account once, which re-asserted plain
        // self-approval and would have passed against an implementation reading created_by.
        val second = draft(organisationId, "1020")
        withRequestContext { lifecycle.submit(command(organisationId, second.id, checker())) }
        withRequestContext {
            lifecycle.reject(command(organisationId, second.id, MAKER, "Wrong parent"))
        }
        withRequestContext { lifecycle.submit(command(organisationId, second.id, MAKER)) }

        val failure =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext { lifecycle.approve(command(organisationId, second.id, MAKER)) }
            }
        assertEquals("accounting.gl_account_self_approval", failure.code)

        withRequestContext { lifecycle.approve(command(organisationId, second.id, checker())) }
        assertEquals(
            GlAccountStatus.ACTIVE,
            accounts.findById(organisationId, second.id)?.status,
            "the earlier submitter is not the latest one, so approving is legitimate for them",
        )
    }

    @Test
    fun `the actor who approved an account cannot also deactivate it`() {
        // INV-10 names GL-account deactivation as privileged, and an earlier revision made it the
        // one privileged transition here a single actor could complete alone: permission plus a
        // reason and the account was out of the chart. Deactivation changes the shape of every
        // future report, so it takes two actors like every other privileged move.
        val organisationId = organisation("deactivate-self")
        val account = draft(organisationId, "1010")
        val approver = checker()
        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }
        withRequestContext { lifecycle.approve(command(organisationId, account.id, approver)) }

        val failure =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext {
                    lifecycle.deactivate(command(organisationId, account.id, approver, "Closing"))
                }
            }

        assertEquals("accounting.gl_account_self_approval", failure.code)
        assertEquals(
            GlAccountStatus.ACTIVE,
            accounts.findById(organisationId, account.id)?.status,
            "a refused deactivation must leave the account in service",
        )

        // And the rule is not simply "nobody may deactivate".
        withRequestContext {
            lifecycle.deactivate(command(organisationId, account.id, MAKER, "Closing"))
        }
        assertEquals(
            GlAccountStatus.INACTIVE,
            accounts.findById(organisationId, account.id)?.status,
        )
    }

    @Test
    fun `a rejection is audited as a rejection, not as an ordinary amendment`() {
        // The audit query API filters on an exact action. An earlier revision recorded a rejection
        // as gl_account.update, so a rejection was unsearchable and indistinguishable from an edit.
        val organisationId = organisation("reject-audit")
        val account = draft(organisationId, "1010")
        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }

        withRequestContext {
            lifecycle.reject(command(organisationId, account.id, checker(), "Wrong class"))
        }

        assertEquals(
            1,
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ORGANISATION_ID
                    .eq(organisationId)
                    .and(AUDIT_EVENT.ACTION.eq("gl_account.reject")),
            ),
            "a rejection must be findable by its own action",
        )
        assertEquals(
            0,
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ORGANISATION_ID
                    .eq(organisationId)
                    .and(AUDIT_EVENT.ACTION.eq("gl_account.update")),
            ),
            "and must not be recorded as an amendment",
        )
    }

    @Test
    fun `an amendment cannot go around the approval it would otherwise need`() {
        // Preserving the stored status is not enough. An actor holding only gl_account.update could
        // rewrite the code, placement, usage or posting flag of an already approved account: the
        // status stayed ACTIVE, nothing was re-approved, and what the checker approved was no
        // longer what the ledger had.
        val organisationId = organisation("amend")
        val account = draft(organisationId, "1010")

        // In DRAFT, anything goes - nobody has approved it yet.
        withRequestContext {
            chart.update(
                UpdateGlAccountCommand(
                    organisationId = organisationId,
                    actorId = MAKER,
                    accountId = account.id,
                    code = AccountCode("1011"),
                ),
            )
        }

        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }

        // Under review, nothing at all: the checker must approve the record they were shown.
        val pending =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    chart.update(
                        UpdateGlAccountCommand(
                            organisationId = organisationId,
                            actorId = MAKER,
                            accountId = account.id,
                            name = "Renamed mid-review",
                        ),
                    )
                }
            }
        assertEquals("accounting.gl_account_not_amendable", pending.code)

        withRequestContext { lifecycle.approve(command(organisationId, account.id, checker())) }

        // Approved: presentation only.
        withRequestContext {
            chart.update(
                UpdateGlAccountCommand(
                    organisationId = organisationId,
                    actorId = MAKER,
                    accountId = account.id,
                    name = "Cash at bank",
                ),
            )
        }
        val structural =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    chart.update(
                        UpdateGlAccountCommand(
                            organisationId = organisationId,
                            actorId = MAKER,
                            accountId = account.id,
                            usage = AccountUsage.HEADER,
                        ),
                    )
                }
            }
        assertEquals("accounting.gl_account_not_amendable", structural.code)
        assertEquals(
            AccountUsage.POSTABLE,
            accounts.findById(organisationId, account.id)?.usage,
            "the refused amendment must not have been applied",
        )
    }

    @Test
    fun `a concurrent transition on the same account loses instead of overwriting`() {
        // The compare-and-set on the observed status. An earlier revision read the account, copied
        // it with a new status and wrote the whole row, so two approvals of one account both
        // succeeded and the later simply overwrote the earlier - with two APPROVE rows in the
        // transition log for one approval.
        val organisationId = organisation("concurrent")
        val account = draft(organisationId, "1010")
        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }

        withRequestContext { lifecycle.approve(command(organisationId, account.id, checker())) }

        // The same PENDING_APPROVAL premise, now stale.
        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    lifecycle.approve(command(organisationId, account.id, checker()))
                }
            }

        assertEquals("accounting.gl_account_transition_not_allowed", failure.code)
        assertEquals(1, transitionNames(organisationId, account.id).count { it == "APPROVE" })
    }

    @Test
    fun `an unauthorised caller is refused before learning that a reason is required`() {
        // reject and deactivate validated the reason first, so an actor with no accounting
        // permission at all learned from the reason_required response that the operation exists
        // and what it needs. Every other entry point gates on the permission first.
        val organisationId = organisation("order")
        val account = draft(organisationId, "1010")
        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }

        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                lifecycle.reject(command(organisationId, account.id, STRANGER, reason = null))
            }
        }
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                lifecycle.deactivate(command(organisationId, account.id, STRANGER, reason = null))
            }
        }
    }

    @Test
    fun `the reason is written to the account row, not only to the transition log`() {
        // status_reason is what the ERD documents as the rejection or deactivation reason on the
        // row itself; an earlier revision never wrote it.
        val organisationId = organisation("reason-column")
        val account = draft(organisationId, "1010")
        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }

        withRequestContext {
            lifecycle.reject(command(organisationId, account.id, checker(), "Wrong class"))
        }

        assertEquals("Wrong class", accounts.findById(organisationId, account.id)?.statusReason)
    }

    @Test
    fun `an illegal transition is refused and leaves no history`() {
        // Approving a DRAFT account skips the submission entirely, which is how the maker-checker
        // control would be bypassed: with no SUBMIT row there is no maker to compare against.
        val organisationId = organisation("illegal")
        val account = draft(organisationId, "1010")

        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    lifecycle.approve(command(organisationId, account.id, checker()))
                }
            }

        assertEquals("accounting.gl_account_transition_not_allowed", failure.code)
        assertEquals(GlAccountStatus.DRAFT, accounts.findById(organisationId, account.id)?.status)
        assertEquals(emptyList(), transitionNames(organisationId, account.id))
    }

    @Test
    fun `rejecting and deactivating both require a reason`() {
        val organisationId = organisation("reason")
        val account = draft(organisationId, "1010")
        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }

        listOf(null, "", "   ").forEach { reason ->
            val failure =
                assertFailsWith<ApplicationException> {
                    withRequestContext {
                        lifecycle.reject(command(organisationId, account.id, checker(), reason))
                    }
                }
            assertEquals("accounting.gl_account_reason_required", failure.code)
        }
        assertEquals(
            GlAccountStatus.PENDING_APPROVAL,
            accounts.findById(organisationId, account.id)?.status,
        )
    }

    @Test
    fun `deactivating an account referenced by an active posting rule is refused`() {
        // ADR 0023's third finding: without this guard the resolver keeps selecting the version -
        // it is still ACTIVE - and every posting through it then fails at the engine's account
        // eligibility check instead, far from the change that caused it. Checked under the same
        // exclusive lock as the different-actor control, alongside it in GlAccountLifecycleService.
        val organisationId = organisation("active-rule")
        val account = accountReferencedByAnActiveRule(organisationId)

        val failure =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    lifecycle.deactivate(command(organisationId, account.id, MAKER, "Closing"))
                }
            }

        assertEquals("accounting.gl_account_referenced_by_active_rule", failure.code)
        assertEquals(
            GlAccountStatus.ACTIVE,
            accounts.findById(organisationId, account.id)?.status,
            "the refused deactivation must leave the account untouched",
        )
    }

    @Test
    fun `deactivating an account referenced only by a retired posting rule is still refused`() {
        // Codex review on PR #97, catching what the original `hasActivePostingRuleLegs` query
        // missed: PostingRuleVersion.governs() resolves a posting against any *approved* status -
        // ACTIVE, SUPERSEDED or RETIRED - so a backdated posting can still route through a version
        // this account was retired from. Deactivation must refuse it exactly as it refuses an
        // ACTIVE one.
        val organisationId = organisation("retired-rule")
        val account = accountReferencedByARetiredRule(organisationId)

        val failure =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    lifecycle.deactivate(command(organisationId, account.id, MAKER, "Closing"))
                }
            }

        assertEquals("accounting.gl_account_referenced_by_active_rule", failure.code)
        assertEquals(
            GlAccountStatus.ACTIVE,
            accounts.findById(organisationId, account.id)?.status,
            "the refused deactivation must leave the account untouched",
        )
    }

    @Test
    fun `every transition is permission gated and tenant scoped`() {
        val organisationId = organisation("permission")
        val other = organisation("permission-other")
        val account = draft(organisationId, "1010")

        assertFailsWith<ForbiddenOperationException> {
            withRequestContext { lifecycle.submit(command(organisationId, account.id, STRANGER)) }
        }
        // A command naming another tenant resolves to nothing rather than to this tenant's account.
        assertFailsWith<ApplicationException> {
            withRequestContext { lifecycle.submit(command(other, account.id, MAKER)) }
        }
        assertEquals(GlAccountStatus.DRAFT, accounts.findById(organisationId, account.id)?.status)
    }

    private fun organisation(label: String): UUID {
        val organisationId = tenants.createActiveOrganisation("coa-fsm-$label", MAKER)
        tenants.grantTenantAdmin(organisationId, checker())
        return organisationId
    }

    private fun draft(
        organisationId: UUID,
        code: String,
    ): GlAccount =
        withRequestContext {
            chart.create(
                CreateGlAccountCommand(
                    organisationId = organisationId,
                    actorId = MAKER,
                    code = AccountCode(code),
                    name = "Account $code",
                    accountClass = AccountClass.ASSET,
                    usage = AccountUsage.POSTABLE,
                ),
            )
        }

    private fun command(
        organisationId: UUID,
        accountId: UUID,
        actorId: UUID,
        reason: String? = "Because",
    ) = GlAccountTransitionCommand(
        organisationId = organisationId,
        accountId = accountId,
        actorId = actorId,
        reason = reason,
    )

    /**
     * An `ACTIVE` account named by a leg of an `ACTIVE` posting-rule version, and a second `ACTIVE`
     * account for the version's other leg - the fixture
     * `deactivating an account referenced by an active posting rule is refused` needs.
     */
    private fun accountReferencedByAnActiveRule(organisationId: UUID): GlAccount {
        val account = submittedAndApproved(organisationId, "1010")
        val creditAccount = submittedAndApproved(organisationId, "2010")
        grantDirectly(organisationId, checker(), AccountingPermissions.POSTING_RULE_APPROVE)

        val ruleId =
            withRequestContext {
                rules
                    .createRule(
                        CreatePostingRuleCommand(
                            organisationId = organisationId,
                            actorId = MAKER,
                            code = "ACTIVE-RULE",
                            name = "Active rule",
                            selector = PostingRuleSelector("ACTIVE_RULE_EVENT"),
                        ),
                    ).id
            }
        val version =
            withRequestContext {
                rules.createVersion(
                    CreatePostingRuleVersionCommand(
                        organisationId = organisationId,
                        actorId = MAKER,
                        ruleId = ruleId,
                        effectiveFrom = LocalDate.of(2026, 1, 1),
                        legs =
                            listOf(
                                ruleLeg(1, PostingSide.DEBIT, account.id),
                                ruleLeg(2, PostingSide.CREDIT, creditAccount.id),
                            ),
                    ),
                )
            }
        withRequestContext {
            rules.submit(PostingRuleVersionTransitionCommand(organisationId, MAKER, version.id))
        }
        withRequestContext {
            rules.approve(
                PostingRuleVersionTransitionCommand(organisationId, checker(), version.id),
            )
        }
        return account
    }

    /**
     * An `ACTIVE` account named by a leg of a version retired straight after activation - the
     * fixture `deactivating an account referenced only by a retired posting rule is still refused`
     * needs. [MAKER] retires it: the retiring actor must differ from whoever approved it
     * ([PostingRuleService.retire]'s own separation-of-duties check), and [MAKER] holds
     * `posting_rule.approve` here for exactly that, granted alongside [checker]'s.
     */
    private fun accountReferencedByARetiredRule(organisationId: UUID): GlAccount {
        val account = submittedAndApproved(organisationId, "1010")
        val creditAccount = submittedAndApproved(organisationId, "2010")
        grantDirectly(organisationId, checker(), AccountingPermissions.POSTING_RULE_APPROVE)
        grantDirectly(organisationId, MAKER, AccountingPermissions.POSTING_RULE_APPROVE)

        val ruleId =
            withRequestContext {
                rules
                    .createRule(
                        CreatePostingRuleCommand(
                            organisationId = organisationId,
                            actorId = MAKER,
                            code = "RETIRED-RULE",
                            name = "Retired rule",
                            selector = PostingRuleSelector("RETIRED_RULE_EVENT"),
                        ),
                    ).id
            }
        val version =
            withRequestContext {
                rules.createVersion(
                    CreatePostingRuleVersionCommand(
                        organisationId = organisationId,
                        actorId = MAKER,
                        ruleId = ruleId,
                        effectiveFrom = LocalDate.of(2026, 1, 1),
                        legs =
                            listOf(
                                ruleLeg(1, PostingSide.DEBIT, account.id),
                                ruleLeg(2, PostingSide.CREDIT, creditAccount.id),
                            ),
                    ),
                )
            }
        withRequestContext {
            rules.submit(PostingRuleVersionTransitionCommand(organisationId, MAKER, version.id))
        }
        withRequestContext {
            rules.approve(
                PostingRuleVersionTransitionCommand(organisationId, checker(), version.id),
            )
        }
        withRequestContext {
            rules.retire(
                PostingRuleVersionTransitionCommand(
                    organisationId = organisationId,
                    actorId = MAKER,
                    versionId = version.id,
                    reason = "Superseded by manual process",
                    effectiveTo = LocalDate.of(2026, 6, 30),
                ),
            )
        }
        return account
    }

    /** A `DRAFT` account submitted by [MAKER] and approved by [checker], landing in `ACTIVE`. */
    private fun submittedAndApproved(
        organisationId: UUID,
        code: String,
    ): GlAccount {
        val account = draft(organisationId, code)
        withRequestContext { lifecycle.submit(command(organisationId, account.id, MAKER)) }
        withRequestContext { lifecycle.approve(command(organisationId, account.id, checker())) }
        return account
    }

    /** A single-fact, fully-allocated leg: 100% of `PRINCIPAL` on [side] into [accountId]. */
    private fun ruleLeg(
        number: Int,
        side: PostingSide,
        accountId: UUID,
    ) = PostingRuleLeg(
        number,
        side,
        AccountResolution.FIXED_ACCOUNT,
        accountId,
        "PRINCIPAL",
        BigDecimal(100),
        false,
        null,
    )

    /**
     * Grants [permissionCode] directly: `TENANT_ADMIN` does not itself carry
     * `posting_rule.approve`, per [PostingRuleService.approve]'s separation of duties from
     * [PostingRuleService.submit].
     */
    private fun grantDirectly(
        organisationId: UUID,
        actorId: UUID,
        permissionCode: String,
    ) {
        val membershipId =
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.ID)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(actorId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID)
                ?: error("no membership for $actorId in $organisationId")
        val permissionId =
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                .fetchOne(PERMISSION.ID)
                ?: error("permission $permissionCode is not seeded")
        val now = OffsetDateTime.now()
        dsl
            .insertInto(MEMBERSHIP_PERMISSION)
            .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, organisationId)
            .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, membershipId)
            .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId)
            .set(MEMBERSHIP_PERMISSION.EFFECT, "ALLOW")
            .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
            .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
            .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    private fun transitionNames(
        organisationId: UUID,
        accountId: UUID,
    ): List<String> =
        dsl
            .select(GL_ACCOUNT_TRANSITION_LOG.TRANSITION_NAME)
            .from(GL_ACCOUNT_TRANSITION_LOG)
            .where(GL_ACCOUNT_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT_TRANSITION_LOG.ENTITY_ID.eq(accountId))
            .orderBy(GL_ACCOUNT_TRANSITION_LOG.ID.asc())
            .fetch(GL_ACCOUNT_TRANSITION_LOG.TRANSITION_NAME)
            .filterNotNull()

    /** A checker owned by this suite, so no seeded actor accumulates roles another test counts. */
    private fun checker(): UUID {
        val existing =
            dsl
                .select(USER_ACCOUNT.ID)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.USERNAME.eq(CHECKER_USERNAME))
                .fetchOne(USER_ACCOUNT.ID)
        if (existing != null) {
            return existing
        }
        val now = OffsetDateTime.now()
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.USERNAME, CHECKER_USERNAME)
            .set(USER_ACCOUNT.EMAIL, "$CHECKER_USERNAME@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Chart Of Accounts Checker")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .returning(USER_ACCOUNT.ID)
            .fetchOne()!!
            .id!!
    }

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val MAKER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        /** Holds no role anywhere, and is refused before any write. */
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

        const val CHECKER_USERNAME = "coa.fsm.checker"
    }
}
