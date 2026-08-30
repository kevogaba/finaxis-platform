# Production Email Transport + Invite/Welcome Emails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two stubbed "would send email" JobRunr handlers (welcome email after
membership activation, organisation-invite email after a new invite) with a real SMTP-backed
`EmailGateway` owned by the `notifications` module, rendering Apache FreeMarker multipart
(HTML + plain-text) templates, with deterministic retryable-vs-permanent JobRunr retry behavior
and PII-free Micrometer observability. Closes GitHub issues #32 and #33.

**Architecture:** `notifications` owns a new `EmailGateway` port (`send(EmailMessage):
EmailDeliveryReceipt`, throwing `RetryableEmailDeliveryException`/`PermanentEmailDeliveryException`)
backed by a `JavaMailSender` + FreeMarker adapter. A new `common::jobs` module wraps JobRunr's own
`JobContext.runStepOnce` to guarantee "send at most once per job, even across retries" without a
new database table. `lifecycle`'s existing `ApplicationInviteJobRequestHandler` gains a dependency
on the new `notifications::email` named interface; `notifications`' existing
`SendWelcomeEmailJobRequestHandler` is rewritten to resolve recipient context via a new read-only
jOOQ lookup (never via `lifecycle`, which `notifications` must never depend on) and call the same
gateway. Both existing reference-pipeline flows (event → outbox → RabbitMQ → listener → JobRunr)
are otherwise unchanged.

**Tech Stack:** Kotlin, Spring Boot 4.1.1, Spring Mail (`spring-boot-starter-mail`, already a
dependency), Apache FreeMarker (`spring-boot-starter-freemarker`, already a dependency), JobRunr
8.8.1, Micrometer (via `spring-boot-starter-actuator`, already a dependency), jOOQ, GreenMail
2.1.13 via Testcontainers for hermetic SMTP integration tests.

## Global Constraints

- Kotlin-first; no Java in new application code.
- Every new public production class/function needs a KDoc comment (Detekt zero-findings gate
  requires it).
- No broad `catch (Exception)`/`catch (RuntimeException)` — every catch clause names a specific
  type (Detekt `TooGenericExceptionCaught`).
- Max line length 100.
- Never log or persist to an audit `reason` field a raw exception message, recipient email
  address, or rendered email body — use `Throwable.toAuditFailureReason()`
  (`com.finaxis.platform.common.audit.AuditFailureReason.kt`) for audit `reason` values, and never
  put a recipient/body value in a Micrometer tag.
- `notifications` must never import `com.finaxis.platform.lifecycle` or
  `com.finaxis.platform.iam` (enforced by `ModuleDependencyRuleTests.kt`).
- No Flyway migration in this plan — do not add one. If a task's tests reveal one is genuinely
  required, stop and flag it rather than improvising a `V5` migration.
- The organisation-invite email contains a generic app link only (`EmailProperties.appBaseUrl`) —
  never embed a token, one-time link, or credential-setup URL in any template.
- Run `./gradlew spotlessApply` before every commit that touches Kotlin files (matches this
  repo's Spotless/ktlint formatting gate) and never skip it because "it's a small change."

---

### Task 1: `common::jobs` — JobRunr step-idempotency seam

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/common/jobs/JobStepGuard.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/jobs/ThreadLocalJobContextStepGuard.kt`
- Create: `src/main/java/com/finaxis/platform/common/jobs/package-info.java`
- Test: `src/test/kotlin/com/finaxis/platform/common/jobs/ThreadLocalJobContextStepGuardTests.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

**Interfaces:**
- Produces: `fun interface JobStepGuard { fun runOnce(step: String, action: () -> Unit) }` and its
  production implementor `ThreadLocalJobContextStepGuard : JobStepGuard`. Later tasks (6 and 9)
  inject `JobStepGuard` (the interface, never `ThreadLocalJobContextStepGuard` directly) into
  their handlers, and their own unit tests use a hand-written fake, not this class.

**Verified correction (2026-08-30):** an earlier draft of this task assumed
`ThreadLocalJobContext.getJobContext()` returns `JobContext.Null` when no job is active. That is
wrong — verified against JobRunr 8.8.1's actual source
(`core/src/main/java/org/jobrunr/server/runner/ThreadLocalJobContext.java`): it **throws**
`JobRunrException("No JobContext available...")`, and its own error message says to use
`MockThreadLocalJobContext` from JobRunr's `test-fixtures` artifact. JobRunr 8.8.1 publishes a
real `testFixturesApiElements`/`testFixturesRuntimeElements` Gradle Module Metadata variant
(verified via the published `.module` file), containing `MockThreadLocalJobContext` (an
`AutoCloseable` that sets/clears the thread-local for the duration of a `.use {}` block) and
`org.jobrunr.jobs.JobTestBuilder` (a fluent `Job` builder, `aJob().withEnqueuedState().build(): Job` — a Job needs at least one initial state). The task below
uses these to test the real `getJobContext()`/`runStepOnce()` path end-to-end instead of guessing
at a fallback that doesn't exist.

- [ ] **Step 1: Add the JobRunr test-fixtures dependency**

In `gradle/libs.versions.toml`, add a new alias next to the existing `jobrunr-spring-boot` one
(the starter doesn't publish test fixtures; the underlying `org.jobrunr:jobrunr` core module
does):

```toml
jobrunr-core = { module = "org.jobrunr:jobrunr", version.ref = "jobrunrVersion" }
```

In `build.gradle.kts`, add alongside the other `testImplementation(...)` entries. **Do not use
Gradle's `testFixtures(...)` helper function here** — verified by direct resolution attempt: it
guesses the capability name `org.jobrunr:jobrunr-test-fixtures`, but JobRunr's internal Gradle
project for this jar is named `core`, so the capability it actually publishes is
`org.jobrunr:core-test-fixtures` (confirmed via the published `.module` Gradle Module Metadata).
Request that capability explicitly instead:

```kotlin
    testImplementation(libs.jobrunr.core) {
        capabilities {
            requireCapability("org.jobrunr:core-test-fixtures")
        }
    }
```

- [ ] **Step 2: Write the failing test**

JobRunr's `JobContext.runStepOnce(step, task)` (verified against the real 8.8.1 jar/sources)
always wraps any exception thrown by `task` into `org.jobrunr.jobs.exceptions.StepExecutionException`
before rethrowing, and persists step-completion into the `Job`'s own metadata map (`job.getMetadata()`),
which `JobTestBuilder`-built `Job` instances expose directly — so reusing the same `Job` object
across two separate `MockThreadLocalJobContext` blocks correctly simulates "the job was retried."

```kotlin
package com.finaxis.platform.common.jobs

import org.jobrunr.jobs.JobTestBuilder.aJob
import org.jobrunr.server.runner.MockThreadLocalJobContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThreadLocalJobContextStepGuardTests {
    private val guard = ThreadLocalJobContextStepGuard()

    @Test
    fun `runs the action once for a fresh job`() {
        var calls = 0

        MockThreadLocalJobContext().use {
            MockThreadLocalJobContext.setUpJobContextForJob(aJob().withEnqueuedState().build())
            guard.runOnce("test-step") { calls++ }
        }

        assertEquals(1, calls)
    }

    @Test
    fun `skips the action on a retry once the step already completed`() {
        var calls = 0
        val job = aJob().withEnqueuedState().build()

        MockThreadLocalJobContext().use {
            MockThreadLocalJobContext.setUpJobContextForJob(job)
            guard.runOnce("test-step") { calls++ }
        }
        MockThreadLocalJobContext().use {
            MockThreadLocalJobContext.setUpJobContextForJob(job)
            guard.runOnce("test-step") { calls++ }
        }

        assertEquals(1, calls)
    }

    @Test
    fun `propagates the original exception type, not JobRunr's wrapper`() {
        class BoomException(message: String) : RuntimeException(message)

        val exception =
            MockThreadLocalJobContext().use {
                MockThreadLocalJobContext.setUpJobContextForJob(aJob().withEnqueuedState().build())
                assertFailsWith<BoomException> {
                    guard.runOnce("boom-step") { throw BoomException("delivery failed") }
                }
            }

        assertTrue(exception.message == "delivery failed")
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.common.jobs.ThreadLocalJobContextStepGuardTests"`
Expected: FAIL — compilation error, `ThreadLocalJobContextStepGuard` and `JobStepGuard` are unresolved references.

- [ ] **Step 4: Write the port and its implementation**

```kotlin
// JobStepGuard.kt
package com.finaxis.platform.common.jobs

/**
 * Runs a background-job step at most once across JobRunr retries, keyed by [step]'s name.
 */
fun interface JobStepGuard {
    /** Executes [action] unless a prior attempt of this job already completed [step]. */
    fun runOnce(
        step: String,
        action: () -> Unit,
    )
}
```

```kotlin
// ThreadLocalJobContextStepGuard.kt
package com.finaxis.platform.common.jobs

import org.jobrunr.jobs.exceptions.StepExecutionException
import org.jobrunr.server.runner.ThreadLocalJobContext
import org.springframework.stereotype.Component

/**
 * Delegates to JobRunr's own [org.jobrunr.jobs.context.JobContext.runStepOnce], which persists
 * step-completion in the job's own metadata so a step is skipped on retry once it has succeeded.
 * `runStepOnce` always wraps any thrown exception in [StepExecutionException]; this class unwraps
 * it so callers see the original exception type from [action].
 */
@Component
class ThreadLocalJobContextStepGuard : JobStepGuard {
    override fun runOnce(
        step: String,
        action: () -> Unit,
    ) {
        try {
            ThreadLocalJobContext.getJobContext().runStepOnce(step) { action() }
        } catch (ex: StepExecutionException) {
            throw ex.cause ?: ex
        }
    }
}
```

```java
// package-info.java
/**
 * Shared JobRunr job-execution helpers, exposed for cross-module reuse by any handler needing
 * per-job step idempotency across retries.
 */
@org.springframework.modulith.NamedInterface("jobs")
package com.finaxis.platform.common.jobs;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.common.jobs.ThreadLocalJobContextStepGuardTests"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts src/main/kotlin/com/finaxis/platform/common/jobs src/main/java/com/finaxis/platform/common/jobs src/test/kotlin/com/finaxis/platform/common/jobs
git commit -m "feat(common): add JobStepGuard for JobRunr step idempotency across retries"
```

---

### Task 2: `EmailGateway` port and exception hierarchy (owned by `notifications`)

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/notifications/application/port/outbound/email/EmailGateway.kt`
- Create: `src/main/java/com/finaxis/platform/notifications/application/port/outbound/email/package-info.java`
- Test: `src/test/kotlin/com/finaxis/platform/notifications/application/port/outbound/email/EmailDeliveryExceptionTests.kt`

**Interfaces:**
- Produces: `EmailCategory` (`WELCOME`, `ORGANISATION_INVITE`), `EmailMessage(category,
  recipientEmail, recipientDisplayName, organisationDisplayName)`, `EmailDeliveryReceipt(messageId:
  String?)`, `EmailDeliveryException` (sealed), `RetryableEmailDeliveryException(message, cause)`,
  `PermanentEmailDeliveryException(message, cause)`, `fun interface EmailGateway { fun
  send(message: EmailMessage): EmailDeliveryReceipt }`. Every later task that sends an email
  (Tasks 5, 6, 8, 11) depends on these exact names and signatures.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.finaxis.platform.notifications.application.port.outbound.email

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class EmailDeliveryExceptionTests {
    @Test
    fun `retryable and permanent exceptions are both EmailDeliveryException`() {
        val retryable = RetryableEmailDeliveryException("transient smtp failure")
        val permanent = PermanentEmailDeliveryException("bad recipient")

        assertIs<EmailDeliveryException>(retryable)
        assertIs<EmailDeliveryException>(permanent)
        assertNull(retryable.cause)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryExceptionTests"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the port and types**

```kotlin
// EmailGateway.kt
package com.finaxis.platform.notifications.application.port.outbound.email

/** Categorizes a Finaxis application email for template selection and metric tagging. */
enum class EmailCategory {
    WELCOME,
    ORGANISATION_INVITE,
}

/**
 * The recipient and business context needed to render and deliver one Finaxis application email.
 * Never carries a template's rendered body, a token, or any secret.
 */
data class EmailMessage(
    val category: EmailCategory,
    val recipientEmail: String,
    val recipientDisplayName: String,
    val organisationDisplayName: String,
)

/** Confirms an email was accepted for delivery by the underlying transport. */
data class EmailDeliveryReceipt(
    val messageId: String?,
)

/** Base type for email rendering/delivery failures, owned by the notifications module. */
sealed class EmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A failure a JobRunr retry may resolve (transient connectivity, transient SMTP failure). */
class RetryableEmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : EmailDeliveryException(message, cause)

/** A failure no retry will fix (bad address, template error, bad auth configuration). */
class PermanentEmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : EmailDeliveryException(message, cause)

/**
 * Outbound port for rendering and delivering Finaxis application emails, owned by the
 * notifications module. Implementations must never be called synchronously inside a tenant/user
 * provisioning transaction — callers invoke this only from durable JobRunr background jobs.
 */
fun interface EmailGateway {
    /**
     * @throws RetryableEmailDeliveryException for failures a retry may resolve
     * @throws PermanentEmailDeliveryException for failures no retry will fix
     */
    fun send(message: EmailMessage): EmailDeliveryReceipt
}
```

```java
// package-info.java
/**
 * Public outbound email-delivery port for Finaxis application emails, owned by the notifications
 * module. Consumed cross-module by lifecycle's application-invite job handler.
 */
@org.springframework.modulith.NamedInterface("email")
package com.finaxis.platform.notifications.application.port.outbound.email;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryExceptionTests"`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/notifications/application/port/outbound/email src/main/java/com/finaxis/platform/notifications/application/port/outbound/email src/test/kotlin/com/finaxis/platform/notifications/application/port/outbound/email
git commit -m "feat(notifications): add EmailGateway port and retry/permanent exception types"
```

---

### Task 3: `EmailProperties`

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailProperties.kt`
- Test: `src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailPropertiesTests.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `EmailProperties(enabled: Boolean = false, fromAddress: String, fromDisplayName:
  String, appBaseUrl: String)` — consumed by Task 5 (`SpringMailEmailGateway`) and Task 6
  (`EmailConfiguration`'s `@EnableConfigurationProperties`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.email

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmailPropertiesTests {
    @Test
    fun `defaults construct without error`() {
        val properties = EmailProperties()

        assertEquals(false, properties.enabled)
        assertEquals("no-reply@finaxis.local", properties.fromAddress)
        assertEquals("Finaxis", properties.fromDisplayName)
        assertEquals("http://localhost:5173", properties.appBaseUrl)
    }

    @Test
    fun `blank from address is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EmailProperties(fromAddress = "  ")
        }
    }

    @Test
    fun `blank from display name is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EmailProperties(fromDisplayName = "")
        }
    }

    @Test
    fun `non-http app base url is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EmailProperties(appBaseUrl = "ftp://example.test")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.email.EmailPropertiesTests"`
Expected: FAIL — unresolved reference `EmailProperties`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.email

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Finaxis-specific email configuration. SMTP transport settings (host/port/credentials/timeouts)
 * are owned by Spring Boot's own `spring.mail.*`/`MailProperties`, not duplicated here.
 */
@ConfigurationProperties(prefix = "finaxis.email")
data class EmailProperties(
    val enabled: Boolean = false,
    val fromAddress: String = "no-reply@finaxis.local",
    val fromDisplayName: String = "Finaxis",
    val appBaseUrl: String = "http://localhost:5173",
) {
    init {
        require(fromAddress.isNotBlank()) { "From address must not be blank" }
        require(fromDisplayName.isNotBlank()) { "From display name must not be blank" }
        require(APP_URL_REGEX.matches(appBaseUrl)) { "App base URL must be an absolute http(s) URL" }
    }

    private companion object {
        val APP_URL_REGEX = Regex("^https?://.+")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.email.EmailPropertiesTests"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailProperties.kt src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailPropertiesTests.kt
git commit -m "feat(notifications): add EmailProperties configuration"
```

---

### Task 4: FreeMarker templates and `EmailTemplateRenderer`

**Files:**
- Create: `src/main/resources/templates/email/welcome.ftlh`
- Create: `src/main/resources/templates/email/welcome.txt.ftl`
- Create: `src/main/resources/templates/email/organisation-invite.ftlh`
- Create: `src/main/resources/templates/email/organisation-invite.txt.ftl`
- Create: `src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailTemplateRenderer.kt`
- Test: `src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailTemplateRendererTests.kt`

**Interfaces:**
- Consumes: `PermanentEmailDeliveryException` (Task 2).
- Produces: `EmailTemplateRenderer(freemarkerConfig: freemarker.template.Configuration)` with `fun
  render(templateName: String, model: Map<String, Any?>): String`. Consumed by Task 5
  (`SpringMailEmailGateway`).

- [ ] **Step 1: Write the four templates**

```html
<!-- welcome.ftlh -->
<!DOCTYPE html>
<html>
<body style="font-family: sans-serif; color: #1a1a1a;">
<p>Hi ${recipientDisplayName},</p>
<p>Your account is now active in <strong>${organisationDisplayName}</strong> on Finaxis.</p>
<p><a href="${appUrl}">Sign in to Finaxis</a></p>
<p>— The Finaxis team</p>
</body>
</html>
```

```
<!-- welcome.txt.ftl -->
Hi ${recipientDisplayName},

Your account is now active in ${organisationDisplayName} on Finaxis.

Sign in: ${appUrl}

— The Finaxis team
```

```html
<!-- organisation-invite.ftlh -->
<!DOCTYPE html>
<html>
<body style="font-family: sans-serif; color: #1a1a1a;">
<p>Hi ${recipientDisplayName},</p>
<p>You've been invited to join <strong>${organisationDisplayName}</strong> on Finaxis.</p>
<p><a href="${appUrl}">Go to Finaxis</a></p>
<p>— The Finaxis team</p>
</body>
</html>
```

```
<!-- organisation-invite.txt.ftl -->
Hi ${recipientDisplayName},

You've been invited to join ${organisationDisplayName} on Finaxis.

Go to Finaxis: ${appUrl}

— The Finaxis team
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.email

import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmailTemplateRendererTests {
    private val freemarkerConfig =
        Configuration(Configuration.VERSION_2_3_32).apply {
            setDirectoryForTemplateLoading(java.io.File("src/main/resources/templates"))
            templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        }
    private val renderer = EmailTemplateRenderer(freemarkerConfig)

    @Test
    fun `renders the welcome html template with the supplied model`() {
        val html =
            renderer.render(
                "email/welcome.ftlh",
                mapOf(
                    "recipientDisplayName" to "Ada Lovelace",
                    "organisationDisplayName" to "Acme Bank",
                    "appUrl" to "https://app.finaxis.test",
                ),
            )

        assertTrue(html.contains("Ada Lovelace"))
        assertTrue(html.contains("Acme Bank"))
        assertTrue(html.contains("https://app.finaxis.test"))
    }

    @Test
    fun `renders the organisation-invite plain-text template`() {
        val text =
            renderer.render(
                "email/organisation-invite.txt.ftl",
                mapOf(
                    "recipientDisplayName" to "Grace Hopper",
                    "organisationDisplayName" to "Acme Bank",
                    "appUrl" to "https://app.finaxis.test",
                ),
            )

        assertTrue(text.contains("Grace Hopper"))
        assertTrue(text.contains("invited to join Acme Bank"))
    }

    @Test
    fun `missing template raises a permanent delivery exception`() {
        assertFailsWith<PermanentEmailDeliveryException> {
            renderer.render("email/does-not-exist.ftlh", emptyMap())
        }
    }
}
```

Note on the test fixture above: it builds its own minimal `freemarker.template.Configuration`
pointed at `src/main/resources/templates` rather than a Spring context, so this stays a plain
unit test (no `@SpringBootTest`).

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.email.EmailTemplateRendererTests"`
Expected: FAIL — unresolved reference `EmailTemplateRenderer`.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import freemarker.template.Configuration
import freemarker.template.TemplateException
import org.springframework.stereotype.Component
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils
import java.io.IOException

/** Renders Finaxis email bodies from FreeMarker templates on the application classpath. */
@Component
class EmailTemplateRenderer(
    private val freemarkerConfig: Configuration,
) {
    /**
     * Renders [templateName] (a classpath-relative path under `templates/`) with [model].
     *
     * @throws PermanentEmailDeliveryException if the template cannot be loaded or rendered
     */
    fun render(
        templateName: String,
        model: Map<String, Any?>,
    ): String =
        try {
            val template = freemarkerConfig.getTemplate(templateName)
            FreeMarkerTemplateUtils.processTemplateIntoString(template, model)
        } catch (ex: IOException) {
            throw PermanentEmailDeliveryException("Failed to load email template $templateName", ex)
        } catch (ex: TemplateException) {
            throw PermanentEmailDeliveryException("Failed to render email template $templateName", ex)
        }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.email.EmailTemplateRendererTests"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/email src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailTemplateRenderer.kt src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailTemplateRendererTests.kt
git commit -m "feat(notifications): add FreeMarker email templates and renderer"
```

---

### Task 5: `SpringMailEmailGateway`

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/SpringMailEmailGateway.kt`
- Test: `src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/SpringMailEmailGatewayTests.kt`

**Interfaces:**
- Consumes: `EmailGateway`, `EmailMessage`, `EmailCategory`, `EmailDeliveryReceipt`,
  `RetryableEmailDeliveryException`, `PermanentEmailDeliveryException` (Task 2);
  `EmailTemplateRenderer` (Task 4); `EmailProperties` (Task 3).
- Produces: `SpringMailEmailGateway(mailSender: JavaMailSender, renderer: EmailTemplateRenderer,
  properties: EmailProperties) : EmailGateway`, consumed by Task 6's `EmailConfiguration`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import freemarker.template.Configuration
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SpringMailEmailGatewayTests {
    private val freemarkerConfig =
        Configuration(Configuration.VERSION_2_3_32).apply {
            setDirectoryForTemplateLoading(File("src/main/resources/templates"))
        }
    private val renderer = EmailTemplateRenderer(freemarkerConfig)
    private val properties = EmailProperties()
    private val message =
        EmailMessage(
            category = EmailCategory.WELCOME,
            recipientEmail = "member@example.test",
            recipientDisplayName = "Ada Lovelace",
            organisationDisplayName = "Acme Bank",
        )

    @Test
    fun `successful send returns a receipt with a message id`() {
        val mailSender = FakeJavaMailSender()
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)

        val receipt = gateway.send(message)

        assertNotNull(receipt.messageId)
    }

    @Test
    fun `authentication failure is permanent`() {
        val mailSender = FakeJavaMailSender(failure = MailAuthenticationException("bad credentials"))
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)

        assertFailsWith<PermanentEmailDeliveryException> { gateway.send(message) }
    }

    @Test
    fun `malformed message failure is permanent`() {
        val mailSender = FakeJavaMailSender(failure = MailParseException("bad address"))
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)

        assertFailsWith<PermanentEmailDeliveryException> { gateway.send(message) }
    }

    @Test
    fun `unclassified send failure is retryable`() {
        val mailSender =
            FakeJavaMailSender(
                failure = MailSendException(mapOf<Any, Exception>(Any() to Exception("timeout"))),
            )
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)

        assertFailsWith<RetryableEmailDeliveryException> { gateway.send(message) }
    }

    @Test
    fun `malformed recipient address during message preparation is permanent`() {
        val mailSender = FakeJavaMailSender()
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)
        val malformedMessage = message.copy(recipientEmail = "not-an-email-@@@")

        assertFailsWith<PermanentEmailDeliveryException> { gateway.send(malformedMessage) }
    }

    private class FakeJavaMailSender(
        private val failure: RuntimeException? = null,
    ) : JavaMailSenderImpl() {
        override fun createMimeMessage(): MimeMessage =
            MimeMessage(jakarta.mail.Session.getDefaultInstance(java.util.Properties()))

        override fun send(mimeMessage: MimeMessage) {
            failure?.let { throw it }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.email.SpringMailEmailGatewayTests"`
Expected: FAIL — unresolved reference `SpringMailEmailGateway`.

- [ ] **Step 3: Write the implementation**

**Verified correction (2026-08-30, after Task 5's implementer flagged it):** the code below was
originally drafted with `catch (ex: MailPreparationException)` around the `MimeMessageHelper`
calls. That is unreachable dead code, confirmed by decompiling `MimeMessageHelper` itself:
`setFrom`/`setTo`/`setSubject`/`setText` declare `throws jakarta.mail.MessagingException` (and the
two-arg `setFrom(String, String)` also declares `throws java.io.UnsupportedEncodingException`) —
`MimeMessageHelper` is a low-level utility that never throws Spring's `MailPreparationException`
(that type is thrown by higher-level `JavaMailSender.send(MimeMessagePreparator)` callback
plumbing, which this code doesn't use). Left as originally drafted, a malformed recipient address
would throw a raw, uncaught `MessagingException` straight out of `send()` — invisible to every
catch clause here — instead of being classified as `PermanentEmailDeliveryException`. The
corrected version below catches the two real declared types and, to keep `send()` within
Detekt's `ThrowsCount` limit (max 2 throws per function — also why the send-phase classification
below is a separate `when`-returning function rather than inline throws), extracts message
preparation into its own private function:

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailPreparationException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import java.io.UnsupportedEncodingException

/**
 * Sends multipart (HTML + plain-text) Finaxis application emails over SMTP via Spring Mail.
 *
 * **Not a `@Component`** — deliberately. `EmailConfiguration` (Task 6) constructs this
 * explicitly inside a `@Bean` method gated on `finaxis.email.enabled=true`. Marking this class
 * `@Component` as well would register a SECOND, always-on, unconditional bean of this type
 * (which implements `EmailGateway`, so `@ConditionalOnMissingBean(EmailGateway::class)` on
 * `disabledEmailGateway()` would see it and back off even when email is disabled) — and, before
 * `spring.mail.host` is configured (Task 15), Spring Boot's mail autoconfiguration doesn't
 * register a `JavaMailSender` bean at all, so an unconditional `@Component` here would fail to
 * construct and break `ApplicationContext` startup for every `@SpringBootTest` in the repo, not
 * just tests exercising email. Confirmed the hard way: this was originally drafted with
 * `@Component` here, and Task 7 — the first task in this plan to boot a full Spring context —
 * hit exactly this failure.
 */
class SpringMailEmailGateway(
    private val mailSender: JavaMailSender,
    private val renderer: EmailTemplateRenderer,
    private val properties: EmailProperties,
) : EmailGateway {
    override fun send(message: EmailMessage): EmailDeliveryReceipt {
        val spec = TEMPLATES.getValue(message.category)
        val model =
            mapOf(
                "recipientDisplayName" to message.recipientDisplayName,
                "organisationDisplayName" to message.organisationDisplayName,
                "appUrl" to properties.appBaseUrl,
            )
        val html = renderer.render(spec.htmlTemplate, model)
        val text = renderer.render(spec.textTemplate, model)
        val mimeMessage = mailSender.createMimeMessage()
        prepareMessage(mimeMessage, spec, message, text, html)
        return try {
            mailSender.send(mimeMessage)
            EmailDeliveryReceipt(mimeMessage.messageID)
        } catch (ex: MailException) {
            throw ex.toDeliveryException()
        }
    }

    /**
     * Populates [mimeMessage] via [MimeMessageHelper], whose setters throw raw
     * `jakarta.mail.MessagingException`/`UnsupportedEncodingException` directly — never a Spring
     * `MailException` subtype — since this utility sits below the `JavaMailSender` abstraction.
     */
    private fun prepareMessage(
        mimeMessage: MimeMessage,
        spec: TemplateSpec,
        message: EmailMessage,
        text: String,
        html: String,
    ) {
        try {
            val helper = MimeMessageHelper(mimeMessage, true, "UTF-8")
            helper.setFrom(properties.fromAddress, properties.fromDisplayName)
            helper.setTo(message.recipientEmail)
            helper.setSubject(spec.subject(message.organisationDisplayName))
            helper.setText(text, html)
        } catch (ex: MessagingException) {
            throw PermanentEmailDeliveryException("Failed to prepare email message", ex)
        } catch (ex: UnsupportedEncodingException) {
            throw PermanentEmailDeliveryException("Failed to prepare email message", ex)
        }
    }

    /**
     * Classifies a Spring Mail send failure into the permanent/retryable distinction JobRunr
     * retry policy depends on, without adding another throw statement to [send].
     */
    private fun MailException.toDeliveryException(): EmailDeliveryException =
        when (this) {
            is MailAuthenticationException -> {
                PermanentEmailDeliveryException("SMTP authentication failed", this)
            }

            is MailParseException -> {
                PermanentEmailDeliveryException("Malformed recipient or message content", this)
            }

            is MailPreparationException -> {
                PermanentEmailDeliveryException("Failed to prepare email message", this)
            }

            is MailSendException -> {
                if (isPermanentAddressFailure()) {
                    PermanentEmailDeliveryException("SMTP rejected recipient permanently", this)
                } else {
                    RetryableEmailDeliveryException("SMTP send failed transiently", this)
                }
            }

            else -> {
                RetryableEmailDeliveryException("Unclassified transient mail failure", this)
            }
        }

    private fun MailSendException.isPermanentAddressFailure(): Boolean =
        failedMessages.values.any { failure -> failure.hasPermanentAddressCause() }

    private fun Throwable.hasPermanentAddressCause(): Boolean =
        generateSequence(this) { it.cause }
            .any {
                it is AddressException ||
                    (it is SendFailedException && it.invalidAddresses?.isNotEmpty() == true)
            }

    private companion object {
        val TEMPLATES =
            mapOf(
                EmailCategory.WELCOME to
                    TemplateSpec(
                        subject = { organisationName -> "Welcome to $organisationName on Finaxis" },
                        htmlTemplate = "email/welcome.ftlh",
                        textTemplate = "email/welcome.txt.ftl",
                    ),
                EmailCategory.ORGANISATION_INVITE to
                    TemplateSpec(
                        subject = { organisationName ->
                            "You've been invited to join $organisationName on Finaxis"
                        },
                        htmlTemplate = "email/organisation-invite.ftlh",
                        textTemplate = "email/organisation-invite.txt.ftl",
                    ),
            )
    }
}

private data class TemplateSpec(
    val subject: (String) -> String,
    val htmlTemplate: String,
    val textTemplate: String,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.email.SpringMailEmailGatewayTests"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/SpringMailEmailGateway.kt src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/SpringMailEmailGatewayTests.kt
git commit -m "feat(notifications): add SpringMailEmailGateway with retry/permanent classification"
```

---

### Task 6: `DisabledEmailGateway`, `MeteredEmailGateway`, and `EmailConfiguration` wiring

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/DisabledEmailGateway.kt`
- Create: `src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/MeteredEmailGateway.kt`
- Create: `src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailConfiguration.kt`
- Test: `src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/DisabledEmailGatewayTests.kt`
- Test: `src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/MeteredEmailGatewayTests.kt`

**Interfaces:**
- Consumes: `EmailGateway`, `EmailMessage`, `EmailCategory`, `EmailDeliveryReceipt`,
  `RetryableEmailDeliveryException`, `PermanentEmailDeliveryException` (Task 2);
  `SpringMailEmailGateway` (Task 5); `EmailProperties` (Task 3).
- Produces: `DisabledEmailGateway : EmailGateway`; `MeteredEmailGateway(delegate: EmailGateway,
  meterRegistry: MeterRegistry) : EmailGateway`, recording `finaxis.email.delivery.attempts.total`
  and `finaxis.email.delivery.duration`; `EmailConfiguration` wiring both behind
  `finaxis.email.enabled`. These are the meter names later tasks' integration tests and
  documentation (Task 15) reference verbatim — do not rename them.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DisabledEmailGatewayTests {
    @Test
    fun `always throws a permanent delivery exception`() {
        val gateway = DisabledEmailGateway()

        assertFailsWith<PermanentEmailDeliveryException> {
            gateway.send(sampleWelcomeMessage())
        }
    }
}
```

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MeteredEmailGatewayTests {
    private val registry = SimpleMeterRegistry()

    @Test
    fun `success records an attempt with outcome success and no pii tag`() {
        val gateway = MeteredEmailGateway({ EmailDeliveryReceipt("id-1") }, registry)

        gateway.send(sampleWelcomeMessage())

        val counter =
            registry.find("finaxis.email.delivery.attempts.total")
                .tags("category", "WELCOME", "outcome", "success")
                .counter()
        assertEquals(1.0, counter?.count())
        assertNull(counter?.id?.getTag("recipientEmail"))
    }

    @Test
    fun `permanent failure records outcome permanent_failure and rethrows`() {
        val delegate =
            EmailGateway { throw PermanentEmailDeliveryException("bad address") }
        val gateway = MeteredEmailGateway(delegate, registry)

        assertFailsWith<PermanentEmailDeliveryException> { gateway.send(sampleWelcomeMessage()) }

        val counter =
            registry.find("finaxis.email.delivery.attempts.total")
                .tags("category", "WELCOME", "outcome", "permanent_failure")
                .counter()
        assertEquals(1.0, counter?.count())
    }

    @Test
    fun `retryable failure records outcome retryable_failure and rethrows`() {
        val delegate =
            EmailGateway { throw RetryableEmailDeliveryException("transient") }
        val gateway = MeteredEmailGateway(delegate, registry)

        assertFailsWith<RetryableEmailDeliveryException> { gateway.send(sampleWelcomeMessage()) }

        val counter =
            registry.find("finaxis.email.delivery.attempts.total")
                .tags("category", "WELCOME", "outcome", "retryable_failure")
                .counter()
        assertEquals(1.0, counter?.count())
    }
}

internal fun sampleWelcomeMessage(): EmailMessage =
    EmailMessage(
        category = EmailCategory.WELCOME,
        recipientEmail = "member@example.test",
        recipientDisplayName = "Ada Lovelace",
        organisationDisplayName = "Acme Bank",
    )
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.email.DisabledEmailGatewayTests" --tests "com.finaxis.platform.notifications.adapter.outbound.email.MeteredEmailGatewayTests"`
Expected: FAIL — unresolved references `DisabledEmailGateway`, `MeteredEmailGateway`.

- [ ] **Step 3: Write the implementations**

```kotlin
// DisabledEmailGateway.kt
package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException

/** No-op gateway active when `finaxis.email.enabled=false`; always fails permanently. */
class DisabledEmailGateway : EmailGateway {
    override fun send(message: EmailMessage): EmailDeliveryReceipt =
        throw PermanentEmailDeliveryException("Email delivery is disabled.")
}
```

```kotlin
// MeteredEmailGateway.kt
package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer

/**
 * Decorates an [EmailGateway] with delivery-attempt count and latency metrics. Tags are limited
 * to the fixed [EmailCategory] name and a fixed outcome value — never a recipient or body value.
 */
class MeteredEmailGateway(
    private val delegate: EmailGateway,
    private val meterRegistry: MeterRegistry,
) : EmailGateway {
    override fun send(message: EmailMessage): EmailDeliveryReceipt {
        val sample = Timer.start(meterRegistry)
        return try {
            val receipt = delegate.send(message)
            record(sample, message.category, OUTCOME_SUCCESS)
            receipt
        } catch (ex: PermanentEmailDeliveryException) {
            record(sample, message.category, OUTCOME_PERMANENT_FAILURE)
            throw ex
        } catch (ex: RetryableEmailDeliveryException) {
            record(sample, message.category, OUTCOME_RETRYABLE_FAILURE)
            throw ex
        }
    }

    private fun record(
        sample: Timer.Sample,
        category: EmailCategory,
        outcome: String,
    ) {
        val tags = Tags.of("category", category.name, "outcome", outcome)
        meterRegistry.counter(ATTEMPTS_METER, tags).increment()
        sample.stop(meterRegistry.timer(DURATION_METER, tags))
    }

    private companion object {
        const val ATTEMPTS_METER = "finaxis.email.delivery.attempts.total"
        const val DURATION_METER = "finaxis.email.delivery.duration"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_PERMANENT_FAILURE = "permanent_failure"
        const val OUTCOME_RETRYABLE_FAILURE = "retryable_failure"
    }
}
```

```kotlin
// EmailConfiguration.kt
package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender

/** Wires the active [EmailGateway] implementation, always wrapped for observability. */
@Configuration
@EnableConfigurationProperties(EmailProperties::class)
class EmailConfiguration {
    @Bean
    @ConditionalOnProperty("finaxis.email.enabled", havingValue = "true")
    fun springMailEmailGateway(
        mailSender: JavaMailSender,
        renderer: EmailTemplateRenderer,
        properties: EmailProperties,
        meterRegistry: MeterRegistry,
    ): EmailGateway = MeteredEmailGateway(SpringMailEmailGateway(mailSender, renderer, properties), meterRegistry)

    @Bean
    @ConditionalOnMissingBean(EmailGateway::class)
    fun disabledEmailGateway(meterRegistry: MeterRegistry): EmailGateway =
        MeteredEmailGateway(DisabledEmailGateway(), meterRegistry)
}
```

**Update `notifications`' module boundary** — this task is the first to need `jooq` (used by Task
7 next) is not yet needed here, but `EmailConfiguration` and its siblings are the first files
requiring nothing beyond what `notifications` already allows, so no `package-info.java` change is
needed in this task.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.email.DisabledEmailGatewayTests" --tests "com.finaxis.platform.notifications.adapter.outbound.email.MeteredEmailGatewayTests"`
Expected: PASS, 4 tests total.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/DisabledEmailGateway.kt src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/MeteredEmailGateway.kt src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/EmailConfiguration.kt src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/DisabledEmailGatewayTests.kt src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/email/MeteredEmailGatewayTests.kt
git commit -m "feat(notifications): wire disabled/metered email gateway and configuration"
```

---

### Task 7: Welcome-email recipient lookup (jOOQ, inside `notifications`)

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/notifications/application/port/outbound/WelcomeEmailRecipientDirectory.kt`
- Create: `src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/persistence/JooqWelcomeEmailRecipientDirectory.kt`
- Modify: `src/main/java/com/finaxis/platform/notifications/package-info.java`
- Test: `src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/persistence/JooqWelcomeEmailRecipientDirectoryTests.kt`

**Interfaces:**
- Produces: `WelcomeEmailRecipient(email: String, displayName: String, organisationDisplayName:
  String)`; `fun interface WelcomeEmailRecipientDirectory { fun findRecipient(userId: UUID,
  organisationId: UUID): WelcomeEmailRecipient? }`. Consumed by Task 8's rewritten
  `SendWelcomeEmailJobRequestHandler`.

`notifications` resolves this itself, via jOOQ, rather than depending on `lifecycle`/`iam` (which
`ModuleDependencyRuleTests.kt` forbids) or adding email/PII fields to the membership-activation
event's metadata (which is persisted **unredacted** into
`user_organisation_membership_transition_log.metadata_jsonb` — verified in
`JooqFoundationLifecyclePersistence.kt`, unlike the audit trail, which redacts via
`SensitiveDataRedactor`).

- [ ] **Step 1: Write the failing test**

This follows the same `@Import(PostgresTestConfiguration::class) @SpringBootTest @TestConstructor
@Transactional` pattern used by `JooqUserProvisioningStoreTests.kt` — find that file first and
confirm the exact `PostgresTestConfiguration` import path and `@Transactional` rollback behavior
before writing this test, then mirror it exactly.

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import org.jooq.DSLContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqWelcomeEmailRecipientDirectoryTests(
    private val dsl: DSLContext,
) {
    private val directory = JooqWelcomeEmailRecipientDirectory(dsl)

    @Test
    fun `resolves recipient email display name and organisation display name`() {
        val organisationId = seedOrganisation("Acme Bank")
        val userId = seedUser("ada@example.test", "Ada Lovelace")

        val recipient = directory.findRecipient(userId, organisationId)

        assertEquals("ada@example.test", recipient?.email)
        assertEquals("Ada Lovelace", recipient?.displayName)
        assertEquals("Acme Bank", recipient?.organisationDisplayName)
    }

    @Test
    fun `returns null when the user does not exist`() {
        val organisationId = seedOrganisation("Acme Bank")

        assertNull(directory.findRecipient(uuidV7(), organisationId))
    }

    private fun seedOrganisation(displayName: String) =
        uuidV7().also { id ->
            dsl.insertInto(ORGANISATION)
                .set(ORGANISATION.ID, id)
                .set(ORGANISATION.TENANT_CODE, "tc-${id.toString().take(8)}")
                .set(ORGANISATION.DISPLAY_NAME, displayName)
                .set(ORGANISATION.COUNTRY_CODE, "KE")
                .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
                .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
                .set(ORGANISATION.STATUS, "ACTIVE")
                .set(ORGANISATION.CREATED_AT, OffsetDateTime.now())
                .set(ORGANISATION.UPDATED_AT, OffsetDateTime.now())
                .execute()
        }

    private fun seedUser(
        email: String,
        displayName: String,
    ) = uuidV7().also { id ->
        dsl.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, email.substringBefore("@"))
            .set(USER_ACCOUNT.EMAIL, email)
            .set(USER_ACCOUNT.DISPLAY_NAME, displayName)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, OffsetDateTime.now())
            .set(USER_ACCOUNT.UPDATED_AT, OffsetDateTime.now())
            .execute()
    }
}
```

Before running, confirm the seeded columns above against
`src/main/resources/db/migration/V1__foundation_schema.sql`'s actual `NOT NULL` columns for
`organisation`/`user_account` (some tables have more required columns than shown in this plan's
summary) and add any missing `.set(...)` calls the insert needs to satisfy `NOT NULL` constraints.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.persistence.JooqWelcomeEmailRecipientDirectoryTests"`
Expected: FAIL — unresolved reference `JooqWelcomeEmailRecipientDirectory`.

- [ ] **Step 3: Write the port and adapter**

```kotlin
// WelcomeEmailRecipientDirectory.kt
package com.finaxis.platform.notifications.application.port.outbound

import java.util.UUID

/** Recipient and organisation context resolved at welcome-email send time. */
data class WelcomeEmailRecipient(
    val email: String,
    val displayName: String,
    val organisationDisplayName: String,
)

/** Internal, notifications-only lookup resolving welcome-email context at send time. */
fun interface WelcomeEmailRecipientDirectory {
    fun findRecipient(
        userId: UUID,
        organisationId: UUID,
    ): WelcomeEmailRecipient?
}
```

```kotlin
// JooqWelcomeEmailRecipientDirectory.kt
package com.finaxis.platform.notifications.adapter.outbound.persistence

import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipient
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipientDirectory
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/** Resolves welcome-email recipient context directly, without depending on lifecycle/iam. */
@Component
class JooqWelcomeEmailRecipientDirectory(
    private val dsl: DSLContext,
) : WelcomeEmailRecipientDirectory {
    override fun findRecipient(
        userId: UUID,
        organisationId: UUID,
    ): WelcomeEmailRecipient? {
        val user =
            dsl.select(USER_ACCOUNT.EMAIL, USER_ACCOUNT.DISPLAY_NAME)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(userId))
                .fetchOne() ?: return null
        val organisationDisplayName =
            dsl.select(ORGANISATION.DISPLAY_NAME)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchOne(ORGANISATION.DISPLAY_NAME) ?: return null
        return WelcomeEmailRecipient(
            email = user.value1(),
            displayName = user.value2(),
            organisationDisplayName = organisationDisplayName,
        )
    }
}
```

Update `src/main/java/com/finaxis/platform/notifications/package-info.java` to allow `jooq`
(already an explicitly open Modulith module for outbound persistence adapters, per
`src/main/java/com/finaxis/platform/jooq/package-info.java`):

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Notifications",
    allowedDependencies = {"common::audit", "common::persistence", "common::transitions", "jooq"})
package com.finaxis.platform.notifications;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.persistence.JooqWelcomeEmailRecipientDirectoryTests"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/notifications/application/port/outbound/WelcomeEmailRecipientDirectory.kt src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/persistence/JooqWelcomeEmailRecipientDirectory.kt src/main/java/com/finaxis/platform/notifications/package-info.java src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/persistence/JooqWelcomeEmailRecipientDirectoryTests.kt
git commit -m "feat(notifications): resolve welcome-email recipient via jOOQ lookup"
```

---

### Task 8: Rewrite `SendWelcomeEmailJobRequestHandler`

**Files:**
- Modify: `src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/jobrunr/SendWelcomeEmailJobRequestHandler.kt`
- Modify: `src/main/java/com/finaxis/platform/notifications/package-info.java`
- Modify (rewrite): `src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/jobrunr/SendWelcomeEmailJobRequestHandlerTests.kt`

**Interfaces:**
- Consumes: `WelcomeEmailRecipientDirectory`, `WelcomeEmailRecipient` (Task 7); `EmailGateway`,
  `EmailMessage`, `EmailCategory`, `RetryableEmailDeliveryException`,
  `PermanentEmailDeliveryException` (Task 2); `JobStepGuard` (Task 1); existing `AuditService`,
  `AuditOutcome`, `SystemActor`, `toAuditFailureReason()`.
- Produces: `SendWelcomeEmailJobRequestHandler(recipientDirectory: WelcomeEmailRecipientDirectory,
  emailGateway: EmailGateway, jobStepGuard: JobStepGuard, auditService: AuditService)` — the
  constructor shape later integration tests (Task 13) rely on Spring to autowire, not to
  construct directly.

- [ ] **Step 1: Write the failing test (full rewrite)**

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.jobs.JobStepGuard
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipient
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipientDirectory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import org.jobrunr.JobRunrException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SendWelcomeEmailJobRequestHandlerTests {
    private val userId = uuidV7()
    private val organisationId = uuidV7()
    private val clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC)
    private val audits = CapturingAudits()
    private val auditService = AuditService(audits, clock)
    private val recipientDirectory =
        WelcomeEmailRecipientDirectory { _, _ ->
            WelcomeEmailRecipient("member@example.test", "Ada Lovelace", "Acme Bank")
        }

    private fun handler(
        gateway: EmailGateway,
        stepGuard: JobStepGuard = InMemoryJobStepGuard(),
    ) = SendWelcomeEmailJobRequestHandler(recipientDirectory, gateway, stepGuard, auditService)

    private fun request() = SendWelcomeEmailJobRequest(uuidV7(), userId, organisationId)

    @Test
    fun `sends the welcome email and audits success`() {
        val sentMessages = mutableListOf<EmailMessage>()
        val gateway =
            EmailGateway { message ->
                sentMessages.add(message)
                EmailDeliveryReceipt("msg-1")
            }

        handler(gateway).run(request())

        assertEquals(1, sentMessages.size)
        assertEquals("member@example.test", sentMessages.single().recipientEmail)
        val audit = audits.items.single()
        assertEquals("user.welcome_email", audit.action)
        assertEquals(AuditOutcome.SUCCESS, audit.outcome)
        assertEquals(SystemActor.ID.toString(), audit.actorId)
    }

    @Test
    fun `permanent failure audits failure and throws a do-not-retry JobRunrException`() {
        val gateway = EmailGateway { throw PermanentEmailDeliveryException("bad address") }

        val exception = assertFailsWith<JobRunrException> { handler(gateway).run(request()) }

        assertEquals(true, exception.isProblematicAndDoNotRetry())
        assertEquals(AuditOutcome.FAILURE, audits.items.single().outcome)
    }

    @Test
    fun `retryable failure audits failure and rethrows the original exception`() {
        val gateway = EmailGateway { throw RetryableEmailDeliveryException("timeout") }

        assertFailsWith<RetryableEmailDeliveryException> { handler(gateway).run(request()) }

        assertEquals(AuditOutcome.FAILURE, audits.items.single().outcome)
    }

    @Test
    fun `missing recipient audits failure and throws a do-not-retry JobRunrException`() {
        val emptyDirectory = WelcomeEmailRecipientDirectory { _, _ -> null }
        val handler =
            SendWelcomeEmailJobRequestHandler(
                emptyDirectory,
                EmailGateway { error("should not be called") },
                InMemoryJobStepGuard(),
                auditService,
            )

        val exception = assertFailsWith<JobRunrException> { handler.run(request()) }

        assertEquals(true, exception.isProblematicAndDoNotRetry())
        assertEquals(AuditOutcome.FAILURE, audits.items.single().outcome)
    }

    @Test
    fun `retrying an already-completed step does not send a second email`() {
        val sentMessages = mutableListOf<EmailMessage>()
        val gateway =
            EmailGateway { message ->
                sentMessages.add(message)
                EmailDeliveryReceipt("msg-1")
            }
        val stepGuard = InMemoryJobStepGuard()

        handler(gateway, stepGuard).run(request())
        handler(gateway, stepGuard).run(request())

        assertEquals(1, sentMessages.size)
    }
}

private class InMemoryJobStepGuard : JobStepGuard {
    private val completedSteps = mutableSetOf<String>()

    override fun runOnce(
        step: String,
        action: () -> Unit,
    ) {
        if (completedSteps.add(step)) action()
    }
}

private class CapturingAudits : AuditEventRepository {
    val items = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        items.add(event)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.jobrunr.SendWelcomeEmailJobRequestHandlerTests"`
Expected: FAIL — constructor signature mismatch (current handler takes only `AuditService`).

- [ ] **Step 3: Rewrite the handler**

```kotlin
package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.toAuditFailureReason
import com.finaxis.platform.common.jobs.JobStepGuard
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipientDirectory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import org.jobrunr.JobRunrException
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.stereotype.Component

**Verified correction (2026-08-30):** uses `checkNotNull`, not `requireNotNull`. Kotlin's
`requireNotNull` throws `IllegalArgumentException`; `checkNotNull` throws `IllegalStateException`.
An earlier draft used `requireNotNull` while the catch clause below expects
`IllegalStateException` — that mismatch would have made "recipient not found" silently
uncaught, escaping as a raw `IllegalArgumentException` with no audit record and no
`doNotRetry` classification (JobRunr would retry it forever). Confirmed via bytecode
disassembly during Task 8's implementation.

/** Sends the real welcome email after a membership activation, at most once per JobRunr job. */
@Component
class SendWelcomeEmailJobRequestHandler(
    private val recipientDirectory: WelcomeEmailRecipientDirectory,
    private val emailGateway: EmailGateway,
    private val jobStepGuard: JobStepGuard,
    private val auditService: AuditService,
) : JobRequestHandler<SendWelcomeEmailJobRequest> {
    override fun run(jobRequest: SendWelcomeEmailJobRequest) {
        try {
            val recipient =
                checkNotNull(
                    recipientDirectory.findRecipient(jobRequest.userId, jobRequest.organisationId),
                ) { "Welcome email recipient not found for user ${jobRequest.userId}" }
            jobStepGuard.runOnce(SEND_STEP) {
                emailGateway.send(
                    EmailMessage(
                        category = EmailCategory.WELCOME,
                        recipientEmail = recipient.email,
                        recipientDisplayName = recipient.displayName,
                        organisationDisplayName = recipient.organisationDisplayName,
                    ),
                )
            }
            auditService.recordExternalDispatch(
                actorId = SystemActor.ID,
                tenantId = jobRequest.organisationId,
                action = WELCOME_EMAIL_ACTION,
                outcome = AuditOutcome.SUCCESS,
                externalSystemRef = WELCOME_EMAIL,
                resourceType = USER_RESOURCE,
                resourceId = jobRequest.userId.toString(),
            )
        } catch (ex: IllegalStateException) {
            recordFailureAndFailPermanently(jobRequest, ex)
        } catch (ex: PermanentEmailDeliveryException) {
            recordFailureAndFailPermanently(jobRequest, ex)
        } catch (ex: RetryableEmailDeliveryException) {
            recordFailureAndRethrow(jobRequest, ex)
        }
    }

    private fun recordFailureAndFailPermanently(
        jobRequest: SendWelcomeEmailJobRequest,
        cause: Throwable,
    ): Nothing {
        auditFailure(jobRequest, cause)
        throw JobRunrException(cause.toAuditFailureReason(), true, cause)
    }

    private fun recordFailureAndRethrow(
        jobRequest: SendWelcomeEmailJobRequest,
        cause: RetryableEmailDeliveryException,
    ): Nothing {
        auditFailure(jobRequest, cause)
        throw cause
    }

    private fun auditFailure(
        jobRequest: SendWelcomeEmailJobRequest,
        cause: Throwable,
    ) {
        auditService.recordExternalDispatch(
            actorId = SystemActor.ID,
            tenantId = jobRequest.organisationId,
            action = WELCOME_EMAIL_ACTION,
            outcome = AuditOutcome.FAILURE,
            externalSystemRef = WELCOME_EMAIL,
            resourceType = USER_RESOURCE,
            resourceId = jobRequest.userId.toString(),
            reason = cause.toAuditFailureReason(),
        )
    }

    private companion object {
        const val SEND_STEP = "send-welcome-email"
        const val WELCOME_EMAIL = "WELCOME_EMAIL"
        const val WELCOME_EMAIL_ACTION = "user.welcome_email"
        const val USER_RESOURCE = "USER"
    }
}
```

Update `src/main/java/com/finaxis/platform/notifications/package-info.java` to also allow
`common::jobs`:

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Notifications",
    allowedDependencies = {
      "common::audit", "common::jobs", "common::persistence", "common::transitions", "jooq"
    })
package com.finaxis.platform.notifications;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.adapter.outbound.jobrunr.SendWelcomeEmailJobRequestHandlerTests"`
Expected: PASS, 5 tests (a 5th case covering the `checkNotNull` recipient-not-found path is
required, since that's the exact path the requireNotNull→checkNotNull correction above affects).

- [ ] **Step 5: Run the full notifications module test suite to check for regressions**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.*"`
Expected: `NotificationServiceTests.kt`/`JobRunrWelcomeEmailSchedulerTests.kt` PASS (they reference
the handler's old constructor shape only indirectly, through Spring wiring, not direct
construction) — investigate either if they fail; do not skip it.

**`MembershipActivationPipelineIntegrationTests` is EXPECTED to fail here** — this is not a
regression to fix in this task. Before this task, the stub handler always "succeeded" (it only
logged). Now that the real handler is wired in, `finaxis.email.enabled` still defaults to `false`
(Task 3), so `DisabledEmailGateway` is the active gateway, which always throws
`PermanentEmailDeliveryException` — the handler correctly classifies this as permanent and the
job now legitimately ends `FAILED` where it used to end `SUCCEEDED`. Task 13 is the task that
fixes this test, by adding `"finaxis.email.enabled=true"` to its `@SpringBootTest(properties =
[...])` and asserting real delivery via GreenMail. Confirm the failure is exactly
`AssertionFailedError: expected: <SUCCEEDED> but was: <FAILED>` on this one test, and that no
other test in the module suite fails — if any other test fails, or this one fails differently
(a compile error, an exception other than the expected permanent-failure path, a hang), treat
that as a real regression and investigate.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/notifications/adapter/outbound/jobrunr/SendWelcomeEmailJobRequestHandler.kt src/main/java/com/finaxis/platform/notifications/package-info.java src/test/kotlin/com/finaxis/platform/notifications/adapter/outbound/jobrunr/SendWelcomeEmailJobRequestHandlerTests.kt
git commit -m "feat(notifications): send real welcome emails with retry/permanent classification"
```

---

### Task 9: `organisationDisplayName()` on `UserProvisioningAccountStore`

**Files:**
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/port/outbound/UserProvisioningStore.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqUserProvisioningStore.kt`
- Test: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqUserProvisioningStoreTests.kt` (add a case to the existing file — find it first)

**Interfaces:**
- Produces: `UserProvisioningAccountStore.organisationDisplayName(organisationId: UUID): String? =
  null` (default method — no existing implementor needs to change except the real one). Consumed
  by Task 11's `ApplicationInviteJobRequestHandler`.

This task is independently mergeable — it touches only `lifecycle`, adds no email dependency, and
can land before or after Tasks 1–8.

- [ ] **Step 1: Write the failing test**

Open `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqUserProvisioningStoreTests.kt`
first to find its exact seeding helpers (likely similar to Task 7's) and constructor pattern for
`JooqUserProvisioningStore`, then add:

```kotlin
@Test
fun `organisationDisplayName resolves the organisation's display name`() {
    val organisationId = seedOrganisation("Acme Bank") // reuse this file's existing seeding helper

    val displayName = store.organisationDisplayName(organisationId)

    assertEquals("Acme Bank", displayName)
}

@Test
fun `organisationDisplayName returns null for an unknown organisation`() {
    assertNull(store.organisationDisplayName(uuidV7()))
}
```

(Adjust helper/fixture names to match whatever this existing test file already uses — do not
invent a second seeding helper if one already exists.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.adapter.outbound.persistence.JooqUserProvisioningStoreTests"`
Expected: FAIL — unresolved reference `organisationDisplayName`.

- [ ] **Step 3: Add the default method and jOOQ override**

In `UserProvisioningStore.kt`, inside `interface UserProvisioningAccountStore`:

```kotlin
    /** Resolves the organisation's display name for outbound communications. */
    fun organisationDisplayName(organisationId: UUID): String? = null
```

In `JooqUserProvisioningStore.kt`, alongside the existing `organisationState` override:

```kotlin
    override fun organisationDisplayName(organisationId: UUID): String? =
        dsl
            .select(ORGANISATION.DISPLAY_NAME)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne(ORGANISATION.DISPLAY_NAME)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.adapter.outbound.persistence.JooqUserProvisioningStoreTests"`
Expected: PASS, including the two new cases.

- [ ] **Step 5: Run the full lifecycle test suite to check for regressions**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.*"`
Expected: PASS. The default method means `ApplicationInviteStoreFake` in
`ApplicationInviteHandlerTests.kt` and the fake in `KeycloakUserProvisioningHandlerTests.kt` keep
compiling unchanged (they don't override it, so they inherit `null` — Task 11 will change
`ApplicationInviteStoreFake` deliberately to return a real value).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/lifecycle/application/port/outbound/UserProvisioningStore.kt src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqUserProvisioningStore.kt src/test/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqUserProvisioningStoreTests.kt
git commit -m "feat(lifecycle): resolve organisation display name for outbound communications"
```

---

### Task 10: Open the `lifecycle` → `notifications::email` module boundary

**Files:**
- Modify: `src/main/java/com/finaxis/platform/lifecycle/package-info.java`

**Interfaces:**
- Consumes: `notifications::email` named interface (Task 2), `common::jobs` named interface
  (Task 1).
- Produces: nothing new — this task only opens the dependency edge Task 11 will use.

- [ ] **Step 1: Update the module declaration**

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Lifecycle",
    allowedDependencies = {
      "common::application", "common::audit", "common::context", "common::id",
      "common::jobs", "common::persistence", "common::transitions",
      "common::web-api", "common::web-idempotency",
      "notifications::email",
      "jooq"
    })
package com.finaxis.platform.lifecycle;
```

- [ ] **Step 2: Verify the new module edge is accepted with no cycle**

Find the project's Modulith verification test (likely `ModulithVerificationTests.kt` or similar —
search for `ApplicationModules.of(...).verify()`) and the ArchUnit tests referenced in earlier
research (`ModuleDependencyRuleTests.kt`, `HexagonalArchitectureTest.kt`).

Run: `./gradlew test --tests "*Modulith*" --tests "*ArchitectureTest*" --tests "*ModuleDependencyRuleTests*"`
Expected: PASS. If Modulith verification fails with a cycle or an undeclared dependency, the
`notifications::email` named interface from Task 2 is misconfigured — re-check
`package-info.java` under `notifications/application/port/outbound/email/` before touching
anything else.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/finaxis/platform/lifecycle/package-info.java
git commit -m "chore(lifecycle): allow dependency on notifications::email and common::jobs"
```

---

### Task 11: Wire `EmailGateway`/`JobStepGuard` into `ApplicationInviteJobRequestHandler`

**Files:**
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/ApplicationInviteJobRequestHandler.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/lifecycle/application/ApplicationInviteHandlerTests.kt`

**Interfaces:**
- Consumes: `EmailGateway`, `EmailMessage`, `EmailCategory`, `RetryableEmailDeliveryException`,
  `PermanentEmailDeliveryException` (Task 2); `JobStepGuard` (Task 1);
  `organisationDisplayName()` (Task 9); existing `UserProvisioningStore.membershipSnapshot()`
  (already present, returns `MembershipProvisioningSnapshot.displayName`).
- Produces: `ApplicationInviteJobRequestHandler(store: UserProvisioningStore, emailGateway:
  EmailGateway, jobStepGuard: JobStepGuard, dispatchOutcomeAuditor: DispatchOutcomeAuditor)` — new
  constructor shape.

- [ ] **Step 1: Write the failing test (extend the existing file)**

Modify `ApplicationInviteStoreFake` in `ApplicationInviteHandlerTests.kt`: change
`membershipSnapshot` to return a real snapshot, and override `organisationDisplayName`:

```kotlin
    var snapshot: MembershipProvisioningSnapshot? =
        MembershipProvisioningSnapshot(
            id = uuidV7(),
            userId = uuidV7(),
            status = MembershipLifecycleState.PENDING_APPROVAL,
            type = MembershipType.STAFF, // MembershipType has no STANDARD value — real values
            // are STAFF, ADMIN, AUDITOR, SYSTEM (com.finaxis.platform.lifecycle.application
            // .OrganisationBranchProvisioningCommands.kt); STAFF matches this repo's existing
            // test convention for an ordinary member (see JooqUserProvisioningStoreTests.kt)
            email = "member@example.test",
            username = "member",
            displayName = "Ada Lovelace",
            userStatus = UserLifecycleState.INVITED,
            sendKeycloakInvite = true,
            sendApplicationInvite = true,
        )
    var organisationName: String? = "Acme Bank"

    override fun membershipSnapshot(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot? = snapshot

    override fun organisationDisplayName(organisationId: UUID): String? = organisationName
```

Then add new test cases to `ApplicationInviteHandlerTests`:

```kotlin
    @Test
    fun `application invite sends the email before marking dispatch succeeded`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val sentMessages = mutableListOf<EmailMessage>()
        val gateway =
            EmailGateway { message ->
                sentMessages.add(message)
                EmailDeliveryReceipt("msg-1")
            }
        val handler =
            ApplicationInviteJobRequestHandler(
                store,
                gateway,
                InMemoryJobStepGuard(),
                DispatchOutcomeAuditor(store, auditService),
            )

        handler.run(applicationInviteRequest(dispatchKey))

        assertEquals(1, sentMessages.size)
        assertEquals(EmailCategory.ORGANISATION_INVITE, sentMessages.single().category)
        assertEquals("SUCCEEDED", store.dispatches.getValue(dispatchKey).status)
    }

    @Test
    fun `permanent email failure marks dispatch failed and throws a do-not-retry exception`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val gateway = EmailGateway { throw PermanentEmailDeliveryException("bad address") }
        val handler =
            ApplicationInviteJobRequestHandler(
                store,
                gateway,
                InMemoryJobStepGuard(),
                DispatchOutcomeAuditor(store, auditService),
            )

        val exception = assertFailsWith<JobRunrException> { handler.run(applicationInviteRequest(dispatchKey)) }

        assertEquals(true, exception.isProblematicAndDoNotRetry())
        assertEquals("FAILED", store.dispatches.getValue(dispatchKey).status)
    }

    @Test
    fun `retryable email failure marks dispatch failed and rethrows`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val gateway = EmailGateway { throw RetryableEmailDeliveryException("timeout") }
        val handler =
            ApplicationInviteJobRequestHandler(
                store,
                gateway,
                InMemoryJobStepGuard(),
                DispatchOutcomeAuditor(store, auditService),
            )

        assertFailsWith<RetryableEmailDeliveryException> { handler.run(applicationInviteRequest(dispatchKey)) }

        assertEquals("FAILED", store.dispatches.getValue(dispatchKey).status)
    }

    @Test
    fun `retrying an already-completed step does not send a second email`() {
        val dispatchKey = "user-1:org-1:APPLICATION_INVITE"
        store.dispatches[dispatchKey] = InviteDispatchState("PENDING")
        val sentMessages = mutableListOf<EmailMessage>()
        val gateway =
            EmailGateway { message ->
                sentMessages.add(message)
                EmailDeliveryReceipt("msg-1")
            }
        val stepGuard = InMemoryJobStepGuard()
        // Simulate a retry after a later failure by re-invoking run() against the same guard,
        // without resetting dispatch status to PENDING in between.
        val handler =
            ApplicationInviteJobRequestHandler(store, gateway, stepGuard, DispatchOutcomeAuditor(store, auditService))
        store.failure = IllegalStateException("boom")
        assertFailsWith<IllegalStateException> { handler.run(applicationInviteRequest(dispatchKey)) }
        store.failure = null

        handler.run(applicationInviteRequest(dispatchKey))

        assertEquals(1, sentMessages.size)
    }
```

Add the required imports (`EmailCategory`, `EmailDeliveryReceipt`, `EmailGateway`, `EmailMessage`,
`PermanentEmailDeliveryException`, `RetryableEmailDeliveryException` from
`com.finaxis.platform.notifications.application.port.outbound.email`; `JobStepGuard` from
`com.finaxis.platform.common.jobs`; `org.jobrunr.JobRunrException`) and this local fake near the
bottom of the file (mirrors Task 8's):

```kotlin
private class InMemoryJobStepGuard : com.finaxis.platform.common.jobs.JobStepGuard {
    private val completedSteps = mutableSetOf<String>()

    override fun runOnce(
        step: String,
        action: () -> Unit,
    ) {
        if (completedSteps.add(step)) action()
    }
}
```

Update the existing `handler` field construction (used by the two pre-existing tests) to the new
4-argument constructor, passing a fresh `EmailGateway { EmailDeliveryReceipt("msg-1") }` and
`InMemoryJobStepGuard()` — the two pre-existing tests (`marks dispatch succeeded`,
`skips duplicate work`, `dispatch failure...`) don't assert on email content, so a trivially
succeeding fake gateway keeps them passing unchanged.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.application.ApplicationInviteHandlerTests"`
Expected: FAIL — constructor signature mismatch.

- [ ] **Step 3: Update the handler**

```kotlin
package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.toAuditFailureReason
import com.finaxis.platform.common.jobs.JobStepGuard
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import org.jobrunr.JobRunrException
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

**Verified correction (2026-08-30, applying the same fix Task 8 needed):** uses `checkNotNull`,
not `requireNotNull` — `requireNotNull` throws `IllegalArgumentException`, which the
`catch (ex: IllegalStateException)` branch below would not catch, silently escaping with no
audit and no retry classification. `checkNotNull` throws `IllegalStateException`, matching the
existing catch clause and preserving the intended retryable-by-default behavior for a
missing membership/organisation.

/** Sends the real organisation-invite email, at most once per JobRunr job. */
@Component
class ApplicationInviteJobRequestHandler(
    private val store: UserProvisioningStore,
    private val emailGateway: EmailGateway,
    private val jobStepGuard: JobStepGuard,
    private val dispatchOutcomeAuditor: DispatchOutcomeAuditor,
) : JobRequestHandler<ApplicationInviteJobRequest> {
    override fun run(jobRequest: ApplicationInviteJobRequest) {
        if (store.dispatchStatus(jobRequest.dispatchKey) == SUCCEEDED) return
        try {
            val snapshot =
                checkNotNull(
                    store.membershipSnapshot(jobRequest.organisationId, jobRequest.membershipId),
                ) { "Membership not found for application invite." }
            val organisationName =
                checkNotNull(store.organisationDisplayName(jobRequest.organisationId)) {
                    "Organisation not found for application invite."
                }
            jobStepGuard.runOnce("send-application-invite-email") {
                emailGateway.send(
                    EmailMessage(
                        category = EmailCategory.ORGANISATION_INVITE,
                        recipientEmail = jobRequest.email,
                        recipientDisplayName = snapshot.displayName,
                        organisationDisplayName = organisationName,
                    ),
                )
            }
            dispatchOutcomeAuditor.recordSuccess(
                dispatchKey = jobRequest.dispatchKey,
                dispatchRef = APPLICATION_INVITE,
                externalSystemRef = APPLICATION_INVITE,
                actorId = SystemActor.ID,
                tenantId = jobRequest.organisationId,
                action = APPLICATION_INVITE_ACTION,
                resourceId = jobRequest.userId.toString(),
                metadata = mapOf("dispatchKey" to jobRequest.dispatchKey),
            )
        } catch (ex: IllegalStateException) {
            recordFailure(jobRequest, ex)
        } catch (ex: DataAccessException) {
            recordFailure(jobRequest, ex)
        } catch (ex: PermanentEmailDeliveryException) {
            recordFailureAndFailPermanently(jobRequest, ex)
        } catch (ex: RetryableEmailDeliveryException) {
            recordFailure(jobRequest, ex)
        }
    }

    private fun recordFailure(
        jobRequest: ApplicationInviteJobRequest,
        ex: RuntimeException,
    ): Nothing {
        auditFailure(jobRequest, ex)
        throw ex
    }

    private fun recordFailureAndFailPermanently(
        jobRequest: ApplicationInviteJobRequest,
        ex: RuntimeException,
    ): Nothing {
        auditFailure(jobRequest, ex)
        throw JobRunrException(ex.toAuditFailureReason(), true, ex)
    }

    private fun auditFailure(
        jobRequest: ApplicationInviteJobRequest,
        ex: RuntimeException,
    ) {
        dispatchOutcomeAuditor.recordFailure(
            dispatchKey = jobRequest.dispatchKey,
            externalSystemRef = APPLICATION_INVITE,
            actorId = SystemActor.ID,
            tenantId = jobRequest.organisationId,
            action = APPLICATION_INVITE_ACTION,
            resourceId = jobRequest.userId.toString(),
            reason = ex.toAuditFailureReason(),
            detail = ex.message ?: ex.javaClass.name,
            metadata = mapOf("dispatchKey" to jobRequest.dispatchKey),
        )
    }

    private companion object {
        const val SUCCEEDED = "SUCCEEDED"
        const val APPLICATION_INVITE = "APPLICATION_INVITE"
        const val APPLICATION_INVITE_ACTION = "user.application_invite"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.application.ApplicationInviteHandlerTests"`
Expected: PASS, all cases (original 3 + 4 new = 7).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/lifecycle/application/ApplicationInviteJobRequestHandler.kt src/test/kotlin/com/finaxis/platform/lifecycle/application/ApplicationInviteHandlerTests.kt
git commit -m "feat(lifecycle): send real organisation-invite emails with retry/permanent classification"
```

---

### Task 12: GreenMail via Testcontainers for hermetic SMTP integration tests

**Files:**
- Modify: `src/test/kotlin/com/finaxis/platform/TestContainerImages.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/TestcontainersConfiguration.kt`

**Interfaces:**
- Produces: `TestContainerImages.GREENMAIL`; `TestcontainersConfiguration.GREENMAIL_CONTAINER`,
  consumed by Tasks 13 and 14's tests via their own `@DynamicPropertySource`.

**Verified correction (2026-08-30, during implementation):** the original draft below (a
`greenMailContainer(): GenericContainer<*>` bean plus a `greenMailProperties(...):
DynamicPropertyRegistrar` bean) does not work. `MailSenderAutoConfiguration`'s
`@ConditionalOnProperty("spring.mail.host")` is evaluated during
`invokeBeanFactoryPostProcessors()`, but a bean-based `DynamicPropertyRegistrar` only runs later,
inside `finishBeanFactoryInitialization()` — too late for the condition to see the property. The
actual implementation instead exposes a static, eagerly-started singleton container,
`TestcontainersConfiguration.Companion.GREENMAIL_CONTAINER`, and each consuming test class
declares its own `@JvmStatic @DynamicPropertySource` method reading `spring.mail.host`/`port` from
that field directly (Spring only scans a test class and its enclosing classes for
`@DynamicPropertySource`, never `@Import`-ed configuration classes). See
`MembershipActivationPipelineIntegrationTests.kt` for the pattern Task 14 must copy.

- [ ] **Step 1: Add the pinned image constant**

```kotlin
// TestContainerImages.kt — add alongside the existing constants
    const val GREENMAIL = "greenmail/standalone:2.1.13"
```

- [ ] **Step 2: Add the singleton container**

```kotlin
// TestcontainersConfiguration.kt — inside a `companion object`
        val GREENMAIL_CONTAINER: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(TestContainerImages.GREENMAIL))
                .withExposedPorts(3025, 3143)
                .withEnv(
                    "GREENMAIL_ADDITIONAL_OPTS",
                    "-Dgreenmail.auth.disabled -Dgreenmail.verbose",
                ).apply { start() }
```

- [ ] **Step 3: Verify the module compiles and existing integration tests still pass**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.MembershipActivationPipelineIntegrationTests"`
Expected: PASS unchanged — this test doesn't yet set `finaxis.email.enabled=true`, so
`DisabledEmailGateway` is still active and GreenMail, while now started, isn't yet exercised.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/finaxis/platform/TestContainerImages.kt src/test/kotlin/com/finaxis/platform/TestcontainersConfiguration.kt
git commit -m "fix(test): use a singleton GreenMail container instead of a bean-based registrar"
```

---

### Task 13: Extend `MembershipActivationPipelineIntegrationTests` to assert real welcome-email delivery

**Files:**
- Modify: `src/test/kotlin/com/finaxis/platform/notifications/MembershipActivationPipelineIntegrationTests.kt`

**Interfaces:**
- Consumes: `TestcontainersConfiguration.GREENMAIL_CONTAINER` (Task 12, corrected); `EmailConfiguration`'s
  `finaxis.email.enabled` flag (Task 6).

**Verified correction (2026-08-30):** Step 2 below (constructor-injecting a `GenericContainer<*>`
bean) is stale — Task 12's corrected implementation has no such bean. The actual implementation
reads `TestcontainersConfiguration.GREENMAIL_CONTAINER` directly and declares its own
`@JvmStatic @DynamicPropertySource` method for `spring.mail.host`/`port`, plus a
`purgeWelcomeEmailMailbox()` step before triggering activation — the seeded `admin@finaxis.local`
mailbox is shared bootstrap data, not per-test data, so a prior test run in the same JVM would
otherwise leave stale messages and make the `assertEquals(1, ...)` below flaky.

- [ ] **Step 1: Read the existing test file in full**

Before editing, read
`src/test/kotlin/com/finaxis/platform/notifications/MembershipActivationPipelineIntegrationTests.kt`
completely to find its exact `@SpringBootTest(properties = [...])` array, its existing assertion
on `StateName.SUCCEEDED`, and how it injects the seeded local-admin's user ID/email (from the
bootstrap data seeded by `V3__bootstrap_tenant_and_administrator.sql`) — reuse whatever it already
exposes rather than re-deriving it.

- [ ] **Step 2: Write the failing assertion**

Add `"finaxis.email.enabled=true"` to the existing `@SpringBootTest(properties = [...])` array.
Inject the `GenericContainer<*>` bean (constructor parameter, matching this file's existing
`@TestConstructor(autowireMode = ALL)` convention) under a distinguishing name if needed — check
whether Spring disambiguates multiple `GenericContainer<*>` beans (Redis vs GreenMail) by
parameter name; if it can't autowire ambiguously, add `@Qualifier("greenMailContainer")` to the
bean method in Task 12 and to this constructor parameter.

After the existing `SUCCEEDED` assertion, add:

```kotlin
    @Test
    fun `activation delivers exactly one welcome email via GreenMail`() {
        // ... existing pipeline-trigger code from this test's current SUCCEEDED-asserting test,
        // reused or extended in place rather than duplicated into a new @Test ...

        val session = Session.getInstance(java.util.Properties())
        val store = session.getStore("imap")
        store.connect(greenMailContainer.host, greenMailContainer.getMappedPort(3143), recipientEmail, "test")
        val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
        try {
            assertEquals(1, inbox.messageCount)
            val subject = inbox.getMessage(1).subject
            assertTrue(subject.contains("Welcome to"))
        } finally {
            inbox.close(false)
            store.close()
        }
    }
```

Add imports: `jakarta.mail.Folder`, `jakarta.mail.Session`. Replace `recipientEmail` with however
this test file already knows the seeded user's email (e.g. a constant from the bootstrap fixtures,
or a value returned by whatever setup this test already performs) — do not hardcode a guessed
address; read it from the existing test's own fixtures.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.MembershipActivationPipelineIntegrationTests"`
Expected: FAIL initially if `finaxis.email.enabled=true` wasn't set before this edit (email
disabled → `DisabledEmailGateway` → `PermanentEmailDeliveryException` → job `FAILED` instead of
`SUCCEEDED`), then re-run after adding the property — expect it to fail on the new IMAP assertion
specifically (0 messages) until the flag is actually wired through, which confirms the test is
exercising the real path rather than trivially passing.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.notifications.MembershipActivationPipelineIntegrationTests"`
Expected: PASS, including the existing `SUCCEEDED` assertion and the new GreenMail assertion.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/finaxis/platform/notifications/MembershipActivationPipelineIntegrationTests.kt
git commit -m "test(notifications): assert real welcome-email delivery via GreenMail"
```

---

### Task 14: New `ApplicationInvitePipelineIntegrationTests` (full pipeline + idempotency)

**Files:**
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/ApplicationInvitePipelineIntegrationTests.kt`

**Interfaces:**
- Consumes: `TestcontainersConfiguration.GREENMAIL_CONTAINER` (Task 12, corrected) via this
  class's own `@JvmStatic @DynamicPropertySource` method — copy
  `MembershipActivationPipelineIntegrationTests.kt`'s pattern exactly, not the stale
  `@Qualifier("greenMailContainer")` bean-injection shown further below (that bean no longer
  exists); the full existing invite pipeline (`UserProvisioningService` →
  `ExternalizedTransitionEvent` → outbox → RabbitMQ → `IdentityProvisioningListener` →
  `ApplicationInviteJobRequestHandler` → `EmailGateway`).

- [ ] **Step 1: Find the existing invite-triggering entry point**

Read `UserProvisioningService.kt`'s public invite/approve methods (e.g. `inviteUser`,
`approveUser` — confirm actual names) and any existing integration test that already exercises
the Keycloak-provisioning half of this same pipeline (likely
`KeycloakProvisioningPipelineIntegrationTests.kt` or similar — search for one) to copy its
Spring context setup, Testcontainers imports, and Awaitility polling style exactly.

- [ ] **Step 2: Write the full-pipeline test**

```kotlin
package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import jakarta.mail.Folder
import jakarta.mail.Session
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.testcontainers.containers.GenericContainer
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
class ApplicationInvitePipelineIntegrationTests(
    @Qualifier("greenMailContainer") private val greenMailContainer: GenericContainer<*>,
    // add whatever this repo's real invite-triggering service/store dependencies are, following
    // the pattern found in Step 1's reference integration test
) {
    @Test
    fun `inviting a user delivers exactly one organisation-invite email`() {
        val invitedEmail = "invitee-${System.nanoTime()}@example.test"
        // ... call the real invite-triggering flow found in Step 1 with invitedEmail ...

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertEquals(1, fetchInboxMessageCount(invitedEmail))
        }
        val subject = fetchFirstSubject(invitedEmail)
        assertTrue(subject.contains("invited to join"))
    }

    @Test
    fun `redelivering the same invite event sends exactly one email`() {
        val invitedEmail = "invitee-redeliver-${System.nanoTime()}@example.test"
        // ... trigger the invite flow once, then republish the identical event payload to
        //     the "finaxis.lifecycle.user-provisioning-events" queue a second time (via the
        //     same RabbitTemplate/AMQP test helper this repo's other pipeline tests already use)

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertEquals(1, fetchInboxMessageCount(invitedEmail))
        }
    }

    @Test
    fun `a job retry after a later failure does not send a second email`() {
        // This is the actual gap Task 1/11's JobStepGuard closes: force a failure in
        // DispatchOutcomeAuditor.recordSuccess AFTER the email has already been sent inside the
        // same job run (e.g. via a test-only AuditEventRepository that throws exactly once on
        // its first invocation), so JobRunr retries the same job. On the retry, the job must
        // succeed without invoking EmailGateway.send() a second time.
        val invitedEmail = "invitee-retry-${System.nanoTime()}@example.test"
        // ... trigger the flow with the throw-once audit repository wired in ...

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertEquals(1, fetchInboxMessageCount(invitedEmail))
        }
    }

    private fun fetchInboxMessageCount(recipientEmail: String): Int {
        val session = Session.getInstance(java.util.Properties())
        val store = session.getStore("imap")
        store.connect(greenMailContainer.host, greenMailContainer.getMappedPort(3143), recipientEmail, "test")
        return try {
            val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            inbox.messageCount.also { inbox.close(false) }
        } finally {
            store.close()
        }
    }

    private fun fetchFirstSubject(recipientEmail: String): String {
        val session = Session.getInstance(java.util.Properties())
        val store = session.getStore("imap")
        store.connect(greenMailContainer.host, greenMailContainer.getMappedPort(3143), recipientEmail, "test")
        return try {
            val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            inbox.getMessage(1).subject.also { inbox.close(false) }
        } finally {
            store.close()
        }
    }
}
```

The third test's "throw exactly once" audit repository needs a small test-only wrapper bean —
follow whichever pattern the reference test found in Step 1 uses for injecting test doubles into
a `@SpringBootTest` context (e.g. a `@TestConfiguration` nested class with `@Primary`).

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.ApplicationInvitePipelineIntegrationTests"`
Expected: FAIL initially (compile errors until Step 1's real method names are filled in; then
logical failures until the flow is wired correctly).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.ApplicationInvitePipelineIntegrationTests"`
Expected: PASS, all 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/finaxis/platform/lifecycle/ApplicationInvitePipelineIntegrationTests.kt
git commit -m "test(lifecycle): cover full invite pipeline and email-send idempotency via GreenMail"
```

---

### Task 15: Configuration — `application*.yaml` and local Mailpit catcher

**Files:**
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/resources/application-production.yaml`
- Modify: `src/main/resources/application-local.yaml`
- Modify: `compose.yaml`

**Interfaces:**
- Consumes: `EmailProperties` field names (Task 3): `enabled`, `from-address`,
  `from-display-name`, `app-base-url`.

- [ ] **Step 1: Add SMTP and Finaxis email config to `application.yaml`**

```yaml
spring:
  mail:
    host: ${FINAXIS_SMTP_HOST:localhost}
    port: ${FINAXIS_SMTP_PORT:1025}
    username: ${FINAXIS_SMTP_USERNAME:}
    password: ${FINAXIS_SMTP_PASSWORD:}
    properties:
      mail:
        smtp:
          auth: ${FINAXIS_SMTP_AUTH:false}
          starttls:
            enable: ${FINAXIS_SMTP_STARTTLS:false}
          connectiontimeout: 5000
          timeout: 10000
          writetimeout: 10000

finaxis:
  email:
    enabled: ${FINAXIS_EMAIL_ENABLED:false}
    from-address: ${FINAXIS_EMAIL_FROM_ADDRESS:no-reply@finaxis.local}
    from-display-name: ${FINAXIS_EMAIL_FROM_DISPLAY_NAME:Finaxis}
    app-base-url: ${FINAXIS_EMAIL_APP_BASE_URL:http://localhost:5173}
```

- [ ] **Step 2: Add fail-fast production config**

In `application-production.yaml` (no defaults, matching the existing
`FINAXIS_ACTIVE_ORGANISATION_CONTEXT_SECRET`-style convention — find that exact line first to
match formatting):

```yaml
spring:
  mail:
    host: ${FINAXIS_SMTP_HOST}
    port: ${FINAXIS_SMTP_PORT}
    username: ${FINAXIS_SMTP_USERNAME}
    password: ${FINAXIS_SMTP_PASSWORD}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true

finaxis:
  email:
    enabled: true
    from-address: ${FINAXIS_EMAIL_FROM_ADDRESS}
    app-base-url: ${FINAXIS_EMAIL_APP_BASE_URL}
```

- [ ] **Step 3: Point local dev at a Mailpit catcher**

In `application-local.yaml`, add (or leave `application.yaml`'s defaults as-is if they already
match Mailpit's default SMTP port):

```yaml
finaxis:
  email:
    enabled: ${FINAXIS_EMAIL_ENABLED:false}
```

In `compose.yaml`, add (matching the existing `keycloak`/`grafana-lgtm` service style — check
their exact indentation/label conventions first):

```yaml
  mailpit:
    image: 'axllent/mailpit:v1.27.5'
    ports:
      - '1025:1025'
      - '8025:8025'
    labels:
      org.springframework.boot.ignore: true
```

Verify `axllent/mailpit:v1.27.5` is a real, current tag before committing:
`curl -s https://hub.docker.com/v2/repositories/axllent/mailpit/tags/v1.27.5` should return a
200 with tag metadata, not a 404 — if it 404s, list current tags and pick the latest stable one
instead of guessing.

- [ ] **Step 4: Verify the application context still starts**

Run: `./gradlew test --tests "com.finaxis.platform.PlatformApplicationTests"` (or whatever this
repo's context-load smoke test is named — search for a class annotated
`@SpringBootTest` with no other qualifiers if the name differs)
Expected: PASS — confirms the new `spring.mail.*`/`finaxis.email.*` properties don't break context
startup with `EmailProperties`' validation `init {}` block.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yaml src/main/resources/application-production.yaml src/main/resources/application-local.yaml compose.yaml
git commit -m "feat: configure SMTP transport and local Mailpit email catcher"
```

---

### Task 16: Documentation

**Files:**
- Create: `docs/adr/0016-email-delivery-transport-and-retry-classification.md`
- Create: `docs/architecture/email-delivery.md`
- Modify: `docs/adr/0004-membership-activation-notification-pipeline.md`
- Modify: `docs/security/user-provisioning-keycloak.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Write ADR 0016**

Follow this repo's existing ADR format (open `docs/adr/0004-...md` and copy its section
headings — Status/Context/Decision/Consequences/Alternatives-considered, or whatever headings it
actually uses). Content must cover: `EmailGateway` owned by `notifications`; Spring
Mail/`JavaMailSender` + FreeMarker multipart adapter; `RetryableEmailDeliveryException`/
`PermanentEmailDeliveryException` classification and the `JobRunrException(reason, true, cause)`
mechanism used to enforce "do not retry"; `common::jobs`/`JobStepGuard` as the chosen per-job
idempotency primitive (JobRunr's own `JobContext.runStepOnce`, persisted on the job record) over a
new database table; GreenMail-via-Testcontainers as the hermetic SMTP test strategy (no Spring
Boot `@ServiceConnection` factory exists for generic SMTP, hence the `DynamicPropertyRegistrar`
bean); the new `lifecycle → notifications::email` dependency direction and why it introduces no
cycle (`notifications` never imports `lifecycle`/`iam`).

- [ ] **Step 2: Write `docs/architecture/email-delivery.md`**

Cover: a short port/adapter diagram in prose (`EmailGateway` port → `SpringMailEmailGateway`/
`DisabledEmailGateway` → `MeteredEmailGateway` decorator); the template file map (`welcome.ftlh`/
`welcome.txt.ftl`/`organisation-invite.ftlh`/`organisation-invite.txt.ftl`); the `EmailProperties`
reference table; the Micrometer meter table:

| Meter | Type | Tags |
|---|---|---|
| `finaxis.email.delivery.attempts.total` | Counter | `category`, `outcome` |
| `finaxis.email.delivery.duration` | Timer | `category`, `outcome` |

and an explicit note that retry volume is derived as
`sum(attempts.total) - count(attempts.total{outcome="success"})` per category rather than tracked
by a dedicated counter, since the gateway layer has no visibility into JobRunr's own retry
attempt number. Document local dev setup (Mailpit at `http://localhost:8025`) and restate the
non-goals from issue #32 (no marketing subsystem; Keycloak's own required-action/reset emails are
entirely separate and out of scope here).

- [ ] **Step 3: Update ADR 0004**

Remove or rewrite the line stating the notification handler intentionally does not integrate with
an email provider yet. Add the new test files from Tasks 8 and 13 to its Verification/testing
section.

- [ ] **Step 4: Update `docs/security/user-provisioning-keycloak.md`**

Find the section describing dispatch failure handling ("Failures mark the dispatch FAILED...and
rethrow so JobRunr...can retry"). Add a paragraph noting that, as of this change, a *permanent*
email-delivery failure short-circuits that retry via `JobRunrException(doNotRetry=true)` instead
of rethrowing plainly, and link to the new `docs/architecture/email-delivery.md`.

- [ ] **Step 5: Update `CLAUDE.md`**

Remove the "Known follow-ups" bullet stating `notifications` email delivery is stubbed
(logs only) — search for that exact phrase and delete it, since it's now false.

- [ ] **Step 6: Commit**

```bash
git add docs/adr/0016-email-delivery-transport-and-retry-classification.md docs/architecture/email-delivery.md docs/adr/0004-membership-activation-notification-pipeline.md docs/security/user-provisioning-keycloak.md CLAUDE.md
git commit -m "docs: document email delivery transport, retry classification, and testing strategy"
```

---

### Task 17: Full quality gate

**Files:** none new — this task fixes whatever the gate finds.

- [ ] **Step 1: Run the full gate**

Run: `./gradlew qualityGate`
Expected: initially likely to surface Detekt findings (missing KDoc on a new public
class/function — check every file created in Tasks 1–14) and possibly ktlint/Spotless formatting
diffs.

- [ ] **Step 2: Fix Spotless/ktlint formatting**

Run: `./gradlew spotlessApply`
Expected: reformats any non-conforming file in place.

- [ ] **Step 3: Fix any Detekt findings**

For each finding, add the missing KDoc or adjust the offending construct (e.g. replace a broad
catch with a specific type) — do not add a suppression unless a fix is genuinely impossible, and
if you do, keep it narrow and explain why in a comment next to it, per this repo's static-analysis
policy.

- [ ] **Step 4: Re-run the full gate**

Run: `./gradlew qualityGate`
Expected: PASS — staticAnalysis, `check` (all unit + integration tests, including every test
written in Tasks 1–14), JaCoCo verification (scoped to `iam`, unaffected by this feature), and
`bootJar`.

- [ ] **Step 5: Final commit (if Steps 2–3 produced changes)**

```bash
git add -A
git commit -m "chore: satisfy qualityGate for email delivery feature"
```

---

## Self-review notes (completed during authoring)

- **Spec coverage:** every acceptance-criterion bullet from issues #32 and #33 maps to a task
  above — SMTP adapter (Task 5), typed config (Task 3), retry/permanent classification (Tasks 5, 8,
  11), no-PII observability (Task 6), thin listeners/JobRunr preserved (no listener code changed),
  no synchronous SMTP in provisioning transactions (email send happens only inside JobRunr
  handlers, never inside `UserProvisioningService`'s transactional methods), hermetic SMTP test
  (Tasks 12–14), local/dev + production config docs (Tasks 15–16), invite/welcome emails (Tasks 8,
  11), idempotent retries (Tasks 1, 8, 11, 14), audit without secrets/bodies (Tasks 8, 11 reuse
  `toAuditFailureReason()`), full event→outbox→listener→job→gateway integration coverage (Tasks
  13–14), zero migrations (stated explicitly in Global Constraints and nowhere contradicted).
- **Type consistency verified:** `EmailGateway.send(EmailMessage): EmailDeliveryReceipt` signature
  is identical across Tasks 2, 5, 6, 8, 11; `JobStepGuard.runOnce(step: String, action: () ->
  Unit)` identical across Tasks 1, 8, 11; `WelcomeEmailRecipientDirectory.findRecipient(userId,
  organisationId): WelcomeEmailRecipient?` identical across Tasks 7 and 8.
- **Known follow-ups intentionally left out of scope** (call these out in the PR description,
  don't silently drop them): extending `doNotRetry` classification to
  `IllegalStateException`/`DataAccessException` branches or to
  `KeycloakUserProvisioningJobRequestHandler`; a dedicated retry counter (vs. the derived metric);
  SMTP-reply-code-aware permanent/retryable splitting via the concrete Jakarta Mail provider
  artifact (`org.eclipse.angus:angus-mail`, expected transitively — confirm at Task 5 if finer
  classification is wanted later).
