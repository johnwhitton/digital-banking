# Phase 4A Durable Signing-Authority Boundary Execution Plan

**Status:** `ready_to_commit`

**Goal:** Implement a provider-neutral, durable signing-authority boundary that binds exact EVM digests or Solana message bytes to immutable operation, authority, key, policy, and approval context without handling production private keys or performing a chain effect.

**Approved request:** Action Request 11, with the repository owner's later `Add Volume III` commit superseding only the starting Git baseline.

**Starting baseline:** `main` at `113c0f90bf21590501d0c62dab693176dedb195f`. On 2026-07-17, local `HEAD`, `origin/main`, and live `refs/heads/main` matched this SHA after `git fetch origin main`; the worktree was clean and `origin` was `git@github.com:johnwhitton/digital-banking.git`.

**Authority:** `AGENTS.md`, `SECURITY.md`, accepted ADRs 0001-0005, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, `docs/IMPLEMENTATION_STANDARDS.md`, and Action Request 11.

## Scope and design checkpoint

The existing Phase 2 `application.port.SignerPort` already binds operation/attempt, exact quantity, destination, native constraints, policy/approval evidence, bytes, and digest, but it has only an ambiguous `sign(SigningRequest)` operation, two outcomes, string identities, no key registry, no durable signer aggregate, no provider inquiry, and no persistence. Operation attempts and PostgreSQL V1-V3 provide reusable stable identities, optimistic versioning, append-only history, explicit JDBC, and transaction patterns.

The current Phase 4 design is compatible with Action Request 11: both require exact request binding, independent signing authority, policy/approval before signing, provider-neutral ports, durable identity/evidence, test-only synthetic behavior, and no application-held production key. No accepted ADR authorizes a provider or requires a new module. ADR 0004 already governs explicit PostgreSQL/JDBC/Flyway persistence; no new ADR is required.

Minimal implementation decisions:

- Add framework-free signing identities, immutable context, key metadata, lifecycle, attempt lineage, and append-only evidence under `domain`; reuse existing operation, transfer, quantity, network, and evidence types where their semantics match.
- Evolve the existing application-owned `SignerPort`; do not create a competing signer abstraction. Expose distinct EVM-digest and Solana-message methods plus inquiry rather than a generic `sign(byte[])`.
- Add only materially distinct application ports: non-secret key registry, signing authorization, signing persistence, and identity generation. One application service sequences durable acceptance, authorization, provider-request persistence, provider invocation outside transactions, ambiguity inquiry, and explicit outcomes.
- Add one dedicated `PostgresSigningRequestRepository` and forward-only V4 migration. Persist request/attempt identities, canonical hashes, non-secret key metadata, lifecycle/version, evidence references, payload/signature hashes and lengths, and provider identity. Persist neither raw signable material nor completed signature bytes.
- Keep raw signable and synthetic signature bytes transient and defensively copied at the application/provider boundary. Redact request, key, provider, payload, signature, policy, approval, and failure details from diagnostics.
- Put the deterministic synthetic signer only in test sources. Add no runtime signer bean, provider credential, key configuration, public endpoint, OpenAPI path, module, dependency, SDK, or cryptographic implementation.
- Treat a signature only as signing evidence. It cannot advance submission, execution, any finality, settlement, or transfer effect status.

## Expected files

- Domain signing package: typed request/attempt/provider/key identities and one immutable signing-request aggregate with explicit lifecycle and attempt lineage.
- Application: evolved `SignerPort`, key-registry/authorization/repository/identity ports, canonical signing request encoding, explicit service outcomes, and signing-authority orchestration.
- Persistence: `V4__create_signing_authority_persistence.sql`, one explicit JDBC repository, and real PostgreSQL tests.
- Tests: focused domain/application tests, a test-only deterministic synthetic signer, persistence/migration/concurrency/restart tests, and control-plane boundary regressions.
- Documentation: the smallest accurate updates to `README.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, and this plan. `docs/IMPLEMENTATION_STANDARDS.md` and ADRs remain unchanged because their current signing and persistence rules already govern this slice.

## Complete RED matrix before production implementation

### Domain and application

- [x] Defensive copies protect EVM digest, Solana message, and returned signature bytes; diagnostics expose no raw bytes, aliases, provider IDs, policy details, or signatures.
- [x] EVM `secp256k1` requires an exact 32-byte digest and cannot use Solana message mode; Solana `Ed25519` requires serialized message bytes and cannot use EVM digest mode.
- [x] Canonical request identity binds request/attempt lineage, operation/transfer/effect correlation, action, network, exact asset/unit/quantity, source/destination, native action/lifetime/fee constraints, payload identity, key alias/reference/role/algorithm, policy version, approval evidence, and expiry.
- [x] Key resolution rejects unknown, disabled, revoked, expired, version-mismatched, role-mismatched, algorithm-mismatched, and network-mismatched metadata before provider invocation.
- [x] Missing or pending policy/approval returns an explicit approval-required outcome and never invokes the provider.
- [x] Exact replay returns the original durable request/result without re-resolving later key configuration or issuing another provider command.
- [x] Changed payload, context, key, role, algorithm/mode, policy, approval, expiry, or lineage under one request ID is a deterministic conflict.
- [x] Provider identity is durable before invocation; provider work occurs outside repository transactions.
- [x] Provider success, denial, retryable no-signature failure, ambiguity, expiry/revocation, and manual-review outcomes are explicit and append-only.
- [x] An ambiguous or persisted-but-unresolved provider request is inquired by its stable provider identity and cannot be blindly signed again.
- [x] Inquiry can recover the original signed result or prove no signature before a separately authorized retry.
- [x] A changed native payload requires a new request and linked signing-attempt lineage; an existing authorized request is never mutated.
- [x] Backdated transitions/outcomes and optimistic-version mismatches are rejected.
- [x] Signed evidence does not mutate token-operation, transfer-effect, submission, or finality state.
- [x] Expected outcomes use explicit result types; invariant and unexpected infrastructure failures remain failures rather than client/policy outcomes.
- [x] The synthetic test signer supports success, denial, retryable no-signature failure, ambiguity/inquiry, and same-provider-ID conflict, and its evidence is unmistakably synthetic.

### PostgreSQL V1-V4

- [x] An empty pinned PostgreSQL database migrates through V4 and creates constrained signing request, attempt, transition/evidence, and approval-evidence structures.
- [x] Acceptance atomically records complete immutable request context and initial history; forced failure rolls back every signer row.
- [x] Exact replay and conflicting replay are deterministic.
- [x] Barrier-based parallel duplicate and conflicting requests select one durable result without arbitrary sleeps.
- [x] Optimistic version fencing prevents stale lifecycle/evidence writes.
- [x] Attempt lineage and transition/evidence histories are append-only and completely reconstructed after a fresh pool/repository restart.
- [x] Ambiguous provider identity and inquiry evidence survive restart.
- [x] Schema and stored fixtures contain no raw private key, seed, credential, raw signable payload, or raw signature column/value.
- [x] Authority isolation is enforced by immutable key role/algorithm/network/version binding. Participant isolation is not applicable because this slice adds no participant-facing signing resource.

### Boundary and regression

- [x] No public signing path, controller, OpenAPI operation, default signer, key alias, provider credential, or production fallback is configured.
- [x] Existing public responses/problems expose no signing request, key, provider, raw payload/signature, policy/approval, or sensitive provider-error data.
- [x] Existing mint/burn, worker, transfer, participant isolation, security, OpenAPI, and readiness behavior remains unchanged.
- [x] Enforcer retains `control-plane/adapters -> application -> domain`; no Spring/JDBC/provider/native SDK type enters inner signatures. Focused `jdeps` remains a closeout check.

## Execution and evidence

- [x] Read Action Request 11 completely.
- [x] Confirm the superseding clean synchronized baseline `113c0f90bf21590501d0c62dab693176dedb195f`.
- [x] Read governing repository instructions, living docs, standards, accepted ADRs, and matching workflows.
- [x] Inventory existing signing, operation/attempt, persistence, configuration, and test boundaries using direct `rg` and source reads before Graphify.
- [x] Complete the design checkpoint; no authority conflict or new ADR requirement was found.
- [x] Add focused tests and record the expected RED command/output before each production slice.
- [x] Implement the smallest domain/application behavior and keep focused tests green.
- [x] Add V4 and the dedicated JDBC repository test-first; record migration, rollback, concurrency, restart, inquiry, and no-secret evidence.
- [x] Add only necessary boundary regression tests and living-document updates.
- [x] Complete a self-review against every matrix item after focused tests are green.
- [x] Invoke exactly one independent review after production code is stable; resolve in-scope Critical and Important findings.
- [x] Run the complete offline reactor exactly once after final source/docs are stable: `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify`.
- [x] Run applicable focused Enforcer/`jdeps`, changed-document links, secret/key/credential/public-network/raw-payload/signature/provider-error scans, generated-artifact checks, `git diff --check`, and staged-diff checks.
- [x] Invoke Ponytail once on the final stable diff and record retained/deferred simplifications.
- [x] Attempt one tracked Graphify refresh after all source/docs changes with a hard two-minute limit, no HTML, queries, subagents, retries, manual merge, or custom reconstruction. On failure/stall, restore only the three tracked artifacts to this baseline, remove only attempt-created `.graphify_*` transients, record `tooling_deferred`, and continue.
- [x] Compare reference PDF Git blob identities only against the superseding baseline. Do not render, extract, inspect, hash filesystem contents, or alter a PDF.
- [x] Re-fetch/inspect live `origin/main`; stop if it advanced from the approved starting baseline.
- [x] Stage only authorized Phase 4A source/tests/V4/docs/plan and successful tracked Graphify artifacts if any; exclude PDFs, secrets, environment files, targets, caches, and transients.
- [ ] Commit exactly once as `feat: add durable signing authority boundary`, push non-force, and verify clean synchronized local/tracking/live remote state.

## Baseline publication note

The superseding baseline adds `docs/reference/digital-banking-reference-implementation.pdf` and updates README. The PDF is immutable pre-existing content for this action. Its Git blob identity will be compared at closeout without content inspection. Stale living-document wording that still calls Volume III planned or counts only three reference PDFs may be corrected only in files otherwise authorized by Action Request 11.

## Deferred by scope

Real cryptography; private/development keys; runtime signer composition; HSM/MPC/custody or remote provider integration; provider credentials; public signing APIs; Web3j, Solana SDKs, native transaction construction, nonce/blockhash/gas, RPC, submission, observation, finality, reconciliation, bank effects, transfer execution, public networks, production custody, and production readiness.

## Evidence log

Append exact commands, observed results, review dispositions, Graphify outcome/deferral, blob identities, commit/push evidence, and final SHA here as work proceeds.

- **Domain RED:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl domain -Dtest=SigningRequestTest test` failed as expected during test compilation because `SigningRequest`, `SigningRequestId`, `SigningAttemptId`, `ProviderRequestId`, and `KeyAlias` did not exist. Maven Enforcer passed before the expected compilation failure. No production signing source existed at this checkpoint.
- **Domain GREEN:** the same command passed 5 tests with zero failures/errors/skips and the plain-domain Enforcer rule passed. `SigningRequest.java` is 732 physical lines, so the required extraction review was performed: its nested context, lifecycle, attempt, transition, and outcome types form one invariant boundary and remain below the roughly 800-line design-smell threshold. Retain provisionally and re-evaluate during Ponytail and independent review rather than mechanically splitting it.
- **Application RED:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl application -am -Dtest=SigningAuthorityServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` passed domain/application Enforcer, then failed as expected during application test compilation because `SigningAuthorityService`, the signing repository/key/authorization/identity ports, explicit EVM/Solana provider commands/results, and the new `SignerPort` methods did not exist. The compiler also confirmed the existing single `SignerPort.sign(...)` method could not implement the required native-mode and inquiry contract.
- **Application GREEN:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl application -am -Dtest=SigningAuthorityServiceTest,PortContractTest -Dsurefire.failIfNoSpecifiedTests=false test` passed 11 tests after adding key algorithm/network mismatch and unexpected-provider-failure inquiry coverage; both inner-module Enforcer rules passed. The version-1 golden canonicalization test then passed 2 additional tests and proves 11 context mutations plus resolved key registry/version changes produce distinct digests.
- **Persistence RED/GREEN:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/persistence-postgres -am -Dtest=PostgresSigningRequestRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test` first failed at test compilation because the repository did not exist. After V4 and the explicit JDBC adapter, 5 of 6 tests passed; the remaining assertion was corrected to Spring's commit-boundary `TransactionSystemException` for the intentionally deferred lineage foreign key, with zero partial rows. The final focused run passed all 6 tests. A self-review test then exposed same-hash/different-context replay acceptance in the repository contract; tightening replay to compare the reconstructed immutable context made that RED test and the suite green.
- **Focused stable gate:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=SigningRequestTest,SigningAuthorityServiceTest,SigningRequestCanonicalizerTest,PortContractTest,PostgresSigningRequestRepositoryTest,PostgresOperationRepositoryTest,PostgresTransferRepositoryTest,DigitalBankingApplicationTests,OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test` passed 55 tests with zero failures/errors/skips: 5 domain, 13 application, 30 real-PostgreSQL persistence, and 7 control-plane/OpenAPI. All Enforcer rules passed and empty-schema migrations reached V4 in new signing, legacy operation, legacy transfer, and Spring-context databases.
- **Self-review:** all matrix items were traced to code/tests. The review removed the unnecessary production `TEST_ONLY` key role, confirmed provider invocation occurs only after repository calls return, confirmed the signing service has no operation/transfer/finality mutation dependency, and retained `SigningRequest` (731 lines) plus `PostgresSigningRequestRepository` (637 lines) as cohesive invariant/mapping boundaries for independent-review and Ponytail scrutiny rather than adding speculative layers.
- **Independent review:** the single authorized review found no Critical issue and five Important issues: recovered awaiting/authorized requests did not recheck expiry; retry could reuse its original approval; registry output was not checked against the requested alias/role; acceptance replay compared a server-generated creation time; and multi-query reconstruction lacked one consistent database transaction. The review also confirmed there is no private key/credential, runtime fallback, public signing endpoint, chain submission, or misleading finality claim. All five findings were accepted and resolved in scope; no second review was started.
- **Review-remediation RED/GREEN:** the application focused command first ran 11 tests with three expected failures proving the skipped expiry check, single authorization evaluation, and accepted registry substitution. It then passed all 11 after expiry fencing, fresh retry authorization, and exact registry identity/authority validation. The domain focused command then failed one of 5 tests because a no-signature retry could directly persist a second provider request; after requiring `RETRYABLE_NO_SIGNATURE -> AWAITING_AUTHORIZATION -> AUTHORIZED`, all 5 passed. The PostgreSQL focused command first ran 6 tests with two expected errors because independent identical requests with different server creation times conflicted, then passed all 6 after separating replay context from persisted creation time and reconstructing under a parent-row lock in one transaction.
- **Post-review stable gate:** the focused stable command above passed 57 tests with zero failures/errors/skips: 5 domain, 15 application, 30 real-PostgreSQL persistence, and 7 control-plane/OpenAPI. All Enforcer rules passed; all four fresh databases migrated through V4. No production or test source changed after this gate.
- **Ponytail:** the single required final-diff pass found no dependency, module, runtime provider, duplicate signer port, factory/builder/wrapper layer, speculative option, TODO, or removable production abstraction. The four small application ports remain materially distinct authority, registry, persistence, and identity seams required by the action; the typed IDs remain domain boundary types. The cohesive 732-line aggregate and 651-line explicit JDBC mapper are retained rather than split mechanically. No change was made by this pass.
- **Complete offline gate:** the one authorized `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` run passed all five reactor modules in 17.984 seconds. Fresh Surefire XML reports record 386 tests with zero failures, errors, or skips: 254 domain, 50 application, 44 real-PostgreSQL persistence, and 38 control-plane. Enforcer passed in every governed module; empty-schema and existing-schema Flyway checks reached V4. No production, test, POM, runtime-configuration, or OpenAPI file changed after this successful gate. `./mvnw --version` confirmed Maven 3.9.16 and Java 25.0.2 from the approved Homebrew JDK.
- **Focused closeout checks:** changed-document local links passed with PDF targets intentionally excluded; `git diff --check` passed; secret-like/private-key credential patterns, production public-network URLs/names, and raw payload/signature/provider-error persistence-field scans returned no matches. Generated-output and changed-POM/runtime-config/OpenAPI checks returned no paths. The first read-only `jdeps` command treated the Spring Boot `.jar.original` suffix as a class file and failed with `Bad magic number`; rerunning against compiled class directories passed and showed domain -> JDK only, application -> domain/JDK, persistence -> application/domain/JDK/JDBC/external unresolved libraries, and control-plane -> persistence/application/domain/JDK/JDBC/external unresolved libraries.
- **PDF Git blob identity:** baseline tree and current index both retain four mode-`100644` PDF blobs with identical object IDs: engineering companion `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`, reference implementation Volume III `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`, stablecoin architecture `ebe456d6e71685aca63312c8d7466f17a2b86828`, and executive brief `ae25c88118b3bb8356784d2f0f02f1096b034331`. `git diff --name-status <baseline> -- docs/reference` returned no path. No PDF content or filesystem hash was read.
- **Graphify — `tooling_deferred`:** verified the approved installed `graphify 0.8.47`, read its repository/update instructions, and made the one authorized command attempt: `graphify update . --no-cluster`. `--no-cluster` was selected to prohibit clustering, report regeneration, semantic backends, and HTML while preserving existing semantic navigation data. The command exited 1 immediately with `[Errno 1] Operation not permitted`; no extraction result was produced. No retry, query, HTML, subagent, backend, manual reconstruction, or substitute artifact was used. The three tracked Graphify files were restored explicitly from `113c0f90bf21590501d0c62dab693176dedb195f`; they had no resulting diff. The only `.graphify_*` files present before and after were the pre-existing `.graphify_python` and `.graphify_root`, so no attempt-created transient required removal. Existing Graphify artifacts remain non-authoritative navigation data.
- **Pre-delivery remote fence:** `git fetch origin main`, local `HEAD`, `origin/main`, `FETCH_HEAD`, and live `git ls-remote --heads origin main` all returned `113c0f90bf21590501d0c62dab693176dedb195f`. `origin` remains the approved `git@github.com:johnwhitton/digital-banking.git`; no divergence or unexpected remote history was found.
- **Staging and final-diff fence:** staged exactly 30 authorized Phase 4A files (4,232 insertions and 326 deletions before this evidence update). Segmented content review plus `git diff --cached --name-status`, `--summary`, `--check`, and staged secret/path scans passed. Every new file is mode `100644`; no PDF, Graphify artifact, executable, POM, runtime configuration, OpenAPI document, generated output, cache, transient, environment file, or unrelated path is staged. No Critical correctness or security issue was found. The repository plan is intentionally snapshotted as `ready_to_commit`; commit, push, and synchronized-remote evidence follow outside the commit and are reported in the final handoff so the action creates exactly one commit.
- **Documentation:** README, DESIGN, IMPLEMENTATION, and TRANSFER_DEMO now distinguish durable authority evidence from cryptographic signing/submission/finality, identify the test-only synthetic provider, record V4 and ambiguity inquiry, defer runtime/provider/native integration, and recognize the baseline Volume III publication without inspecting or changing any PDF. Existing implementation standards and ADRs already govern the slice and remain unchanged.
