package com.finaxis.platform.foundation

import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION_VIEW_REQUIREMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The one test helper that applies ADR 0030 to a fixture: a mutation code is only usable together
 * with every view code the catalogue pairs with it (`permission_view_requirement`), so a fixture
 * that grants one mutation code adds its views through this file instead of listing them by hand.
 * The requirement is read from the migrated catalogue, never copied, so a fixture cannot drift
 * from the rule the guard enforces.
 */
internal object ViewCoupledGrants {
    /** The view codes the catalogue pairs with [permissionCode], sorted; empty for a view. */
    fun requiredViews(
        dsl: DSLContext,
        permissionCode: String,
    ): List<String> {
        val mutation = PERMISSION.`as`("mutation")
        val view = PERMISSION.`as`("required_view")
        return dsl
            .select(view.PERMISSION_CODE)
            .from(PERMISSION_VIEW_REQUIREMENT)
            .join(mutation)
            .on(mutation.ID.eq(PERMISSION_VIEW_REQUIREMENT.PERMISSION_ID))
            .join(view)
            .on(view.ID.eq(PERMISSION_VIEW_REQUIREMENT.REQUIRED_VIEW_PERMISSION_ID))
            .where(mutation.PERMISSION_CODE.eq(permissionCode))
            .orderBy(view.PERMISSION_CODE)
            .fetch(view.PERMISSION_CODE)
            .filterNotNull()
    }

    /** [permissionCodes] followed by every view they require, without duplicates. */
    fun withRequiredViews(
        dsl: DSLContext,
        vararg permissionCodes: String,
    ): Set<String> =
        permissionCodes.flatMapTo(linkedSetOf()) { code ->
            listOf(code) + requiredViews(dsl, code)
        }

    /**
     * Grants [permissionCode] and every view it requires straight to [actorId]'s membership in
     * [organisationId], as an `ALLOW` override, as a tenant administrator would. Idempotent.
     */
    fun grantDirectly(
        dsl: DSLContext,
        organisationId: UUID,
        actorId: UUID,
        permissionCode: String,
    ) {
        val membershipId =
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.ID)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(actorId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID)
                ?: error("no membership for $actorId in $organisationId")
        val now = OffsetDateTime.now()
        withRequiredViews(dsl, permissionCode).forEach { code ->
            val permissionId =
                dsl
                    .select(PERMISSION.ID)
                    .from(PERMISSION)
                    .where(PERMISSION.PERMISSION_CODE.eq(code))
                    .fetchOne(PERMISSION.ID)
                    ?: error("permission $code is not seeded")
            dsl
                .insertInto(MEMBERSHIP_PERMISSION)
                .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, organisationId)
                .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, membershipId)
                .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId)
                .set(MEMBERSHIP_PERMISSION.EFFECT, "ALLOW")
                .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
                .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
                .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute()
        }
    }
}
