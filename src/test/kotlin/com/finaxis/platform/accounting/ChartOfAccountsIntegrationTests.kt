package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ChartOfAccountsService
import com.finaxis.platform.accounting.application.CreateGlAccountCommand
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.GlAccountWriteStore
import com.finaxis.platform.accounting.application.ListGlAccountsCommand
import com.finaxis.platform.accounting.application.Patch
import com.finaxis.platform.accounting.application.UpdateGlAccountCommand
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.ChartHierarchyPolicy
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.domain.NormalBalance
import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chart-of-accounts behaviour against PostgreSQL.
 *
 * The rules here are enforced in three different places and this suite is what keeps them agreeing:
 * `V6` rejects a postable parent and a self-parent at the database, [ChartHierarchyPolicy] rejects
 * a longer cycle and an over-deep placement in the domain, and [ChartOfAccountsService] is what
 * reads the ancestry those domain rules need. A test that exercised only the service would pass
 * against a schema with no constraints at all.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ChartOfAccountsIntegrationTests(
    private val chart: ChartOfAccountsService,
    private val accounts: GlAccountStore,
    private val writes: GlAccountWriteStore,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `a header and its postable child are created and read back`() {
        // The positive control for a class whose other assertions are rejections.
        val organisationId = tenants.createActiveOrganisation("coa-create", ACTOR_ID)

        val header = withRequestContext { chart.create(headerCommand(organisationId, "1000")) }
        val cash =
            withRequestContext {
                chart.create(postableCommand(organisationId, "1010", parent = header.id))
            }

        assertEquals(GlAccountStatus.DRAFT, header.status, "create always lands in DRAFT")
        assertEquals(header.id, cash.parentAccountId)
        assertEquals(
            NormalBalance.DEBIT,
            cash.normalBalance,
            "an omitted normal balance is derived from the account class",
        )
        assertEquals(cash, accounts.findByCode(organisationId, AccountCode("1010")))
    }

    @Test
    fun `the ancestry of a deep account comes back in one bounded read`() {
        val organisationId = tenants.createActiveOrganisation("coa-ancestry", ACTOR_ID)
        val chain = headerChain(organisationId, depth = ChartHierarchyPolicy.MAX_DEPTH - 1)
        val leaf =
            withRequestContext {
                chart.create(postableCommand(organisationId, "9999", parent = chain.last().id))
            }

        val ancestry = withRequestContext { chart.ancestry(organisationId, leaf.id, ACTOR_ID) }

        assertEquals(
            chain.map { it.code },
            ancestry.map { it.code },
            "ancestry reads root-first and stops at the root",
        )
    }

    @Test
    fun `a cycle longer than one hop is rejected, which no row constraint can see`() {
        // chk_gl_account_not_own_parent covers the one-hop case. This is the one it cannot: making
        // a root the child of its own grandchild detaches the subtree from every rollup while
        // every individual row still satisfies its constraints.
        val organisationId = tenants.createActiveOrganisation("coa-cycle", ACTOR_ID)
        val chain = headerChain(organisationId, depth = 3)

        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    chart.update(
                        UpdateGlAccountCommand(
                            organisationId = organisationId,
                            actorId = ACTOR_ID,
                            accountId = chain.first().id,
                            parentAccountId = Patch.Set(chain.last().id),
                        ),
                    )
                }
            }

        assertEquals(ChartHierarchyPolicy.CYCLE, failure.code)
    }

    @Test
    fun `a postable account cannot be given children`() {
        val organisationId = tenants.createActiveOrganisation("coa-parent", ACTOR_ID)
        val postable = withRequestContext { chart.create(postableCommand(organisationId, "1010")) }

        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    chart.create(postableCommand(organisationId, "1011", parent = postable.id))
                }
            }

        assertEquals(ChartHierarchyPolicy.INVALID_PARENT, failure.code)
    }

    @Test
    fun `a parent in another tenant is not even visible`() {
        // The tenant predicate is on the lookup, not only on the write, so the failure is
        // "no such parent" rather than a constraint violation - the caller learns nothing about
        // another tenant's chart.
        val owner = tenants.createActiveOrganisation("coa-tenant-a", ACTOR_ID)
        val other = tenants.createActiveOrganisation("coa-tenant-b", ACTOR_ID)
        val header = withRequestContext { chart.create(headerCommand(owner, "1000")) }

        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    chart.create(postableCommand(other, "1010", parent = header.id))
                }
            }

        assertEquals(ChartHierarchyPolicy.INVALID_PARENT, failure.code)
    }

    @Test
    fun `a duplicate code is a conflict within a tenant and free across tenants`() {
        val first = tenants.createActiveOrganisation("coa-dup-a", ACTOR_ID)
        val second = tenants.createActiveOrganisation("coa-dup-b", ACTOR_ID)
        withRequestContext { chart.create(headerCommand(first, "1000")) }

        assertFailsWith<ConflictException> {
            withRequestContext { chart.create(headerCommand(first, "1000")) }
        }
        withRequestContext { chart.create(headerCommand(second, "1000")) }
    }

    @Test
    fun `a structural change is refused once the account has children`() {
        val organisationId = tenants.createActiveOrganisation("coa-immutable", ACTOR_ID)
        val header = withRequestContext { chart.create(headerCommand(organisationId, "1000")) }
        withRequestContext {
            chart.create(postableCommand(organisationId, "1010", parent = header.id))
        }

        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    chart.update(
                        UpdateGlAccountCommand(
                            organisationId = organisationId,
                            actorId = ACTOR_ID,
                            accountId = header.id,
                            code = AccountCode("1099"),
                        ),
                    )
                }
            }

        assertEquals(ChartHierarchyPolicy.IMMUTABLE, failure.code)
        assertEquals(
            AccountCode("1000"),
            accounts.findById(organisationId, header.id)?.code,
            "the refused change must not have been partially applied",
        )
    }

    @Test
    fun `listing is keyset paginated, bounded and tenant filtered`() {
        val organisationId = tenants.createActiveOrganisation("coa-page", ACTOR_ID)
        val other = tenants.createActiveOrganisation("coa-page-other", ACTOR_ID)
        (1..5).forEach {
            withRequestContext { chart.create(postableCommand(organisationId, "20$it")) }
        }
        withRequestContext { chart.create(postableCommand(other, "201")) }

        val first =
            withRequestContext {
                chart.list(ListGlAccountsCommand(organisationId, ACTOR_ID, pageSize = 2))
            }
        val second =
            withRequestContext {
                chart.list(
                    ListGlAccountsCommand(organisationId, ACTOR_ID, first.nextCursor, pageSize = 2),
                )
            }

        assertEquals(listOf("201", "202"), first.accounts.map { it.code.value })
        assertEquals(listOf("203", "204"), second.accounts.map { it.code.value })
        assertTrue(
            first.accounts.all { it.organisationId == organisationId },
            "another tenant's identical code must not appear in this page",
        )
        // The bound itself is asserted by `an over-large page is refused with a named code`; the
        // adapter no longer carries a second ceiling of its own to check here.
    }

    @Test
    fun `every entry point is permission gated`() {
        // The actor exists and has a membership, but holds no role in this organisation. Without
        // the checks the reads and writes below would all succeed.
        val organisationId = tenants.createActiveOrganisation("coa-permission", ACTOR_ID)
        val account = withRequestContext { chart.create(postableCommand(organisationId, "1010")) }

        assertFailsWith<ForbiddenOperationException> {
            withRequestContext { chart.create(headerCommand(organisationId, "1000", STRANGER)) }
        }
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext { chart.get(organisationId, account.id, STRANGER) }
        }
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                chart.update(
                    UpdateGlAccountCommand(
                        organisationId = organisationId,
                        actorId = STRANGER,
                        accountId = account.id,
                        name = "Renamed by a stranger",
                    ),
                )
            }
        }
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext { chart.ancestry(organisationId, account.id, STRANGER) }
        }
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                chart.list(ListGlAccountsCommand(organisationId, STRANGER))
            }
        }
    }

    @Test
    fun `the optimistic lock rejects a write from a stale snapshot`() {
        // Tested at the store, because that is where the guarantee lives: ChartOfAccountsService
        // re-reads before every write, so a stale snapshot can only reach the database through the
        // port. An earlier revision incremented row_version without ever comparing it, which is a
        // counter, not a lock - the second writer would have won silently.
        val organisationId = tenants.createActiveOrganisation("coa-stale", ACTOR_ID)
        val account = withRequestContext { chart.create(postableCommand(organisationId, "1010")) }
        val stale = requireNotNull(accounts.findById(organisationId, account.id))

        assertTrue(writes.update(stale.copy(name = "First writer"), ACTOR_ID))
        assertFalse(
            writes.update(stale.copy(name = "Second writer"), ACTOR_ID),
            "a write from a snapshot whose row version has moved must not land",
        )
        assertEquals("First writer", accounts.findById(organisationId, account.id)?.name)
    }

    @Test
    fun `the edit path cannot write the status, so it cannot bypass approval`() {
        // The claim the class KDoc makes, asserted against the port rather than trusted. The
        // update statement writes every editable column; if `status` were among them, an edit begun
        // before an approval committed would write the pre-approval status back and silently
        // un-approve the account while its transition log still recorded the approval.
        val organisationId = tenants.createActiveOrganisation("coa-status-untouched", ACTOR_ID)
        val account = withRequestContext { chart.create(postableCommand(organisationId, "1010")) }
        activate(organisationId, account.id)
        val active = requireNotNull(accounts.findById(organisationId, account.id))

        assertTrue(
            writes.update(
                active.copy(status = GlAccountStatus.DRAFT, name = "Renamed"),
                ACTOR_ID,
            ),
        )

        val reread = requireNotNull(accounts.findById(organisationId, account.id))
        assertEquals("Renamed", reread.name, "the editable field must have been written")
        assertEquals(
            GlAccountStatus.ACTIVE,
            reread.status,
            "the status must be untouched even when the caller supplies a different one",
        )
    }

    @Test
    fun `moving a subtree counts its own height against the depth bound`() {
        // The half of the bound an earlier revision did not measure. Re-parenting a three-level
        // subtree under a four-level chain would leave its leaf at depth 7, and because the
        // recursive descent is itself capped at MAX_DEPTH the result is not an error but an
        // ancestry that silently stops short of the root.
        val organisationId = tenants.createActiveOrganisation("coa-move-depth", ACTOR_ID)
        val deepChain = headerChain(organisationId, depth = ChartHierarchyPolicy.MAX_DEPTH - 2)
        val movable = headerChain(organisationId, depth = 3, prefix = "9")

        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    chart.update(
                        UpdateGlAccountCommand(
                            organisationId = organisationId,
                            actorId = ACTOR_ID,
                            accountId = movable.first().id,
                            parentAccountId = Patch.Set(deepChain.last().id),
                        ),
                    )
                }
            }

        assertEquals(ChartHierarchyPolicy.TOO_DEEP, failure.code)
    }

    @Test
    fun `a leaf still moves under the same parent, so the bound is not simply stricter`() {
        // Guards the rule above against being satisfied by refusing every move.
        val organisationId = tenants.createActiveOrganisation("coa-move-ok", ACTOR_ID)
        val chain = headerChain(organisationId, depth = ChartHierarchyPolicy.MAX_DEPTH - 2)
        val leaf = withRequestContext { chart.create(headerCommand(organisationId, "9000")) }

        withRequestContext {
            chart.update(
                UpdateGlAccountCommand(
                    organisationId = organisationId,
                    actorId = ACTOR_ID,
                    accountId = leaf.id,
                    parentAccountId = Patch.Set(chain.last().id),
                ),
            )
        }

        assertEquals(chain.last().id, accounts.findById(organisationId, leaf.id)?.parentAccountId)
    }

    @Test
    fun `a header cannot take manual entries`() {
        // chk_gl_account_manual_posting is a CHECK constraint in V6. Reaching it means a client
        // gets an untranslated 500 instead of a named refusal, while every other chart rule is
        // refused with a stable code.
        val organisationId = tenants.createActiveOrganisation("coa-flags", ACTOR_ID)

        val header =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    chart.create(
                        headerCommand(organisationId, "3000").copy(manualPostingAllowed = true),
                    )
                }
            }
        assertEquals("accounting.gl_account_header_not_postable", header.code)
    }

    @Test
    fun `a contra account is stored on the side opposite its class`() {
        // The command has no normalBalance to disagree with, so this asserts the derivation end to
        // end: the contra flag is what the caller sets, and both the derived property and the
        // generated column come back inverted. The previous shape of this rule let a caller pass
        // isContraAccount = true with no balance and get the class-implied side, because the
        // service defaulted it and the schema's disjunctive CHECK did not object.
        val organisationId = tenants.createActiveOrganisation("coa-contra", ACTOR_ID)

        val ordinary = withRequestContext { chart.create(postableCommand(organisationId, "1010")) }
        val contra =
            withRequestContext {
                chart.create(
                    postableCommand(organisationId, "1900").copy(isContraAccount = true),
                )
            }

        assertEquals(AccountClass.ASSET, contra.accountClass, "a contra asset is still an asset")
        assertEquals(NormalBalance.DEBIT, ordinary.normalBalance)
        assertEquals(NormalBalance.CREDIT, contra.normalBalance)
        assertEquals(
            "CREDIT",
            dsl
                .select(GL_ACCOUNT.NORMAL_BALANCE)
                .from(GL_ACCOUNT)
                .where(GL_ACCOUNT.ID.eq(contra.id))
                .fetchOne(GL_ACCOUNT.NORMAL_BALANCE),
            "the generated column and the derived property must agree",
        )
    }

    @Test
    fun `an account can be moved back to the root of the chart`() {
        // parentAccountId = null used to mean "leave the parent alone", so this was unreachable and
        // a child could never become a root. Patch.Set(null) is the assignment; Patch.Unchanged is
        // the omission.
        val organisationId = tenants.createActiveOrganisation("coa-unparent", ACTOR_ID)
        val header = withRequestContext { chart.create(headerCommand(organisationId, "1000")) }
        val child =
            withRequestContext {
                chart.create(postableCommand(organisationId, "1010", parent = header.id))
            }

        withRequestContext {
            chart.update(
                UpdateGlAccountCommand(
                    organisationId = organisationId,
                    actorId = ACTOR_ID,
                    accountId = child.id,
                    parentAccountId = Patch.Set(null),
                ),
            )
        }
        assertNull(accounts.findById(organisationId, child.id)?.parentAccountId)

        // And the omission still means "unchanged", which is the half a naive fix breaks.
        withRequestContext {
            chart.update(
                UpdateGlAccountCommand(
                    organisationId = organisationId,
                    actorId = ACTOR_ID,
                    accountId = child.id,
                    parentAccountId = Patch.Set(header.id),
                ),
            )
        }
        withRequestContext {
            chart.update(
                UpdateGlAccountCommand(
                    organisationId = organisationId,
                    actorId = ACTOR_ID,
                    accountId = child.id,
                    name = "Renamed only",
                ),
            )
        }
        assertEquals(header.id, accounts.findById(organisationId, child.id)?.parentAccountId)
    }

    @Test
    fun `an over-large page is refused with a named code, not an IllegalArgumentException`() {
        val organisationId = tenants.createActiveOrganisation("coa-page-bound", ACTOR_ID)

        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    chart.list(ListGlAccountsCommand(organisationId, ACTOR_ID, pageSize = 10_000))
                }
            }

        assertEquals("accounting.gl_account_invalid_page_size", failure.code)
    }

    /** Creates [depth] nested header accounts, root first, each the child of the previous. */
    @Test
    fun `two concurrent re-parentings cannot both commit a cycle`() {
        // The multi-hop cycle rule reads the chart and then writes to it, and at READ COMMITTED
        // those are two snapshots. Without serialisation, one transaction moving A under B and
        // another moving B under A each validate against a chart that predates the other's write,
        // both pass, and the cycle lands - chk_gl_account_not_own_parent sees only the one hop.
        //
        // The interleaving is forced rather than hoped for, because the first version of this test
        // was only a probabilistic detector: with the lock removed it sometimes still passed,
        // because thread A had committed before thread B read and B then correctly saw the cycle.
        // The protocol below pins the one ordering that distinguishes the two implementations -
        // B reads while A's write is still uncommitted:
        //
        //   A: update(first -> second), then announce, then wait for B to reach its update
        //   B: wait for A's announcement, announce itself, then update(second -> first)
        //
        // With the lock, B blocks on it before reading, A commits, and B then reads a chart where
        // the cycle is visible and is refused. Without it, B reads a snapshot that predates A's
        // still-uncommitted write, sees nothing wrong, and both commit.
        val organisationId = tenants.createActiveOrganisation("coa-cycle-race", ACTOR_ID)
        val first = withRequestContext { chart.create(headerCommand(organisationId, "1000")) }
        val second = withRequestContext { chart.create(headerCommand(organisationId, "2000")) }

        val firstWrote = CountDownLatch(1)
        val secondReachedUpdate = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()

        fun move(
            childId: UUID,
            parentId: UUID,
            before: () -> Unit,
            after: () -> Unit,
        ) = Runnable {
            try {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    before()
                    withRequestContext {
                        chart.update(
                            UpdateGlAccountCommand(
                                organisationId = organisationId,
                                actorId = ACTOR_ID,
                                accountId = childId,
                                parentAccountId = Patch.Set(parentId),
                            ),
                        )
                    }
                    after()
                }
            } catch (ex: RuntimeException) {
                failures += ex
            }
        }

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            listOf(
                pool.submit(
                    move(
                        childId = first.id,
                        parentId = second.id,
                        before = {},
                        after = {
                            firstWrote.countDown()
                            secondReachedUpdate.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            // The one window a latch cannot express: the second thread is blocked
                            // inside jOOQ taking the advisory lock, which is not observable from
                            // here. Bounded and one-directional - if this settle were too short the
                            // test would pass under both implementations rather than fail under the
                            // correct one.
                            Thread.sleep(SETTLE.toMillis())
                        },
                    ),
                ),
                pool.submit(
                    move(
                        childId = second.id,
                        parentId = first.id,
                        before = {
                            firstWrote.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            secondReachedUpdate.countDown()
                        },
                        after = {},
                    ),
                ),
            ).forEach { it.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }

        // The invariant, asserted on the committed chart: a two-hop cycle must not exist. Which of
        // the two moves survives is not specified - the lock decides the order - but both cannot.
        val firstParent = accounts.findById(organisationId, first.id)?.parentAccountId
        val secondParent = accounts.findById(organisationId, second.id)?.parentAccountId
        assertFalse(
            firstParent == second.id && secondParent == first.id,
            "both re-parentings committed, so the chart contains a cycle no row constraint can see",
        )
        assertEquals(
            1,
            failures.size,
            "exactly one move must be refused once the other is visible, got: $failures",
        )
        assertEquals(
            ChartHierarchyPolicy.CYCLE,
            (failures.single() as ApplicationException).code,
            "the refusal must be the cycle rule, not a lock timeout or a stale-version conflict",
        )
    }

    @Test
    fun `a duplicate code loses at the unique index with the published conflict, not a 500`() {
        // The findByCode precheck is a courtesy. Two requests can both see the code as free, and
        // the loser then hits uq_gl_account_organisation_code. Untranslated that surfaces as an
        // infrastructure exception - a 500 - for a case the contract publishes as a conflict, so
        // the database has to be the authority and its violation has to be mapped.
        val organisationId = tenants.createActiveOrganisation("coa-dup-race", ACTOR_ID)

        // Write the row directly so the precheck cannot see it, which is what a lost race looks
        // like from inside the service: findByCode returned nothing, and the insert still collides.
        dsl
            .insertInto(GL_ACCOUNT)
            .set(GL_ACCOUNT.ORGANISATION_ID, organisationId)
            .set(GL_ACCOUNT.ACCOUNT_CODE, "1010")
            .set(GL_ACCOUNT.ACCOUNT_NAME, "Already there")
            .set(GL_ACCOUNT.ACCOUNT_CLASS, "ASSET")
            .set(GL_ACCOUNT.ACCOUNT_USAGE, "POSTABLE")
            .set(GL_ACCOUNT.STATUS, "ACTIVE")
            .set(GL_ACCOUNT.CREATED_AT, OffsetDateTime.now())
            .set(GL_ACCOUNT.UPDATED_AT, OffsetDateTime.now())
            .execute()

        val failure =
            assertFailsWith<ConflictException> {
                withRequestContext { chart.create(postableCommand(organisationId, "1010")) }
            }

        assertEquals("accounting.gl_account_duplicate_code", failure.code)
    }

    private fun headerChain(
        organisationId: UUID,
        depth: Int,
        prefix: String = "",
    ): List<GlAccount> =
        (1..depth).fold(emptyList()) { chain, level ->
            chain +
                withRequestContext {
                    chart.create(
                        headerCommand(
                            organisationId,
                            "$prefix${level}000",
                            parent = chain.lastOrNull()?.id,
                        ),
                    )
                }
        }

    /**
     * Marks an account active by writing the status directly.
     *
     * The lifecycle FSM that would do this properly is issue #38's, one branch above. These
     * assertions are about what the edit path does to an already-active account, not about how it
     * became active.
     */
    private fun activate(
        organisationId: UUID,
        accountId: UUID,
    ) {
        dsl
            .update(GL_ACCOUNT)
            .set(GL_ACCOUNT.STATUS, GlAccountStatus.ACTIVE.name)
            .where(GL_ACCOUNT.ID.eq(accountId))
            .and(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    private fun headerCommand(
        organisationId: UUID,
        code: String,
        actorId: UUID = ACTOR_ID,
        parent: UUID? = null,
    ) = CreateGlAccountCommand(
        organisationId = organisationId,
        actorId = actorId,
        code = AccountCode(code),
        name = "Header $code",
        accountClass = AccountClass.ASSET,
        usage = AccountUsage.HEADER,
        parentAccountId = parent,
    )

    private fun postableCommand(
        organisationId: UUID,
        code: String,
        parent: UUID? = null,
    ) = CreateGlAccountCommand(
        organisationId = organisationId,
        actorId = ACTOR_ID,
        code = AccountCode(code),
        name = "Account $code",
        accountClass = AccountClass.ASSET,
        usage = AccountUsage.POSTABLE,
        parentAccountId = parent,
    )

    private companion object {
        /** The `V3` bootstrap administrator: `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        /** A second bootstrap actor holding no role in the organisations created here. */
        val STRANGER: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

        /** Bounds every latch and future in this suite, so a lost race fails instead of hanging. */
        const val LATCH_TIMEOUT_SECONDS = 20L

        /** How long the winning transaction stays open after its rival reaches its own write. */
        val SETTLE: Duration = Duration.ofMillis(300)
    }
}
