# Phase 3A Java/Spring Implementation Standards Review

## 1. Baseline and scope

This bounded review evaluates the completed Phase 3A implementation at Git commit `cb5d4ad4c8e17e5a000448a271b330ed2f704fde` against [`docs/IMPLEMENTATION_STANDARDS.md`](../IMPLEMENTATION_STANDARDS.md). At the start, local `main`, `origin/main`, and the live `origin` `refs/heads/main` all resolved to that exact commit and the worktree was clean. The verified remote was `git@github.com:johnwhitton/digital-banking.git`.

The review covers the Maven reactor, all production Java sources, all test sources, the PostgreSQL migration, runtime YAML, authoritative OpenAPI contract, accepted ADRs, governing design and implementation documents, and active plans. It is a documentation and audit action: no production code, test, POM, migration, OpenAPI, dependency, runtime configuration, or PDF was changed.

The baseline implements durable participant-scoped mint/burn acceptance and read-back only. It does not process operations, publish the outbox, sign, submit, mint, burn, transfer, observe, reconcile, settle, or integrate a chain or signer. Findings therefore distinguish present Phase 3A behavior from risks that become active only when Phase 3B or later code uses the existing seams.

This report is not a quality, security, compliance, or production-readiness certification.

## 2. Repository, module, and source/test inventory

Physical line counts include blank and comment lines and are used only as navigation and extraction-review prompts.

| Module | Production packages | Production Java | Production lines | Test Java | Test lines | Annotated test methods |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `domain` | `domain`, `domain.asset`, `domain.operation` | 16 | 846 | 4 | 592 | 23 |
| `application` | `application`, `application.command`, `application.port` | 28 | 1,084 | 4 | 760 | 23 |
| `adapters/persistence-postgres` | `persistence.postgres` | 1 | 864 | 1 | 619 | 14 |
| `control-plane` | application root, `controlplane.api`, `controlplane.config` | 8 | 551 | 6 | 717 | 19 |
| **Total** | 10 production package names | **53** | **3,345** | **15** | **2,688** | **79** |

The reactor contains one parent POM and four module POMs. Relevant non-Java executable inputs are one 268-line Flyway migration, one 19-line runtime YAML file, and one 521-line OpenAPI 3.1 contract. The parameterized lifecycle matrix expands the 79 annotated test methods to 302 executed tests in the full reactor gate.

Largest production files:

| File | Lines | Review disposition |
| --- | ---: | --- |
| [`PostgresOperationRepository.java`](../../adapters/persistence-postgres/src/main/java/io/github/johnwhitton/digitalbanking/persistence/postgres/PostgresOperationRepository.java) | 864 | Directly read in full; Advisory A-01 because several stable responsibilities share one file. Size alone was not treated as a defect. |
| [`TokenOperation.java`](../../domain/src/main/java/io/github/johnwhitton/digitalbanking/domain/operation/TokenOperation.java) | 355 | Directly read in full; cohesive aggregate behavior and invariant ownership support the current shape. |
| [`SignerPort.java`](../../application/src/main/java/io/github/johnwhitton/digitalbanking/application/port/SignerPort.java) | 236 | Directly read in full; defensive copying and binding are strong, while executable signer assessment is not applicable. |
| [`ChainPort.java`](../../application/src/main/java/io/github/johnwhitton/digitalbanking/application/port/ChainPort.java) | 179 | Directly read in full; provider-neutral seam only, with no adapter implementation. |

## 3. Methodology and limitations

Method:

1. Read the governing request, repository guidance, security and autonomous-execution policy, accepted ADRs, canonical design, implementation roadmap, and every active plan.
2. Used the existing Graphify 0.8.47 graph only to navigate module, lifecycle, persistence, transaction, idempotency, outbox, API, and security relationships. Every conclusion below was checked against direct source and tests.
3. Read all 53 production Java files and all 15 test Java files in full. No repetitive fixture or setup body was sampled or omitted.
4. Read all five POMs, the complete Flyway migration, runtime YAML, and OpenAPI contract. Inspected imports, binary dependencies, file sizes, exception paths, mutability, time/ID generation, SQL construction, transaction boundaries, response mapping, and test synchronization.
5. Ran focused searches as triage for framework leakage, financial floating point, hidden clocks/IDs, broad catches, nullable/boolean outcomes, field injection, reflection/code generation, mutable arrays, `SELECT *`, SQL concatenation, arbitrary sleeps, secrets, and public-network material. Each relevant match was adjudicated by reading its surrounding implementation and tests.
6. Compared README, design, implementation, ADR, and active-plan capability claims with executable evidence.
7. Ran the required offline Maven, Enforcer, dependency-tree, and JDK `jdeps` gates, then the focused repository document, integrity, and safety checks recorded in the active plan.
8. Obtained one independent read-only review of the completed standards and audit for missed Critical or Important findings and unsupported claims. It found no new Critical or Important finding, confirmed I-01/I-02, and identified four documentation corrections that were independently verified and incorporated: exact annotation counts, nested OpenAPI conformance/example drift, the in-transaction evidence-port contract, and policy-version typing status.

Limitations:

- This is a bounded source, test, and local-build review. It did not operate a deployed service, run load or fault-injection infrastructure outside the existing tests, or assess production identity, secrets, networks, deployment, observability, or operations.
- No workflow worker, signer implementation, Ethereum/Solana adapter, native contract/program, independent observer, or reconciliation implementation exists, so executable assessment of those areas is `not_applicable`.
- No web research or dependency update analysis was performed. Maven ran offline against already resolved inputs; `~/.m2` was not inspected.
- The existing PostgreSQL/Testcontainers tests exercise a local pinned container, not a managed production database or failover topology.
- Static searches, Graphify, line counts, Maven success, and `jdeps` were treated as leads or boundary evidence, never as proof of business correctness by themselves.

## 4. Standards checklist

Status vocabulary: `pass` means the bounded baseline supplies direct supporting evidence; `partial` means important evidence exists but the area is incomplete; `finding` links to a specific issue below; `not_applicable` means the executable capability is absent. This checklist records the audited baseline; the finding dispositions record the later, separately approved Action Request 07 corrections.

| Standards area | Status | Evidence and disposition |
| --- | --- | --- |
| Reactor and inward dependency direction | `pass` | POM/Enforcer rules, source imports, and `jdeps` show `application -> domain`, persistence inward to application/domain plus JDBC/Spring transaction APIs, and control-plane inward plus its approved Spring stack. |
| Framework-free domain and application | `pass` | Domain uses JDK/domain types only; application runtime uses domain/JDK only. No Spring, HTTP, persistence, serialization, chain SDK, or provider type enters core signatures. |
| Exact quantity, typed identity, lifecycle, evidence, and finality modeling | `partial` | Exact `BigInteger` atomic units, versioned `AssetUnit`, typed IDs, guarded transitions, four independent finalities, stable attempt lineage, and canonical command hashes are directly tested. Policy versions remain bounded strings pending first real policy/finality use (Advisory A-02). |
| Immutability, defensive copying, clocks, and IDs | `pass` | Aggregate collections are copied/unmodifiable; signer/chain byte arrays clone on input/output; application time/IDs are injected. Technical outbox IDs remain adapter-owned. |
| Structured failures and boundary classification | `finding` | Important I-02: every `IllegalArgumentException`, including possible internal invariant/rehydration failures, maps to HTTP 400. |
| Readability and source structure | `partial` | Most types are focused; the 864-line JDBC repository crosses the 800-line review prompt and has several extractable responsibilities (Advisory A-01). |
| Spring composition and controller thinness | `pass` | Production components use constructor/method injection; controller behavior is limited to principal/transport mapping and application invocation. |
| Stateless deny-by-default security and participant scope | `pass` | No local credentials or identity provider are configured; business paths require authentication and distinct mint/burn/read authorities; scope comes from `ParticipantPrincipal`; cross-participant reads are 404-equivalent. |
| Participant-safe response projection | `finding` | Important I-01: evidence is allowlisted, but transition actor/reason and finality authority/policy are copied verbatim into the participant contract. |
| Design-first OpenAPI and safe problem shape | `partial` | Runtime resource, top-level record components, quantities/finality collections, and problem URNs are tested; nested status projection and accepted-example conformance are incomplete under I-01, while internal-failure classification requires I-02. |
| JDBC/Flyway/PostgreSQL and migration discipline | `pass` | Explicit parameterized SQL, explicit columns/mapping, constraints, one forward migration, real PostgreSQL migration/round-trip tests, and no ORM/JPA are present. |
| Atomic acceptance, idempotency, outbox, and transactions | `pass` | Read-committed acceptance atomically writes required records and pending outbox; replay/conflict and rollback tests prove no duplicate/partial effect; no external effect occurs in the transaction. |
| Database concurrency and recovery | `pass` | Unique idempotency binding, optimistic aggregate version, deterministic barrier/latch races, timeouts, rollback, fresh-pool restart, and whole-aggregate reconstruction are tested. |
| At-least-once delivery semantics | `pass` | Documents and schema call the outbox pending/scaffolded and do not claim exactly-once external effects; no publisher exists. |
| Worker/retry/recovery implementation | `not_applicable` | Phase 3B worker, claims, inbox, publication, retries, recovery loop, and scheduler do not exist. |
| Signer and chain implementation | `not_applicable` | Provider-neutral ports exist, but no signer, chain SDK, adapter, key, contract/program, submission, or observation implementation exists. Advisory A-02 applies before those seams become executable. |
| Test design and evidence | `partial` | Exhaustive lifecycle, exactness, real PostgreSQL, deterministic race, rollback/restart, security, OpenAPI, and redaction coverage is strong. The two Important findings each identify a missing boundary test. |
| YAGNI and dependency discipline | `pass` | No empty future module, chain SDK, messaging runtime, ORM, workflow engine, signer provider, or speculative dependency exists. Accepted material choices have ADRs. |
| Documentation and implementation consistency | `partial` | README/design/implementation capability claims match code and 302-test evidence. Two completed action-plan closeout rows remain stale (Advisory A-03). |

## 5. Positive evidence and well-executed patterns

### Domain and application

- [`TokenOperation`](../../domain/src/main/java/io/github/johnwhitton/digitalbanking/domain/operation/TokenOperation.java) owns lifecycle legality, version checks, event-time ordering, attempt prerequisites, ambiguous-effect retry prohibition, finality monotonicity, and terminal immutability without framework dependencies.
- [`TokenQuantity`](../../domain/src/main/java/io/github/johnwhitton/digitalbanking/domain/asset/TokenQuantity.java) validates bounded canonical decimal input before numeric conversion and preserves versioned atomic-unit semantics without `float` or `double`.
- The lifecycle parameter matrix covers every allowed and unspecified state transition. Separate tests cover attempts, ambiguity, all four finalities, exact quantities, canonical digest binding, participant/kind scope, and raw idempotency-key redaction.
- Application services receive `ClockPort`, `IdGenerator`, repository, catalog, and evidence ports. The Spring composition root, rather than core behavior, supplies current time and UUID generation.
- Signer and chain request byte arrays are cloned on construction and access. Signing diagnostics redact provider, policy, evidence, and payload details.

### Persistence and transactions

- [`V1__create_token_operation_persistence.sql`](../../adapters/persistence-postgres/src/main/resources/db/migration/V1__create_token_operation_persistence.sql) uses explicit types, checks, foreign keys, unique identities, and indexes for the accepted aggregate, history, attempts, finalities, idempotency, and outbox.
- [`PostgresOperationRepository`](../../adapters/persistence-postgres/src/main/java/io/github/johnwhitton/digitalbanking/persistence/postgres/PostgresOperationRepository.java) forces `READ_COMMITTED`, parameterizes business input, selects explicit columns, maps atomic quantities exactly, and appends one aggregate event per version.
- Acceptance writes the aggregate, scoped key digest binding, initial transition/evidence, four finality rows, and one pending outbox event in one transaction. A forced outbox trigger failure proves complete rollback.
- Two-thread tests place barriers inside both contested operation factories, use latches and explicit timeouts, and prove one original plus one replay or one winner plus one conflict without duplicate operations/outbox rows.
- Persistence tests compare the complete reconstructed aggregate, including attempt lineage and each finality history, and reopen a fresh pool/repository to prove committed restart read-back.

### API and security

- Security is stateless and deny-by-default, with no default credential, token decoder, issuer, JWK, or public-network configuration. The only anonymous resources are the documented health and OpenAPI paths.
- Mint, burn, and read authorities are distinct. Participant scope is derived from the authenticated principal, and unknown/cross-participant reads return indistinguishable 404 problems.
- HTTP 202 follows the repository acceptance call and the contract explicitly limits it to a committed local acceptance transaction, with no processing or external effect claim.
- The OpenAPI document has no server URL and is served byte-for-byte. Contract tests compare top-level record component names, security requirements, status sets, problem URNs, canonical quantity type/pattern, and the presence of four finality collections; I-01 records the missing nested/example conformance.
- Problem bodies and idempotency conflicts are stable and do not reflect raw keys, stack traces, SQL, Hikari/provider details, or command digests.

## 6. Findings

### Important I-01: participant status projection and nested OpenAPI conformance are incomplete

**Evidence.** [`TokenOperationResponse.java`](../../control-plane/src/main/java/io/github/johnwhitton/digitalbanking/controlplane/api/TokenOperationResponse.java) filters only evidence references by the `participant:` prefix (lines 77-82). It copies transition `actor` and `reason` verbatim (lines 101-115) and finality `authority` and `policyVersion` verbatim (lines 125-135). The authoritative OpenAPI includes all four fields in the participant response. [`TokenOperationResponseTest.java`](../../control-plane/src/test/java/io/github/johnwhitton/digitalbanking/TokenOperationResponseTest.java) proves evidence filtering but does not prove that internal actor, reason, authority, or policy data is omitted. [`OpenApiContractTest.java`](../../control-plane/src/test/java/io/github/johnwhitton/digitalbanking/OpenApiContractTest.java) compares only the top-level `TokenOperationResponse` record components; it does not recursively compare nested records or validate the accepted-response example. That example shows four `NOT_ASSESSED` records with `phase-3a-bootstrap`, `phase-3a-v1`, and the acceptance timestamp, while production acceptance constructs `not-assessed`, `none`, and `Instant.EPOCH`.

**Impact.** Current Phase 3A responses contain only no-assessment finality metadata and no post-acceptance workflow transitions, so this review observed no current secret or participant-crossing disclosure. The accepted-response example is nevertheless not an example of the current production response. Phase 3B will introduce worker actors, transition reasons, and policy/finality authorities. Copying those internal strings into a participant contract can expose implementation, policy, case, or provider details and contradicts the response type's "safe participant-facing" claim; shallow conformance tests can let that contract drift persist.

**Bounded recommendation.** Before Phase 3B expands status, define a versioned participant-safe status projection. Remove these fields from the participant API or map them through explicit safe enums/allowlists; retain the full values only in durable internal evidence. Update the accepted example to match an executable response and add recursive nested-schema/example conformance plus negative tests using deliberately sensitive internal actor/reason/authority/policy values. This requires a separately approved code/test/OpenAPI action.

**Disposition (Action Request 07).** Resolved. The versioned participant response and OpenAPI schemas omit transition `actor`/`reason` and finality `authority`/`policyVersion`; the full values remain unchanged in the internal domain and PostgreSQL model. A negative projection test populates deliberately sensitive values and proves they are absent while `participant:` evidence remains. OpenAPI tests now compare every nested response record's properties and required fields and compare the complete accepted example with an executable production response, including `Instant.EPOCH` for initial finalities. The existing principal-derived scope, distinct authorities, and indistinguishable missing/cross-participant 404 behavior remain covered by the focused API gate.

### Important I-02: broad `IllegalArgumentException` mapping misclassifies internal invariant failures as caller errors

**Evidence.** [`ApiExceptionHandler.java`](../../control-plane/src/main/java/io/github/johnwhitton/digitalbanking/controlplane/api/ApiExceptionHandler.java) maps every `IllegalArgumentException` to the stable 400 problem (lines 25-36). Caller validation uses this exception, but domain construction and PostgreSQL aggregate rehydration also throw it for invalid internal state. Existing API tests prove malformed/caller input and database-unavailable paths, not an invariant/mapping failure reached behind the transport boundary.

**Impact.** Corrupt or incompatible persisted data, a mapper defect, or another internal invariant breach can be reported as a client mistake. That classification can conceal a service integrity/recovery problem, mislead clients and operations, and bypass the intended stable internal-failure behavior. This review found no raw detail leakage and no observed corrupt row; the risk is classification and recovery, not a demonstrated data-loss defect.

**Bounded recommendation.** Introduce a specific caller-validation failure at the transport/application boundary, narrow the 400 handler to caller-owned failures, and map unexpected internal/invariant failures to a safe stable 5xx problem while preserving internal diagnostics. Add controller tests that distinguish invalid request data from a simulated internal aggregate/mapper failure, and synchronize OpenAPI. This requires a separately approved code/test/OpenAPI action.

**Disposition (Action Request 07).** Resolved. `InvalidRequestException` now identifies only caller-owned operation-ID, idempotency-key, quantity, and command-construction validation. The application catch ends before lifecycle/repository execution, and a regression test proves the original synthetic persistence `IllegalArgumentException` is not reclassified. The API maps unexpected `IllegalArgumentException` and `IllegalStateException` invariant failures to the documented `urn:digital-banking:problem:internal-error` HTTP 500 body, returns no exception class or diagnostic marker, and logs the original failure for server diagnostics. Existing explicit 400/401/403/404/409/415/422/503 classifications remain unchanged.

### Advisory A-01: the PostgreSQL repository has crossed a useful extraction boundary

**Evidence.** `PostgresOperationRepository.java` is 864 physical production lines. Direct review found atomic acceptance/idempotency, aggregate save/delta classification, SQL append methods, seed/history queries, row mapping, event replay, timestamp/quantity conversion, and internal row/event records in one type.

**Impact.** The current tests make these responsibilities understandable and no correctness defect was inferred from size. Adding worker claims, outbox delivery, or additional aggregate events to the same type would increase change coupling and make transaction/failure review harder.

**Bounded recommendation.** Before the next material change to this file, perform a focused extraction review. Prefer the smallest stable split—such as aggregate loading/replay mapping and append/event writing—while keeping transaction ownership explicit in the repository and adding no speculative interface or dependency. This can accompany Phase 3B if Phase 3B changes the file.

### Advisory A-02: future policy, evidence, signer, and chain port semantics need stronger types and contracts before use

**Evidence.** [`PolicyApprovalPort.PolicyDecision`](../../application/src/main/java/io/github/johnwhitton/digitalbanking/application/port/PolicyApprovalPort.java) represents approval as a bare boolean. Policy versions remain bounded `String` values in finality, retry, policy, signer, and chain contracts rather than one validated domain type. `ChainPort.SubmissionResult` uses a nullable native identity with a classification-dependent constructor check. [`EvidenceReferencePort.registerAcceptance`](../../application/src/main/java/io/github/johnwhitton/digitalbanking/application/port/EvidenceReferencePort.java) is invoked by the aggregate supplier inside `OperationRepository.accept`'s `READ_COMMITTED` transaction; the current bean only creates a local UUID, but the port does not state that implementations must avoid I/O or external effects. `PolicyApprovalPort`, `EvidenceReferencePort`, [`SignerPort`](../../application/src/main/java/io/github/johnwhitton/digitalbanking/application/port/SignerPort.java), and [`ChainPort`](../../application/src/main/java/io/github/johnwhitton/digitalbanking/application/port/ChainPort.java) lack complete documentation of authority, idempotency, failure, retry, transaction, and implementation expectations.

**Impact.** The current evidence implementation is local and no policy, signer, or chain provider consumes the other seams, so there is no current external-effect defect. Once a worker or provider-backed implementation uses them, boolean/nullable outcomes, interchangeable policy strings, or an I/O-capable acceptance-evidence implementation can create invalid combinations, inconsistent translations, or an external effect inside the acceptance transaction.

**Bounded recommendation.** Before first executable use, replace boolean/nullable multi-outcome shapes with an enum, sealed result, or explicit optional/variant whose states cannot contradict one another; introduce a typed `PolicyVersion` when real policy/finality semantics begin. State that acceptance evidence generation is deterministic/local and performs no I/O or move any provider-backed registration outside the database transaction. Add concise semantic Javadoc and contract tests for each permitted and forbidden outcome. Preserve native Ethereum/Solana differences in adapter-owned types rather than expanding a lowest-common-denominator shared enum.

### Advisory A-03: two completed action-plan closeout rows are stale

**Evidence.** At the audited baseline, [`AI_ASSISTED_ENGINEERING_TOOLCHAIN.md`](../plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md) still reports `ready_for_commit`, and [`ZELLE_SHARE_READINESS_AND_TRANSFER_ROADMAP.md`](../plans/active/ZELLE_SHARE_READINESS_AND_TRANSFER_ROADMAP.md) still reports `in_progress` with commit/push/remote verification remaining, although the baseline history and later action start conditions show both actions were committed and pushed.

**Impact.** Current README/design/implementation capability claims remain accurate. The stale closeout metadata can nevertheless mislead a restart or audit about whether Git closeout evidence remains outstanding.

**Bounded recommendation.** Use one later documentation-only hygiene action to reconcile completed active-plan closeout states with immutable commit/push evidence. Do not rewrite their technical evidence or combine that cleanup with production changes.

## 7. File-size and complexity hotspots

Only one production file exceeds the 500-line extraction-review prompt: `PostgresOperationRepository.java` at 864 lines. The direct responsibility analysis supporting A-01 is described above. No other production file exceeds 500 lines, and no defect was inferred from line count.

`TokenOperation.java` is the next largest at 355 lines. Its transition table, history validation, retry guard, finality rules, and immutable reconstruction form one cohesive aggregate responsibility, so extraction is not recommended now. `SignerPort.java` at 236 lines and `ChainPort.java` at 179 lines are type-rich provider-neutral contracts; their future concern is semantic shape/documentation (A-02), not current file size.

The migration is 268 lines and intentionally keeps the initial schema visible in one forward migration. Its explicit constraints and table ownership remain reviewable; splitting an already-applied migration would be incorrect.

## 8. Test-quality and failure-path coverage

The test design is strong for the implemented scope:

- 246 domain cases include the complete allowed/forbidden lifecycle matrix, quantity boundaries, time/version guards, terminal behavior, attempt lineage, ambiguity, and four-finality independence.
- 23 application tests cover canonical digest stability/binding, participant/tenant/kind idempotency scope, replay/conflict, service coordination, redaction, provider-neutral byte contracts, and participant-scoped lookup without Spring or external infrastructure.
- 14 persistence tests use real pinned PostgreSQL and cover empty migration, exact 512-digit quantity round trip, complete aggregate reconstruction, optimistic conflict, atomic acceptance, replay/conflict, forced rollback, uniqueness, deterministic two-thread contention, participant scope, and fresh-pool restart.
- 19 control-plane tests cover context/health, three OpenAPI contract areas, response evidence projection, authentication, distinct authorities, validation/media, accepted replay/conflict, participant non-disclosure, runtime contract delivery, redaction, and database-unavailable 503 behavior.

No arbitrary sleep appears in the test sources. Concurrency tests use barriers, latches, and explicit timeouts. Persistence assertions compare the whole reconstructed aggregate rather than selected getters. Fixtures are synthetic and use explicit fixture-only credentials for local containers.

At the audited baseline, the material gaps were narrow and tied to I-01 and I-02: response tests did not reject internal actor/reason/authority/policy output, OpenAPI tests did not recursively validate nested response records or the accepted example, and API tests did not distinguish caller `IllegalArgumentException` from internal invariant/mapping failure. Action Request 07 adds each missing boundary test and its bounded correction; the final focused post-audit gate ran 25 selected tests across application and control-plane (5 + 20) with zero failures, errors, or skips. The audit's full Maven run also emitted one test-compilation deprecation warning for `TokenOperationApiIntegrationTest`; the build does not identify the deprecated symbol under current compiler settings. A focused lint evaluation remains a separate advisory and does not justify installing a new lint plugin.

## 9. Dependency and framework-leakage assessment

Source imports, POMs, Enforcer, dependency tree, and direct `jdeps` agree:

- Domain binary dependencies resolve to `java.base` only.
- Application resolves to domain and `java.base` at runtime; JUnit is test scope.
- Persistence directly resolves inward modules, `java.base`, `java.sql`, Spring JDBC, and Spring transactions.
- Control-plane directly resolves inward modules plus Jakarta validation and the approved Spring Boot/web/security/configuration stack.
- No ORM/JPA, Spring Data, R2DBC, jOOQ, messaging runtime, workflow engine, Web3j, Solana SDK, signer/custody SDK, or native-chain artifact appears.

JDBC row objects, Spring transactions, HTTP `ProblemDetail`, security principals, and validation annotations stop at their adapter/control-plane boundaries. Domain and application signatures remain framework-free. The Maven application dependency-tree command reports the expected reactor-artifact POM warning because the exact command does not use `-am` and reactor outputs were packaged rather than installed; it still resolves the declared tree and Enforcer passes. No dependency or build correction is indicated.

## 10. Security, API, SQL, transaction, idempotency, outbox, and concurrency assessment

At the audited baseline, security and API behavior passed for the implemented acceptance/read-back slice except I-01 and I-02. Action Request 07 resolves both: principal-derived scope, distinct authorities, deny-by-default paths, safe 404 equivalence, key/digest redaction, no default identity credential, and no public-network configuration remain supported, while the participant projection is minimized and unexpected internal invariant failures receive a distinct safe 500 classification.

SQL and transaction behavior pass. Statements use parameters and explicit mappings; the only dynamic table-name SQL appears in a test helper with a hardcoded allowlist. The repository's dynamic participant predicate is a fixed internal SQL fragment, not untrusted input. There is no `SELECT *`. Acceptance and outbox insertion share one explicit `READ_COMMITTED` transaction; no network, signing, messaging, or chain call occurs inside it.

Idempotency scope binds tenant, participant, resource, and operation kind to a hashed 1-128-character visible-ASCII key and versioned canonical command digest. Replay returns the committed operation and adds no outbox row; digest conflict adds no rows and does not expose the key. Database uniqueness and deterministic races make one winner authoritative.

The outbox is correctly described as pending scaffolding for at-least-once delivery. There is no publisher, inbox, retry loop, scheduler, or exactly-once claim. Aggregate saves use optimistic version comparison and append event/history deltas. Rollback, restart, and full reconstruction evidence support recovery at the current boundary.

## 11. Documentation and implementation consistency

At the audited baseline, README, `docs/DESIGN.md`, and `docs/IMPLEMENTATION.md` accurately distinguished the verified Phase 3A API from scaffolded ports and planned Phase 3B/later work. The documented 302-test gate matched that audit's fresh offline run. The module map, PostgreSQL/JDBC/Flyway choice, security default, participant-scoped resources, pending outbox, four finalities, absence of external effects, and non-production limitations matched source and tests; I-01 recorded the narrower OpenAPI accepted-example drift and nested projection gap.

Accepted ADRs 0001-0004 match the reactor, chain deferrals, and persistence approach. No undocumented dependency or architectural decision was found. The implementation does not claim that acceptance, outbox insertion, signer/chain port presence, or a native identity constitutes processing or settlement.

Action Request 07 resolves I-01's accepted-example drift without altering the capability boundary. Advisory A-03's stale closeout rows remain a separate documentation-only follow-up.

## 12. Conclusion and ordered follow-up actions

The bounded audit found **0 Critical, 2 Important, and 3 Advisory** findings. Architecture, core modeling, exactness, persistence transactions, idempotency, outbox atomicity, deterministic concurrency, dependency direction, and most API/security behavior had strong direct evidence. The result is not a certification. Action Request 07 resolves both Important boundary findings before Phase 3B; the three Advisory findings remain scoped as below.

### Completed corrections before Phase 3B

1. **Participant-safe status projection and conformance (I-01) - resolved by Action Request 07.** The participant representation omits internal actor/reason/authority/policy values; the accepted example and recursive nested OpenAPI shape are executable tests.
2. **Internal-versus-client failure classification (I-02) - resolved by Action Request 07.** Caller validation is explicit, persistence/application invariant failure remains unexpected, and the stable redacted internal-error contract is tested.

The audit itself made no executable change; the separately approved [`PHASE_3A_API_BOUNDARY_CORRECTIONS.md`](../plans/active/PHASE_3A_API_BOUNDARY_CORRECTIONS.md) action owns their RED-GREEN implementation and verification evidence.

### Improvements that may accompany Phase 3B

3. **Repository extraction review (A-01).** If Phase 3B changes `PostgresOperationRepository`, extract the smallest stable loader/replay or append/event responsibility while retaining explicit transaction ownership.
4. **Policy/evidence contracts (part of A-02).** If Phase 3B first consumes `PolicyApprovalPort` or changes acceptance evidence generation, replace the boolean decision with a structured outcome, introduce typed policy versioning when it becomes real, and enforce a no-I/O acceptance-evidence contract or move provider work outside the transaction.

### Future signer and chain gates

5. **Port semantics (remainder of A-02).** Before a signer or chain adapter exists, document authority/idempotency/failure/transaction expectations and replace nullable/contradictory result combinations with explicit variants. Keep native Ethereum and Solana semantics adapter-owned.
6. Apply the signer, nonce/replacement/reorg, blockhash/lifetime/commitment, independent observation, ambiguity, and reconciliation standards only in separately approved vertical slices. They remain `not_applicable` to executable Phase 3A.

### Documentation hygiene

7. Reconcile the two stale active-plan closeout rows in one documentation-only action (A-03), using actual commit/push evidence.

### Optional tooling evaluations

8. During the next relevant test/build change, identify the observed deprecation warning with a focused compiler-lint run and decide whether replacement or a time-bounded suppression is warranted.
9. Do not add formatting, Checkstyle, SpotBugs, Error Prone, ArchUnit, mutation testing, or coverage thresholds now. Enforcer plus `jdeps` already prove the observed module risk, and the two material gaps need direct behavioral tests rather than another tool. Reconsider a tool only when a specific recurring defect pattern supplies a bounded evaluation target.
