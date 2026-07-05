# Audit Logging

Audit trails are for security-sensitive and business-critical actions. They are not a replacement
for access logs, metrics, traces, or event logs.

## What To Audit

Audit these actions when they are implemented:

- admin changes;
- role, permission, membership, and scope changes;
- critical state transitions;
- manual overrides;
- security-sensitive actions;
- rate-limit policy changes;
- critical configuration changes.

Do not persist every normal successful `GET` request as an audit event.

## Event Shape

The common audit foundation records:

- actor type and actor ID;
- tenant or organisation ID when available;
- action;
- resource type and resource ID;
- outcome;
- reason/comment;
- metadata;
- request/correlation ID;
- source IP;
- timestamp.

Do not store passwords, bearer tokens, session cookies, API keys, raw authorization headers, or
sensitive PII in audit metadata.

## Current Adapter

`AuditService` writes to an `AuditEventRepository` port. The default repository logs structured
audit events until a durable persistence adapter is introduced. Domain modules should call the
application-level audit service or publish explicit events that audit listeners consume.

## Request Correlation

`HttpAccessLogFilter` preserves inbound `X-Request-Id` or generates one. It returns the header to
clients and stores `requestId` in MDC for request logs.
