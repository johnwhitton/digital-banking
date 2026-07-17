# ADR 0001: Maven reactor and module boundaries

- Status: accepted
- Date: 2026-07-16
- Owners: repository owner; control-plane engineering
- Supersedes: None
- Superseded by: None

## Context

The foundation needs a locally buildable Java SE 25 and Spring Boot 4.0.6 baseline, a committed build wrapper, and an enforceable boundary that prevents Spring or chain/provider SDKs from becoming the core domain model.

The starting repository contained only an initial README. Neither Maven nor Gradle was on `PATH`. Homebrew OpenJDK 25.0.2 was locally available. Spring Initializr for Spring Boot 4.0.6 generated Maven Wrapper 3.3.4 with Apache Maven 3.9.16, confirming a supported current wrapper path. The action's architecture requirements place Java/Spring in the control plane and require chain-native technologies behind adapters.

## Decision

Use one Maven reactor with a committed Maven Wrapper:

- parent reactor at `pom.xml`;
- plain Java `domain` JAR module;
- framework-free `application` JAR module depending on `domain`;
- Spring Boot `control-plane` application module depending on `application`;
- Java release 25;
- Spring Boot 4.0.6, which manages Spring Framework 7.0.x;
- Maven Wrapper 3.3.4 configured for Apache Maven 3.9.16 with a distribution checksum; and
- Maven Enforcer dependency-ban rules in `domain` and `application` rejecting framework, JSON, chain SDK, HTTP, and persistence dependencies.

The initial foundation contained only the `domain` package boundary. Phase 2 added domain behavior and the `application` module test-first. The control plane still exposes Actuator health/readiness only; no business API is created without durable semantics.

## Alternatives considered

### Single Spring Boot module

Rejected because package conventions alone would not make the core boundary visible or enforceable. It would be too easy for Spring annotations and adapter types to become domain dependencies.

### Gradle multi-project build

Credible, but not selected. The empty repository supplied no Gradle convention, Maven's reactor is sufficient for these small modules, Spring Initializr confirmed the exact requested baseline, and Maven Wrapper provides the needed local build without installing a system build tool.

### JPMS modules in the bootstrap

Deferred. Java module descriptors could add stronger compile-time visibility, but Spring/test/plugin interactions would add bootstrap complexity before domain interfaces exist. The physical Maven boundary and Enforcer rule supply the required guardrail now.

### Many speculative adapter modules

Rejected. Ethereum, Solana, signing, persistence, and API modules will be created only when a vertical slice uses and tests them.

## Consequences

- Domain code has an explicit, independently buildable ownership boundary.
- The repository has one build tool and one wrapper.
- Contributors need JDK 25; the bootstrap workstation must set `JAVA_HOME` because Homebrew's JDK is not linked onto `PATH`.
- A three-module build is slightly more verbose than a single application module.
- Future adapters can depend inward without requiring the domain to know framework or chain types.
- Moving to Gradle, changing the Java/Spring baseline, or restructuring modules requires a superseding ADR and updated validation evidence.

## Validation

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain enforcer:enforce
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl application enforcer:enforce dependency:tree
```

The build must also prove the Spring application context and readiness endpoint. Dependency inspection must show no runtime dependency in `domain`, only `domain` at runtime in `application`, and no Spring, JSON framework, Web3j, Solana, HTTP, or persistence dependency in either inner module.
