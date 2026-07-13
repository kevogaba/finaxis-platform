package com.finaxis.platform.notifications

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.FoundationLifecycleService
import com.finaxis.platform.lifecycle.application.MembershipTransitionCommand
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import org.awaitility.Awaitility.await
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
        "jobrunr.dashboard.enabled=false",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MembershipActivationPipelineIntegrationTests(
    private val lifecycleService: FoundationLifecycleService,
    private val storageProvider: StorageProvider,
    private val dsl: DSLContext,
) {
    @Test
    fun `membership activation is externalized and processed as a successful welcome email job`() {
        resetSeededMembershipToPendingApproval()

        RequestContexts.withActor(
            ActorContext(LOCAL_USER_ID, "local-admin", "local.admin", "admin@finaxis.local"),
        ) {
            lifecycleService.transition(
                MembershipTransitionCommand(
                    organisationId = LOCAL_ORGANISATION_ID,
                    membershipId = LOCAL_MEMBERSHIP_ID,
                    branchId = HEAD_OFFICE_BRANCH_ID,
                    transition = MembershipLifecycleTransition.ACTIVATE,
                    command = TransitionCommand(occurredAt = ACTIVATED_AT),
                ),
            )
        }

        await()
            .ignoreException(JobNotFoundException::class.java)
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted {
                assertEquals(
                    StateName.SUCCEEDED,
                    storageProvider.getJobById(deterministicWelcomeEmailJobId()).state,
                )
            }
    }

    private fun resetSeededMembershipToPendingApproval() {
        assertEquals(
            1,
            dsl
                .update(USER_ORGANISATION_MEMBERSHIP)
                .set(
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                    MembershipLifecycleState.PENDING_APPROVAL.name,
                ).where(USER_ORGANISATION_MEMBERSHIP.ID.eq(LOCAL_MEMBERSHIP_ID))
                .and(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(LOCAL_ORGANISATION_ID))
                .execute(),
        )
    }

    private fun deterministicWelcomeEmailJobId(): UUID =
        UUID.nameUUIDFromBytes(
            "$LOCAL_MEMBERSHIP_ID:${MembershipLifecycleTransition.ACTIVATE}:$ACTIVATED_AT"
                .toByteArray(),
        )

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val LOCAL_ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val HEAD_OFFICE_BRANCH_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val LOCAL_MEMBERSHIP_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val ACTIVATED_AT: Instant = Instant.parse("2026-07-13T12:00:00Z")
    }
}
