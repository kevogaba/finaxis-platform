package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.AccountingLedgerActivity
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/** Answers [AccountingLedgerActivity] from `journal_entry`, bounded to one existence probe. */
@Component
class JooqAccountingLedgerActivity(
    private val dsl: DSLContext,
) : AccountingLedgerActivity {
    override fun hasPostedJournals(organisationId: UUID): Boolean =
        dsl.fetchExists(
            DSL
                .selectOne()
                .from(JOURNAL_ENTRY)
                .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId)),
        )
}
