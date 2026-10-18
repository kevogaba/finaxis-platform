package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.lifecycle.application.AmendOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The business date a tenant opens on (#271): derived at approval from the timezone the tenant
 * then has, unless the draft named one. Two timezones more than a day apart make a stale date
 * impossible to confuse with the right one.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
class TenantDraftBusinessDateIntegrationTests
    @Autowired
    constructor(
        private val dsl: DSLContext,
        private val organisations: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisations, dsl)

        @Test
        fun `a draft with no business date has none until approval derives it from its timezone`() {
            val operator = fixture.createPlatformOperator("bd-derive")
            val checker = fixture.createPlatformOperator("bd-derive-checker")

            withRequestContext {
                val id = createDraft(operator, businessDate = null)
                assertNull(storedDate(id), "nothing is seeded for a draft that named no date")

                amend(id, operator, timezone = AHEAD)
                submit(id, operator)
                val before = LocalDate.now(ZoneId.of(AHEAD))
                approve(id, checker)

                val derived = requireNotNull(storedDate(id))
                assertTrue(
                    derived == before || derived == LocalDate.now(ZoneId.of(AHEAD)),
                    "derived $derived from the amended timezone",
                )
                assertTrue(
                    derived != LocalDate.now(ZoneId.of(BEHIND)),
                    "not the old timezone's day",
                )
            }
        }

        @Test
        fun `an explicit business date survives a timezone change and approval`() {
            val operator = fixture.createPlatformOperator("bd-explicit")
            val checker = fixture.createPlatformOperator("bd-explicit-checker")

            withRequestContext {
                val id = createDraft(operator, businessDate = LocalDate.of(2031, 3, 9))
                assertEquals(LocalDate.of(2031, 3, 9), storedDate(id))

                amend(id, operator, timezone = AHEAD)
                submit(id, operator)
                approve(id, checker)

                assertEquals(LocalDate.of(2031, 3, 9), storedDate(id))
            }
        }

        @Test
        fun `an amend that names a business date sets it, creating the row when absent`() {
            val operator = fixture.createPlatformOperator("bd-amend")

            withRequestContext {
                val id = createDraft(operator, businessDate = null)

                amend(id, operator, timezone = BEHIND, businessDate = LocalDate.of(2032, 1, 5))
                assertEquals(LocalDate.of(2032, 1, 5), storedDate(id))

                amend(id, operator, timezone = BEHIND, businessDate = LocalDate.of(2032, 2, 6))
                assertEquals(LocalDate.of(2032, 2, 6), storedDate(id))
                amend(id, operator, timezone = AHEAD)
                assertEquals(LocalDate.of(2032, 2, 6), storedDate(id), "absent leaves it as it is")
            }
        }

        private fun createDraft(
            operator: UUID,
            businessDate: LocalDate?,
        ): UUID =
            organisations
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "bd-${uuidV7()}",
                        displayName = "Business Date Tenant",
                        legalName = "Business Date Tenant Limited",
                        registrationNumber = "BD-${uuidV7()}",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = BEHIND,
                        businessDate = businessDate,
                        requestedBy = operator,
                    ),
                ).organisationId

        private fun amend(
            id: UUID,
            operator: UUID,
            timezone: String,
            businessDate: LocalDate? = null,
        ) {
            val tenantCode =
                requireNotNull(
                    dsl
                        .select(ORGANISATION.TENANT_CODE)
                        .from(ORGANISATION)
                        .where(ORGANISATION.ID.eq(id))
                        .fetchOne(0, String::class.java),
                )
            organisations.amendDraft(
                AmendOrganisationDraftCommand(
                    organisationId = id,
                    tenantCode = tenantCode,
                    displayName = "Business Date Tenant",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = timezone,
                    businessDate = businessDate,
                    actorId = operator,
                    requestId = uuidV7(),
                    admin = null,
                ),
            )
        }

        private fun submit(
            id: UUID,
            operator: UUID,
        ) = organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(id, actorId = operator),
        )

        private fun approve(
            id: UUID,
            checker: UUID,
        ) = organisations.approveProvisioning(
            ApproveOrganisationProvisioningCommand(id, actorId = checker),
        )

        private fun storedDate(id: UUID): LocalDate? =
            dsl
                .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                .from(BUSINESS_DATE)
                .where(BUSINESS_DATE.ORGANISATION_ID.eq(id))
                .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE)

        private companion object {
            const val BEHIND = "Pacific/Pago_Pago"
            const val AHEAD = "Pacific/Kiritimati"
        }
    }
