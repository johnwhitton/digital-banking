# Digital Banking Implementation Plan and Current State

## Purpose

This is the living delivery plan and current-state record. It turns `docs/DESIGN.md` into dependency-aware, evidence-gated slices. Update it whenever module layout, dependencies, validation commands, status, sequencing, or the recommended next slice changes.

## Status vocabulary

| Status        | Meaning                                                                            |
| ------------- | ---------------------------------------------------------------------------------- |
| `not_started` | No implementation has begun; design may still contain open questions.              |
| `in_progress` | Work is active but has not passed its acceptance gate.                             |
| `blocked`     | A named external input, authority, tool, or decision prevents meaningful progress. |
| `implemented` | The scoped behavior exists and focused tests pass; broader phase gates may remain. |
| `verified`    | All phase acceptance commands and evidence have been run, inspected, and recorded. |

`planned` and `scaffolded` may appear in the README's capability view, but phase execution uses the vocabulary above.

## Repository baseline discovered on 2026-07-16

- Target: `/Users/johnwhitton/dev/johnwhitton/digital-banking`.
- Starting branch/SHA: `main` at `2e7ec1f0fe700de3b7072b7995371b4727b2c535`.
- Starting state: clean worktree with one tracked `README.md` containing `# settlement`.
- Remote: `git@github.com:johnwhitton/digital-banking.git`; live `origin/HEAD` was `main` at the same SHA.
- Toolchain: Homebrew OpenJDK 25.0.2 existed at `/opt/homebrew/opt/openjdk` but was not linked onto `PATH`; Maven and Gradle were absent; Docker 28.5.1 and Compose 2.40.3 were available.
- Framework evidence: Spring Boot 4.0.6 is the requested baseline and was available from Spring Initializr/Maven Central; it manages Spring Framework 7.0.x.
- Build decision: Maven Wrapper 3.3.4 with Apache Maven 3.9.16; see [ADR 0001](adr/0001-maven-reactor-and-module-boundaries.md).
- Revised source baseline: commit `84b2ff350639f537adddd2fc1695e09bae5375b4` supplied both exact PDFs. They are now normalized under `docs/reference/`, byte-compared with the source blobs, rendered for visual review, and indexed with SHA-256 provenance.
- Read-only tooling source: Salus `main` at `fd9ffaf0d569ebaa232575d143e00488d31a2974`; no Salus file was changed.

## Bootstrap action: actual scope

Foundation creates:

- repository purpose, architecture, security, contribution, ADR, plan, and reference documentation;
- immutable source PDFs and a reference index with exact metadata, normalized paths, source-commit provenance, and verified SHA-256 values;
- a Maven reactor with dependency-free `domain` and Spring Boot `control-plane` modules;
- a committed wrapper, Java 25 / Spring Boot 4.0.6 baseline, and domain dependency guard;
- Spring application context and health/readiness verification only;
- repository-local Codex config, valid empty hooks, reusable prompt templates, and focused workflow skills; and
- hygiene rules for generated files, secrets, line endings, and build output.

Foundation intentionally does not create mint/burn endpoints, domain lifecycle behavior, persistence, OpenAPI, signers, Web3j, Solana SDKs, Solidity, Rust, Compose, mainnet/testnet configuration, or production claims.

## Current phase status

| Phase                                  | Status        | Evidence summary                                                                               |
| -------------------------------------- | ------------- | ---------------------------------------------------------------------------------------------- |
| 1. Foundation                          | `verified`    | Source publications and the full foundation gate are verified; see the closed bootstrap plan. |
| 2. Domain and operation lifecycle      | `in_progress` | The active Phase 2 plan owns exact quantities, lifecycle, idempotency, ports, and pure tests.   |
| 3. Durable API and persistence         | `not_started` | No API/database dependency or contract exists.                                                 |
| 4. Signing boundary                    | `not_started` | Design only; no signer code or keys.                                                           |
| 5. Ethereum vertical slice             | `not_started` | No Web3j/Solidity/local-chain code.                                                            |
| 6. Solana vertical slice               | `not_started` | No Java SDK/Rust/local-validator code.                                                         |
| 7. Observation and reconciliation      | `not_started` | Design only.                                                                                   |
| 8. Integrated local environment        | `not_started` | No Compose file.                                                                               |
| 9. Hardening and publication readiness | `not_started` | No production-readiness claim.                                                                 |

## Phase 1: Foundation

**Deliverables:** repository policy; architecture and delivery docs; immutable source references and index; ADR/plan system; `.codex` operating model; Maven wrapper/reactor; plain domain boundary; Spring application; health/readiness; context and boundary tests.

**Tests and validation:** wrapper version; reactor clean verify; targeted Spring tests; domain Enforcer rule; PDF SHA-256 and byte comparison; Markdown links; skill metadata/references; hooks JSON; stale-reference/secret search; diff and Git status checks.

**Acceptance gate:** application context loads, `/actuator/health/readiness` is `UP`, only health is exposed, domain has no forbidden dependencies, exact supplied references are byte-identical when available, docs and status claims agree, and the active plan records fresh evidence.

**Risks:** local JDK not on `PATH`; first wrapper run needs network; installed Codex discovers repo skills under `.agents/skills` rather than the requested `.codex/skills`; immutable publication provenance must remain intact.

**Disposition:** set `JAVA_HOME` explicitly; commit wrapper; expose `.codex/skills` through native discovery compatibility entries; preserve the supplied PDFs byte-for-byte under `docs/reference/`.

**Deferred:** all business behavior and external infrastructure.

## Phase 2: Domain and operation lifecycle

**Deliverables:**

- exact `AssetUnit` and `TokenQuantity` types with canonical string serialization and bounds;
- `MintCommand` and `BurnCommand` with versioned canonical payloads;
- scoped idempotency key and payload-hash contract;
- stable `OperationId` and `AttemptId` types;
- token-operation aggregate and explicit transition guards;
- policy, approval, signer, chain, clock, ID, and evidence ports expressed in plain Java; and
- pure unit/property tests with no Spring context.

**Tests:** excess precision, negative/zero/boundary quantities, overflow, unit mismatch, serialization round-trip, same-key/same-hash replay, same-key/different-hash conflict, every allowed/forbidden state transition, ambiguous outcome rules, attempt lineage, and distinct finality records.

**Acceptance gate:** `domain` remains dependency-free, all transitions are only reachable through invariant-enforcing methods, no `double`/`float` or framework/native type exists, and pure tests prove the lifecycle independent of a chain.

**Risks:** premature database/JSON choices; over-generic chain port; conflating operation completion with all finalities.

**Deferred:** Spring API, SQL implementation, actual signing, and native adapters.

## Phase 3: Durable API and persistence

**Deliverables:** versioned OpenAPI; Spring mint/burn create and operation-status resources; relational schema/migrations; durable idempotency; optimistic concurrency; operation/attempt/evidence repositories; transactional acceptance; outbox foundation; worker lease/timer mechanics; safe problem responses.

**Tests:** OpenAPI/implementation contract, HTTP validation, 202/Location behavior, replay/conflict, parallel duplicate requests, rollback, uniqueness, version conflicts, worker recovery, process restart, outbox atomicity, and sensitive-field redaction.

**Acceptance gate:** an accepted command is durable before response, duplicates cannot create a second operation, no external effect occurs inside the acceptance transaction, and restart/concurrency tests prove recovery.

**Risks:** transaction leaks, database-specific coupling, exactly-once claims, status exposure of sensitive evidence.

**Deferred:** broker topology, full ledger, chain execution.

## Phase 4: Signing boundary

**Deliverables:** exact signing request/decision/evidence contracts; policy/approval binding; signer request idempotency; isolated local-development signer or deterministic test double; interfaces for HSM/MPC/custody providers; no vendor selection.

**Tests:** exact digest/bytes binding, mismatched amount/destination/chain rejection, allowlists, limits, quorum, expiry, repeated request identity, timeout ambiguity, redaction, and proof that raw keys never cross the port.

**Acceptance gate:** application code can authorize and record a signer decision without owning production key material; local signer is impossible to enable through production configuration.

**Risks:** treating signing as business authorization; mutable approval payloads; test keys escaping fixtures.

**Deferred:** production provider integration and recovery ceremony.

## Phase 5: Ethereum vertical slice

**Dependency:** phases 2-4 verified. Build this first, then review the common ports before beginning Solana.

**Deliverables:** execute [ADR 0002](adr/0002-evm-foundry-and-web3j.md); use Foundry (`forge`, `anvil`, `cast`, and scripts) as the sole contract toolchain; add a minimal reviewed Solidity token/authority contract only if required; add a Web3j adapter; prove deterministic encoding, nonce/replacement lineage, event/receipt observation, independent inquiry, and mint/burn happy, rejection, revert, timeout, ambiguity, replacement, and reorg/canonicality paths.

**Tests:** golden encoding, chain ID/nonce/destination/amount binding, signer digest, submit-once, response loss, inquiry, replacement rules, failed receipt, event effect, canonicality change, duplicate request, and reconciliation evidence.

**Acceptance gate:** local mint/burn effects are authorized, observable, and reconcilable by stable IDs; ambiguous submission never blind-resubmits; no Web3j/native model leaks into domain.

**Risks:** unsafe admin/upgrade authority, fake finality, submit/observe provider coupling, fixture keys presented as production patterns.

**Deferred:** public networks, production contracts, provider/custody selection.

## Phase 6: Solana vertical slice

**Dependency:** phases 2-4 verified and the first chain slice reviewed for common-port changes.

**Deliverables:** execute [ADR 0003](adr/0003-native-solana-spl-token.md); run the bounded Sava evaluation; use native SVM semantics and classic SPL Token on a local validator; add a Java adapter only after the client gate passes; define recent-blockhash/durable-nonce and lifetime policy, instruction/account evidence, commitment observation, and mint/burn happy and failure paths. Add a pinned Rust/Anchor program only if existing programs cannot safely express required business logic. Neon is excluded from this baseline.

**Tests:** deterministic message/instruction accounts, authority and amount binding, blockhash expiry, same-signed-transaction resend versus new-blockhash attempt, instruction error, signature/slot/commitment progression, provider disagreement, duplicate request, and reconciliation evidence.

**Acceptance gate:** local effects are authorized and reconcilable; lifetime and commitment semantics remain explicit; Java SDK/Rust types do not leak into domain; Rust is absent if existing programs suffice.

**Risks:** unmaintained SDK, unnecessary custom program, erasing Solana semantics to match EVM, upgrade authority ambiguity.

**Deferred:** public clusters, production program deployment, vendor selection.

Direct issuer-authority mint/burn remains distinct from Circle CCTP. A future CCTP cross-chain workflow requires its own operation semantics, evidence, and decision gate.

## Phase 7: Observation and reconciliation

**Deliverables:** independent observer ports/adapters; versioned native evidence store; canonicality/commitment policy; operation/signer/chain/inventory reconciliation; ambiguous outcome recovery; break/case model; authorized repair and append-only evidence.

**Tests:** submit provider unavailable, observer disagreement, reorg/commitment regression, signer/chain digest mismatch, supply/account mismatch, delayed data, duplicate observations, rerunnable reconciliation, break aging, and repair authorization.

**Acceptance gate:** every external effect can be located from stable internal IDs and independently observed; unresolved disagreement blocks unsafe progression; repair preserves original history.

**Risks:** "independent" sources sharing a failure domain, silent tolerance, mutable evidence, repair becoming a bypass.

**Deferred:** enterprise case tooling and full accounting-close integration.

## Phase 8: Integrated local environment

**Deliverables:** Docker Compose where practical; API/database/local-chain processes; deterministic fixtures; development signer; readiness/dependency ordering; end-to-end tests; failure injection; operator runbooks.

**Tests:** clean startup, restart, dependency outage/recovery, duplicate delivery, timeouts, chain reset/reorg/expiry, reconciliation rerun, deterministic teardown, and no secret/public-network access.

**Acceptance gate:** one command produces a deterministic local environment, end-to-end tests prove both happy and failure paths, and runbooks match executed commands.

**Risks:** Compose hiding flaky dependencies, slow nondeterministic tests, build artifacts or fixture secrets committed.

**Deferred:** cloud/CI deployment and production infrastructure.

## Phase 9: Hardening and publication readiness

**Deliverables:** threat review; dependency/SBOM and vulnerability evidence; contract/program independent review; authorization/recovery drills; performance and failure budgets; backup/restore; documentation/link verification; deterministic demo script; explicit limitations and exit plan.

**Tests:** abuse cases, compromised provider/signing scenarios, limit and kill-switch drills, dependency outage, data restore, reconciliation control totals, load/latency tests, and complete clean-room demo.

**Acceptance gate:** evidence shows the bounded demo is reproducible and fails safely. This gate still does not authorize production use or claim certification.

**Risks:** publication language exceeding evidence; security tooling treated as certification; scope expansion across asset/network/corridor/authority at once.

**Deferred:** any production, regulatory, legal, vendor, or mainnet decision.

## Plans and ADRs

- Closed foundation plan: [`docs/plans/active/BOOTSTRAP.md`](plans/active/BOOTSTRAP.md).
- Active Phase 2 plan: [`docs/plans/active/DOMAIN_OPERATION_LIFECYCLE.md`](plans/active/DOMAIN_OPERATION_LIFECYCLE.md).
- ADR process and index: [`docs/adr/README.md`](adr/README.md).
- Accepted build/module choice: [`ADR 0001`](adr/0001-maven-reactor-and-module-boundaries.md).
- Accepted EVM approach: [`ADR 0002`](adr/0002-evm-foundry-and-web3j.md).
- Accepted Solana approach: [`ADR 0003`](adr/0003-native-solana-spl-token.md).

Create or update an active plan before implementation. Create an ADR only when evidence requires an accepted material decision.

## Current validation commands

These commands are the verified foundation entry points on the bootstrap workstation:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl control-plane -am -Dtest=DigitalBankingApplicationTests,HealthReadinessSmokeTests -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain enforcer:enforce
```

Reference, documentation, skill/hook, stale-reference, diff, and Git commands are recorded with results in the active bootstrap plan. Add commands here only after they are stable contributor entry points.

## Current bounded vertical slice

Action Request 02 implements **Domain and Operation Lifecycle** only:

- exact asset/unit and token quantity types;
- mint and burn commands;
- scoped idempotency and canonical payload hashing;
- operation and attempt identity;
- explicit operation state transitions including ambiguous submission and manual review;
- distinct finality records;
- provider-neutral chain and signer ports; and
- pure Java tests proving invariants without Spring, persistence, Web3j, Solana SDKs, Solidity, Rust, or Compose.

The slice ends with a review of whether the common ports preserve enough native-semantic room. It does not implement either chain adapter. After it is verified, Phase 3 durable API and persistence is the next recommended slice; chain work still waits for the Phase 4 signing boundary.
