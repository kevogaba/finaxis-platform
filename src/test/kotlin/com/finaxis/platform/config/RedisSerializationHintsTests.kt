package com.finaxis.platform.config

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.TypeReference
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.util.ClassUtils
import java.io.Serializable
import kotlin.test.Test
import kotlin.test.assertTrue

class RedisSerializationHintsTests {
    private val classLoader = javaClass.classLoader
    private val hints =
        RuntimeHints().also {
            RedisSerializationHints().registerHints(it, classLoader)
        }

    @Test
    fun `registers every declared type for java serialization`() {
        RedisSerializationHints.SERIALIZED_TYPES.forEach {
            // reflection().onType(...), not the deprecated serialization() predicate: Spring marked
            // the whole Java-serialization hint family forRemoval in 7.0.6. Registration alone is
            // not the assertion that matters, though - a plain reflection hint would satisfy it
            // while emitting no `serializable` flag - so the java-serialization flag is checked
            // against the hint model below.
            assertTrue(
                RuntimeHintsPredicates.reflection().onType(TypeReference.of(it)).test(hints),
                "expected $it to be registered for reflection",
            )
            val typeHint = hints.reflection().getTypeHint(TypeReference.of(it))
            assertTrue(
                typeHint?.hasJavaSerialization() == true,
                "expected $it to carry the java-serialization flag, which is what makes " +
                    "ReflectionHintsAttributes emit a serializable entry in reflect-config.json",
            )
        }
    }

    @Test
    fun `every declared type is on the classpath and serializable`() {
        // Matched by name, so a rename would silently drop the entry and only show up as a 500 on
        // whichever endpoint writes to Redis first.
        RedisSerializationHints.SERIALIZED_TYPES.forEach {
            assertTrue(ClassUtils.isPresent(it, classLoader), "$it is no longer on the classpath")
            val type = ClassUtils.resolveClassName(it, classLoader)
            assertTrue(
                Serializable::class.java.isAssignableFrom(type),
                "$it is no longer Serializable; check whether Redis still stores it",
            )
        }
    }
}
