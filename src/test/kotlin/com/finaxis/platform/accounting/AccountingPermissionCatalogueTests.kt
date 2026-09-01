package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the accounting permission catalogue seeded by `V5__accounting_permission_catalogue.sql`
 * and the constants that name it.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AccountingPermissionCatalogueTests(
    private val dsl: DSLContext,
) {
    @Test
    fun `the catalogue holds exactly the twenty six accounting codes and all are active`() {
        val rows =
            dsl
                .select(PERMISSION.PERMISSION_CODE, PERMISSION.STATUS)
                .from(PERMISSION)
                .where(PERMISSION.MODULE_CODE.eq(ACCOUNTING_MODULE))
                .fetch()

        assertEquals(
            AccountingPermissions.ALL.sorted(),
            rows.map { it.value1() }.filterNotNull().sorted(),
        )
        assertTrue(rows.all { it.value2() == "ACTIVE" }, "every accounting code must be ACTIVE")
    }

    @Test
    fun `the constants object and the database catalogue agree in both directions`() {
        val seeded =
            dsl
                .select(PERMISSION.PERMISSION_CODE)
                .from(PERMISSION)
                .where(PERMISSION.MODULE_CODE.eq(ACCOUNTING_MODULE))
                .fetchSet(PERMISSION.PERMISSION_CODE)
                .filterNotNull()
                .toSet()

        assertEquals(
            emptySet(),
            AccountingPermissions.ALL - seeded,
            "AccountingPermissions names codes that were never seeded",
        )
        assertEquals(
            emptySet(),
            seeded - AccountingPermissions.ALL,
            "the migration seeded codes that AccountingPermissions does not name",
        )
    }

    @Test
    fun `accounting identifiers form a contiguous block with no gaps`() {
        val ids =
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.MODULE_CODE.eq(ACCOUNTING_MODULE))
                .fetchSet(PERMISSION.ID)
                .filterNotNull()
                .map { it.toString() }
                .sorted()

        val expected =
            (1..AccountingPermissions.ALL.size).map {
                "41000000-0000-0000-0000-%012d".format(it)
            }
        assertEquals(expected, ids)
    }

    @Test
    fun `no accounting code collides with a foundation code`() {
        val foundation =
            dsl
                .select(PERMISSION.PERMISSION_CODE)
                .from(PERMISSION)
                .where(PERMISSION.MODULE_CODE.ne(ACCOUNTING_MODULE))
                .fetchSet(PERMISSION.PERMISSION_CODE)
                .filterNotNull()
                .toSet()

        assertEquals(emptySet(), AccountingPermissions.ALL intersect foundation)
    }

    @Test
    fun `the platform super admin role still holds the entire catalogue`() {
        val ungranted =
            dsl.fetchCount(
                PERMISSION,
                PERMISSION.ID.notIn(
                    dsl
                        .select(ROLE_PERMISSION.PERMISSION_ID)
                        .from(ROLE_PERMISSION)
                        .where(ROLE_PERMISSION.ROLE_ID.eq(PLATFORM_SUPER_ADMIN_ROLE_ID)),
                ),
            )

        assertEquals(0, ungranted, "V2's superset grant ran once, so V5 must grant its own codes")
    }

    @Test
    fun `platform support receives no accounting permission`() {
        val granted =
            dsl.fetchCount(
                ROLE_PERMISSION,
                ROLE_PERMISSION.ROLE_ID
                    .eq(PLATFORM_SUPPORT_ROLE_ID)
                    .and(
                        ROLE_PERMISSION.PERMISSION_ID.`in`(
                            dsl
                                .select(PERMISSION.ID)
                                .from(PERMISSION)
                                .where(PERMISSION.MODULE_CODE.eq(ACCOUNTING_MODULE)),
                        ),
                    ),
            )

        assertEquals(
            0,
            granted,
            "platform staff must not read tenant financial data by default; a support-escalation " +
                "role is a separate, audited decision",
        )
    }

    @Test
    fun `the bootstrap local admin holds only the tenant configuration subset`() {
        val codes =
            dsl
                .select(PERMISSION.PERMISSION_CODE)
                .from(ROLE_PERMISSION)
                .join(PERMISSION)
                .on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
                .where(ROLE_PERMISSION.ROLE_ID.eq(LOCAL_ADMIN_ROLE_ID))
                .and(PERMISSION.MODULE_CODE.eq(ACCOUNTING_MODULE))
                .fetchSet(PERMISSION.PERMISSION_CODE)
                .filterNotNull()
                .toSet()

        assertEquals(EXPECTED_LOCAL_ADMIN_ACCOUNTING_CODES, codes)
        assertEquals(
            emptySet(),
            codes intersect AccountingPermissions.BREAK_GLASS,
            "break-glass codes are granted deliberately per tenant, never by the bootstrap",
        )
    }

    @Test
    fun `every role code named by the bootstrap defaults exists and is active`() {
        // This is the regression that ADR 0010 records as having been missed once: a migration
        // granting to a role that no code path creates, or naming a permission that was never
        // seeded, silently grants nothing.
        val activeRoleCodes =
            dsl
                .select(ROLE.ROLE_CODE)
                .from(ROLE)
                .where(ROLE.STATUS.eq("ACTIVE"))
                .fetchSet(ROLE.ROLE_CODE)
                .filterNotNull()
                .toSet()

        assertTrue(
            "local-admin" in activeRoleCodes,
            "the V3 bootstrap role must exist for V5's grant to land",
        )
    }

    private companion object {
        const val ACCOUNTING_MODULE = "accounting"
        val PLATFORM_SUPER_ADMIN_ROLE_ID: UUID =
            UUID.fromString("50000000-0000-0000-0000-000000000001")
        val PLATFORM_SUPPORT_ROLE_ID: UUID =
            UUID.fromString("50000000-0000-0000-0000-000000000002")
        val LOCAL_ADMIN_ROLE_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")

        val EXPECTED_LOCAL_ADMIN_ACCOUNTING_CODES =
            setOf(
                "gl_account.view",
                "gl_account.create",
                "gl_account.update",
                "gl_account.submit",
                "gl_account.approve",
                "gl_account.deactivate",
                "fiscal_period.view",
                "fiscal_period.open",
                "posting_rule.view",
                "accounting_report.view",
            )
    }
}
