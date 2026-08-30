package com.finaxis.platform.notifications

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.FoundationLifecycleService
import com.finaxis.platform.lifecycle.application.MembershipTransitionCommand
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import io.namastack.outbox.OutboxRecordRepository
import jakarta.mail.Folder
import jakarta.mail.Session
import org.awaitility.Awaitility.await
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestConstructor
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
        "jobrunr.dashboard.enabled=false",
        "finaxis.email.enabled=true",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MembershipActivationPipelineIntegrationTests(
    private val lifecycleService: FoundationLifecycleService,
    private val storageProvider: StorageProvider,
    private val outboxRecords: OutboxRecordRepository,
    private val dsl: DSLContext,
) {
    @Test
    fun `membership activation is externalized and processed as a successful welcome email job`() {
        resetSeededMembershipToPendingApproval()
        purgeWelcomeEmailMailbox()

        RequestContexts.withActor(
            ActorContext(LOCAL_USER_ID, "local-admin", "local.admin", LOCAL_ADMIN_EMAIL),
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
                assertTrue(
                    outboxRecords
                        .findCompletedRecords()
                        .mapNotNull { it.payload as? ExternalizedTransitionEvent }
                        .any { it.target == MEMBERSHIP_ACTIVATED_TARGET },
                )
            }

        assertExactlyOneWelcomeEmailDelivered()
    }

    /**
     * Connects to the GreenMail container's IMAP port and confirms the real
     * [com.finaxis.platform.notifications.adapter.outbound.email.SpringMailEmailGateway]
     * delivered exactly one welcome email to the seeded local admin's mailbox.
     */
    private fun assertExactlyOneWelcomeEmailDelivered() {
        val container = TestcontainersConfiguration.GREENMAIL_CONTAINER
        val session = Session.getInstance(Properties())
        val store = session.getStore("imap")
        store.connect(
            container.host,
            container.getMappedPort(GREENMAIL_IMAP_PORT),
            LOCAL_ADMIN_EMAIL,
            "test",
        )
        val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
        try {
            assertEquals(1, inbox.messageCount)
            val message = inbox.getMessage(1)
            assertTrue(message.subject.contains("Welcome to"))
            assertTrue(message.allRecipients.any { it.toString() == LOCAL_ADMIN_EMAIL })
        } finally {
            inbox.close(false)
            store.close()
        }
    }

    /**
     * `admin@finaxis.local` is a fixed bootstrap mailbox (seeded by
     * `V3__bootstrap_tenant_and_administrator.sql`, not per-test data) shared by every test in
     * this suite against the singleton GreenMail container. Clearing it first keeps
     * `assertExactlyOneWelcomeEmailDelivered` accurate regardless of prior test runs in the same
     * JVM.
     */
    private fun purgeWelcomeEmailMailbox() {
        val container = TestcontainersConfiguration.GREENMAIL_CONTAINER
        val session = Session.getInstance(Properties())
        val store = session.getStore("imap")
        store.connect(
            container.host,
            container.getMappedPort(GREENMAIL_IMAP_PORT),
            LOCAL_ADMIN_EMAIL,
            "test",
        )
        val inbox = store.getFolder("INBOX").apply { open(Folder.READ_WRITE) }
        try {
            inbox.messages.forEach { it.setFlag(jakarta.mail.Flags.Flag.DELETED, true) }
        } finally {
            inbox.close(true)
            store.close()
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
        const val MEMBERSHIP_ACTIVATED_TARGET = "finaxis.lifecycle.membership.activated"

        // Seeded by V3__bootstrap_tenant_and_administrator.sql: user_account.id
        // 11111111-1111-1111-1111-111111111111 owns membership LOCAL_MEMBERSHIP_ID and its
        // email is the welcome-email recipient this test verifies via GreenMail.
        const val LOCAL_ADMIN_EMAIL = "admin@finaxis.local"
        const val GREENMAIL_IMAP_PORT = 3143
        const val GREENMAIL_SMTP_PORT = 3025

        /**
         * Publishes the singleton GreenMail container's SMTP host/port as `spring.mail.*`
         * properties early enough (before context refresh) for
         * `MailSenderAutoConfiguration`'s `@ConditionalOnProperty("spring.mail.host")` to see
         * them — see the KDoc on
         * [com.finaxis.platform.TestcontainersConfiguration.Companion.GREENMAIL_CONTAINER] for
         * why this can't be a bean-based `DynamicPropertyRegistrar` in that shared config class.
         */
        @DynamicPropertySource
        @JvmStatic
        fun greenMailProperties(registry: DynamicPropertyRegistry) {
            val container = TestcontainersConfiguration.GREENMAIL_CONTAINER
            registry.add("spring.mail.host") { container.host }
            registry.add("spring.mail.port") { container.getMappedPort(GREENMAIL_SMTP_PORT) }
        }
    }
}
