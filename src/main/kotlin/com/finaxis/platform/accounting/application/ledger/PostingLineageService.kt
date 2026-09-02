package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.web.pagination.PaginationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A posting request together with the journal it produced, read in either direction.
 *
 * [journal] is null only for a request that is `PENDING` inside another, still-open transaction -
 * which a reader outside that transaction never observes - so in practice a lineage always
 * carries its journal. The type keeps the null because the store cannot promise otherwise.
 */
data class PostingLineage(
    val request: PostingRequestView,
    val journal: JournalEntryView?,
    val lines: List<JournalLineView>,
)

/** One bounded page of lineages for a business entity, newest request first. */
data class PostingLineagePage(
    val items: List<PostingLineage>,
    val nextCursor: UUID?,
)

/**
 * Source lineage, answered in both directions through the public application layer.
 *
 * *Forward*: from the durable source identity a product module holds - its module and reference,
 * or its business entity - to the request and journal accounting recorded for it. *Backward*: from
 * a journal an accountant is looking at to the business transaction that caused it. Both are what
 * issue #42 means by *"queryable in both directions"*, and both stop at the descriptive
 * `source_entity_id`: accounting holds no foreign key into a product module (`INV-16`), so the
 * last hop into the product's own records is the product module's to make.
 *
 * Every read is permission-gated on `journal.view`, tenant-scoped by parameter, and bounded: a
 * business entity may have produced many postings, so that listing is keyset-paginated over the
 * request id and capped at the platform page-size ceiling (`INV-15`).
 */
@Service
class PostingLineageService(
    private val journals: JournalReadStore,
    private val permissions: AccountingPermissionGuard,
    private val pagination: PaginationProperties,
) {
    /** The lineage recorded for a source module's durable reference, or null. */
    @Transactional(readOnly = true)
    fun findBySource(query: SourceLineageQuery): PostingLineage? {
        requireView(query.actorId, query.organisationId)
        return journals
            .findPostingRequestBySource(
                query.organisationId,
                query.sourceModule,
                query.sourceReference,
            )?.let { lineage(query.organisationId, it) }
    }

    /** The lineage of one journal, back to the business transaction that produced it. */
    @Transactional(readOnly = true)
    fun findByJournal(query: JournalLineageQuery): PostingLineage? {
        requireView(query.actorId, query.organisationId)
        val journal =
            journals.findJournalEntry(query.organisationId, query.journalEntryId) ?: return null
        val request =
            checkNotNull(
                journals.findPostingRequest(query.organisationId, journal.postingRequestId),
            ) {
                "journal_entry ${journal.id} references posting_request " +
                    "${journal.postingRequestId}, which does not exist; the foreign key forbids this"
            }
        return PostingLineage(
            request = request,
            journal = journal,
            lines = journals.findJournalLines(query.organisationId, journal.id),
        )
    }

    /** Every posting a business entity produced, newest first, one bounded page at a time. */
    @Transactional(readOnly = true)
    fun listForSourceEntity(query: SourceEntityLineageQuery): PostingLineagePage {
        requireView(query.actorId, query.organisationId)
        val pageSize = query.pageSize ?: pagination.defaultPageSize
        if (pageSize < 1 || pageSize > pagination.maxPageSize) {
            throw InvalidOperationException(
                code = PAGE_SIZE_OUT_OF_RANGE,
                safeDetail = "The page size must be between 1 and ${pagination.maxPageSize}.",
            )
        }
        val requests =
            journals.listPostingRequestsForEntity(
                organisationId = query.organisationId,
                sourceModule = query.sourceModule,
                sourceEntityType = query.sourceEntityType,
                sourceEntityId = query.sourceEntityId,
                beforeId = query.cursor,
                pageSize = pageSize,
            )
        return PostingLineagePage(
            items = requests.map { lineage(query.organisationId, it) },
            nextCursor = if (requests.size < pageSize) null else requests.last().id,
        )
    }

    private fun lineage(
        organisationId: UUID,
        request: PostingRequestView,
    ): PostingLineage {
        val journal = journals.findJournalEntryForRequest(organisationId, request.id)
        return PostingLineage(
            request = request,
            journal = journal,
            lines = journal?.let { journals.findJournalLines(organisationId, it.id) }.orEmpty(),
        )
    }

    private fun requireView(
        actorId: UUID,
        organisationId: UUID,
    ) = permissions.requireTenantPermission(
        actorId,
        organisationId,
        AccountingPermissions.JOURNAL_VIEW,
    )

    private companion object {
        const val PAGE_SIZE_OUT_OF_RANGE = "accounting.page_size_out_of_range"
    }
}

/** Forward lineage by the source module's durable reference. */
data class SourceLineageQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val sourceModule: String,
    val sourceReference: String,
)

/** Backward lineage from a journal. */
data class JournalLineageQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val journalEntryId: UUID,
)

/** Forward lineage from a business entity, keyset-paginated over the request id. */
data class SourceEntityLineageQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val sourceModule: String,
    val sourceEntityType: String,
    val sourceEntityId: UUID,
    val pageSize: Int? = null,
    val cursor: UUID? = null,
)
