package com.finaxis.platform.iam.domain

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionGuardException
import com.finaxis.platform.common.transitions.TransitionNotAllowedException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The role graph in isolation: edges, the terminal state and the archive guard. */
class RoleLifecycleTests {
    private val organisationId = uuidV7()
    private val roleId = uuidV7()
    private var activeAssignments = 0L
    private val asked = mutableListOf<Pair<UUID, UUID>>()
    private val graph =
        RoleLifecycle.graph { organisation, role ->
            asked += organisation to role
            activeAssignments
        }

    @Test
    fun `the graph declares exactly the four edges`() {
        assertEquals(
            listOf(
                "ACTIVATE" to ("DISABLED" to "ACTIVE"),
                "ARCHIVE" to ("ACTIVE" to "ARCHIVED"),
                "ARCHIVE_DISABLED" to ("DISABLED" to "ARCHIVED"),
                "DEACTIVATE" to ("ACTIVE" to "DISABLED"),
            ),
            graph.definitions().map { it.transition.name to (it.from.name to it.to.name) },
        )
    }

    @Test
    fun `nothing leaves archived`() {
        assertEquals(emptyList(), graph.allowedTransitionsFrom(RoleStatus.ARCHIVED))
        RoleTransition.entries.forEach { transition ->
            assertFalse(graph.isAllowed(RoleStatus.ARCHIVED, transition), "$transition")
            assertFailsWith<TransitionNotAllowedException> {
                graph.requireDefinition(RoleStatus.ARCHIVED, transition)
            }
        }
    }

    @Test
    fun `archive is reachable from active and from disabled only, by their own names`() {
        assertEquals(
            listOf(RoleTransition.ARCHIVE, RoleTransition.DEACTIVATE),
            graph.allowedTransitionsFrom(RoleStatus.ACTIVE).map { it.transition },
        )
        assertEquals(
            listOf(RoleTransition.ACTIVATE, RoleTransition.ARCHIVE_DISABLED),
            graph.allowedTransitionsFrom(RoleStatus.DISABLED).map { it.transition },
        )
        assertFailsWith<TransitionNotAllowedException> {
            graph.requireDefinition(RoleStatus.DISABLED, RoleTransition.ARCHIVE)
        }
        assertFailsWith<TransitionNotAllowedException> {
            graph.requireDefinition(RoleStatus.ACTIVE, RoleTransition.ARCHIVE_DISABLED)
        }
    }

    @Test
    fun `both archive edges carry the active-assignment guard and the others carry none`() {
        val guarded =
            graph
                .definitions()
                .filter { it.guards.isNotEmpty() }
                .map { it.transition }
                .toSet()

        assertEquals(setOf(RoleTransition.ARCHIVE, RoleTransition.ARCHIVE_DISABLED), guarded)
    }

    @Test
    fun `the guard names the count and refuses, asking for this role in this organisation`() {
        activeAssignments = 2
        val definition = graph.requireDefinition(RoleStatus.ACTIVE, RoleTransition.ARCHIVE)

        val refusal =
            assertFailsWith<TransitionGuardException> {
                definition.guards.single().check(contextFor(RoleStatus.ACTIVE))
            }

        assertTrue("2 active assignments; revoke them" in refusal.message.orEmpty())
        assertEquals(listOf(organisationId to roleId), asked)
    }

    @Test
    fun `the guard passes when no assignment is active`() {
        activeAssignments = 0
        val definition =
            graph.requireDefinition(RoleStatus.DISABLED, RoleTransition.ARCHIVE_DISABLED)

        definition.guards.single().check(contextFor(RoleStatus.DISABLED))
    }

    private fun contextFor(status: RoleStatus) =
        com.finaxis.platform.common.transitions.TransitionContext(
            aggregate = RoleAggregate(roleId, organisationId, status),
            transition = RoleTransition.ARCHIVE,
            fromState = status,
            toState = RoleStatus.ARCHIVED,
            actor =
                com.finaxis.platform.common.transitions
                    .TransitionActor("USER", "u"),
            command =
                com.finaxis.platform.common.transitions
                    .TransitionCommand(),
            occurredAt = java.time.Instant.EPOCH,
        )
}
