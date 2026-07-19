package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE_HISTORY
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.lifecycle.application.query.BranchFilter
import com.finaxis.platform.lifecycle.application.query.BusinessDateHistoryFilter
import com.finaxis.platform.lifecycle.application.query.TenantFilter
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqFoundationQueryStoreTests(
    private val dsl: DSLContext,
    private val store: JooqFoundationQueryStore,
) {
    @Test
    fun `searchTenants filters, sorts and paginates organisation data`() {
        val org1 = insertOrganisation("uniq1", "UniqueUnique Test 1", "ZZ")
        insertOrganisation("uniq2", "Other Other 2", "XX")

        val page = store.searchTenants(TenantFilter(q = "UniqueUnique", country = "ZZ"))
        assertEquals(1, page.items.size)
        assertEquals(org1, page.items.single().id)
    }

    @Test
    fun `findTenantById returns tenant details when found`() {
        val orgId = insertOrganisation("org3", "Test Org 3", "KE")
        val detail = store.findTenantById(orgId)
        assertNotNull(detail)
        assertEquals("org3", detail.tenantCode)
    }

    @Test
    fun `searchBranches scopes by organisation and type`() {
        val orgId = insertOrganisation("org4", "Org 4", "KE")
        val otherOrgId = insertOrganisation("org5", "Org 5", "KE")

        val branch1 = insertBranch(orgId, "br1", "Branch 1", "RETAIL")
        insertBranch(otherOrgId, "br2", "Branch 2", "RETAIL")

        val page = store.searchBranches(orgId, BranchFilter(type = "RETAIL"))
        assertEquals(1, page.items.size)
        assertEquals(branch1, page.items.single().id)
    }

    @Test
    fun `listBusinessDateHistory lists business date history records`() {
        val orgId = insertOrganisation("org6", "Org 6", "KE")
        val eventId = insertBusinessDateHistory(orgId, "ADVANCED", "ACTIVE", "ACTIVE")

        val page = store.listBusinessDateHistory(orgId, BusinessDateHistoryFilter())
        assertEquals(1, page.items.size)
        assertEquals(eventId, page.items.single().id)
    }

    private fun insertOrganisation(
        code: String,
        name: String,
        country: String,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, code)
            .set(ORGANISATION.DISPLAY_NAME, name)
            .set(ORGANISATION.COUNTRY_CODE, country)
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranch(
        orgId: UUID,
        code: String,
        name: String,
        type: String,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, orgId)
            .set(BRANCH.BRANCH_CODE, code)
            .set(BRANCH.BRANCH_NAME, name)
            .set(BRANCH.BRANCH_TYPE, type)
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBusinessDateHistory(
        orgId: UUID,
        eventType: String,
        fromStatus: String,
        toStatus: String,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BUSINESS_DATE_HISTORY)
            .set(BUSINESS_DATE_HISTORY.ID, id)
            .set(BUSINESS_DATE_HISTORY.ORGANISATION_ID, orgId)
            .set(BUSINESS_DATE_HISTORY.EVENT_TYPE, eventType)
            .set(BUSINESS_DATE_HISTORY.FROM_STATUS, fromStatus)
            .set(BUSINESS_DATE_HISTORY.TO_STATUS, toStatus)
            .set(BUSINESS_DATE_HISTORY.FROM_BUSINESS_DATE, java.time.LocalDate.now())
            .set(BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE, java.time.LocalDate.now())
            .set(BUSINESS_DATE_HISTORY.OCCURRED_AT, now)
            .set(BUSINESS_DATE_HISTORY.CREATED_AT, now)
            .execute()
        return id
    }
}
