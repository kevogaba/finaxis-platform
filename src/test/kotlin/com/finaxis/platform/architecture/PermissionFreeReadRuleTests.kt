package com.finaxis.platform.architecture

import com.finaxis.platform.accounting.application.reporting.FinancialStatementService
import com.finaxis.platform.accounting.application.reporting.LedgerReportingService
import com.finaxis.platform.architecture.fixtures.helper.ReadBackHelperFixture
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.BeanReachingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.BootstrapStartingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.CallerBuildingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.CallerCopyingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.CompliantFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.DomainPortReachingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.EngineReachingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.GetReadsUnmarkedFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.GetViaBareRequestMappingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.GetViaReferenceFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.GetViaRequestMappingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.InterfaceReachingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.ReaderReachingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.StoreReachingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.SystemActorFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.SystemInviteFixtureCaller
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.SystemInviteReferenceFixtureCaller
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.UngatedReadFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.UngatedReferenceFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.adapter.inbound.web.UnguardedServiceReachingFixtureController
import com.finaxis.platform.architecture.fixtures.readgate.application.FixtureBalanceFinder
import com.finaxis.platform.architecture.fixtures.readgate.application.FixtureEngine
import com.finaxis.platform.architecture.fixtures.readgate.application.FixtureGateway
import com.finaxis.platform.architecture.fixtures.readgate.application.UnguardedFixtureService
import com.finaxis.platform.architecture.fixtures.readgate.application.UnmarkedApplicationFixtureService
import com.finaxis.platform.architecture.fixtures.readgate.application.port.FixtureThingStore
import com.finaxis.platform.architecture.fixtures.readgate.application.query.AreaGuardFixtureQueryService
import com.finaxis.platform.architecture.fixtures.readgate.application.query.CallerlessFixtureQueryService
import com.finaxis.platform.architecture.fixtures.readgate.application.query.GatedFixtureQueryService
import com.finaxis.platform.architecture.fixtures.readgate.application.query.GatedFixtureReadService
import com.finaxis.platform.architecture.fixtures.readgate.application.query.LateGuardFixtureQueryService
import com.finaxis.platform.architecture.fixtures.readgate.application.query.LyingFixtureQueryService
import com.finaxis.platform.architecture.fixtures.readgate.application.query.MarkedFixtureReadAdapter
import com.finaxis.platform.architecture.fixtures.readgate.application.query.UngatedFixtureQueryService
import com.finaxis.platform.architecture.fixtures.readgate.application.query.UnmarkedFixtureReadAdapter
import com.finaxis.platform.architecture.fixtures.readgate.domain.FixtureDomainPort
import com.finaxis.platform.architecture.fixtures.readgate.other.FixtureEventReader
import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapService
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR 0030 decision 6: no web adapter reads without a permission check, and a mutation's result is
 * never read back through a permission-free helper. Each rule (`ReadGateRules`) is proved twice: it
 * passes on the real code, and it fails on a deliberately violating class compiled into the test
 * sources (`architecture.fixtures`), so a rule that has gone vacuous is itself a red build.
 *
 * ## The approach
 *
 * Authorisation lives in the application layer. A *query service* is a class in an
 * `..application.query..` or `..application.reporting..` package whose name ends in `Service`, or
 * a `*QueryService` (`AuditQueryService`). A query
 * method is trusted only when it carries the marker. The marker is a claim; the build verifies it:
 *
 * 1. No method named `get...AfterAuthorizedMutation` exists.
 * 2. A web adapter calls (or takes a method reference to) a query-service method only if every
 *    overload of it is marked. Separately, every GET handler (`@GetMapping`, or `@RequestMapping`
 *    for GET on the method or its class), with the helpers and callable references of its own
 *    class, calls an application-package service or bean only through a marked method or one on the
 *    explicit, commented `ReadGateRules.UNMARKED_GET_ALLOWLIST` (fully qualified; each entry says
 *    where that read authorises). It does not cover a read a non-GET handler makes outside a query
 *    service (a mutation service authorises inside its own method, ADR 0030 decision 4, and the
 *    named-403 integration tests own those routes).
 * 3. A marked method in a query package takes the caller (a `FoundationCaller`), so the check it
 *    makes is the caller's own. Accounting reporting and `AuditQueryService` take an `actorId` in a
 *    query object and are outside this clause only.
 * 4. A marked method with a body asks a guard (any `*PermissionGuard`, or a marked delegate)
 *    **before** its first store call, in line order, following calls inside its own class. A store
 *    call is told apart from other bean calls by a name heuristic (`*Store`, `*Reader`, ...), which
 *    only this ordering check uses. It is structural, not path-complete: a method that checks in
 *    only one branch passes, and the integration suites own that. An abstract method has no body,
 *    so:
 * 5. Every implementation of a marked method carries the marker itself, and is checked. Erased
 *    parameter types are compared, so a generic port method implemented with a concrete type is
 *    skipped; Kotlin `by` delegation fails closed, since it forces explicit overrides.
 * 6. A web adapter depends on a platform type outside `..adapter.inbound.web..`
 *    only if it is a `*Service` that depends on a `*PermissionGuard` or declares a marked method,
 *    or neither a Spring bean (a stereotype, or the return type of a `@Bean` method) nor an
 *    interface (a command, result, DTO, enum; annotations too), or is on the commented
 *    `WEB_DEPENDENCY_ALLOWLIST`. No name list: a store, a reader class, a `@Component`, a `@Bean`
 *    engine, a resolver or a port of any name is refused. A bean registered some other way
 *    (`registerBean`, a factory bean) is not seen.
 * 7. Only `InitialAdministratorBootstrapService` calls or references
 *    `UserProvisioningService.inviteAsSystem`/`approveAsSystem`, and no web adapter depends on the
 *    bootstrap service (it checks no permission).
 * 8. A web adapter never names `SystemActor` (it passes every guard) and neither constructs nor
 *    copies a `TenantCaller`/`PlatformCaller` outside `CallerContextResolver`.
 */
class PermissionFreeReadRuleTests {
    private val production: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(ROOT)

    @Test
    fun `no permission-free read-back helper exists`() {
        ReadGateRules.noReadBackHelper().check(production)
    }

    @Test
    fun `web adapters call only gated query methods`() {
        ReadGateRules.webAdaptersCallOnlyGatedQueryMethods().check(production)
    }

    @Test
    fun `GET handlers call application services only through gated methods`() {
        ReadGateRules.getHandlersCallOnlyGatedApplicationMethods().check(production)
    }

    @Test
    fun `gated query methods take the caller`() {
        ReadGateRules.gatedQueryMethodsTakeTheCaller().check(production)
    }

    @Test
    fun `gated methods authorise before they read`() {
        ReadGateRules.gatedMethodsAuthoriseBeforeReading().check(production)
    }

    @Test
    fun `implementations of gated methods are gated`() {
        ReadGateRules.implementationsOfGatedMethodsAreGated().check(production)
    }

    @Test
    fun `web adapters never reach a query or store port directly`() {
        ReadGateRules.webAdaptersDependOnlyOnServicesAndValues().check(production)
    }

    @Test
    fun `only the bootstrap service starts a system-run invitation or approval`() {
        ReadGateRules.onlyBootstrapRunsAsSystem().check(production)
    }

    @Test
    fun `web adapters never start the bootstrap`() {
        ReadGateRules.webAdaptersNeverStartTheBootstrap().check(production)
    }

    @Test
    fun `web adapters never act as the system actor or build a caller`() {
        ReadGateRules.webAdaptersNeverUseTheSystemActor().check(production)
        ReadGateRules.callersAreBuiltOnlyByTheResolver().check(production)
    }

    @Test
    fun `the rules see the production code they guard`() {
        val queryCalls = ReadGateRules.queryServiceCallsFromWebAdapters(production)
        val gated =
            production
                .flatMap { it.methods }
                .filter { it.isAnnotatedWith(GatedRead::class.java) }
        val systemRunCalls =
            production
                .flatMap { it.accessesFromSelf }
                .filter { ReadGateRules.isSystemRunEntryPoint(it) }
                .map { it.originOwner.name }
                .toSet()
        val accountingMarked =
            production
                .filter {
                    it.isEquivalentTo(LedgerReportingService::class.java) ||
                        it.isEquivalentTo(FinancialStatementService::class.java)
                }.flatMap { it.methods }
                .filter { it.isAnnotatedWith(GatedRead::class.java) }

        assertTrue(queryCalls.size >= MIN_WEB_QUERY_CALLS, "web to query calls: ${queryCalls.size}")
        assertTrue(gated.size >= MIN_GATED_METHODS, "@GatedRead methods: ${gated.size}")
        assertEquals(
            setOf(BOOTSTRAP_SERVICE),
            systemRunCalls,
            "exactly one production class starts a system-run invitation or approval",
        )
        assertTrue(
            accountingMarked.size >= MIN_ACCOUNTING_MARKED,
            "accounting marked: $accountingMarked",
        )
    }

    @Test
    fun `a read-back helper name is caught`() {
        assertViolation(
            ReadGateRules.noReadBackHelper(),
            "ReadBackHelperFixture",
            ReadBackHelperFixture::class.java,
        )
    }

    @Test
    fun `a web adapter calling an unmarked query method is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersCallOnlyGatedQueryMethods(),
            "UngatedReadFixtureController",
            UngatedReadFixtureController::class.java,
            UngatedFixtureQueryService::class.java,
        )
    }

    @Test
    fun `a web adapter referencing an unmarked query method is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersCallOnlyGatedQueryMethods(),
            "UngatedReferenceFixtureController",
            UngatedReferenceFixtureController::class.java,
            UngatedFixtureQueryService::class.java,
        )
    }

    @Test
    fun `a GET handler reading through an unmarked application service is caught`() {
        assertViolation(
            ReadGateRules.getHandlersCallOnlyGatedApplicationMethods(),
            "GetReadsUnmarkedFixtureController.read() calls UnmarkedApplicationFixtureService.peek",
            GetReadsUnmarkedFixtureController::class.java,
            UnmarkedApplicationFixtureService::class.java,
        )
    }

    @Test
    fun `a marked query method without the caller is caught`() {
        assertViolation(
            ReadGateRules.gatedQueryMethodsTakeTheCaller(),
            "CallerlessFixtureQueryService",
            CallerlessFixtureQueryService::class.java,
        )
    }

    @Test
    fun `a marked method that never asks a guard is caught`() {
        assertViolation(
            ReadGateRules.gatedMethodsAuthoriseBeforeReading(),
            "LyingFixtureQueryService",
            LyingFixtureQueryService::class.java,
        )
    }

    @Test
    fun `a marked method that reads before it asks a guard is caught`() {
        assertViolation(
            ReadGateRules.gatedMethodsAuthoriseBeforeReading(),
            "LateGuardFixtureQueryService",
            LateGuardFixtureQueryService::class.java,
            FixtureThingStore::class.java,
        )
    }

    @Test
    fun `an implementation that dropped the marker is caught`() {
        assertViolation(
            ReadGateRules.implementationsOfGatedMethodsAreGated(),
            "UnmarkedFixtureReadAdapter",
            UnmarkedFixtureReadAdapter::class.java,
            GatedFixtureReadService::class.java,
        )
    }

    @Test
    fun `a web adapter reaching a store port is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersDependOnlyOnServicesAndValues(),
            "StoreReachingFixtureController",
            StoreReachingFixtureController::class.java,
            FixtureThingStore::class.java,
        )
    }

    @Test
    fun `a web adapter reaching a read port outside the application packages is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersDependOnlyOnServicesAndValues(),
            "ReaderReachingFixtureController",
            ReaderReachingFixtureController::class.java,
            FixtureEventReader::class.java,
        )
    }

    @Test
    fun `a web adapter reaching a concrete bean that is no service is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersDependOnlyOnServicesAndValues(),
            "BeanReachingFixtureController",
            BeanReachingFixtureController::class.java,
            FixtureBalanceFinder::class.java,
        )
    }

    @Test
    fun `a web adapter reaching an interface named like no port is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersDependOnlyOnServicesAndValues(),
            "InterfaceReachingFixtureController",
            InterfaceReachingFixtureController::class.java,
            FixtureGateway::class.java,
        )
    }

    @Test
    fun `a GET declared through RequestMapping is caught`() {
        assertViolation(
            ReadGateRules.getHandlersCallOnlyGatedApplicationMethods(),
            "GetViaRequestMappingFixtureController.read() calls",
            GetViaRequestMappingFixtureController::class.java,
            UnmarkedApplicationFixtureService::class.java,
        )
    }

    @Test
    fun `a GET reading inside a callable reference is caught`() {
        assertViolation(
            ReadGateRules.getHandlersCallOnlyGatedApplicationMethods(),
            "GetViaReferenceFixtureController",
            GetViaReferenceFixtureController::class.java,
            UnmarkedApplicationFixtureService::class.java,
        )
    }

    @Test
    fun `a web adapter reaching an interface in a domain package is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersDependOnlyOnServicesAndValues(),
            "DomainPortReachingFixtureController",
            DomainPortReachingFixtureController::class.java,
            FixtureDomainPort::class.java,
        )
    }

    @Test
    fun `a web adapter reaching a type a bean method builds is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersDependOnlyOnServicesAndValues(),
            "EngineReachingFixtureController",
            EngineReachingFixtureController::class.java,
            FixtureEngine::class.java,
        )
    }

    @Test
    fun `a web adapter reaching a service that cannot authorise is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersDependOnlyOnServicesAndValues(),
            "UnguardedServiceReachingFixtureController",
            UnguardedServiceReachingFixtureController::class.java,
            UnguardedFixtureService::class.java,
        )
    }

    @Test
    fun `a GET declared by a bare RequestMapping is caught`() {
        assertViolation(
            ReadGateRules.getHandlersCallOnlyGatedApplicationMethods(),
            "GetViaBareRequestMappingFixtureController.read() calls",
            GetViaBareRequestMappingFixtureController::class.java,
            UnmarkedApplicationFixtureService::class.java,
        )
    }

    @Test
    fun `a web adapter starting the bootstrap is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersNeverStartTheBootstrap(),
            "BootstrapStartingFixtureController",
            BootstrapStartingFixtureController::class.java,
            InitialAdministratorBootstrapService::class.java,
        )
    }

    @Test
    fun `a web adapter copying a caller is caught`() {
        assertViolation(
            ReadGateRules.callersAreBuiltOnlyByTheResolver(),
            "CallerCopyingFixtureController",
            CallerCopyingFixtureController::class.java,
        )
    }

    @Test
    fun `a stray caller of the system-run invitation is caught`() {
        assertViolation(
            ReadGateRules.onlyBootstrapRunsAsSystem(),
            "SystemInviteFixtureCaller",
            SystemInviteFixtureCaller::class.java,
            UserProvisioningService::class.java,
        )
    }

    @Test
    fun `a reference to the system-run invitation is caught`() {
        assertViolation(
            ReadGateRules.onlyBootstrapRunsAsSystem(),
            "SystemInviteReferenceFixtureCaller",
            SystemInviteReferenceFixtureCaller::class.java,
            UserProvisioningService::class.java,
        )
    }

    @Test
    fun `a web adapter naming the system actor is caught`() {
        assertViolation(
            ReadGateRules.webAdaptersNeverUseTheSystemActor(),
            "SystemActorFixtureController",
            SystemActorFixtureController::class.java,
        )
    }

    @Test
    fun `a web adapter building its own caller is caught`() {
        assertViolation(
            ReadGateRules.callersAreBuiltOnlyByTheResolver(),
            "CallerBuildingFixtureController",
            CallerBuildingFixtureController::class.java,
        )
    }

    @Test
    fun `the compliant fixtures pass every rule`() {
        val classes =
            ClassFileImporter()
                .importClasses(
                    CompliantFixtureController::class.java,
                    GatedFixtureQueryService::class.java,
                    AreaGuardFixtureQueryService::class.java,
                    GatedFixtureReadService::class.java,
                    MarkedFixtureReadAdapter::class.java,
                )

        listOf(
            ReadGateRules.noReadBackHelper(),
            ReadGateRules.webAdaptersCallOnlyGatedQueryMethods(),
            ReadGateRules.getHandlersCallOnlyGatedApplicationMethods(),
            ReadGateRules.gatedQueryMethodsTakeTheCaller(),
            ReadGateRules.gatedMethodsAuthoriseBeforeReading(),
            ReadGateRules.implementationsOfGatedMethodsAreGated(),
            ReadGateRules.webAdaptersDependOnlyOnServicesAndValues(),
            ReadGateRules.onlyBootstrapRunsAsSystem(),
            ReadGateRules.webAdaptersNeverStartTheBootstrap(),
            ReadGateRules.webAdaptersNeverUseTheSystemActor(),
            ReadGateRules.callersAreBuiltOnlyByTheResolver(),
        ).forEach { it.check(classes) }
    }

    private fun assertViolation(
        rule: ArchRule,
        expectedOffender: String,
        vararg fixtureClasses: Class<*>,
    ) {
        val failure =
            assertFailsWith<AssertionError>("the rule must reject $expectedOffender") {
                // The packages, not the listed classes: a method reference compiles to a synthetic
                // nested class that an explicit class list would not import.
                rule.check(ClassFileImporter().importPackagesOf(*fixtureClasses))
            }
        assertContains(failure.message.orEmpty(), expectedOffender)
    }

    private companion object {
        const val ROOT = "com.finaxis.platform"
        const val MIN_WEB_QUERY_CALLS = 20
        const val MIN_GATED_METHODS = 30
        const val MIN_ACCOUNTING_MARKED = 7
        val BOOTSTRAP_SERVICE: String = InitialAdministratorBootstrapService::class.java.name
    }
}
