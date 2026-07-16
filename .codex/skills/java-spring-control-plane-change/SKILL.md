---
name: java-spring-control-plane-change
description: Use when changing Java, Maven, Spring Boot, HTTP APIs, validation, persistence, transactions, configuration, or JVM tests in this repository.
---

# Java and Spring Control-Plane Change

## Overview

Implement test-first Java/Spring changes while keeping the domain plain, the control plane durable, and infrastructure phase-scoped.

## Baseline and ownership

- Use Java 25, Spring Boot 4.0.6 / Spring Framework 7.0.x, Maven Wrapper 3.3.4, and Maven 3.9.16 unless a superseding ADR changes them.
- Keep `domain` free of Spring, Jakarta Persistence, HTTP, Web3j, Solana SDKs, and provider dependencies.
- Keep Spring request/response DTOs, validation annotations, controllers, persistence mappings, and configuration outside domain.
- Keep `control-plane` the composition root. Controllers translate HTTP and delegate; they do not decide lifecycle, idempotency, signing, or settlement.
- Add a new module or dependency only when the current approved slice uses and tests it. Do not pre-create `application`, persistence, adapter, or SDK modules.
- For Spring Boot 4, use the focused starters managed by the baseline (for example, `spring-boot-starter-webmvc` and matching test starter) rather than remembered Boot 3 layouts.

## Workflow

1. Read the active plan, ADR 0001, owning design sections, and current reactor POMs.
2. Identify the pure business behavior and the delivery/infrastructure behavior separately.
3. Use `financial-state-invariants` for amount, idempotency, lifecycle, finality, signing, ambiguity, or reconciliation changes.
4. Write the narrow failing test first and observe the expected failure.
5. Implement the smallest code in the owning module. Map between domain and Spring/persistence/native types at the boundary.
6. Run the focused module/test command, then `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify` when the slice is ready.
7. Run the domain Enforcer gate after dependency changes and inspect dependency trees for leakage.
8. Synchronize README/design/implementation/plan/ADR facts with `digital-banking-doc-sync`.

## API and persistence contract

- Accept value-moving commands durably before returning HTTP 202 and `Location`.
- Require scoped idempotency and exact quantity strings; never use `float` or `double`.
- Return stable operation identity and truthful lifecycle state. Transaction hashes/signatures are attempt evidence, never a `settled` result.
- Keep external signing/submission outside database transactions.
- Enforce uniqueness, optimistic versioning/locking, restart recovery, and outbox/inbox boundaries with integration tests before claiming durability.

## Test matrix

| Change | Focused evidence |
| --- | --- |
| Domain | Pure unit/property tests and forbidden-dependency gate |
| Controller | Contract, validation, problem response, 202/Location, sensitive-field redaction |
| Persistence | Migration, uniqueness/concurrency, rollback, restart/recovery |
| Configuration | Context startup, safe local defaults, no public network/secret requirement |
| Dependency/build | Wrapper version, focused module test, Enforcer, full reactor verify |

## Common mistakes

- Putting annotations on domain objects for convenience.
- Adding Web3j, Solana, database, or broker dependencies before their slice.
- Writing tests after an implementation already passes.
- Holding a transaction open across signer/provider calls.
- Creating a business endpoint backed only by an in-memory fake or node response.
