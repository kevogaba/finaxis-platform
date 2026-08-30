# Email Delivery

> **Architecture overview.** For the "why", including the retry/permanent classification rules and
> the alternatives considered, see
> [ADR 0016](../adr/0016-email-delivery-transport-and-retry-classification.md).

The platform sends two application emails today: a welcome email after membership activation, and
an organisation-invite email after a local invitation is approved. Both flow through the
[membership-activation reference
pattern](../adr/0004-membership-activation-notification-pipeline.md) (transition → outbox →
RabbitMQ → JobRunr) unchanged; this document covers what happens once a JobRunr handler decides
to send.

## Non-Goals

There is no marketing/bulk-email subsystem here, and none is planned as part of this work.
Keycloak's own required-action and password-reset emails (see
[user-provisioning-keycloak.md](../security/user-provisioning-keycloak.md)) are sent entirely by
Keycloak and are out of scope — this document only covers emails the application itself composes
and sends.

## Port And Adapters

```
EmailGateway (port, notifications::email named interface)
  ├─ SpringMailEmailGateway   — real SMTP send via JavaMailSender + FreeMarker
  └─ DisabledEmailGateway     — always throws PermanentEmailDeliveryException

Both are always wrapped by:
  MeteredEmailGateway (decorator) — records delivery metrics regardless of which is active
```

`EmailConfiguration` selects `SpringMailEmailGateway` when `finaxis.email.enabled=true`, and falls
back to `DisabledEmailGateway` otherwise (the default). Either way, the bean exposed to callers is
the `MeteredEmailGateway`-wrapped instance, so delivery is observable in every environment.

## Templates

Apache FreeMarker renders four templates under `src/main/resources/templates/email/`, one HTML and
one plain-text per category, selected by `EmailCategory`:

| Category              | HTML template               | Text template                   |
|------------------------|------------------------------|----------------------------------|
| `WELCOME`               | `welcome.ftlh`               | `welcome.txt.ftl`                |
| `ORGANISATION_INVITE`   | `organisation-invite.ftlh`   | `organisation-invite.txt.ftl`    |

Every template renders from the same model: `recipientDisplayName`, `organisationDisplayName`, and
`appUrl` (`EmailProperties.appBaseUrl`). The organisation-invite email links only to that generic
app URL — it never embeds a token, one-time link, or credential-setup URL; the invited user signs
in through the normal Keycloak flow.

## Configuration Reference

SMTP transport itself is Spring Boot's own `spring.mail.*` (`MailProperties`), not duplicated here.
Finaxis-specific settings live under `finaxis.email.*` (`EmailProperties`):

| Property                        | Env var                          | Default                        |
|----------------------------------|-----------------------------------|----------------------------------|
| `finaxis.email.enabled`          | `FINAXIS_EMAIL_ENABLED`           | `false` (`true` in production)  |
| `finaxis.email.from-address`     | `FINAXIS_EMAIL_FROM_ADDRESS`      | `no-reply@finaxis.local`        |
| `finaxis.email.from-display-name`| `FINAXIS_EMAIL_FROM_DISPLAY_NAME` | `Finaxis`                        |
| `finaxis.email.app-base-url`     | `FINAXIS_EMAIL_APP_BASE_URL`      | `http://localhost:5173`         |

Production (`application-production.yaml`) requires `FINAXIS_SMTP_HOST`/`PORT`/`USERNAME`/
`PASSWORD` and `FINAXIS_EMAIL_FROM_ADDRESS`/`APP_BASE_URL` with no default, fails fast if unset,
and forces `smtp.auth`/`smtp.starttls.enable` to `true` — matching the fail-fast pattern used for
`FINAXIS_ACTIVE_ORGANISATION_CONTEXT_SECRET` (see
[production-hardening.md](../security/production-hardening.md)).

## Observability

| Meter | Type | Tags |
|---|---|---|
| `finaxis.email.delivery.attempts.total` | Counter | `category`, `outcome` |
| `finaxis.email.delivery.duration` | Timer | `category`, `outcome` |

`outcome` is one of `success`, `permanent_failure`, `retryable_failure`. Neither meter is ever
tagged with a recipient address or any rendered body content. There is no dedicated retry counter;
retry volume per category is derived as
`sum(attempts.total) - count(attempts.total{outcome="success"})`, since the gateway layer has no
visibility into JobRunr's own retry attempt number — that lives on the job record itself.

## Local Development

Local development points at [Mailpit](https://github.com/axllent/mailpit) via `compose.yaml`
(SMTP on `1025`, web UI at `http://localhost:8025`). `application.yaml`'s default
`spring.mail.host`/`port` (`localhost`/`1025`) already match Mailpit, and `finaxis.email.enabled`
still defaults to `false` — set `FINAXIS_EMAIL_ENABLED=true` locally to actually send through it.

## Testing

There is no Spring Boot `@ServiceConnection` factory for a generic SMTP server, so integration
tests run a real SMTP/IMAP server, [GreenMail](https://greenmail-mail-test.github.io/greenmail/),
via a Testcontainers *singleton* container
(`TestcontainersConfiguration.Companion.GREENMAIL_CONTAINER`) rather than a per-test `@Bean`. Each
consuming test class exposes the container's mapped host/port through its own static
`@DynamicPropertySource` method — see `MembershipActivationPipelineIntegrationTests` and
`ApplicationInvitePipelineIntegrationTests` for the pattern, and ADR 0016 for why a `@Bean`-based
`DynamicPropertyRegistrar` does not work here. Tests connect over IMAP (`greenmail.auth.disabled`
is set, so any username/password authenticates) to assert exactly one message was delivered to a
given mailbox.
