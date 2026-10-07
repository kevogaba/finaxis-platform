package com.finaxis.platform.architecture.fixtures.readgate.application.query

import com.finaxis.platform.architecture.fixtures.readgate.application.port.FixtureThingStore
import com.finaxis.platform.architecture.fixtures.readgate.guard.FixtureAreaPermissionGuard
import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PermissionGuard
import java.util.UUID

// Fixtures for PermissionFreeReadRuleTests. They are deliberately shaped like the real query
// services (a package under `application.query`, a name ending in `Service`) so the rules, which
// select by package and name, treat them exactly as they treat production code. Nothing here is
// a Spring bean and nothing is reachable from production code.

private val ANY_ORGANISATION: UUID = UUID(0L, 7L)

/** A correct gated query service: every shape the rule must accept. */
class GatedFixtureQueryService(
    private val guard: PermissionGuard,
) {
    /** Calls the guard directly. */
    @GatedRead
    fun getThing(caller: FoundationCaller): String {
        guard.requireTenantPermission(caller.actorId, ANY_ORGANISATION, "thing.view")
        return "thing"
    }

    /** Reaches the guard through a private helper of its own class. */
    @GatedRead
    fun getThroughHelper(caller: FoundationCaller): String = checkedThing(caller)

    /** Delegates to another gated method. */
    @GatedRead
    fun getByDelegation(caller: FoundationCaller): String = getThing(caller)

    private fun checkedThing(caller: FoundationCaller): String {
        guard.requireTenantPermission(caller.actorId, ANY_ORGANISATION, "thing.view")
        return "thing"
    }
}

/** A read with no marker: a web adapter must not be allowed to call it. */
class UngatedFixtureQueryService {
    /** Takes a caller and checks nothing, and is not marked. */
    fun getThing(caller: FoundationCaller): String = caller.actorId.toString()
}

/** Marked, takes the caller, never asks the guard: the marker would be a lie. */
class LyingFixtureQueryService {
    /** Claims to be gated and reads freely. */
    @GatedRead
    fun getThing(caller: FoundationCaller): String = caller.actorId.toString()
}

/** Marked and checks the guard, but identifies no caller at all. */
class CallerlessFixtureQueryService(
    private val guard: PermissionGuard,
) {
    /** Authorises somebody else's identity, not the caller's. */
    @GatedRead
    fun getThing(): String {
        guard.requirePlatformPermission(ANY_ORGANISATION, "thing.view")
        return "thing"
    }
}

/** Marked, but reads the store before it asks the guard: the check comes too late. */
class LateGuardFixtureQueryService(
    private val store: FixtureThingStore,
    private val guard: PermissionGuard,
) {
    /** Reads, then authorises. */
    @GatedRead
    fun getThing(caller: FoundationCaller): String? {
        val thing = store.find()
        guard.requireTenantPermission(caller.actorId, ANY_ORGANISATION, "thing.view")
        return thing
    }
}

/** Marked, and authorises through an area guard that is not the lifecycle `PermissionGuard`. */
class AreaGuardFixtureQueryService(
    private val guard: FixtureAreaPermissionGuard,
    private val store: FixtureThingStore,
) {
    /** Authorises, then reads. */
    @GatedRead
    fun getThing(caller: FoundationCaller): String? {
        guard.requireTenantPermission(caller.actorId, ANY_ORGANISATION, "thing.view")
        return store.find()
    }
}

/** A marked port: the contract an adapter implements. */
interface GatedFixtureReadService {
    /** A gated read. */
    @GatedRead
    fun getThing(caller: FoundationCaller): String
}

/** An implementation that carries the marker and delegates to a gated method. */
class MarkedFixtureReadAdapter(
    private val delegate: GatedFixtureQueryService,
) : GatedFixtureReadService {
    @GatedRead
    override fun getThing(caller: FoundationCaller): String = delegate.getThing(caller)
}

/** An implementation that dropped the marker, so nothing would check it. */
class UnmarkedFixtureReadAdapter(
    private val delegate: GatedFixtureQueryService,
) : GatedFixtureReadService {
    override fun getThing(caller: FoundationCaller): String = delegate.getThing(caller)
}
