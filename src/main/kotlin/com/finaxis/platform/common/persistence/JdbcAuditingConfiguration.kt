package com.finaxis.platform.common.persistence

import com.finaxis.platform.common.context.RequestContexts
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing
import java.time.Clock
import java.time.temporal.TemporalAccessor
import java.util.Optional
import java.util.UUID

/** Stable actor used when a scheduled worker, migration, or test has no authenticated user. */
object SystemActor {
    /** Canonical non-user actor id used by runtime audit callbacks. */
    val ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private val bootstrapId: UUID = UUID(0L, 0L)
    private val systemIds = setOf(ID, bootstrapId)

    /** Returns true when [actorId] is one of the platform system-actor sentinels. */
    fun isSystemActor(actorId: UUID?): Boolean = actorId != null && systemIds.contains(actorId)
}

/** Resolves the authenticated actor, falling back to the explicit system actor. */
class ContextAuditorAware : AuditorAware<UUID> {
    override fun getCurrentAuditor(): Optional<UUID> =
        Optional.of(RequestContexts.actor()?.userId ?: SystemActor.ID)
}

/** Enables Spring Data JDBC audit callbacks with UTC timestamps and contextual actors. */
@Configuration
@EnableJdbcAuditing(
    auditorAwareRef = "contextAuditorAware",
    dateTimeProviderRef = "utcDateTimeProvider",
)
class JdbcAuditingConfiguration {
    /** Creates the auditor resolver used by Spring Data JDBC callbacks. */
    @Bean
    fun contextAuditorAware(): AuditorAware<UUID> = ContextAuditorAware()

    /** Creates the UTC timestamp provider used by Spring Data JDBC callbacks. */
    @Bean
    fun utcDateTimeProvider(clock: Clock): DateTimeProvider =
        DateTimeProvider { Optional.of<TemporalAccessor>(clock.instant()) }
}
