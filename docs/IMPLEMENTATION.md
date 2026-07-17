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
| 3. Durable API and persistence         | `in_progress` | Phase 3A durable acceptance/read-back is `verified` by the 302-test clean reactor gate; Phase 3B worker/recovery and planned Phase 3C transfer orchestration remain absent. |
| 4. Signing boundary                    | `not_started` | Design only; no signer code or keys.                                                           |
| 5. Ethereum vertical slice             | `not_started` | No Web3j/Solidity/local-chain code.                                                            |
| 6. Solana vertical slice               | `not_started` | No Java SDK/Rust/local-validator code.                                                         |
| 7. Observation and reconciliation      | `not_started` | Design only.                                                                                   |
| 8. Integrated local environment        | `not_started` | No Compose file.                                                                               |
| 9. Hardening and publication readiness | `not_started` | No production-readiness claim.                                                                 |

The current executable boundary remains Phase 3A. The planned [bank-to-bank transfer demonstration](TRANSFER_DEMO.md) does not change any implementation status. Every future transfer-related slice requires its own focused active plan before code, dependency, schema, API, signer, adapter, contract/program, or environment work begins.

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

**Implemented deliverables:** one design-first OpenAPI 3.1 contract; secured Spring mint/burn create and participant-scoped operation-status resources; stateless deny-by-default security and safe RFC 9457 problems; PostgreSQL schema/Flyway migration; explicit JDBC repository; durable hashed idempotency; optimistic concurrency; normalized operation/attempt/transition/finality/evidence storage; one atomic pending acceptance-outbox record; and production composition with no in-memory fallback. See [ADR 0004](adr/0004-postgresql-jdbc-flyway-atomic-outbox.md).

**Tests:** OpenAPI parsing/conformance, 401/403 boundaries, HTTP validation, 202/Location behavior, replay/conflict, participant non-disclosure, safe database failure, empty migration, schema/index/quantity constraints, complete aggregate replay, optimistic conflict, parallel duplicate/conflicting requests without retries, rollback, uniqueness, outbox atomicity, process restart, and sensitive-field absence.

**Acceptance gate:** an accepted command is committed before HTTP 202; duplicates cannot create a second operation or outbox row; conflicts leave no partial state; no external effect occurs inside the acceptance transaction; and real PostgreSQL restart/concurrency tests prove durable read-back.

**Current limitation:** the repository configures no real identity adapter, issuer, token decoder, local user, password, or static credential. Business endpoints deny by default; tests use fixture-only `ParticipantPrincipal` authentication and authorities.

### Phase 3B: asynchronous worker and delivery recovery

**Deferred deliverables:** outbox claiming/leasing, publication or local worker handoff, compare-and-set lifecycle progression, retries/backoff, inbox/deduplication, crash recovery, durable timers, and operator evidence. These remain unimplemented; a pending outbox row proves only durable local acceptance.

This slice supplies the worker, timer, delivery, and recovery contracts required by the planned [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md) workflow; it does not implement that transfer or any bank/chain effect.

The focused Phase 3B plan must evaluate two bounded execution approaches against the same domain-owned state/version, idempotency, durable-timer, retry, recovery, audit/export, availability, and evidence contracts:

1. **Database-backed Java/Spring worker** - the self-contained reference-implementation baseline, using PostgreSQL outbox/inbox state, leases, compare-and-set progression, and durable recovery.
2. **Approved enterprise BPM/durable-workflow platform** - permitted only when organizational evidence establishes an approved platform and an evidence spike proves it can coordinate the same domain contracts without becoming the ledger, policy authority, signer, observer, or reconciliation record.

Camunda 8 and Temporal are representative future evaluation candidates, not selected dependencies or current recommendations. A comparison must cover state ownership, deterministic/versioned behavior, idempotency, durable timers, ambiguous-effect recovery, human tasks, audit/export, HA/DR, security, operational ownership, deployment constraints, licensing, and exit/migration strategy. Temporal's Java/Spring SDK experience does not make its server a Java runtime.

Action Request 05 supplies an inference from a job description that an application-owned Java/Spring state machine with Oracle persistence and Kafka/JMS/TIBCO EMS messaging is the most plausible organizational pattern; it is not a discovered Zelle/EWS implementation fact. Kafka, JMS, and TIBCO EMS remain transports. TIBCO BusinessWorks/BPM Enterprise, MuleSoft, and SAP integration products remain integration/process boundaries unless organizational evidence and an ADR justify broader ownership.

**Future tests:** worker concurrency, lease expiry/recovery, duplicate delivery, monotonic transition guards, poison/failure handling, process death at every delivery boundary, and no blind external-effect retry.

**Acceptance gate:** one selected Phase 3B implementation passes the same deterministic delivery/recovery contract. Any new workflow-platform dependency requires a focused evidence spike and ADR before implementation; no vendor is selected by this roadmap.

**Risks:** database-specific coupling, workflow-platform state leakage, exactly-once overclaims, and future worker/recovery semantics. Phase 3A explicitly enforces `READ_COMMITTED`, canonical microsecond timestamps, and participant-safe evidence filtering; later response fields must preserve those controls.

**Deferred beyond Phase 3:** broker topology selection, full ledger, signing, and chain execution.

### Phase 3C: chain-neutral transfer aggregate and mock-bank boundary

**Dependency:** Phase 3B worker/recovery mechanics are verified before this slice performs or coordinates any child effect.

**Planned deliverables:** execute the chain-neutral portion of [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md): one durable `Transfer` parent aggregate; `POST /v1/transfers` and participant-scoped `GET /v1/transfers/{transferId}`; versioned canonical idempotency and exact amount/currency; normalized transfer/child/evidence/finality persistence; source- and destination-bank ports; local mock-bank adapters; route/wallet/asset/approval configuration contracts; and child token-operation correlation. Do not add a chain adapter or claim the five-step demonstration in this phase.

**Future tests:** API contract/validation/security, exact amount and canonical hash, replay/conflict and concurrent duplicates, participant isolation, parent/child persistence and restart recovery, outbox/inbox delivery, bank-effect inquiry after timeout, mock withdrawal/deposit duplicate handling, append-only evidence/finality, and compensation as a new authorized effect.

**Acceptance gate:** the parent and each bank effect have stable durable identities; duplicate delivery creates no duplicate transfer/effect; a lost bank-mock response is inquired before retry; no synchronous controller method or distributed transaction spans the workflow; chain children remain planned and no external value effect is claimed.

**Risks:** hiding child ambiguity behind a parent status, caller-controlled infrastructure fields, treating mocks as production integrations, and beginning chain execution before signer/worker controls.

**Deferred:** settlement-wallet provisioning, development/production signer implementations, native chain effects, independent observation/reconciliation, and the complete five-step demonstrations.

Phases 4-9 below consume the relevant acceptance criteria in [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md). Each future phase or bounded sub-slice requires a focused active plan before implementation; this roadmap does not authorize combining both chains in one action.

## Phase 4: Signing boundary

**Deliverables:** exact signing request/decision/evidence contracts for mint, wallet transfer, and burn; policy/approval binding; signer request idempotency; isolated local-development signer using disposable local wallet authorities; settlement-wallet role/configuration binding; interfaces for HSM/MPC/custody providers; no vendor selection.

**Tests:** exact digest/bytes binding, mismatched amount/destination/chain rejection, allowlists, limits, quorum, expiry, repeated request identity, timeout ambiguity, redaction, and proof that raw keys never cross the port.

**Acceptance gate:** application code can authorize and record a signer decision for the exact child operation/attempt without owning production key material; raw keys never enter the Java application; the local signer and disposable wallets are impossible to enable through production configuration.

**Risks:** treating signing as business authorization; mutable approval payloads; test keys escaping fixtures.

**Deferred:** production provider integration and recovery ceremony.

## Phase 5: Ethereum vertical slice

**Dependency:** phases 2-4 verified. Build this first, then review the common ports before beginning Solana.

**Deliverables:** execute [ADR 0002](adr/0002-evm-foundry-and-web3j.md) and the Ethereum portion of [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md); use Foundry (`forge`, `anvil`, `cast`, and scripts) as the sole contract toolchain; add and deploy a minimal reviewed local stablecoin contract supporting authorized mint, ERC-20 transfer, and authorized burn; add a Web3j adapter; prove deterministic encoding, nonce/replacement lineage, event/receipt observation, independent inquiry, and mint/transfer/burn happy, rejection, revert, timeout, ambiguity, replacement, and reorg/canonicality paths.

**Tests:** deterministic deployment, golden mint/transfer/burn encoding, chain ID/nonce/source/destination/amount binding, signer digest, submit-once, response loss, inquiry, replacement rules, failed receipt, event effect, canonicality change, duplicate request, and reconciliation evidence.

**Acceptance gate:** local mint/transfer/burn effects on Anvil are authorized, independently observable, recoverable, and reconcilable by stable IDs; ambiguous submission never blind-resubmits; no Web3j/native model leaks into domain. This phase proves chain effects, not the complete bank-to-bank demonstration.

**Risks:** unsafe admin/upgrade authority, fake finality, submit/observe provider coupling, fixture keys presented as production patterns.

**Deferred:** public networks, production contracts, provider/custody selection.

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

## Future publications

These are documentation roadmaps with explicit `planned` status. No placeholder publication file or empty tree exists.

- **Volume II - Digital Banking Engineering Companion** (`planned`): engineering handbook covering implementation patterns, wallets, signing, HSMs, Java, Spring, EVM, Solana, testing, deployment, observability, runbooks, and performance.
- **Volume III - Digital Banking Reference Implementation** (`planned`): written companion covering architecture-to-code mapping, module walkthroughs, API examples, database schema, code excerpts, build/run/test guides, and local-versus-production implementations.

Publication work follows executable evidence and Phase 9 review; it does not replace a focused implementation plan or acceptance test.

## Plans and ADRs

- Closed foundation plan: [`docs/plans/active/BOOTSTRAP.md`](plans/active/BOOTSTRAP.md).
- Completed Phase 2 plan: [`docs/plans/active/DOMAIN_OPERATION_LIFECYCLE.md`](plans/active/DOMAIN_OPERATION_LIFECYCLE.md).
- Verified Phase 3A plan: [`docs/plans/active/PHASE_3A_DURABLE_API_AND_PERSISTENCE.md`](plans/active/PHASE_3A_DURABLE_API_AND_PERSISTENCE.md).
- Active Zelle share-readiness and transfer-roadmap plan: [`docs/plans/active/ZELLE_SHARE_READINESS_AND_TRANSFER_ROADMAP.md`](plans/active/ZELLE_SHARE_READINESS_AND_TRANSFER_ROADMAP.md).
- ADR process and index: [`docs/adr/README.md`](adr/README.md).
- Accepted build/module choice: [`ADR 0001`](adr/0001-maven-reactor-and-module-boundaries.md).
- Accepted EVM approach: [`ADR 0002`](adr/0002-evm-foundry-and-web3j.md).
- Accepted Solana approach: [`ADR 0003`](adr/0003-native-solana-spl-token.md).
- Accepted PostgreSQL/JDBC/Flyway/atomic-outbox approach: [`ADR 0004`](adr/0004-postgresql-jdbc-flyway-atomic-outbox.md).

Create or update an active plan before implementation. Create an ADR only when evidence requires an accepted material decision.

## AI-assisted engineering workflow

The repository tracks Graphify's project-scoped skill, portable fail-open hook, scan policy, and the reviewed `graphify-out/GRAPH_REPORT.md`, `graph.json`, and `manifest.json`. Transient visualization, cache, query-memory, cost, reflection, and machine-state output remains ignored; immutable source PDFs are not indexed. Use the graph first for repository-navigation questions and validate its suggestions against authoritative source.

Ponytail and Superpowers are user-installed plugins, not repository dependencies or vendored content. Ponytail supplies explicit simplicity/YAGNI modes and the final over-engineering review. Superpowers supplies matching planning, TDD, debugging, review, and verification workflows. The user's approved request, repository policy/security/design, active plan, ADRs, focused repository skills, source, and tests take precedence over all three tools.

Contributor setup, reviewed update commands, exact hook-trust behavior, and evidence limitations are maintained in the README. The restartable implementation and review record for this integration is [`docs/plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md`](plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md). This workflow changes no product capability, phase status, module dependency, financial invariant, or next-slice recommendation.

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
graphify --version
test -f .agents/skills/graphify/SKILL.md
jq -e '.hooks.PreToolUse[] | select(.matcher == "^Bash$")' .codex/hooks.json
```

Reference, documentation, skill/hook, stale-reference, diff, and Git commands are recorded with results in the active bootstrap plan. Add commands here only after they are stable contributor entry points.

## Latest bounded vertical slice

Action Request 04 implements **Phase 3A Durable API and Persistence**:

- one `adapters/persistence-postgres` module using explicit Spring JDBC, HikariCP, Flyway, and the PostgreSQL driver;
- one normalized migration for operations, hashed idempotency bindings, ordered transitions/evidence, attempt lineage, four finality/evidence histories, and pending outbox events;
- one atomic acceptance transaction with database-authoritative concurrent replay/conflict behavior;
- `POST /v1/token-operations/mints`, `POST /v1/token-operations/burns`, and participant-scoped `GET /v1/token-operations/{operationId}`;
- stateless Spring Security with separate mint/burn/read authorities and no configured identity provider or credential;
- one authoritative OpenAPI 3.1 YAML resource plus executable conformance tests; and
- real pinned PostgreSQL integration/API tests for migrations, exact quantities, rollback, concurrency, restart, safe failures, authorization, and data exposure.

The slice durably accepts and records requests only. It does not poll or publish the outbox, process operations, sign, submit, mint, burn, reconcile, settle, or claim exactly-once external effects. Phase 3B worker and delivery recovery is the next recommended bounded slice; chain work still waits for the signing boundary.

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

The planned transfer aggregate, API, bank ports, signer/chain effects, and two end-to-end demonstrations are specified in [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md). They do not change the current capability claim or dependency order. Phase 3B worker and delivery recovery remains the next recommended bounded implementation action.
