# ADR 0017: BIAN As Semantic Reference Architecture, Not Deployment Topology

## Status

Accepted

Date: 2026-08-31

## Context

Finaxis is about to build its accounting foundation (epic #55), and every later financial
capability — member, savings, shares, loans, teller, payments, billing, reconciliation, financial
statements — will need a consistent vocabulary and a way to tell whether a capability is covered,
adapted or genuinely new. GitHub issue #28 asks for that reference map.

BIAN is the obvious candidate: it is the banking industry's semantic reference architecture, its
`Financial Accounting` Service Domain is directly relevant to the ledger, and its `Position
Keeping` Service Domain describes the product-level position journals the product modules will
need. The risk is equally obvious. BIAN's Service Domains are frequently read as a
microservice decomposition, and its `Position Keeping` definition describes a staging-then-reconcile
flow that is incompatible with the transactional guarantee this platform is built on.

Two practical constraints shaped the evidence base. The Service Landscape portal requires
registration, and the URL cited in issue #28
(`bian.org/servicelandscape-11-0-0/object_41.html?object=43420`) is JavaScript-rendered — a plain
fetch returns an empty `InSite` shell with no Service Domain content. However, the
`bian-official/public` GitHub repository publishes the full v14.0.0 Service Domain definitions
without registration, so the mapping can be built against primary v14.0.0 material rather than
against secondary summaries.

One number needed care. Secondary sources circulate a figure of 340 Service Domains for v14.0. The
v14.0.0 release notes, which are directly downloadable, contain a metrics table stating **322**
Service Domains for v14.0 — down from 327 in v13.0, consistent with the release's own description
of Service Domain removals and renames. The lower, primary figure is the one recorded.

## Decision

**BIAN is adopted as a semantic and capability reference only.** Finaxis maps its module boundaries
onto BIAN Service Domains for vocabulary and gap analysis. BIAN never dictates deployment topology,
package structure, transport shape, or database schema. Finaxis domain invariants, tenant
isolation, security, audit, transactional posting and performance requirements remain
authoritative.

**No microservice split.** Finaxis remains one Spring Modulith deployable. A BIAN Service Domain
maps to at most a module, a package, or a named interface — never to a separate deployable. The
reason is the ledger invariant, not convenience: a balance-affecting product transaction must
commit its business effect, its subsidiary-ledger effect and its general-ledger posting in one
PostgreSQL transaction, and a network boundary between product fulfilment and accounting would make
that impossible. Changing deployment topology requires a separate ADR that supersedes this one.

**Version pinned to BIAN Service Landscape 14.0, released February 2026, with 322 Service
Domains.** The count is taken from the v14.0.0 release-notes metrics table, not from secondary
sources.

**The evidence base is the public `bian-official/public` GitHub repository.** `Financial
Accounting` and `Position Keeping` are quoted from `release14.0.0`; the older narrative "Service
Domain Role" and "General comment" prose is quoted from `bianapis/wave1` where v14 no longer
carries it. The registration-gated portal and the JavaScript-rendered landscape URL are recorded as
not machine-fetchable and are not cited as evidence.

**Re-verification is triggered** by any of: a BIAN release above 14.0; a new `release*` directory in
`bian-official/public`; a new `@ApplicationModule` carrying financial capability; or twelve months
elapsing. `docs/architecture/bian-service-landscape.md` carries a "Sources verified on" date that
must be bumped on every re-verification.

**`Financial Accounting` is the accounting semantic anchor, adapted.** Finaxis adopts the
fact-in, instruction-out semantic: product modules state what economically happened and accounting
decides which accounts move. Finaxis rejects the timing the BIAN phrasing admits — that the ledger
update happens downstream. Posting is synchronous and in-transaction.

**`Position Keeping` is evaluated and deliberately not adopted as a Finaxis boundary.** BIAN says
product fulfilment domains "delegate transaction posting/track to the Position Keeping service
domain" and that "reconciled financial transactions are subsequently used for posting to the
accounting systems". Finaxis inverts this. The general-ledger journal is the authoritative
real-time record, written in the same PostgreSQL transaction as the source-domain mutation and the
subsidiary-ledger effect. Product-owned subsidiary positions are Position-Keeping-shaped but are
never a staging area later reconciled into the ledger. Summary and interval bookings are derived
projections and performance optimisations only, never the sole source of truth, and must be
reconstructable from immutable journal lines. A design that makes a rollup authoritative is a
defect, not a variation.

**BIAN nouns are borrowed; BIAN transport shapes are banned from the domain model.** Finaxis uses
BIAN nouns — financial fact, posting, ledger, sub-ledger, position, chart of accounts, control
account — where they add clarity. Finaxis does not adopt BIAN Service Operation verbs
(`Initiate`, `Update`, `Retrieve`, `Execute`, `Request`, `Exchange`, `Control`), control-record
identifiers, behaviour-qualifier URL segments, or BIAN and ISO 20022 payload schemas in `domain` or
`application` code. A BIAN-shaped external API, if ever required, is an inbound adapter with its
own DTOs.

**Deviations are recorded, not hidden.** `docs/architecture/bian-service-landscape.md` carries a
deviation register with a stable `FX-DEV-nnn` id per deviation, each stating the BIAN position, the
Finaxis position, the driver, and what enforces it.

**Every `@ApplicationModule` must declare its BIAN mapping in its `package-info.java` Javadoc**, on
a line beginning `BIAN:`, either naming Service Domain(s) with an `(adopted)` or `(adapted)`
qualifier, or reading `BIAN: none —` plus why the boundary is platform-specific. This is enforced
by `BianModuleMappingTests` and recorded as a canonical rule in `CLAUDE.md`.

**Platform-specific boundaries are legitimate.** `lifecycle`, `common`, `config` and `jooq` are
declared to have no BIAN Service Domain. Tenant, branch and user provisioning state machines, the
controlled business date, the reserved `PLATFORM` organisation, and generated schema metadata are
multi-tenant SaaS platform concerns that BIAN's single-bank model does not express. Declaring
`BIAN: none` with a reason is a first-class outcome, not a gap to be papered over.

## Consequences

No code, schema, or deployment changes. Existing Spring Modulith boundaries and
`ApplicationModules.verify()` are untouched — only Javadoc is added to the four existing module
descriptors.

One new repository-wide rule, enforced by one new test. A new `@ApplicationModule` cannot merge
without a `BIAN:` line and a matching `### <module>` section in the landscape document, so the map
cannot silently drift from the module graph. The test asserts descriptors were actually discovered,
so a path typo cannot make it vacuous.

The version gap is small and explicit: the v14.0.0 Service Domain definitions are public, and only
the browsable landscape diagram is gated. Where a capability has no Service Domain — teller and
cash handling, member equity and share capital, reconciliation as a first-class capability — the
map records `BIAN: none — nearest is <X>` rather than inventing a name. Several plausible-sounding
names (`TellerOperations`, `CashManagement`, `FinancialStatements`, `SubLedgerAccounting`,
`ShareCapital`) were checked and do **not** exist in v14.0.0.

The cost is that BIAN vocabulary in port names is a one-way door for consumers: renaming a port
later is a breaking cross-module change. That is accepted in exchange for a shared vocabulary
across nine planned financial modules.
