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
- repository-local Codex config, structurally valid reviewed hooks, reusable prompt templates, focused workflow skills, and portable Graphify navigation artifacts; and
- hygiene rules for generated files, secrets, line endings, and build output.

Foundation intentionally does not create mint/burn endpoints, domain lifecycle behavior, persistence, OpenAPI, signers, Web3j, Solana SDKs, Solidity, Rust, Compose, mainnet/testnet configuration, or production claims.

## Current phase status

| Phase                                  | Status        | Evidence summary                                                                               |
| -------------------------------------- | ------------- | ---------------------------------------------------------------------------------------------- |
| 1. Foundation                          | `verified`    | Source publications and the full foundation gate are verified; see the closed bootstrap plan. |
| 2. Domain and operation lifecycle      | `verified`    | Exact quantities, guarded lifecycle/finality histories, canonical commands, idempotent acceptance contracts, and bound ports passed 264 pure tests and the 266-test reactor. |
| 3. Durable API and persistence         | `verified`    | Phase 3A acceptance/read-back, Phase 3B worker/recovery, and Phase 3C transfer acceptance/first deduplicated internal preparation pass the 364-test offline reactor. External workflow effects remain absent by scope. |
| 4. Signing boundary                    | `implemented` | Phase 4A provides durable authority/evidence; Phase 4B adds explicit-profile, session-ephemeral secp256k1/Ed25519 signing. Production custody remains absent. |
| 5. Ethereum vertical slice             | `implemented` | Phase 5A proves one accepted mint on local Anvil through durable nonce/attempt, signing, submit-once ambiguity recovery, and independent receipt/event/canonicality observation. Transfer, burn, replacement, and production integration remain absent. |
| 6. Solana vertical slice               | `not_started` | No Java SDK/Rust/local-validator code.                                                         |
| 7. Observation and reconciliation      | `not_started` | Design only.                                                                                   |
| 8. Integrated local environment        | `not_started` | No Compose file.                                                                               |
| 9. Hardening and publication readiness | `not_started` | No production-readiness claim.                                                                 |

The current executable boundary includes Phase 3C transfer acceptance, Phase 4A's durable signing-authority use case, Phase 4B's isolated local-development provider, and Phase 5A's bounded local-Anvil mint. The default runtime has no signer and no chain client. Profiles `local-signer` plus `local-ethereum` compose the mint handler, require explicit loopback/local-chain configuration, and expose no new public endpoint. The complete [bank-to-bank transfer demonstration](TRANSFER_DEMO.md) remains future work; Phase 5A is not wired to its five effects. Every later effect/provider/chain slice requires its own focused active plan.

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
- operation/attempt repository, policy/approval, signer, chain, clock, ID, and evidence ports expressed in plain Java; and
- pure unit tests with no Spring context.

**Tests:** excess precision, negative/zero/boundary quantities, overflow, unit mismatch, serialization round-trip, NFC normalization/malformed Unicode, same-key/same-version/hash replay, canonical-version/digest conflict, retained acceptance context, every allowed/forbidden state transition, attempt prerequisites and lineage, ambiguous outcome rules, finality status/time rules, exact signer digest/effect/lifetime binding, rejected-payload safety, and stable chain inquiry/observation correlation.

**Acceptance gate:** `domain` has no runtime dependency, `application` has only `domain` at runtime, all transitions are reachable only through invariant-enforcing methods, no `double`/`float` or framework/native type exists, and pure tests prove the lifecycle independent of a chain. The repository acceptance contract does not claim persistence until Phase 3 supplies a durable implementation.

**Risks:** premature database/JSON choices; over-generic chain port; conflating operation completion with all finalities.

**Deferred:** Spring API, SQL implementation, actual signing, and native adapters.

## Phase 3: Durable API and persistence

### Phase 3A: durable acceptance and read-back

**Implemented deliverables:** one design-first OpenAPI 3.1 contract; secured Spring mint/burn create and participant-scoped operation-status resources; a versioned participant projection that omits internal transition actor/reason and finality authority/policy strings; stateless deny-by-default security and safe RFC 9457 problems including a redacted internal-error boundary; PostgreSQL schema/Flyway migration; explicit JDBC repository; durable hashed idempotency; optimistic concurrency; normalized operation/attempt/transition/finality/evidence storage; one atomic pending acceptance-outbox record; and production composition with no in-memory fallback. See [ADR 0004](adr/0004-postgresql-jdbc-flyway-atomic-outbox.md).

**Tests:** OpenAPI parsing, recursive nested-record conformance, and executable accepted-example equality; 401/403 boundaries; HTTP validation and expected problem classification; 202/Location behavior; replay/conflict; principal-derived participant scope despite request-controlled participant fields/parameters/headers; missing/cross-participant 404 equivalence; participant-status field minimization; safe internal invariant and database failures; empty migration; schema/index/quantity constraints; complete aggregate replay; optimistic conflict; parallel duplicate/conflicting requests without retries; rollback; uniqueness; outbox atomicity; process restart; and sensitive-field absence.

**Acceptance gate:** an accepted command is committed before HTTP 202; duplicates cannot create a second operation or outbox row; conflicts leave no partial state; no external effect occurs inside the acceptance transaction; and real PostgreSQL restart/concurrency tests prove durable read-back.

**Current limitation:** the repository configures no real identity adapter, issuer, token decoder, local user, password, or static credential. Business endpoints deny by default; tests use fixture-only `ParticipantPrincipal` authentication and authorities.

### Phase 3B: asynchronous worker and delivery recovery

**Verified deliverables:** framework-free delivery identity/outcome/queue/handler contracts, deterministic bounded retry policy, and a non-overlapping worker use case; one forward-only `V2` migration for outbox delivery state and append-only attempt history; explicit PostgreSQL `READ_COMMITTED`/`FOR UPDATE SKIP LOCKED` claim, lease, fenced acknowledgement/retry/manual-review, measurement, and expired-lease recovery mechanics; and a typed Spring-managed polling lifecycle with existing Micrometer observability. See [ADR 0005](adr/0005-postgresql-operation-delivery-worker.md).

The worker is disabled by default. Enabling requires an explicit visible-ASCII worker identity, a real `OperationDeliveryHandler`, and bounded durations of at least one microsecond to match database timestamp precision; startup fails rather than wiring a no-op consumer. It claims and commits durable delivery intent before invoking the handler and never holds the claim transaction across handler work.

Delivery is at least once. Lease expiry or post-handler/pre-acknowledgement loss can redeliver the same stable outbox `event_id`. Phase 3C applies this rule to `TransferAccepted`: its handler persists the delivery identity with the first-withdrawal preparation transition and returns the durable duplicate result on redelivery. Other future consumers must provide the same atomic inbox/effect proof.

Explicit handler outcomes distinguish delivered, duplicate, retryable no-effect, terminal no-effect, and ambiguous acknowledgement. Unexpected exceptions are retained only as safe internal class diagnostics and a stable ambiguous failure code. Retries use durable deterministic bounded backoff; terminal/exhausted work and all attempt evidence are retained for manual review. Stale owners cannot overwrite a replacement lease, and earlier unresolved work blocks later work for the same operation without serializing unrelated operations.

Implemented measurements are eligible work, active leases, oldest eligible age, claims, deliveries, duplicates/redeliveries, retries, manual review/exhaustion, lease recovery, stale updates, and overlap suppression. They have no operation/participant/idempotency/address/payload/error labels. Handler/business latency, full audit export, alerts, and production SLOs remain future work.

The database-backed Java/Spring worker is the accepted self-contained baseline, not a selection of an enterprise messaging or workflow product. Camunda, Temporal, Kafka, JMS, TIBCO EMS/BusinessWorks/BPM Enterprise, MuleSoft, and SAP remain unselected; any later dependency requires organizational evidence, a focused spike, and a superseding/additional ADR while preserving the application-owned contracts.

**Tests:** deterministic retry/backoff/exhaustion and unexpected-exception ambiguity; scheduler overlap/config/lifecycle/metrics; real PostgreSQL two-worker exclusion, commit-before-handler lock release, success/no-redelivery, lease expiry, post-handler/pre-ack deduplication, durable retry eligibility, exhaustion, stale-owner fencing, cross-operation concurrency, same-operation ordering, forced rollback, fresh-pool restart recovery, empty migration, and existing API/readiness/security/redaction behavior.

**Acceptance gate:** the PostgreSQL baseline passes its deterministic delivery/recovery contract without adding a handler business effect, broker, workflow platform, signer, chain, transfer, or public network. Any real consumer must prove transactional deduplication for its own effect.

**Risks:** PostgreSQL-specific queue coupling, handler work exceeding its lease, incorrect future consumer deduplication, exactly-once overclaims, and configuration/SLO choices not yet production-tested. Expiry makes a late owner stale rather than authoritative; it does not cancel a possible handler effect.

**Deferred:** other consumer transitions, broker/workflow topology, full ledger, effect execution, signing, chain execution, observation, reconciliation, and settlement.

### Phase 3C: chain-neutral transfer aggregate and mock-bank boundary

**Dependency:** Phase 3B worker/recovery mechanics are verified before this slice performs or coordinates any child effect.

**Verified deliverables:** one framework-free `Transfer` aggregate with five stable ordered effects; `POST /v1/transfers` and participant-scoped `GET /v1/transfers/{transferId}`; exact amount/currency and caller-command canonical idempotency; immutable server-resolved asset/unit, network, and opaque sender/recipient wallet context; normalized V3 parent/effect/history/finality/inbox persistence; `TransferAccepted` support in the existing outbox worker; a provider-neutral mock-bank port and deterministic synthetic test/local adapter; and a transactional handler that advances only the first withdrawal from `PLANNED` to `PREPARED`.

**Tests:** aggregate ordering/ambiguity guards/finality separation; route defaulting and allowlisting; server-owned wallet selection; mock-bank command/outcome/idempotency behavior; API/OpenAPI/security/minimization; exact replay/conflict and deterministic parallel duplicates; participant isolation; V1-V3 empty migration; parent/effect/history reconstruction through a fresh repository; outbox claim compatibility; inbox/preparation redelivery; and rollback of inbox plus transition/effect state.

**Acceptance gate:** the parent and every planned effect have stable durable identities; duplicate acceptance/delivery creates no duplicate transfer/effect/preparation; no external call occurs in acceptance or inbox transactions; caller fields cannot select custody wallets; chain children remain planned and no external value effect is claimed.

**Risks:** future attempt/evidence persistence beyond the preparation transition, hiding child ambiguity behind a parent status, treating synthetic adapters as production integrations, and beginning chain execution before signer controls.

**Deferred:** runtime mock-bank execution/inquiry, mint/transfer/burn/deposit execution, child token-operation acceptance, settlement-wallet provisioning, production signer/custody implementations, native chain effects, observation/reconciliation, compensation execution, and the complete five-step demonstrations.

Phases 4-9 below consume the relevant acceptance criteria in [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md). Each future phase or bounded sub-slice requires a focused active plan before implementation; this roadmap does not authorize combining both chains in one action.

## Phase 4: Signing boundary

**Phase 4A implemented deliverables:** framework-free typed signing request/attempt/provider/key identities; exact mint/transfer/burn authority context; distinct EVM `secp256k1` digest and Solana `Ed25519` serialized-message commands; versioned complete-context canonicalization; non-secret key-registry and policy ports; durable replay/conflict, provider-request-before-call, ambiguity inquiry, linked retry, append-only evidence, and optimistic concurrency; explicit JDBC/Flyway V4 persistence; and a deterministic synthetic signer in test sources only.

**Phase 4B implemented deliverables:** one executable `adapters/signer-local` module implementing the Phase 4A provider and key registry; Bouncy Castle 1.82 confined to real exact-digest secp256k1 signing with compact low-`s` recovery encoding; JDK-native exact-message Ed25519; two session-only in-memory keys with opaque aliases/versions, public fingerprints, typed role/network allowlists, safe local evidence, and supported cleanup; and explicit Spring profile `local-signer` composition with no default bean, fallback, endpoint, or key source. See [ADR 0006](adr/0006-local-development-signing-provider.md).

**Tests:** Phase 4A defensive copies/redaction, complete-context binding, authorization, replay/conflict, ambiguity/inquiry/retry, V1-V4 persistence, concurrency, restart, and no-secret schema; plus Phase 4B exact EVM verification/no-double-hash/low-`s`/recovery, exact 64-byte Solana verification, length/mode/authority/session fencing, same-provider replay/conflict, no-signature and ambiguity fixtures, completed replay without re-signing, stale-pending manual review, explicit-profile/default-disabled/invalid-config behavior, warning, and no public signing mapping.

**Acceptance gate:** application code can authorize, durably invoke by stable identity, inquire, and record signer evidence for the exact child operation/attempt. The explicit local profile can sign exact prebuilt material with disposable process-memory keys; the default runtime cannot. No key/seed/credential/key file is read or persisted, completed replay does not re-sign, stale pending work cannot use a replacement session key, and a signature advances no submission or financial finality.

**Risks:** treating signing as business authorization; presenting local keys as production custody; Java/provider memory not guaranteeing physical zeroization; using a local signature as execution/finality evidence.

**Deferred:** HSM/MPC/custody adapters and recovery ceremony, provider credentials, persistent keys, native transaction construction, submission, observation, and all production integration.

## Phase 5: Ethereum vertical slice

**Dependency:** phases 2-4 verified. Build this first, then review the common ports before beginning Solana.

**Phase 5A implemented deliverables:** Foundry 1.5.1 with Solidity 0.8.25 and pinned OpenZeppelin Contracts v5.6.1; one non-upgradeable two-decimal `LocalReferenceToken` with explicit admin and `MINTER_ROLE`; one isolated `adapters/ethereum-web3j` module using Web3j Core 4.14.0; deterministic EIP-1559 mint encoding and signer recovery; one forward-only V5 migration for local-chain nonce cursors, immutable mint/finality context, signature/submission evidence, detailed observations, and reconciliation disposition; one mint-only delivery handler and queue view reusing the Phase 3B and Phase 4 boundaries without claiming burn/transfer work; and explicit `local-ethereum` plus `local-signer` Spring composition. See [ADR 0007](adr/0007-local-ethereum-mint-vertical-slice.md).

**Phase 5A tests:** Foundry role/exact-mint/event/zero-address behavior; independent Cast/Web3j transaction vector, low-`s` and signer checks; real PostgreSQL V1-V5 migration and concurrent nonce allocation; mint-only queue isolation; and a real random-session Anvil path covering exact balance/supply/event, detailed durable observation evidence, full-operation concurrent nonces, a pre-send outage with zero transmitted bytes, post-acceptance response loss recovered by the precomputed hash without resubmission, changed restart configuration without policy drift, duplicate delivery, and unauthorized-minter revert/manual review. Default-context/readiness and local-only configuration tests remain green.

**Phase 5A acceptance gate:** an already-accepted mint can progress through durable authorization, signing, submission, and observation on chain `31337`; the adapter records stable nonce/hash/evidence, never blind-resubmits after ambiguity, verifies transaction plus exact mint event and canonical block, and advances only blockchain finality and narrow technical completion. Web3j/native models do not enter application/domain signatures. The public API and default runtime remain unchanged.

**Risks:** unsafe admin/upgrade authority, fake finality, submit/observe provider coupling, fixture keys presented as production patterns.

**Deferred:** Ethereum wallet transfer, burn, replacement/cancellation, longer-lived reorg monitoring, parent-transfer integration, public networks, production contracts/deployment/admin, and provider/custody selection.

## Phase 6: Solana vertical slice

**Dependency:** phases 2-4 verified and the first chain slice reviewed for common-port changes.

**Deliverables:** execute [ADR 0003](adr/0003-native-solana-spl-token.md) and the Solana portion of [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md); run the bounded Java-client evaluation; use native SVM semantics and classic SPL Token on a local validator; create the local SPL mint plus sender/recipient token accounts; add a Java adapter only after the client gate passes; define recent-blockhash/durable-nonce and lifetime policy, instruction/account evidence, commitment observation, and native mint/transfer/burn happy and failure paths. Add a pinned Rust/Anchor program only if existing programs cannot safely express required business logic. Neon is excluded from this baseline.

**Tests:** deterministic mint/account setup and mint/transfer/burn messages/instruction accounts, authority/source/destination/amount binding, blockhash expiry, same-signed-transaction resend versus new-blockhash attempt, instruction error, signature/slot/commitment progression, provider disagreement, duplicate request, and reconciliation evidence.

**Acceptance gate:** local native SPL mint/transfer/burn effects are authorized, independently observable, recoverable, and reconcilable; lifetime and commitment semantics remain explicit; Java SDK/Rust types do not leak into domain; Rust is absent if existing programs suffice. This phase proves chain effects, not the complete bank-to-bank demonstration.

**Risks:** unmaintained SDK, unnecessary custom program, erasing Solana semantics to match EVM, upgrade authority ambiguity.

**Deferred:** public clusters, production program deployment, vendor selection.

Direct issuer-authority mint/burn remains distinct from Circle CCTP. A future CCTP cross-chain workflow requires its own operation semantics, evidence, and decision gate.

## Phase 7: Observation and reconciliation

**Deliverables:** independent observer ports/adapters; versioned native and bank-effect evidence stores; canonicality/commitment policy; transfer/child/bank/signer/chain/inventory reconciliation; ambiguous outcome recovery; break/case model; authorized repair and append-only evidence.

**Tests:** submit provider unavailable, observer disagreement, reorg/commitment regression, signer/chain digest mismatch, supply/account mismatch, delayed data, duplicate observations, rerunnable reconciliation, break aging, and repair authorization.

**Acceptance gate:** every external effect can be located from stable internal IDs and independently observed; unresolved disagreement blocks unsafe progression; repair preserves original history.

**Risks:** "independent" sources sharing a failure domain, silent tolerance, mutable evidence, repair becoming a bypass.

**Deferred:** enterprise case tooling and full accounting-close integration.

## Phase 8: Integrated local environment

**Deliverables:** Docker Compose where practical; API/database/bank-mock/local-chain/observer processes; deterministic fixtures and disposable wallet authorities; development signer; readiness/dependency ordering; end-to-end tests; failure injection; operator runbooks; and two separately evidenced demonstrations from [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md): complete five-step Ethereum and complete five-step Solana.

**Tests:** clean startup; five-step Ethereum and Solana happy paths; restart at every bank/worker/signer/chain/observer boundary; duplicate delivery; bank and chain response loss; dependency outage/recovery; chain reset/reorg/expiry/commitment regression; reconciliation rerun; deterministic teardown; and no secret/public-network access.

**Acceptance gate:** one command produces a deterministic local environment; each chain's five-step flow is durably correlated, independently observable where applicable, idempotent under duplicate delivery, and recoverable across restart/timeout ambiguity; and clean-room runbooks match executed commands. The two demonstrations remain separate claims and imply no production/legal/accounting/compliance readiness.

**Risks:** Compose hiding flaky dependencies, slow nondeterministic tests, build artifacts or fixture secrets committed.

**Deferred:** cloud/CI deployment and production infrastructure.

## Phase 9: Hardening and publication readiness

**Deliverables:** threat review; dependency/SBOM and vulnerability evidence; contract/program independent review; authorization/recovery drills; performance and failure budgets; backup/restore; documentation/link verification; deterministic demo script; explicit limitations and exit plan.

**Tests:** abuse cases, compromised provider/signing scenarios, limit and kill-switch drills, dependency outage, data restore, reconciliation control totals, load/latency tests, and complete clean-room demo.

**Acceptance gate:** evidence shows the bounded demo is reproducible and fails safely. This gate still does not authorize production use or claim certification.

**Risks:** publication language exceeding evidence; security tooling treated as certification; scope expansion across asset/network/corridor/authority at once.

**Deferred:** any production, regulatory, legal, vendor, or mainnet decision.

## Publications

- **Volume II - [Digital Banking Engineering Companion](reference/digital-banking-engineering-companion.pdf)** (`published`): vendor-neutral implementation and operations guidance covering durable workflow, Java/Spring, wallets and signing, EVM, Solana, submission and observation, infrastructure, testing, performance, and delivery. It is not production certification or a runnable implementation. Its code-status discussion is pinned to `e921fcb1877b46a6881437f46b1a6ebfa115ae58`; use this current plan, the README, accepted ADRs, source, and tests for live repository status.
- **Volume III - [Digital Banking Reference Implementation](reference/digital-banking-reference-implementation.pdf)** (`published`): repository companion supplied as immutable contextual reference material. Current design, ADRs, contracts, source, tests, and this living plan remain authoritative for implementation status.

Publishing Volumes II and III does not change executable phase status, replace a focused implementation plan or acceptance test, complete Phase 9, or imply production readiness.

## Plans and ADRs

- Closed foundation plan: [`docs/plans/active/BOOTSTRAP.md`](plans/active/BOOTSTRAP.md).
- Completed Phase 2 plan: [`docs/plans/active/DOMAIN_OPERATION_LIFECYCLE.md`](plans/active/DOMAIN_OPERATION_LIFECYCLE.md).
- Verified Phase 3A plan: [`docs/plans/active/PHASE_3A_DURABLE_API_AND_PERSISTENCE.md`](plans/active/PHASE_3A_DURABLE_API_AND_PERSISTENCE.md).
- Active Phase 3B worker/recovery plan: [`docs/plans/active/PHASE_3B_DURABLE_WORKER_AND_RECOVERY.md`](plans/active/PHASE_3B_DURABLE_WORKER_AND_RECOVERY.md).
- Active Phase 3C transfer plan: [`docs/plans/active/PHASE_3C_TRANSFER_AGGREGATE_AND_MOCK_BANK.md`](plans/active/PHASE_3C_TRANSFER_AGGREGATE_AND_MOCK_BANK.md).
- Active Phase 4A signing-authority plan: [`docs/plans/active/PHASE_4A_SIGNING_AUTHORITY_BOUNDARY.md`](plans/active/PHASE_4A_SIGNING_AUTHORITY_BOUNDARY.md).
- Active Phase 4B local signer plan: [`docs/plans/active/PHASE_4B_LOCAL_DEVELOPMENT_SIGNER.md`](plans/active/PHASE_4B_LOCAL_DEVELOPMENT_SIGNER.md).
- Active Phase 5A local Ethereum mint plan: [`docs/plans/active/PHASE_5A_ETHEREUM_LOCAL_MINT_VERTICAL_SLICE.md`](plans/active/PHASE_5A_ETHEREUM_LOCAL_MINT_VERTICAL_SLICE.md).
- Active Zelle share-readiness and transfer-roadmap plan: [`docs/plans/active/ZELLE_SHARE_READINESS_AND_TRANSFER_ROADMAP.md`](plans/active/ZELLE_SHARE_READINESS_AND_TRANSFER_ROADMAP.md).
- ADR process and index: [`docs/adr/README.md`](adr/README.md).
- Accepted build/module choice: [`ADR 0001`](adr/0001-maven-reactor-and-module-boundaries.md).
- Accepted EVM approach: [`ADR 0002`](adr/0002-evm-foundry-and-web3j.md).
- Accepted Solana approach: [`ADR 0003`](adr/0003-native-solana-spl-token.md).
- Accepted PostgreSQL/JDBC/Flyway/atomic-outbox approach: [`ADR 0004`](adr/0004-postgresql-jdbc-flyway-atomic-outbox.md).
- Accepted PostgreSQL delivery-worker/lease-recovery approach: [`ADR 0005`](adr/0005-postgresql-operation-delivery-worker.md).
- Accepted local-development signing provider: [`ADR 0006`](adr/0006-local-development-signing-provider.md).
- Accepted local Ethereum mint realization: [`ADR 0007`](adr/0007-local-ethereum-mint-vertical-slice.md).

Create or update an active plan before implementation. Create an ADR only when evidence requires an accepted material decision.

## AI-assisted engineering workflow

The repository tracks Graphify's project-scoped skill, portable fail-open hook, scan policy, and the reviewed `graphify-out/GRAPH_REPORT.md`, `graph.json`, and `manifest.json`. Transient visualization, cache, query-memory, cost, reflection, and machine-state output remains ignored; immutable source PDFs are not indexed. Use the graph first for repository-navigation questions and validate its suggestions against authoritative source.

Ponytail and Superpowers are user-installed plugins, not repository dependencies or vendored content. Ponytail supplies explicit simplicity/YAGNI modes and the final over-engineering review. Superpowers supplies matching planning, TDD, debugging, review, and verification workflows. The user's approved request, repository policy/security/design, active plan, ADRs, focused repository skills, source, and tests take precedence over all three tools.

Contributor setup, reviewed update commands, exact hook-trust behavior, and evidence limitations are maintained in the README. The restartable implementation and review record for this integration is [`docs/plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md`](plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md). This workflow changes no product capability, phase status, module dependency, financial invariant, or next-slice recommendation.

## Implementation Standards

[`docs/IMPLEMENTATION_STANDARDS.md`](IMPLEMENTATION_STANDARDS.md) is the detailed normative authority for Java, Spring, persistence, API, asynchronous workflow, signer, chain, and test implementation. It requires inward dependency direction, immutable validated domain types, exact quantities, constructor injection, principal-derived scope, explicit participant-safe response projections, classified safe problems, parameterized JDBC, atomic acceptance and outbox, database-authoritative concurrency, attempt-before-effect sequencing, native adapter semantics, deterministic failure-path tests, and an executable present need for every dependency or abstraction.

The completed Phase 3A baseline was reviewed against those rules in [`docs/reviews/PHASE_3A_IMPLEMENTATION_STANDARDS_REVIEW.md`](reviews/PHASE_3A_IMPLEMENTATION_STANDARDS_REVIEW.md). Action Request 07 resolves its two required pre-Phase-3B findings. Action Request 09 then implements the database-authoritative claim/lease/retry/recovery boundary under the focused Phase 3B plan and ADR 0005 without changing the standards, API, domain lifecycle, dependency set, or external-effect boundary.

## Current validation commands

These commands are the verified foundation entry points on the bootstrap workstation:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl control-plane -am -Dtest=DigitalBankingApplicationTests,HealthReadinessSmokeTests -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain enforcer:enforce
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl application enforcer:enforce dependency:tree
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl adapters/persistence-postgres -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl control-plane -am test
(cd contracts/evm && forge test)
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/ethereum-web3j -am test
graphify --version
test -f .agents/skills/graphify/SKILL.md
jq -e '.hooks.PreToolUse[] | select(.matcher == "^Bash$")' .codex/hooks.json
```

Reference, documentation, skill/hook, stale-reference, diff, and Git commands are recorded with results in the active bootstrap plan. Add commands here only after they are stable contributor entry points.

## Latest bounded corrective slice

Action Request 07 corrects the Phase 3A API boundary without adding capability:

- participant responses no longer copy internal transition actor/reason or finality authority/policy values;
- the OpenAPI status schemas and accepted example match an executable response and nested record shapes recursively;
- caller-owned operation-ID, idempotency-key, quantity, and command validation uses an explicit application failure, while internal invariant failures are not reclassified as HTTP 400; and
- focused tests preserve acceptance, replay, conflict, distinct authorities, principal-derived scope, safe 404 equivalence, redaction, and durable read-back.

The restartable RED-GREEN and validation record is [`docs/plans/active/PHASE_3A_API_BOUNDARY_CORRECTIONS.md`](plans/active/PHASE_3A_API_BOUNDARY_CORRECTIONS.md). This correction adds no endpoint, dependency, migration, runtime configuration, worker, external effect, signer, chain adapter, or production-readiness claim.

## Latest bounded vertical slice

Action Request 13 implements **Phase 5A Local Ethereum Mint Vertical Slice**:

- a Foundry project pinned to Solidity 0.8.25 and OpenZeppelin Contracts v5.6.1, with a minimal role-gated, two-decimal, non-upgradeable local reference token;
- a Web3j 4.14.0 adapter that owns ABI/EIP-1559 encoding, signer recovery, nonce/hash/native evidence, RPC submission/inquiry, and receipt/event/canonicality interpretation;
- PostgreSQL V5 nonce, immutable attempt, signature/submission, observation, and reconciliation records with short transaction scopes and attempt-before-effect fencing;
- a mint-only application handler that reuses accepted operation, delivery, signing-authority, and finality models without adding an endpoint or transfer path;
- explicit `local-ethereum` plus `local-signer` composition fenced to uncredentialed loopback HTTP and chain `31337`, with no default contract/recipient address; and
- Foundry, independent transaction-vector, real PostgreSQL, real Anvil, concurrent nonce, duplicate, response-loss, revert, default-context, and configuration evidence under [ADR 0007](adr/0007-local-ethereum-mint-vertical-slice.md) and the [Phase 5A plan](plans/active/PHASE_5A_ETHEREUM_LOCAL_MINT_VERTICAL_SLICE.md).

This is one local development mint effect, not the five-step transfer demonstration. It neither executes an accepted transfer effect nor implements burn, wallet transfer, replacement/cancellation, production custody, hosted/public RPC, legal/customer/accounting finality, or settlement. The next bounded recommendation is an Ethereum wallet-transfer slice that reuses the same durable attempt and observation seams without broadening public or production authority.

The preceding Action Request 12 implements **Phase 4B Isolated Local-Development Signer**:

- one focused `adapters/signer-local` module reusing the Phase 4A `SignerPort` and `SigningKeyRegistry` rather than creating another authority model;
- one Bouncy Castle 1.82 dependency confined to exact 32-byte secp256k1 digest signing, low-`s` normalization, and compact recovery encoding, with JDK-native Ed25519 for exact Solana message bytes;
- one in-memory EVM key and one in-memory Solana key generated only under explicit profile `local-signer`, with opaque session aliases/versions, public fingerprints, typed role/network allowlists, and no persisted/private input;
- exact replay through the durable Phase 4A result without re-signing, stable provider-identity conflict, inquiry after ambiguity, bounded no-signature retry, and stale-session manual review;
- explicit Spring composition, safe startup warning, invalid-configuration failure, and unchanged default context/readiness/OpenAPI/public resources; and
- accepted [ADR 0006](adr/0006-local-development-signing-provider.md) plus the restartable [Phase 4B plan](plans/active/PHASE_4B_LOCAL_DEVELOPMENT_SIGNER.md).

This capability is local-development infrastructure, not production custody. It reads or persists no private key, seed, mnemonic, keystore, credential, or wallet address. Phase 5A now consumes its transient EVM signature only under the separate local-Ethereum profile; the signer itself still constructs/submits no transaction and grants no finality.

The preceding Action Request 11 implements **Phase 4A Durable Signing-Authority Boundary**:

- one immutable signing aggregate with stable request/provider-attempt identities, complete operation/transfer/effect correlation, exact quantity, key-role metadata, policy/approval context, expiry, and append-only lifecycle/evidence;
- separate EVM 32-byte `secp256k1` digest and Solana serialized-message `Ed25519` provider commands plus inquiry—no generic signer command;
- versioned canonical intent and resolved-key hashes that make exact replay stable and any bound substitution a conflict;
- policy/key validation before provider use, a durable provider identity before invocation, inquiry after unresolved or ambiguous outcomes, and linked retry only after evidence proves no signature;
- one forward-only V4 migration and explicit JDBC repository storing hashes, lengths, encodings, non-secret metadata, and opaque evidence references rather than raw payloads, signatures, keys, or credentials; and
- a deterministic test-only synthetic provider with success, denial, retryable-no-signature, ambiguity/inquiry, conflict, and infrastructure-failure fixtures.

At the Phase 4A checkpoint this internal use case was deliberately not composed into Spring and exposed no runtime provider or real cryptography. Action Request 12 supplies the isolated local-development implementation described above; Action Request 13 consumes its EVM mode for one bounded mint. HSM/MPC/custody adapters and all other native effects remain later slices, and every provider/chain change must retain Phase 4A's durable identity/inquiry boundary.

Action Request 10 implements **Phase 3C Chain-Neutral Transfer Aggregate and Mock-Bank Boundary**:

- one immutable parent with exact quantity, participant scope, server-resolved custody context, separate finalities, and five ordered stable effect identities;
- one forward-only V3 migration for transfer/idempotency/effect/history/finality/inbox records plus transfer aggregate support in the existing outbox;
- participant-scoped transfer POST/GET resources with separate authorities, safe 404 equivalence, minimized projections, and an authoritative OpenAPI contract that contains no wallet request fields;
- database-authoritative replay/conflict, deterministic concurrent duplicate resolution, rollback, restart reconstruction, and exact V3 constraints;
- a provider-neutral mock-bank port plus a deterministic synthetic adapter that is not runtime fallback infrastructure; and
- a `TransferAccepted` handler that transactionally deduplicates the Phase 3B delivery identity and prepares only the first withdrawal.

The final offline reactor passed 364 tests with zero failures, errors, or skips: 249 domain, 40 application, 38 real-PostgreSQL persistence, and 37 control-plane tests. The independent review has no unresolved Critical or Important findings.

This slice performs no bank, signer, RPC, mint, token-transfer, burn, deposit, observation, reconciliation, compensation, or settlement effect. The server chooses institution-controlled wallet roles; callers supply only amount/currency, source/destination synthetic bank references, and an optional allowlisted logical network. The next bounded recommendation is Phase 4's provider-neutral signing-authority boundary; any attempt to execute the five-effect flow still needs separate bank/chain plans and evidence.

The previously verified Action Request 09 implements **Phase 3B Durable Worker and Delivery Recovery**:

- framework-free application delivery identity, outcomes, handler/queue ports, retry policy, and worker orchestration;
- one forward-only delivery-state/attempt-history migration and a separate explicit JDBC queue adapter;
- PostgreSQL multi-worker exclusion, ordered claims, expiring leases, stale-owner fencing, durable retries, manual review, and restart recovery;
- a disabled-by-default, typed, Spring-managed single-thread polling lifecycle that requires a real handler when enabled;
- existing Micrometer counters/gauges with bounded, non-sensitive dimensions; and
- deterministic pure tests plus real PostgreSQL failure-window tests, including transactional consumer deduplication in test scope.

Phase 3B remains at-least-once delivery infrastructure. Phase 3C now consumes it only for the bounded internal first-withdrawal preparation; the default worker is still disabled and no external effect is performed.

The previously verified Phase 3A slice supplies:

- one `adapters/persistence-postgres` module using explicit Spring JDBC, HikariCP, Flyway, and the PostgreSQL driver;
- one normalized migration for operations, hashed idempotency bindings, ordered transitions/evidence, attempt lineage, four finality/evidence histories, and pending outbox events;
- one atomic acceptance transaction with database-authoritative concurrent replay/conflict behavior;
- `POST /v1/token-operations/mints`, `POST /v1/token-operations/burns`, and participant-scoped `GET /v1/token-operations/{operationId}`;
- stateless Spring Security with separate mint/burn/read authorities and no configured identity provider or credential;
- one authoritative OpenAPI 3.1 YAML resource plus executable conformance tests; and
- real pinned PostgreSQL integration/API tests for migrations, exact quantities, rollback, concurrency, restart, safe failures, authorization, and data exposure.

The Phase 3A acceptance transaction durably records requests only; it does not poll the outbox or perform an external effect. Phase 3B adds the separate delivery boundary described above without changing that acceptance claim.

The previously verified Phase 2 slice supplies:

- exact asset/unit and token quantity types;
- mint and burn commands;
- scoped idempotency, versioned canonical payload hashing, and immutable accepted-command context;
- operation and attempt identity;
- explicit operation state transitions including ambiguous submission and manual review;
- distinct append-only finality histories with lifecycle prerequisites;
- provider-neutral chain and signer ports with stable internal correlation and exact-byte constraint binding; and
- pure Java tests proving invariants without Spring, persistence, Web3j, Solana SDKs, Solidity, Rust, or Compose.

Those contracts preserve opaque native identity and separate prepare, submit-once, inquiry, observation, lifetime/retry, and evidence semantics without implementing either chain adapter.

The implemented transfer/signing boundaries, bounded local-Ethereum mint, remaining bank/provider/chain effects, and two end-to-end demonstrations are mapped in [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md). The next recommended bounded action is one Ethereum wallet-transfer effect with its own approved plan; it must not be presented as execution of the complete parent transfer.
