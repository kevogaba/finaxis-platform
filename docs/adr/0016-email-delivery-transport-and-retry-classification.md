# ADR 0016: Email Delivery Transport And Retry Classification

## Status

Accepted

Date: 2026-08-30

## Context

Both JobRunr handlers on the reference notification pipeline (`SendWelcomeEmailJobRequestHandler`
after membership activation, `ApplicationInviteJobRequestHandler` after an organisation invite,
see ADR 0004) only logged "would send email" — GitHub issues #32 and #33. Replacing the stub
needs an SMTP transport, a template engine, and — because JobRunr will retry any handler that
rethrows — a deliberate answer to "which failures are worth retrying" so a bad recipient address
does not retry forever and a transient SMTP outage is not treated as permanent.

## Decision

**Port.** `notifications` owns a new outbound port,
`com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway`:
`fun send(message: EmailMessage): EmailDeliveryReceipt`, throwing either
`RetryableEmailDeliveryException` (a JobRunr retry may resolve it) or
`PermanentEmailDeliveryException` (no retry will help). Both extend a sealed
`EmailDeliveryException`. The port is exposed as the `notifications::email` Spring Modulith named
interface so `lifecycle` can depend on it without `notifications` ever depending back on
`lifecycle` or `iam`.

**Adapter.** `SpringMailEmailGateway` sends multipart (HTML + plain-text) mail via Spring Mail's
`JavaMailSender`, rendering `EmailCategory.WELCOME`/`ORGANISATION_INVITE` from four Apache
FreeMarker templates (`welcome.ftlh`/`.txt.ftl`, `organisation-invite.ftlh`/`.txt.ftl`) through
`EmailTemplateRenderer`. It classifies `MailAuthenticationException` and `MailParseException` as
permanent, a `MailSendException` as permanent only when the underlying cause chain contains a
Jakarta Mail `AddressException`/`SendFailedException` with invalid addresses, and everything else
(including a raw `MessagingException` from `MimeMessageHelper` during message preparation) as
permanent-or-retryable per the same rule set documented on `SpringMailEmailGateway.kt`. A
`DisabledEmailGateway` (always throws `PermanentEmailDeliveryException`) backs
`finaxis.email.enabled=false`, the default; a `MeteredEmailGateway` decorator wraps whichever
implementation is active, always, so delivery is observable even when email is off.

**Do-not-retry mechanism.** JobRunr has no native "stop retrying" signal from inside a handler
other than throwing `org.jobrunr.JobRunrException(reason, doNotRetry = true, cause)`. Both handlers
catch `PermanentEmailDeliveryException` and rethrow it wrapped that way; a
`RetryableEmailDeliveryException` (or, for the application-invite handler, an
`IllegalStateException` from a missing membership/organisation or a `DataAccessException`)
rethrows as-is so JobRunr's default retry policy applies.

**Per-job send-once idempotency.** A JobRunr retry re-runs the handler from the top, so sending the
email is wrapped in a step that must not repeat even though the audit/dispatch bookkeeping after it
can still fail and legitimately trigger a retry. `common::jobs`' `JobStepGuard` (backed by
`JobContext.runStepOnce`, JobRunr's own per-job step-completion metadata — no new table) is that
seam: `jobStepGuard.runOnce("send-welcome-email") { emailGateway.send(...) }` skips the lambda on a
retry once it has already completed for that job. This is the mechanism the "job retry after a
later failure does not send a second email" scenario in
`ApplicationInvitePipelineIntegrationTests` and `SendWelcomeEmailJobRequestHandlerTests` verifies.

**Recipient/organisation-name resolution stays inside each module.** `notifications` cannot depend
on `lifecycle`, so its welcome-email handler resolves the recipient and organisation display name
through a new read-only jOOQ lookup, `WelcomeEmailRecipientDirectory`, rather than by adding
email/PII fields to the membership-activation event's metadata (which is persisted unredacted into
`user_organisation_membership_transition_log.metadata_jsonb`). `lifecycle`'s
`ApplicationInviteJobRequestHandler` already has a `UserProvisioningStore`; it gained one default
method, `organisationDisplayName(organisationId): String?`.

**Hermetic SMTP testing.** No Spring Boot `@ServiceConnection` factory exists for a generic SMTP
server, so integration tests use GreenMail via a Testcontainers *singleton* container
(`TestcontainersConfiguration.GREENMAIL_CONTAINER`, started eagerly in a companion-object
initializer) rather than a `@Bean` + `DynamicPropertyRegistrar` pair. That pairing was tried first
and does not work: `MailSenderAutoConfiguration`'s `@ConditionalOnProperty("spring.mail.host")` is
evaluated during `invokeBeanFactoryPostProcessors()`, before a bean-based
`DynamicPropertyRegistrar` runs (that only happens later, inside
`finishBeanFactoryInitialization()`). Each consuming test class instead declares its own static
`@DynamicPropertySource` method reading the singleton's mapped host/port directly (Spring only
scans a test class and its enclosing classes for `@DynamicPropertySource`, never `@Import`-ed
configuration classes).

## Consequences

- Both stubbed handlers are gone; welcome and organisation-invite emails are delivered over real
  SMTP, gated by `finaxis.email.enabled` (default `false`, `true` in production).
- A permanent delivery failure now stops JobRunr retries immediately instead of retrying forever;
  a transient one still retries under JobRunr's default backoff.
- `lifecycle` gained one new allowed dependency, `notifications::email` (alongside `common::jobs`),
  with no cycle: `notifications` still never imports `lifecycle`/`iam` — enforced by
  `ModuleDependencyRuleTests`.
- Observability is uniform regardless of whether email is enabled: `finaxis.email.delivery
  .attempts.total` (counter) and `finaxis.email.delivery.duration` (timer), both tagged only by
  `category`/`outcome` — never a recipient address or rendered body. See
  `docs/architecture/email-delivery.md`.
- Every SMTP integration test needs Docker (GreenMail via Testcontainers); there is no in-memory
  fallback.

## Alternatives Considered

Add a dedicated `email_dispatch_attempt` table for send-once tracking, mirroring
`identity_dispatch_log`:

- Rejected. JobRunr already persists per-job step completion in the job's own metadata via
  `JobContext.runStepOnce`; a new table would duplicate that guarantee for no benefit.

Classify every SMTP failure as retryable and rely on a maximum retry count alone:

- Rejected. A permanently bad recipient address would then retry on the same exponential backoff
  as a transient outage, wasting JobRunr attempts and delaying the eventual audit failure record
  that operators need to see.

Add the recipient email and organisation name to the membership-activation event's metadata so
`notifications` never needs its own lookup:

- Rejected. That metadata is persisted unredacted (`ADR 0007`'s redaction applies to the audit
  trail, not this transition log), so it would leak PII into a table with no redaction policy.

## Verification

- `ThreadLocalJobContextStepGuardTests`
- `EmailDeliveryExceptionTests`, `EmailPropertiesTests`, `EmailTemplateRendererTests`,
  `SpringMailEmailGatewayTests`, `DisabledEmailGatewayTests`, `MeteredEmailGatewayTests`
- `JooqWelcomeEmailRecipientDirectoryTests`, `JooqUserProvisioningStoreTests`
- `SendWelcomeEmailJobRequestHandlerTests`, `ApplicationInviteHandlerTests`
- `MembershipActivationPipelineIntegrationTests` (GreenMail-backed welcome-email delivery)
- `ApplicationInvitePipelineIntegrationTests` (GreenMail-backed delivery, redelivery, and
  job-retry idempotency for the organisation-invite email)
