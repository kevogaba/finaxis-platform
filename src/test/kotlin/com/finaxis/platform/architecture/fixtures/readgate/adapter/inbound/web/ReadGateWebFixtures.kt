package com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web

import com.finaxis.platform.architecture.fixtures.readgate.application.FixtureBalanceFinder
import com.finaxis.platform.architecture.fixtures.readgate.application.FixtureEngine
import com.finaxis.platform.architecture.fixtures.readgate.application.FixtureGateway
import com.finaxis.platform.architecture.fixtures.readgate.application.UnguardedFixtureService
import com.finaxis.platform.architecture.fixtures.readgate.application.UnmarkedApplicationFixtureService
import com.finaxis.platform.architecture.fixtures.readgate.application.port.FixtureThingStore
import com.finaxis.platform.architecture.fixtures.readgate.application.query.GatedFixtureQueryService
import com.finaxis.platform.architecture.fixtures.readgate.application.query.UngatedFixtureQueryService
import com.finaxis.platform.architecture.fixtures.readgate.domain.FixtureDomainPort
import com.finaxis.platform.architecture.fixtures.readgate.other.FixtureEventReader
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapService
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.UserInvitationResult
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import java.util.UUID

// Fixture web adapters for PermissionFreeReadRuleTests. Not Spring beans.

/** The compliant adapter: reads only through a gated method, in a GET handler too. */
class CompliantFixtureController(
    private val service: GatedFixtureQueryService,
) {
    /** A gated read. */
    @GetMapping
    fun read(caller: TenantCaller): String = service.getThing(caller)
}

/** Reads through a query method that carries no marker. */
class UngatedReadFixtureController(
    private val service: UngatedFixtureQueryService,
) {
    /** The violation: an unmarked query method. */
    fun read(caller: FoundationCaller): String = service.getThing(caller)
}

/** Takes a reference to an unmarked query method instead of calling it. */
class UngatedReferenceFixtureController(
    private val service: UngatedFixtureQueryService,
) {
    /** The violation: a method reference is an access too. */
    fun reference(): (FoundationCaller) -> String = service::getThing
}

/** A GET handler that reads through an application service with no marker. */
class GetReadsUnmarkedFixtureController(
    private val service: UnmarkedApplicationFixtureService,
) {
    /** The violation: not a query service by name, still an application read. */
    @GetMapping
    fun read(): String = helper()

    private fun helper(): String = service.peek()
}

/** Reaches a store port directly. */
class StoreReachingFixtureController(
    private val store: FixtureThingStore,
) {
    /** The violation: no query service, no guard. */
    fun read(): String? = store.find()
}

/** Reaches a read port that no package or naming convention of the first rule version caught. */
class ReaderReachingFixtureController(
    private val reader: FixtureEventReader,
) {
    /** The violation: a read port outside `application.port`. */
    fun read(): String? = reader.next()
}

/** Calls the system-run invitation entry point. */
class SystemInviteFixtureCaller(
    private val service: UserProvisioningService,
) {
    /** The violation: the entry point that asks no permission. */
    fun invite(command: InviteUserCommand) = service.inviteAsSystem(command)
}

/** Takes a reference to the system-run invitation entry point. */
class SystemInviteReferenceFixtureCaller(
    private val service: UserProvisioningService,
) {
    /** The violation: a reference reaches the same entry point. */
    fun reference(): (InviteUserCommand) -> UserInvitationResult = service::inviteAsSystem
}

/** Names the system actor. */
class SystemActorFixtureController {
    /** The violation: the actor every guard waves through. */
    fun act(): UUID = SystemActor.ID
}

/** Builds a caller by hand. */
class CallerBuildingFixtureController {
    /** The violation: a caller not built from the authenticated request. */
    fun build(): FoundationCaller = TenantCaller(UUID(0L, 1L), UUID(0L, 2L))
}

/** A GET declared through `@RequestMapping(method = GET)`. */
class GetViaRequestMappingFixtureController(
    private val service: UnmarkedApplicationFixtureService,
) {
    /** The violation: not a `@GetMapping`, still a GET. */
    @RequestMapping(method = [RequestMethod.GET])
    fun read(): String = service.peek()
}

/** A GET handler that hands back a reference to an unmarked read. */
class GetViaReferenceFixtureController(
    private val service: UnmarkedApplicationFixtureService,
) {
    /** The violation: the read happens inside the reference. */
    @GetMapping
    fun read(): () -> String = service::peek
}

/** Copies a caller. */
class CallerCopyingFixtureController {
    /** The violation: `copy` builds a caller with another actor. */
    fun copy(caller: TenantCaller): TenantCaller = caller.copy(actorId = UUID(0L, 3L))
}

/** Depends on a concrete bean that is no service. */
class BeanReachingFixtureController(
    private val finder: FixtureBalanceFinder,
) {
    /** The violation: a `@Component` read with no check. */
    fun read(): String = finder.find()
}

/** Depends on an interface named like neither a port nor a service. */
class InterfaceReachingFixtureController(
    private val gateway: FixtureGateway,
) {
    /** The violation: any interface in the application packages. */
    fun read(): String? = gateway.fetch()
}

/** Starts the bootstrap directly. */
class BootstrapStartingFixtureController(
    private val bootstrap: InitialAdministratorBootstrapService,
) {
    /** The violation: `bootstrap` checks no permission. */
    fun start(organisationId: UUID) = bootstrap.bootstrap(organisationId)
}

/** A GET declared by a bare `@RequestMapping`, which answers every verb including GET. */
class GetViaBareRequestMappingFixtureController(
    private val service: UnmarkedApplicationFixtureService,
) {
    /** The violation: no `method` on the mapping or on the class. */
    @RequestMapping("/bare")
    fun read(): String = service.peek()
}

/** Depends on a `*Service` that has no guard and no marked method. */
class UnguardedServiceReachingFixtureController(
    private val service: UnguardedFixtureService,
) {
    /** The violation: the name says service, nothing can authorise. */
    fun read(): String = service.peek()
}

/** Depends on a type a `@Bean` method builds. */
class EngineReachingFixtureController(
    private val engine: FixtureEngine,
) {
    /** The violation: a bean with no stereotype. */
    fun read(): String = engine.run()
}

/** Depends on an interface that lives in a domain package. */
class DomainPortReachingFixtureController(
    private val port: FixtureDomainPort,
) {
    /** The violation: a domain interface is as much a read port as any other. */
    fun read(): String? = port.organisationState()
}
