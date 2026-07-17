# Java and Spring Implementation Standards

## Purpose and authority

These standards govern Java, Spring, persistence, API, asynchronous workflow, signer, and chain implementation in the Digital Banking Reference Implementation. They specialize the boundaries in [`DESIGN.md`](DESIGN.md), the accepted [ADRs](adr/README.md), and repository guidance in [`AGENTS.md`](../AGENTS.md). They do not make this reference software production-ready.

`MUST`, `MUST NOT`, `SHOULD`, and `MAY` carry their usual normative meanings. A `MUST` exception requires an accepted ADR or an equally authoritative, explicitly approved plan decision that records the need, alternatives, consequences, tests, and removal or review condition. `SHOULD` exceptions require a short rationale in the owning plan or review. A tool report, generated graph, static search, or passing build is evidence input, not authority to waive a rule.

Apply the narrowest rule that preserves financial-state, authorization, evidence, privacy, and recovery semantics. Do not add a dependency, abstraction, wrapper, module, extension point, or configuration option for a hypothetical future use.

## Architecture and dependency direction

The executable dependency direction is:

```text
control-plane and adapters -> application -> domain
```

- `domain` MUST remain plain Java. It MUST NOT depend on Spring, HTTP, persistence, serialization frameworks, chain SDKs, signer providers, or adapter types.
- `application` MUST depend only on `domain` at runtime. It owns use cases and provider-neutral ports; it MUST NOT import Spring, JDBC, HTTP, chain SDK, or provider types.
- Adapters MUST depend inward and translate at their boundary. JDBC rows, RPC models, native SDK values, provider errors, and transport objects MUST NOT cross into application or domain signatures.
- `control-plane` MUST remain the Spring composition and transport boundary: authentication and authorization, request/response mapping, safe errors, configuration, health, and OpenAPI delivery.
- Business rules belong in domain types; use-case sequencing belongs in application services; protocol and provider details belong in adapters. Framework annotations MUST NOT substitute for a domain invariant.
- A new module or abstraction MUST have a present executable consumer and a testable responsibility. Empty future module trees and speculative generalization are prohibited.
- A material dependency, database/store, workflow runtime, chain, signer/custody provider, contract/program, or authority decision requires an ADR before implementation.
- Native chain adapters MUST NOT depend on each other. A shared abstraction MUST preserve, rather than flatten, materially different Ethereum and Solana semantics.

## Java and domain modeling

### Types and invariants

- Prefer immutable records and value objects where their invariants fit construction-time validation. Use classes when guarded behavior, identity, or evolution makes a record misleading.
- Financial and lifecycle concepts MUST use domain types: typed IDs, versioned asset units, exact quantities, operation and attempt states, evidence references, finality types and records, policy versions, and explicit result classifications.
- Expected multi-outcome behavior SHOULD use enums, sealed hierarchies, or explicit result types instead of boolean flags, magic strings, nullable values, or an ambiguous `Optional` constructor parameter.
- `switch` handling over financial, lifecycle, submission, retry, and finality cases SHOULD be exhaustive. A wildcard or `default` branch MUST NOT silently accept a newly added case.
- Constructors and transition methods MUST validate their invariants. Aggregates MUST NOT expose setters or mutable internal collections.
- Mutable inputs and outputs, especially unsigned or signed bytes and digests, MUST be defensively copied at construction and access boundaries.
- Public ports and security or financial abstractions SHOULD include useful Javadoc that states their role, authority, idempotency, failure, and implementation expectations rather than repeating the signature.
- Core modules SHOULD avoid Lombok, reflection-heavy mapping, runtime code generation, and serialization annotations. Introducing one requires an explicit decision and tests proving that invariants and dependency boundaries remain visible.

### Exact quantity and canonical data

- Money and token values MUST NOT use `float` or `double`. Canonical APIs use bounded decimal strings; domain logic uses exact decimal or integer atomic/minor-unit representations.
- Asset and unit version, scale, maximum magnitude, rounding policy, overflow behavior, and serialization MUST be explicit. Value-moving operations default to no rounding.
- Conversion to atomic units MUST occur only after scale and canonical form validation. Overflow or excess precision MUST fail; values MUST NOT truncate or round implicitly.
- Stable canonicalization and digest schemes MUST be versioned and covered by golden tests. Every effect-relevant field MUST be bound.
- Timestamps MUST be canonical UTC instants at the repository-defined precision. Business behavior MUST receive clock and ID providers; domain or application code MUST NOT hide `Instant.now()`, random IDs, or wall-clock sleeps.

### Failures

- Expected domain and application outcomes SHOULD have specific exception or result types. Boundary validation failures MUST be distinguishable from invariant violations and infrastructure failures.
- Code MUST NOT swallow exceptions. A broad catch requires explicit classification, safe translation, and preservation of the cause for internal diagnostics.
- Infrastructure failures MUST NOT be exposed as client faults or leak stack traces, SQL, provider data, secrets, raw identifiers, or untrusted input.

## Readability and source structure

- Packages and module entry points MUST remain focused. Add `package-info.java` when package intent or boundary rules need durable explanation.
- Prefer cohesive types and named methods over generic managers, broad utility classes, service locators, or indirect registries.
- A production source file above roughly 500 physical non-test lines triggers an extraction review. Above roughly 800 lines is a design smell unless cohesion or another reason is documented.
- Line thresholds are review prompts, not mechanical split rules. Extraction MUST reveal a stable responsibility and MUST NOT add speculative interfaces or layers.
- A method SHOULD expose its invariant, side effects, transaction scope, and failure path without requiring the reader to navigate unrelated responsibilities.
- Repeated test setup SHOULD use named fixtures or builders. Long positional construction SHOULD be replaced with a named fixture; if that is not practical, use consistent adjacent parameter-name comments where meaning is otherwise unclear.
- Duplicated business rules across controllers, services, repositories, and tests indicate misplaced ownership and MUST be consolidated at the correct boundary.

## Spring, API, and security

- Production Spring components MUST use constructor injection. Field injection and hidden service-locator access are prohibited.
- Controllers MUST remain thin. They may authenticate, validate transport input, map types, select HTTP semantics, and invoke one use case; they MUST NOT own financial policy, transaction orchestration, or lifecycle decisions.
- Security MUST be stateless and deny by default. Participant and tenant scope and authorities MUST come from the authenticated principal, never request JSON or an untrusted tenant header.
- Mint, burn, and read authorities MUST remain separate. Unknown and other-participant resources MUST remain indistinguishable where non-disclosure requires it.
- The design-first OpenAPI document is authoritative for the HTTP surface and MUST have executable conformance tests for paths, methods, security, problem types, quantities, and response shape.
- Problem responses SHOULD follow RFC 9457 conventions with stable repository-owned type URNs. They MUST NOT reflect secrets, idempotency keys, stack traces, SQL/provider details, internal policy, or untrusted input.
- Exception mapping MUST distinguish caller validation, authorization, conflict, not-found, dependency unavailability, and unexpected internal failure. A broad exception superclass MUST NOT turn internal invariant or mapping defects into a client error.
- HTTP `202 Accepted` MUST be returned only after the durable acceptance transaction commits. The response and contract MUST say that processing, signing, submission, minting, burning, finality, reconciliation, and settlement have not occurred.
- Runtime configuration MUST NOT provide default users, passwords, bearer tokens, identity providers, public-network endpoints, or permissive development fallbacks. Anonymous endpoints require a documented present need and an explicit allowlist.
- Participant-facing response projections MUST be explicit allowlists. Evidence, transition actor/reason, finality authority/policy, provider identity, native diagnostics, and internal correlation MUST be omitted or deliberately transformed unless a participant-safe contract approves them.

## Persistence and transaction boundaries

- ADR 0004's explicit JDBC, Flyway, and PostgreSQL choice governs the current persistence adapter. ORM/JPA, another data access abstraction, or another store requires evidence and an ADR.
- Migrations MUST be forward-only, deterministic, reviewable from an empty database, and protected by database constraints for important identities, exact quantities, lifecycle shape, and uniqueness.
- SQL MUST be parameterized and use explicit column lists and mappings. `SELECT *`, order-dependent implicit mapping, and untrusted SQL concatenation are prohibited.
- Acceptance MUST atomically commit the operation, scoped idempotency binding, initial history and finality records, evidence references, and pending outbox event.
- Signing, submission, network calls, messaging, and other external effects MUST NOT occur inside a database transaction.
- The database MUST remain authoritative for concurrency. Acceptance uses uniqueness and deterministic replay/conflict; aggregate changes use optimistic versions; failures MUST roll back every affected record.
- Persistence reconstruction MUST prove the whole aggregate, ordered history, attempts, all four finalities, evidence, exact quantities, and timestamp precision.
- Outbox delivery is at-least-once transport. Consumers MUST be idempotent or deduplicate by stable identity; documentation MUST NOT claim exactly-once external effects.
- Repository or framework exceptions MUST be classified at the adapter/API boundary and MUST NOT leak into domain types or participant responses.

## Asynchronous workflow and external effects

- Persist operation intent and an authorized attempt identity before an external effect.
- Build, policy/approval, sign, submit-once, inquire, observe, reconcile, and compensate MUST remain separate steps with durable evidence.
- A timeout or lost submission response is ambiguous. The system MUST inquire and observe by stable identity before authorizing a new value-moving attempt; blind resubmission is prohibited.
- Retries MUST be explicit, bounded, evidence-backed, and idempotent where the native system permits. A new or replacement attempt MUST retain predecessor and authorization evidence.
- Compensation is a new authorized operation or ledger correction. Confirmed history MUST NOT be deleted or rewritten.
- Workflow-engine state, timers, and delivery cursors are subordinate to domain state, ledger truth, and immutable evidence. A workflow runtime MUST NOT become financial-state authority.
- Every worker claim/lease, handler transition, retry, dead-letter/manual-review route, and recovery scan requires deterministic crash and duplicate-delivery tests.

## Signing and chain adapters

- Raw production private keys MUST NOT enter application memory, configuration, logs, fixtures, exceptions, or the repository. Development signing MUST be isolated, obvious, and impossible to enable as a production fallback.
- A signing request MUST bind stable operation and attempt IDs, exact bytes and digest, route and chain identity, operation kind, asset/unit and amount, source authority, destination, contract/program action, lifetime, fee constraints, policy version, allowlist, and approval/simulation evidence.
- Signing decisions MUST be explicit, evidence-backed, and redacted in diagnostics. The signer authorizes exact bytes; it does not decide business policy or finality.
- Web3j, Solana SDK, Foundry artifacts, Rust/Anchor values, HSM/MPC, custody, RPC, native transaction, nonce, blockhash, signature, receipt, log, slot, and commitment types MUST remain inside their owning adapters.
- Ethereum nonce reservation, replacement, chain ID, receipt/log, confirmation, and reorg/canonicality rules MUST remain native to the Ethereum adapter.
- Solana recent-blockhash lifetime, durable nonce choice, accounts/PDAs, instructions, signatures, slots, commitment, and expiry MUST remain native to the Solana adapter.
- Submission and materially independent observation MUST use separate authority and evidence paths. A submit provider's mutable response MUST NOT be the only source of observation truth.

## Testing and evidence

- Domain and application tests MUST run without Spring, a database, Docker, network access, or native SDKs.
- Tests SHOULD assert behavior and whole values rather than isolated getters. Persistence tests MUST assert complete aggregate reconstruction at the adapter boundary.
- Lifecycle tests MUST cover every allowed and forbidden transition, version conflict, time ordering, terminal state, attempt prerequisite, ambiguity, and finality prerequisite.
- Quantity tests MUST cover canonical round trip, unit version, scale, precision, maximum magnitude, zero/negative input, overflow, and serialization.
- Idempotency tests MUST cover original acceptance, replay, conflict, participant/tenant/kind isolation, concurrency, rollback, restart, and no additional outbox effect.
- API/security tests MUST cover authentication, each authority, participant non-disclosure, malformed input, unsupported media, stable problem mapping, redaction, commit-before-`202`, OpenAPI conformance, and dependency/internal failure classification.
- PostgreSQL behavior MUST use the pinned real PostgreSQL image through Testcontainers for migration, SQL mapping, constraints, transactions, concurrency, rollback, and restart evidence.
- Concurrency tests MUST use barriers, latches, explicit timeouts, and outcome assertions. Arbitrary sleeps or timing-based success assumptions are prohibited.
- Fixtures MUST be synthetic, local-only, non-secret, and clearly separated from production configuration.
- Compiler/build warnings MUST be adjudicated. Suppression requires a local rationale and, for security or correctness warnings, a regression test.
- A passing build is necessary but insufficient. Completion also requires dependency-direction checks, source/test diff review, documentation and link validation, secret/public-network scans, immutable-reference checks, and evidence that claims match executable behavior.

## Review and change discipline

- Behavioral changes use test-first development and the smallest focused verification before broader gates.
- Review every changed line and every affected boundary. Generated graphs and searches may prioritize reading but MUST NOT replace it.
- The owning active plan records decisions, commands, observed results, deferrals, and any standards exception.
- Documentation MUST distinguish `verified`, `scaffolded`, `planned`, and `deferred` capability. Do not describe acceptance, a signature, a transaction hash, or native finality as settlement.
- Optional tools such as compiler linting, formatting, Checkstyle, SpotBugs, Error Prone, ArchUnit, mutation testing, or coverage thresholds require a specific observed risk, a bounded evaluation, and an acceptable false-positive and maintenance profile. They MUST NOT be added for checklist appearance.
