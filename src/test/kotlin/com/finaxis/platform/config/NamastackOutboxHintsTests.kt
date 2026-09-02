package com.finaxis.platform.config

import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.TypeReference
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.util.ClassUtils
import kotlin.test.Test
import kotlin.test.assertTrue

class NamastackOutboxHintsTests {
    private val classLoader = javaClass.classLoader
    private val hints =
        RuntimeHints().also {
            NamastackOutboxHints().registerHints(it, classLoader)
        }

    @Test
    fun `registers the outbox payload types for construction`() {
        // Without these, serialization silently writes `{}` into outbox_record rather than failing.
        listOf(ExternalizedTransitionEvent::class.java, TransitionActor::class.java).forEach {
            assertTrue(
                RuntimeHintsPredicates
                    .reflection()
                    .onType(TypeReference.of(it))
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                    .test(hints),
                "expected ${it.name} to be registered with its constructors",
            )
        }
    }

    @Test
    fun `registers every reflectively invoked outbox type`() {
        NamastackOutboxHints.REFLECTIVELY_INVOKED_TYPES.keys.forEach {
            assertTrue(
                RuntimeHintsPredicates
                    .reflection()
                    .onType(TypeReference.of(it))
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)
                    .test(hints),
                "expected $it to be registered with its methods",
            )
        }
    }

    @Test
    fun `every reflectively invoked method still exists`() {
        // Matched by name, so an upstream rename would leave the outbox silently not draining in a
        // native image instead of failing anything at build time.
        NamastackOutboxHints.REFLECTIVELY_INVOKED_TYPES.forEach { (typeName, methodName) ->
            assertTrue(
                ClassUtils.isPresent(typeName, classLoader),
                "$typeName is no longer on the classpath",
            )
            val declared =
                ClassUtils
                    .resolveClassName(typeName, classLoader)
                    .declaredMethods
                    .any { it.name == methodName }
            assertTrue(declared, "$typeName no longer declares $methodName")
        }
    }
}
