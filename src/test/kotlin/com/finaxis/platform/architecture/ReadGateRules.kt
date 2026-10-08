package com.finaxis.platform.architecture

import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyReplayHandler
import com.finaxis.platform.common.web.idempotency.IdempotencyReplayResponse
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.iam.application.profile.UserProfileService
import com.finaxis.platform.iam.application.selection.AuthSelectionService
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.adapter.inbound.web.CallerContextResolver
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapService
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.AccessTarget
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod

/**
 * The ADR 0030 decision 6 rules, as ArchUnit rules over any set of imported classes, so
 * `PermissionFreeReadRuleTests` can run each against the production code and against a violating
 * fixture. Classes are selected by package and name, never by a list of production types, so a
 * class added tomorrow is held to the rule with no edit here.
 */
internal object ReadGateRules {
    /**
     * Reads a `@GetMapping` handler may call without the marker, keyed by fully qualified class
     * and method, each with where its authorisation lives.
     */
    val UNMARKED_GET_ALLOWLIST: Set<String> =
        setOf(
            // The caller's own profile, assembled from the authenticated principal. It is gated
            // only by the route's `@PreAuthorize("hasAuthority('iam.profile.read')")`; the
            // principal itself is built by the security configuration, which matches the JWT
            // subject, the membership, the organisation and the branch. The service makes no
            // check of its own (finding recorded in ADR 0030, open items).
            "${UserProfileService::class.java.name}.profile",
            // The organisations and branches the authenticated subject itself may select; the
            // lookup is keyed by that subject (the membership), not by a permission code.
            "${AuthSelectionService::class.java.name}.availableOrganisations",
            "${AuthSelectionService::class.java.name}.availableBranches",
        )

    /**
     * Platform types a web adapter may depend on although they are interfaces, beans or services
     * that cannot authorise, each with why. A value type, a command, a result, an annotation and a
     * governed `*Service` (one that depends on a `*PermissionGuard` or declares a `@GatedRead`
     * method) need no entry. Six entries today, all found by running the rule on the real code.
     */
    val WEB_DEPENDENCY_ALLOWLIST: Set<String> =
        setOf(
            // Web infrastructure the controllers need to serialise and replay a response: they
            // read nothing from the application.
            ApiJsonCodec::class.java.name,
            IdempotencyReplayHandler::class.java.name,
            IdempotencyReplayResponse::class.java.name,
            // The three identity services of `/auth`: they work on the authenticated principal and
            // the subject's own memberships, so they ask no permission code. The selection routes
            // authorise by membership; the two reads are on the GET allowlist above;
            // ActiveOrganisationContextService is only the session store the replay handler uses.
            ActiveOrganisationContextService::class.java.name,
            AuthSelectionService::class.java.name,
            UserProfileService::class.java.name,
        )

    private const val WEB_ADAPTERS = "..adapter.inbound.web.."
    private const val QUERY_MARKER = ".application.query"
    private const val REPORTING_MARKER = ".application.reporting"
    private const val DEFAULT_ARGUMENTS_BRIDGE = "\$default"
    private const val READ_BACK_HELPER = "get.*AfterAuthorizedMutation"
    private const val ROOT_PACKAGE = "com.finaxis.platform"
    private val BOOTSTRAP_SERVICE: String = InitialAdministratorBootstrapService::class.java.name
    private val SYSTEM_RUN_ENTRY_POINTS = setOf("inviteAsSystem", "approveAsSystem")
    private val COMMON_PACKAGES =
        listOf(
            "com.finaxis.platform.common.audit",
            "com.finaxis.platform.common.transitions",
            "com.finaxis.platform.common.persistence",
        )

    /** Only the line-order check uses a name, to tell a store read from any other bean call. */
    private val PORT_NAME =
        Regex(".*(Store|Queries|Repository|Reader|Query|Lookup|Source|Persistence)")

    /** No method named like the five deleted permission-free read-backs. */
    fun noReadBackHelper(): ArchRule =
        noMethods()
            .should()
            .haveNameMatching(READ_BACK_HELPER)
            .because(
                "a mutation reads its result back through the gated query a GET uses (ADR 0030 " +
                    "decision 6); the permission-free helpers are deleted and stay deleted",
            )

    /** Calls and method references from a web adapter reach only marked query-service methods. */
    fun webAdaptersCallOnlyGatedQueryMethods(): ArchRule =
        noClasses()
            .that()
            .resideInAPackage(WEB_ADAPTERS)
            .should()
            .accessTargetWhere(
                access("a query-service method that is not marked @GatedRead") {
                    isQueryService(it.targetOwner) && callable(it) && !isGated(it)
                },
            ).because("a web adapter may read only through a method that authorises its caller")

    /**
     * Every GET handler (`@GetMapping`, or `@RequestMapping` for GET on the method or its class),
     * with the helpers and callable references of its own class, calls an application service or
     * bean only through a marked method, or one named in [UNMARKED_GET_ALLOWLIST].
     */
    fun getHandlersCallOnlyGatedApplicationMethods(): ArchRule =
        methods()
            .that(
                object : DescribedPredicate<JavaMethod>("are GET handlers") {
                    override fun test(input: JavaMethod): Boolean = isGetHandler(input)
                },
            ).and()
            .areDeclaredInClassesThat()
            .resideInAPackage(WEB_ADAPTERS)
            .should(
                condition("call application services only through @GatedRead methods") {
                    method,
                    events,
                    ->
                    ungatedServiceCalls(method).forEach {
                        events.add(
                            SimpleConditionEvent.violated(
                                method,
                                "${method.fullName} calls ${it.targetOwner.simpleName}." +
                                    "${it.target.name}: not @GatedRead and not allowlisted",
                            ),
                        )
                    }
                },
            )

    /** A marked query method takes the caller, so the check it makes is the caller's own. */
    fun gatedQueryMethodsTakeTheCaller(): ArchRule =
        methods()
            .that()
            .areAnnotatedWith(GatedRead::class.java)
            .and()
            .areDeclaredInClassesThat()
            .resideInAPackage("..application.query..")
            .should(
                condition("take a FoundationCaller parameter") { method, events ->
                    val takesCaller =
                        method.rawParameterTypes.any {
                            it.isAssignableTo(
                                FoundationCaller::class.java,
                            )
                        }
                    if (!takesCaller) {
                        events.add(
                            SimpleConditionEvent.violated(
                                method,
                                "${method.fullName} is @GatedRead but takes no caller",
                            ),
                        )
                    }
                },
            )

    /** A marked method asks a guard (or a marked delegate) before it touches a port. */
    fun gatedMethodsAuthoriseBeforeReading(): ArchRule =
        methods()
            .that()
            .areAnnotatedWith(GatedRead::class.java)
            .should(
                condition("authorise before reading") { method, events ->
                    if (!method.modifiers.contains(JavaModifier.ABSTRACT)) {
                        authorisationFailure(method)?.let {
                            val reason = "${method.fullName} $it"
                            events.add(SimpleConditionEvent.violated(method, reason))
                        }
                    }
                },
            )

    /** An implementation of a marked method carries the marker itself, and so is checked. */
    fun implementationsOfGatedMethodsAreGated(): ArchRule =
        methods()
            .should(
                condition("carry @GatedRead when implementing one") { method, events ->
                    val synthetic =
                        method.modifiers.contains(JavaModifier.SYNTHETIC) ||
                            method.modifiers.contains(JavaModifier.BRIDGE)
                    if (!synthetic &&
                        !method.isAnnotatedWith(GatedRead::class.java) &&
                        overridesGated(method)
                    ) {
                        events.add(
                            SimpleConditionEvent.violated(
                                method,
                                "${method.fullName} implements a @GatedRead method unmarked",
                            ),
                        )
                    }
                },
            )

    /**
     * A web adapter depends on a type from the application or `common` packages only if it is a
     * `*Service` (governed by the call rules), a plain value (neither a Spring bean nor an
     * interface: a command, result, DTO or enum), or in [WEB_DEPENDENCY_ALLOWLIST]. So a store, a
     * reader class, a `@Component`, a resolver or a port of any name is refused, whatever it is
     * called and wherever it lives.
     */
    fun webAdaptersDependOnlyOnServicesAndValues(): ArchRule =
        noClasses()
            .that()
            .resideInAPackage(WEB_ADAPTERS)
            .should()
            .dependOnClassesThat(
                object : DescribedPredicate<JavaClass>(
                    "a bean or interface that is not a service",
                ) {
                    override fun test(input: JavaClass): Boolean = isRestrictedDependency(input)
                },
            ).because("reads go through a gated service, never around it to a bean or port")

    /** The bootstrap has no permission check of its own, so no web adapter may start it. */
    fun webAdaptersNeverStartTheBootstrap(): ArchRule =
        noClasses()
            .that()
            .resideInAPackage(WEB_ADAPTERS)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(BOOTSTRAP_SERVICE)
            .because("bootstrap(organisationId) checks no permission; only a service starts it")

    /** Only the bootstrap service starts a system-run invitation or approval (calls and refs). */
    fun onlyBootstrapRunsAsSystem(): ArchRule =
        noClasses()
            .that(
                object : DescribedPredicate<JavaClass>("are not the bootstrap service itself") {
                    override fun test(input: JavaClass): Boolean =
                        !isOrNestedIn(input, BOOTSTRAP_SERVICE) &&
                            !isOrNestedIn(input, UserProvisioningService::class.java.name)
                },
            ).should()
            .accessTargetWhere(
                access("a system-run invitation or approval") {
                    it.targetOwner.isEquivalentTo(UserProvisioningService::class.java) &&
                        it.target.name in SYSTEM_RUN_ENTRY_POINTS
                },
            ).because(
                "inviteAsSystem and approveAsSystem ask no actor permission: only the " +
                    "initial-administrator bootstrap may use them",
            )

    /** SystemActor passes every guard, so a web adapter never names it. */
    fun webAdaptersNeverUseTheSystemActor(): ArchRule =
        noClasses()
            .that()
            .resideInAPackage(WEB_ADAPTERS)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(SystemActor::class.java.name)
            .because("SystemActor passes every permission guard; a controller must never act as it")

    /** A caller is built from the authenticated request by `CallerContextResolver` only. */
    fun callersAreBuiltOnlyByTheResolver(): ArchRule =
        noClasses()
            .that()
            .resideInAPackage(WEB_ADAPTERS)
            .and(
                object : DescribedPredicate<JavaClass>("are not the resolver") {
                    override fun test(input: JavaClass): Boolean =
                        !isOrNestedIn(input, CallerContextResolver::class.java.name)
                },
            ).should()
            .accessTargetWhere(
                access("a TenantCaller or PlatformCaller constructor or copy") {
                    (it.target.name == "<init>" || it.target.name.startsWith("copy")) &&
                        (
                            it.targetOwner.isEquivalentTo(TenantCaller::class.java) ||
                                it.targetOwner.isEquivalentTo(PlatformCaller::class.java)
                        )
                },
            ).because("a caller built from request input could name any actor")

    /** Calls into web-adapter-visible query services, for the non-vacuity test. */
    fun queryServiceCallsFromWebAdapters(classes: Iterable<JavaClass>): List<JavaAccess<*>> =
        classes
            .filter { it.packageName.contains(".adapter.inbound.web") }
            .flatMap { it.accessesFromSelf }
            .filter { isQueryService(it.targetOwner) && callable(it) }

    /** True when the call targets the system-run entry points (non-vacuity test). */
    fun isSystemRunEntryPoint(access: JavaAccess<*>): Boolean =
        access.targetOwner.isEquivalentTo(UserProvisioningService::class.java) &&
            access.target.name in SYSTEM_RUN_ENTRY_POINTS

    private fun isQueryService(owner: JavaClass): Boolean {
        val pkg = owner.packageName
        val inQueryPackage = pkg.contains(QUERY_MARKER) || pkg.contains(REPORTING_MARKER)
        return (inQueryPackage && owner.simpleName.endsWith("Service")) ||
            owner.simpleName.endsWith("QueryService")
    }

    /** Where the GET rule looks for services and beans whose reads must be gated. */
    private fun inApplicationScope(candidate: JavaClass): Boolean {
        val pkg = candidate.packageName
        return pkg.startsWith(ROOT_PACKAGE) &&
            (pkg.contains(".application") || COMMON_PACKAGES.any { pkg.startsWith(it) })
    }

    /** Every platform type a web adapter could depend on: all but the web layer itself. */
    private fun inRestrictedPackage(candidate: JavaClass): Boolean {
        val pkg = candidate.packageName
        return pkg.startsWith(ROOT_PACKAGE) &&
            !pkg.contains(".adapter.inbound.web")
    }

    /**
     * A Spring bean: meta-annotated `@Component`, or the return type of a `@Bean` method (a type a
     * configuration class builds carries no stereotype of its own).
     */
    private fun isBean(candidate: JavaClass): Boolean =
        candidate.isMetaAnnotatedWith(Component::class.java) ||
            candidate.methodsWithReturnTypeOfSelf.any { it.isAnnotatedWith(Bean::class.java) }

    private fun isServiceLike(candidate: JavaClass): Boolean =
        candidate.isAnnotatedWith(Service::class.java) || candidate.simpleName.endsWith("Service")

    /**
     * A `*Service` is trusted only when it can authorise: it depends on a `*PermissionGuard`, or
     * declares a `@GatedRead` method. One that does neither (an engine, an audit writer, the
     * identity services) is a plain dependency like any other bean, and needs an allowlist entry.
     */
    private fun isGovernedService(candidate: JavaClass): Boolean =
        isServiceLike(candidate) &&
            (
                candidate.methods.any { it.isAnnotatedWith(GatedRead::class.java) } ||
                    candidate.directDependenciesFromSelf.any { isGuard(it.targetClass) }
            )

    /** A service or any other bean of the application or `common` packages: its reads are gated. */
    private fun isApplicationService(owner: JavaClass): Boolean =
        inApplicationScope(owner) && (isServiceLike(owner) || isBean(owner))

    private fun isRestrictedDependency(candidate: JavaClass): Boolean {
        val plain = !isServiceLike(candidate) && (candidate.isInterface || isBean(candidate))
        val ungovernedService = isServiceLike(candidate) && !isGovernedService(candidate)
        return inRestrictedPackage(candidate) &&
            !candidate.isAnnotation &&
            !isGuard(candidate) &&
            candidate.name !in WEB_DEPENDENCY_ALLOWLIST &&
            (plain || ungovernedService)
    }

    private fun isGetHandler(method: JavaMethod): Boolean {
        val own = method.tryGetAnnotationOfType(RequestMapping::class.java).orElse(null)
        val onClass = method.owner.tryGetAnnotationOfType(RequestMapping::class.java).orElse(null)
        return when {
            method.isAnnotatedWith(GetMapping::class.java) -> {
                true
            }

            own == null -> {
                false
            }

            own.method.isEmpty() -> {
                onClass == null || onClass.method.isEmpty() || RequestMethod.GET in onClass.method
            }

            else -> {
                RequestMethod.GET in own.method
            }
        }
    }

    private fun member(access: JavaAccess<*>): JavaCodeUnit? =
        (access.target as AccessTarget.CodeUnitAccessTarget).resolveMember().orElse(null)

    private fun callable(access: JavaAccess<*>): Boolean =
        access.target is AccessTarget.CodeUnitAccessTarget && access.target.name != "<init>"

    /** Every overload of the target (a `$default` bridge counts as its base) is marked. */
    private fun isGated(access: JavaAccess<*>): Boolean {
        val name = access.target.name.removeSuffix(DEFAULT_ARGUMENTS_BRIDGE)
        val overloads = access.targetOwner.allMethods.filter { it.name == name }
        return overloads.isNotEmpty() && overloads.all { it.isAnnotatedWith(GatedRead::class.java) }
    }

    private fun ungatedServiceCalls(handler: JavaMethod): List<JavaAccess<*>> {
        val seen = mutableSetOf<JavaMethod>()
        val found = mutableListOf<JavaAccess<*>>()
        val pending = ArrayDeque(listOf(handler))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            pending.addAll(nestedReferenceMethods(current, handler))
            current.accessesFromSelf.filter { callable(it) }.forEach { access ->
                val member = member(access)
                if (access.targetOwner == handler.owner && member is JavaMethod) {
                    pending.add(member)
                } else if (isUngatedServiceAccess(access)) {
                    found += access
                }
            }
        }
        return found
    }

    /** A callable reference compiles to a nested class: its methods are what will be called. */
    private fun nestedReferenceMethods(
        current: JavaMethod,
        handler: JavaMethod,
    ): List<JavaMethod> =
        current.accessesFromSelf
            .map { it.targetOwner }
            .filter { it != handler.owner && isOrNestedIn(it, handler.owner.name) }
            .flatMap { it.methods }

    private fun isUngatedServiceAccess(access: JavaAccess<*>): Boolean =
        isApplicationService(access.targetOwner) && !isGated(access) && !allowlisted(access)

    private fun allowlisted(access: JavaAccess<*>): Boolean {
        val method = access.target.name.removeSuffix(DEFAULT_ARGUMENTS_BRIDGE)
        return "${access.targetOwner.name}.$method" in UNMARKED_GET_ALLOWLIST
    }

    private fun overridesGated(method: JavaMethod): Boolean {
        val owner = method.owner
        val supertypes = owner.allRawSuperclasses + owner.allRawInterfaces
        val signature = method.rawParameterTypes.map { it.name }
        return supertypes.any { type ->
            type.methods.any {
                it.name == method.name &&
                    it.rawParameterTypes.map { p -> p.name } == signature &&
                    it.isAnnotatedWith(GatedRead::class.java)
            }
        }
    }

    /**
     * Null when the method is authorised before it reads; otherwise why not. Follows calls inside
     * the method's own class in line order, depth first, and stops at the first guard call (any
     * `*PermissionGuard`, or a marked delegate, which this same rule verifies in turn) or the first
     * store or query port call, whichever the method makes first.
     */
    private fun authorisationFailure(method: JavaMethod): String? =
        when (firstTerminal(method, mutableSetOf())) {
            Terminal.GUARD -> null
            Terminal.PORT -> "reads a store or query port before it asks a permission guard"
            null -> "never reaches a permission guard"
        }

    private enum class Terminal { GUARD, PORT }

    private fun firstTerminal(
        method: JavaMethod,
        visited: MutableSet<JavaMethod>,
    ): Terminal? {
        if (!visited.add(method)) return null
        val inOrder = method.accessesFromSelf.filter { callable(it) }.sortedBy { it.lineNumber }
        inOrder.forEach { access ->
            val owner = access.targetOwner
            val member = member(access)
            val terminal =
                when {
                    isGuard(owner) || member?.isAnnotatedWith(GatedRead::class.java) == true -> {
                        Terminal.GUARD
                    }

                    isNamedPort(owner) -> {
                        Terminal.PORT
                    }

                    owner == method.owner && member is JavaMethod -> {
                        firstTerminal(member, visited)
                    }

                    else -> {
                        null
                    }
                }
            if (terminal != null) return terminal
        }
        return null
    }

    /**
     * Name-based on purpose: every guard is `PermissionGuard`, `AuditPermissionGuard`,
     * `AccountingPermissionGuard`..., and the name is the one thing they share. A class named like
     * one that does not guard would pass, so the name is also reviewed.
     */
    private fun isGuard(owner: JavaClass): Boolean =
        owner.allRawInterfaces.plus(owner).any { it.simpleName.endsWith("PermissionGuard") }

    /**
     * For the line-order check only: an interface or bean that reads or stores, told apart from
     * the other beans a method calls by its name. It decides nothing about what a web adapter may
     * depend on; that is [isRestrictedDependency], which uses no name.
     */
    private fun isNamedPort(candidate: JavaClass): Boolean =
        inApplicationScope(candidate) &&
            (candidate.isInterface || isBean(candidate)) &&
            !isServiceLike(candidate) &&
            (candidate.simpleName.matches(PORT_NAME) || candidate.packageName.contains(".port"))

    private fun isOrNestedIn(
        candidate: JavaClass,
        outerName: String,
    ): Boolean = candidate.name == outerName || candidate.name.startsWith("$outerName\$")

    private fun access(
        description: String,
        matches: (JavaAccess<*>) -> Boolean,
    ): DescribedPredicate<JavaAccess<*>> =
        object : DescribedPredicate<JavaAccess<*>>(description) {
            override fun test(input: JavaAccess<*>): Boolean = matches(input)
        }

    private fun condition(
        description: String,
        check: (JavaMethod, ConditionEvents) -> Unit,
    ): ArchCondition<JavaMethod> =
        object : ArchCondition<JavaMethod>(description) {
            override fun check(
                item: JavaMethod,
                events: ConditionEvents,
            ) = check(item, events)
        }
}
