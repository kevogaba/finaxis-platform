package com.finaxis.platform.config

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.util.function.Supplier
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies that the local platform smoke seeder is available only under the local profile. */
class LocalPlatformSmokeMembershipSeederConfigurationTests {
    private val contextRunner =
        ApplicationContextRunner()
            .withBean(Clock::class.java, Supplier { Clock.systemUTC() })
            .withBean(
                DSLContext::class.java,
                Supplier { org.mockito.Mockito.mock(DSLContext::class.java) },
            ).withUserConfiguration(LocalPlatformSmokeMembershipSeeder::class.java)

    /** Keeps the local platform privilege seeder out of a profileless application context. */
    @Test
    fun `does not register the seeder when the local profile is inactive`() {
        contextRunner.run { context ->
            assertFalse(context.containsBean(SEEDER_BEAN_NAME))
        }
    }

    /** Excludes the seeder when production is active even if local is also named. */
    @Test
    fun `does not register the seeder when production is active`() {
        contextRunner
            .withInitializer { context ->
                context.environment.setActiveProfiles(LOCAL_PROFILE, PRODUCTION_PROFILE)
            }.run { context ->
                assertFalse(context.containsBean(SEEDER_BEAN_NAME))
            }
    }

    /** Registers the seeder only when local development is explicitly activated. */
    @Test
    fun `registers the seeder when the local profile is active`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles(LOCAL_PROFILE) }
            .run { context ->
                assertTrue(context.containsBean(SEEDER_BEAN_NAME))
            }
    }

    private companion object {
        const val LOCAL_PROFILE = "local"
        const val PRODUCTION_PROFILE = "production"
        const val SEEDER_BEAN_NAME = "localPlatformSmokeMembershipSeeder"
    }
}
