package com.finaxis.platform.architecture

import com.finaxis.platform.lifecycle.adapter.outbound.persistence.JooqAuditRetentionStore
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `audit_event` is append-only but for one path (#278, ADR 0042 amending ADR 0007): the retention
 * job's `JooqAuditRetentionStore.deleteBatch`. This finds every method that both names
 * `AUDIT_EVENT` and calls a jOOQ `delete`, `deleteFrom` or `update`, and allows only that one. It
 * is structural: plain SQL text is not seen, as for every rule in this package.
 */
class AuditRetentionDeletePathTests {
    private val production =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.finaxis.platform")

    @Test
    fun `only the retention store deletes audit rows and nothing updates them`() {
        val mutating = production.flatMap { it.codeUnits }.filter(::mutatesAuditEvent)

        assertEquals(
            listOf("${JooqAuditRetentionStore::class.java.name}.$ALLOWED_METHOD"),
            mutating.map { "${it.owner.name}.${it.name}" }.distinct(),
            "only the retention job may delete from audit_event, and nothing may update it",
        )
    }

    @Test
    fun `the rule sees the delete it allows`() {
        val allowed =
            production
                .get(JooqAuditRetentionStore::class.java)
                .codeUnits
                .filter { it.name == ALLOWED_METHOD }
        assertTrue(allowed.any(::mutatesAuditEvent), "the retention store no longer deletes")
    }

    private fun mutatesAuditEvent(unit: JavaCodeUnit): Boolean {
        val namesAuditEvent =
            unit.accessesFromSelf.any { it.target.name in AUDIT_EVENT_REFERENCES }
        val mutates =
            unit.methodCallsFromSelf.any {
                it.targetOwner.isEquivalentTo(DSLContext::class.java) && it.target.name in MUTATIONS
            }
        return namesAuditEvent && mutates
    }

    private companion object {
        const val ALLOWED_METHOD = "deleteBatch"

        /** The generated Kotlin reference's getter, and the generated table's static field. */
        val AUDIT_EVENT_REFERENCES = setOf("getAUDIT_EVENT", "AUDIT_EVENT")
        val MUTATIONS = setOf("delete", "deleteFrom", "update")
    }
}
