# ADR 0041: The Scheduled Business-Date Advance

## Status

Accepted, **implemented** in the release containing this change (issue #277).

Date: 2026-11-13

Builds on [ADR 0027](0027-derived-balance-projection-and-its-build-trigger.md) (the advance
schedules the daily-balance build for the date it leaves), [ADR 0038](0038-cob-readiness-and-approval-counts.md)
(close-of-business readiness) and the `inviteAsSystem` precedent (a system-run entry point whose
callers an architecture rule fixes). It supersedes nothing.

## Context

`business_date_auto_advance_enabled` (BOOLEAN, default `false`) has been in the settings catalogue
since #164 and nothing read it: the business date moved only when someone called
`POST /api/v1/tenant/business-date/advance`. #272 decided to enforce the key rather than drop it.

What the model offers:

- `BusinessDateService.advance` requires an `ACTIVE` organisation, `business_date.advance`, an
  `OPEN` date, a later date and a fresh `row_version`, bounds its lock wait at 10 s, and schedules
  the daily-balance build for the date left after commit.
- `AuthorizationService.hasPermission` passes `SystemActor` before any grant is read, so a job that
  called `advance` as the system actor would pass `business_date.advance` without anyone having
  granted it, and `advance` hard-coded the actor type `USER` into its audit row and outbox event.
- Close-of-business readiness (ADR 0038) exists, as advice: nothing enforces it.
- `IdempotencyCleanupJob` is the one recurring job. JobRunr's master server alone schedules a
  recurring job and never creates an instance while the previous one is still scheduled, enqueued
  or processing, so a recurring job runs once at a time across every replica.

## Decision

1. **A recurring JobRunr job, hourly, one day per transaction.** `BusinessDateAutoAdvanceJob`
   (cron `finaxis.lifecycle.business-date.auto-advance.schedule`, default minute 5 of every hour,
   no retries) runs `BusinessDateAutoAdvance`. For each opted-in organisation whose business date is
   earlier than its local date, it advances one day at a time, each day its own transaction, so a
   history row, an audit row, an outbox event and a daily-balance build exist for every day, as
   they would for a person advancing day by day. Rejected: jumping straight to today (no record of
   the days between) and one day per run (a week's gap takes a week of hourly runs to clear).
2. **A cap per run.** At most `max-steps-per-run` days (default 7) per organisation and
   `max-organisations-per-run` (default 500) organisations per run, so a run stays bounded; a longer
   gap catches up over the following runs. The candidates are read in organisation-id order, and
   when more are opted in than one run considers, each hour takes the next window of them
   (`BoundedRotation`, keyed by the hour, so no cursor is stored and a restart does not reset it).
   Rejected: oldest date first, which let tenants whose dates cannot move (a pending manual journal)
   fill every run and hold every other tenant back for good.
3. **An explicit system entry point, not the implicit bypass.** `BusinessDateService.advanceAsSystem`
   asks no actor permission, records the system actor with actor type `SYSTEM` in the audit row and
   the outbox event, and keeps every state rule of a manual advance. `ScheduledAdvanceRuleTests`
   allows only `BusinessDateAutoAdvance` to call it. Rejected: calling `advance` as `SystemActor`
   (the authority would be an accident of the guard, and the event would say `USER`), and a named
   system user in every tenant holding the grant (provisioning for a principal that adds nothing the
   opt-in does not already say).
4. **The tenant's opt-in is the authority, and only an advancer can give it.** Storing `true` needs
   `business_date.advance` as well as `settings.update`; storing `false` or deleting the row needs
   only `settings.update`, since it takes authority away. The change is the usual audited
   `settings.update` row. The opt-in is read again in each day's transaction, under the per-key lock
   every writer of the setting takes (`OrganisationSettingsStore.lockedCurrentSetting`), so an
   opt-out committed during a run stops the next day, and one made while a day is advancing waits
   for that day to commit.
5. **No close-of-business.** The job advances only an `OPEN` date, exactly as a manual advance does,
   and skips `CLOSING` and `CLOSED`: a tenant that closes its days by hand keeps doing so. Running
   start, complete, reopen and advance in one go would make the close a formality.
6. **Readiness holds it back.** A day is not advanced while a BLOCKING readiness check fails (a
   manual journal pending approval): a person can choose to advance past one, an unattended job
   should not. Advisory checks do not hold it back.
7. **Skips, never errors.** A day that may not be advanced (opted out, organisation not `ACTIVE`,
   date not `OPEN`, moved by someone else, not ready, a lost version race, an expired lock wait) or
   that fails for any other reason stops that organisation for this run and is retried on the next;
   it never stops another organisation. A failure is logged as a `WARN` with the exception class and
   no message.
8. **The organisation's zone decides "today".** `BusinessDateCalendar.localToday` (shared with
   approval) reads it from `organisation.timezone`; never a branch's zone or `default_timezone`. It
   is computed in Kotlin, because SQL `AT TIME ZONE` reads an offset id with the POSIX sign. The job
   never moves a date past the local date.
9. **Off until the operator turns it on.** `finaxis.lifecycle.business-date.auto-advance.mode` is
   `OFF` by default, with a `WARN` while a tenant has opted in, as email delivery is; `DRY_RUN` logs
   each day it would advance and writes nothing, so a first production run can be watched before it
   writes; `ON` advances.

## Consequences

- An opted-in tenant's business date follows the calendar without a person, once the operator
  allows it, and every day it moves is recorded as the system's act.
- A tenant cannot be put on auto-advance by someone who could not advance by hand.
- A pending manual journal holds a tenant's date back until it is decided; the readiness endpoint
  shows why.
- No grace period after midnight: a day is advanced in the first run after the local date changes.
  Backdated postings into the day left stay legal (ADR 0027), so a late correction still lands.
- Not done: notifying anyone of a skipped or failed day (the logs and the readiness endpoint carry
  it), and a grace period, both left until a tenant asks.
