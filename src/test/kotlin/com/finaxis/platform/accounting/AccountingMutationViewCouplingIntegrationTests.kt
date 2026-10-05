package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ChartOfAccountsService
import com.finaxis.platform.accounting.application.CreateGlAccountCommand
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
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
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ADR 0030, decision 4, at the accounting guard: accounting has no inbound web adapter yet, but
 * its permission adapter applies the same central rule, so the first accounting route inherits it.
 * A mutation code without the view the catalogue pairs with it is refused with the view named,
 * before the service reads or writes anything; the break-glass check holds its views to the same
 * rule, through the locked read.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AccountingMutationViewCouplingIntegrationTests(
    private val guard: AccountingPermissionGuard,
    private val chart: ChartOfAccountsService,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val admin = seedUser("coupling-admin")
    private val organisationId = tenants.createActiveOrganisation("acct-coupling", admin)

    @Test
    fun `an accounting mutation without its view is refused by name and writes nothing`() {
        val actor = seedUser("maker-no-view")
        tenants.grantTenantPermissionsExactly(
            organisationId,
            actor,
            AccountingPermissions.GL_ACCOUNT_CREATE,
        )
        val before = accountCount()

        val refusal =
            assertFailsWith<MissingPermissionException> {
                withRequestContext { chart.create(command(actor, "1000")) }
            }

        assertEquals(AccountingPermissions.GL_ACCOUNT_VIEW, refusal.permissionCode)
        assertEquals("Missing permission: gl_account.view.", refusal.safeDetail)
        assertEquals(before, accountCount())
    }

    @Test
    fun `the mutation code is named before its view`() {
        val actor = seedUser("viewer-only")
        tenants.grantTenantPermissionsExactly(
            organisationId,
            actor,
            AccountingPermissions.GL_ACCOUNT_VIEW,
        )

        val refusal =
            assertFailsWith<MissingPermissionException> {
                withRequestContext { chart.create(command(actor, "1001")) }
            }

        assertEquals(AccountingPermissions.GL_ACCOUNT_CREATE, refusal.permissionCode)
    }

    @Test
    fun `an accounting mutation with its view is created`() {
        val actor = seedUser("maker-with-view")
        tenants.grantTenantPermissionsWithViews(
            organisationId,
            actor,
            AccountingPermissions.GL_ACCOUNT_CREATE,
        )

        val created = withRequestContext { chart.create(command(actor, "1002")) }

        assertEquals("1002", created.code.value)
    }

    @Test
    fun `a branch scoped accounting check needs the view at that branch`() {
        val branchId = headOfficeId()
        val actor = seedUser("branch-maker")
        tenants.grantBranchPermissionsExactly(
            organisationId,
            branchId,
            actor,
            AccountingPermissions.GL_ACCOUNT_CREATE,
        )

        val refusal =
            assertFailsWith<MissingPermissionException> {
                withRequestContext {
                    guard.requireBranchPermission(
                        actor,
                        organisationId,
                        branchId,
                        AccountingPermissions.GL_ACCOUNT_CREATE,
                    )
                }
            }
        assertEquals(AccountingPermissions.GL_ACCOUNT_VIEW, refusal.permissionCode)

        val withView = seedUser("branch-maker-view")
        tenants.grantBranchPermissionsWithViews(
            organisationId,
            branchId,
            withView,
            AccountingPermissions.GL_ACCOUNT_CREATE,
        )
        withRequestContext {
            guard.requireBranchPermission(
                withView,
                organisationId,
                branchId,
                AccountingPermissions.GL_ACCOUNT_CREATE,
            )
        }
    }

    @Test
    fun `a break glass code is held to the same rule through the locked read`() {
        val actor = seedUser("break-glass-no-view")
        tenants.grantTenantPermissionsExactly(
            organisationId,
            actor,
            AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD,
        )

        val refusal =
            assertFailsWith<MissingPermissionException> { breakGlass(actor) }
        assertEquals(AccountingPermissions.JOURNAL_VIEW, refusal.permissionCode)

        val withView = seedUser("break-glass-view")
        tenants.grantTenantPermissionsWithViews(
            organisationId,
            withView,
            AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD,
        )
        breakGlass(withView)
    }

    private fun breakGlass(actor: UUID) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            guard.requireBreakGlassPermission(
                actor,
                organisationId,
                AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD,
            )
        }
    }

    private fun command(
        actor: UUID,
        code: String,
    ) = CreateGlAccountCommand(
        organisationId = organisationId,
        actorId = actor,
        code = AccountCode(code),
        name = "Header $code",
        accountClass = AccountClass.ASSET,
        usage = AccountUsage.HEADER,
    )

    private fun accountCount(): Int =
        dsl.fetchCount(GL_ACCOUNT, GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))

    private fun headOfficeId(): UUID =
        requireNotNull(
            dsl
                .fetchOne(
                    "SELECT id FROM branch " +
                        "WHERE organisation_id = ? AND branch_type = 'HEAD_OFFICE'",
                    organisationId,
                )?.get(0, UUID::class.java),
        )

    private fun seedUser(label: String): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "$label-$id")
            .set(USER_ACCOUNT.EMAIL, "$label-$id@seed.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, label)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
        return id
    }
}
