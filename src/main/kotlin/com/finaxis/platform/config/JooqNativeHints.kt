package com.finaxis.platform.config

import com.finaxis.platform.jooq.Public
import org.jooq.DataType
import org.jooq.impl.SQLDataType
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import java.lang.reflect.Modifier

/**
 * Registers what jOOQ reaches reflectively, which its published metadata does not cover.
 *
 * Two distinct gaps, both fatal and neither visible at build time.
 *
 * `SQLDataType`'s static initializer derives an array type for each built-in type through
 * `Class.arrayType()`, which in a native image resolves only for array classes the image was told
 * about and returns `null` for the rest. jOOQ stores that `null` as a map key and the initializer
 * fails, taking the `DSLContext` bean with it. The type set is read back from `SQLDataType` rather
 * than listed here so that a jOOQ upgrade adding a built-in type cannot reintroduce the gap -
 * which is how it was introduced upstream when 3.20 added `Decfloat` (jOOQ#19124).
 *
 * The generated `*Record` classes are instantiated reflectively whenever a query returns a record,
 * so without their constructors the first `RETURNING` on a write path fails with
 * `IllegalStateException: Could not access record constructor`. They are read off the generated
 * schema rather than listed, so a new migration brings its record type with it.
 */
class JooqNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        builtInTypes().forEach { hints.reflection().registerType(it.arrayType()) }
        generatedRecordTypes().forEach {
            hints.reflection().registerType(
                it,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
            )
        }
    }

    private fun builtInTypes(): Set<Class<*>> =
        SQLDataType::class.java.fields
            .asSequence()
            .filter { Modifier.isStatic(it.modifiers) }
            .filter { DataType::class.java.isAssignableFrom(it.type) }
            .mapNotNull { it.get(null) as DataType<*>? }
            .map { it.type }
            .toSet()

    private fun generatedRecordTypes(): List<Class<*>> = Public.PUBLIC.tables.map { it.recordType }
}
