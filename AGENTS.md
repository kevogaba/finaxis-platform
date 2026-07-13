# Agent Guide

**`CLAUDE.md` is the single source of truth for this repository.** Read it before making any
change. This file only carries the orientation an agent needs before opening `CLAUDE.md`; when
a rule changes, change it in `CLAUDE.md` — do not duplicate rules here.

Essentials:

- Kotlin-first Spring Boot Web MVC OAuth2 resource server on Java 25, hexagonal architecture
  (`domain` / `application` / `adapter` / `config`) with Spring Modulith module boundaries.
  Prefer Kotlin; justify any new Java.
- Keycloak authenticates users; this application authorizes them. It owns users,
  organisations, memberships, roles, permissions, and scopes. Runtime authorization evaluates
  **permission codes, never role names**. Do not add password handling of any kind.
- All work must pass the quality gates (`./gradlew qualityGate`) and ship unit + Spring
  integration tests. Architecture changes must pass ArchUnit and Spring Modulith verification.

Everything else — FSM/event architecture, API governance, security profiles, rate limiting,
audit, testing, and static-analysis rules — is in `CLAUDE.md`, which points into `docs/` for
detail. Keep `CLAUDE.md` and this file in sync when rules change.
