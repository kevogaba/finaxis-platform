package com.finaxis.platform.config

import com.finaxis.platform.jooq.Public
import com.finaxis.platform.jooq.tables.records.ApiIdempotencyRecordRecord
import com.finaxis.platform.jooq.tables.records.GlAccountRecord
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.TypeReference
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class JooqNativeHintsTests {
    private val hints =
        RuntimeHints().also {
            JooqNativeHints().registerHints(it, javaClass.classLoader)
        }

    @Test
    fun `registers array types for jooq built-in data types`() {
        assertArrayTypeRegistered(String::class.java)
        assertArrayTypeRegistered(UUID::class.java)
        assertArrayTypeRegistered(java.math.BigDecimal::class.java)
        assertArrayTypeRegistered(java.time.OffsetDateTime::class.java)
    }

    @Test
    fun `registers array types jooq added after the upstream metadata was written`() {
        assertArrayTypeRegistered(Class.forName("org.jooq.Decfloat"))
        assertArrayTypeRegistered(Class.forName("org.jooq.JSONB"))
    }

    @Test
    fun `registers the constructors of the generated record types`() {
        assertRecordRegistered(GlAccountRecord::class.java)
        assertRecordRegistered(ApiIdempotencyRecordRecord::class.java)
    }

    @Test
    fun `registers a record type for every table in the generated schema`() {
        Public.PUBLIC.tables.forEach { assertRecordRegistered(it.recordType) }
    }

    private fun assertArrayTypeRegistered(elementType: Class<*>) {
        val arrayType = elementType.arrayType()
        assertTrue(
            RuntimeHintsPredicates.reflection().onType(TypeReference.of(arrayType)).test(hints),
            "expected ${arrayType.typeName} to be registered for reflection",
        )
    }

    private fun assertRecordRegistered(recordType: Class<*>) {
        assertTrue(
            RuntimeHintsPredicates
                .reflection()
                .onType(TypeReference.of(recordType))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                .test(hints),
            "expected ${recordType.name} to be registered with its constructors",
        )
    }
}
