package com.finaxis.platform.config

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals

/** Verifies local smoke platform seeding against the Flyway-managed PostgreSQL schema. */
@ActiveProfiles("local")
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class LocalPlatformSmokeMembershipSeederIntegrationTests(
    private val dsl: DSLContext,
    private val seeder: LocalPlatformSmokeMembershipSeeder,
) {
    /** Removes the local-only seed so each test exercises the seeder from the absent state. */
    @BeforeEach
    fun removeLocalPlatformSmokeSeed() {
        dsl
            .deleteFrom(USER_ROLE_ASSIGNMENT)
            .where(USER_ROLE_ASSIGNMENT.ID.eq(LOCAL_PLATFORM_ROLE_ASSIGNMENT_ID))
            .execute()
        dsl
            .deleteFrom(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(LOCAL_MEMBERSHIP_ID))
            .execute()
    }

    /** Seeds the fixed membership and role exactly once when invoked repeatedly. */
    @Test
    fun `seeds the local platform membership and role idempotently`() {
        seeder.run(DefaultApplicationArguments(*emptyArray<String>()))
        seeder.run(DefaultApplicationArguments(*emptyArray<String>()))

        assertEquals(1, platformMembershipCount())
        assertEquals(1, platformSuperAdministratorRoleCount())
    }

    private fun platformMembershipCount(): Int =
        dsl.fetchCount(
            USER_ORGANISATION_MEMBERSHIP,
            USER_ORGANISATION_MEMBERSHIP.ID
                .eq(LOCAL_MEMBERSHIP_ID)
                .and(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(PLATFORM_ORGANISATION_ID))
                .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(LOCAL_ADMIN_USER_ID)),
        )

    private fun platformSuperAdministratorRoleCount(): Int =
        dsl.fetchCount(
            USER_ROLE_ASSIGNMENT,
            USER_ROLE_ASSIGNMENT.ID
                .eq(LOCAL_PLATFORM_ROLE_ASSIGNMENT_ID)
                .and(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(PLATFORM_ORGANISATION_ID))
                .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(LOCAL_ADMIN_USER_ID))
                .and(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(PLATFORM_SUPER_ADMIN_ROLE_ID))
                .and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq(TENANT))
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE)),
        )

    private companion object {
        const val LOCAL_PROFILE = "local"
        const val ACTIVE = "ACTIVE"
        const val TENANT = "TENANT"

        val PLATFORM_ORGANISATION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
        val LOCAL_ADMIN_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val LOCAL_MEMBERSHIP_ID: UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
        val PLATFORM_SUPER_ADMIN_ROLE_ID: UUID =
            UUID.fromString("50000000-0000-0000-0000-000000000001")
        val LOCAL_PLATFORM_ROLE_ASSIGNMENT_ID: UUID =
            UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    }
}
