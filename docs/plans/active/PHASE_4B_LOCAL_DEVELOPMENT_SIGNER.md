# Phase 4B Isolated Local-Development Signer Execution Plan

**Status:** `ready_for_git_closeout`

**Goal:** Add one explicitly enabled, in-memory local-development signer that implements the Phase 4A provider and key-registry ports for exact EVM `secp256k1` digests and exact Solana `Ed25519` messages without creating a public signing API, chain effect, persistent key, or production fallback.

**Approved request:** Action Request 12.

**Starting baseline:** `main` at `c739a048a44cd55139bde3dddfeb0f7c0ebff442`. On 2026-07-17, local `HEAD`, `origin/main`, `FETCH_HEAD`, and live remote `refs/heads/main` matched this SHA after `git fetch origin main`; the worktree was clean and `origin` was `git@github.com:johnwhitton/digital-banking.git`.

**Authority:** `AGENTS.md`, `SECURITY.md`, accepted ADRs 0001-0006, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/IMPLEMENTATION_STANDARDS.md`, the completed Phase 4A plan and contracts, and Action Request 12.

## Scope and Phase 4A integration

Phase 4A already owns stable signing/provider identities, immutable intent and resolved-key hashes, key/policy checks, durable provider-request-before-call sequencing, replay/conflict, inquiry after ambiguity, linked retry, redacted signature evidence, and PostgreSQL V4 persistence. Phase 4B will implement the existing `SignerPort` and `SigningKeyRegistry`, then compose the existing `SigningAuthorityService` only under Spring profile `local-signer`. It will not create another signer abstraction or alter the V1-V4 schema.

Minimal implementation decisions:

- Add the executable `adapters/signer-local` module. It depends inward on `application` and contains every Bouncy Castle type and every ephemeral private-key object.
- Use one `LocalEphemeralSigner` as both the provider and non-secret registry for exactly two in-memory keys: one EVM key and one Solana key. Avoid provider wrappers, factories, key stores, generic crypto helpers, and speculative custody seams.
- Generate a random opaque session identity at startup. Derive distinct non-secret aliases from the session identity and algorithm mode, and key versions from the session identity plus public-key fingerprint; retain creation time as separate key metadata and never derive identity from private material.
- Keep configurable roles and logical networks typed and allowlisted. EVM permits only `ETHEREUM` plus configured authority roles; Solana permits only `SOLANA` plus configured authority roles. Missing, empty, crossed, unknown, disabled, closed, or stale metadata fails before signing.
- Return local evidence references that identify the safe mode, provider request, session/key version, public fingerprint, algorithm, context digest identity, encoding, and safe outcome without raw payload, digest, signature, key, exception, or provider state.
- Use the existing Phase 4A lifecycle for replay, conflict, no-signature retry, ambiguity/inquiry, and durable completion. The adapter retains only session-local provider results needed for inquiry; it never auto-generates a replacement key/signature.
- Compose the PostgreSQL signing repository, local key registry/provider, local approval fixture, UUID identities, and `SigningAuthorityService` only under explicit `local-signer`. The default context creates none of them and no key.
- Add no health detail, metric labels, public route, OpenAPI operation, transaction builder, RPC, wallet address, native SDK, or chain effect.

## Cryptographic-provider decision

JDK 25.0.2 was probed before repository production edits with a compiled Java program using `KeyPairGenerator("EC")`, `ECGenParameterSpec("secp256k1")`, and `Signature("NONEwithECDSA")`. SunEC failed key generation with `InvalidAlgorithmParameterException: Curve not supported: secp256k1 (1.3.132.0.10)`. The same probe used JDK-native `KeyPairGenerator("Ed25519")` and `Signature("Ed25519")`; it produced a 64-byte signature and verified successfully.

ADR 0006 therefore pins the single adapter-scoped dependency `org.bouncycastle:bcprov-jdk18on:1.82`. The authoritative project source is <https://www.bouncycastle.org/java.html>, and the artifact uses the Bouncy Castle License (MIT-style; <https://www.bouncycastle.org/licence.html>). Only `adapters/signer-local` may depend on it. The adapter will use Bouncy Castle's `secp256k1` domain/key/signature primitives with JCA `SecureRandom`, normalize `s` to the lower half-order, and emit compact 65-byte `r || s || recovery-id` encoding. Tests will reconstruct and verify the public key from the exact 32-byte digest, proving no second hash. Ed25519 remains JDK-native. Web3j and other chain SDKs are excluded because this slice signs already-built material and performs no EVM/Solana transaction behavior.

An offline Maven resolution check for `bcprov-jdk18on:1.82` failed because the artifact had not previously been downloaded. One exact authorized online Maven invocation then downloaded only this artifact and its POM; all later builds remained offline.

## Expected files

- Reactor and module: root dependency/version management plus `adapters/signer-local/pom.xml`.
- Adapter source: one cohesive ephemeral signer/provider-registry plus a small immutable typed configuration value if needed.
- Adapter tests: real secp256k1/Ed25519 cryptographic, authority/session, defensive-copy, failure, inquiry, restart, and leakage tests.
- Control plane: profile-scoped typed properties and configuration, safe profile resource, startup warning, and default/profile integration tests.
- Documentation: the smallest accurate changes to `README.md`, `SECURITY.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, ADR index/0006, and this plan. Existing standards remain unchanged unless implementation proves a genuinely new durable rule.

## Complete RED matrix before production implementation

### Adapter cryptography

- [x] Exact 32-byte EVM digest signs and verifies against the generated secp256k1 public key.
- [x] Any non-32-byte EVM digest is rejected before signing.
- [x] Verification succeeds over the supplied digest and fails over `SHA-256(digest)`, proving no double hash.
- [x] EVM output is compact 65-byte `r || s || recovery-id`, `s <= n/2`, recovery is bounded, and the recovered public key matches.
- [x] Exact non-empty bounded Solana message signs and verifies with JDK Ed25519.
- [x] Solana output is exactly 64 bytes; empty and over-policy-limit messages are rejected.
- [x] EVM and Solana modes/algorithms/aliases cannot be interchanged.
- [x] Supplied and returned arrays are defensive copies.
- [x] Same provider identity plus exact request/context/key version returns the retained local result; changed material/context under that identity returns conflict.

### Key, authority, and containment

- [x] Constructing the default Spring context creates no local signer bean and generates no key.
- [x] Explicit profile activation creates exactly the two configured ephemeral keys and no fallback key.
- [x] Resolved aliases expose only bounded non-secret session, algorithm, fingerprint, role/network, creation, and local classification metadata.
- [x] Unknown alias, role/network/algorithm mismatch, disabled/closed signer, and stale session version fail before signing.
- [x] A new adapter instance creates different aliases/versions and cannot sign a pending request bound to the prior session.
- [x] A completed Phase 4A result replays after adapter restart without provider invocation.
- [x] No private material appears in persistence, serialized evidence, captured logs, errors, health, metrics, public responses, repository files, configuration, or diagnostics.
- [x] Missing, empty, crossed, or invalid local profile configuration fails startup; it never activates a fallback.
- [x] Shutdown releases key references and attempts provider-supported destruction without claiming physical zeroization.

### Phase 4A lifecycle integration

- [x] An authorized request invokes the local provider exactly once.
- [x] Missing/pending/denied approval invokes it zero times.
- [x] Exact replay returns the durable signed result without re-signing.
- [x] Injected no-signature failure follows the existing bounded reauthorization/retry contract.
- [x] Injected ambiguity is inquired by stable provider identity and blocks blind re-signing.
- [x] Changed native material uses a separately authorized linked request/attempt.
- [x] Signature evidence advances no operation, submission, transfer effect/finality, reconciliation, or settlement state.

### Runtime and regression

- [x] Default context/readiness and existing public API/OpenAPI behavior remain unchanged.
- [x] Explicit `local-signer` context starts with only allowlisted local aliases and a clear safe startup warning.
- [x] No public signing endpoint exists.
- [x] Existing mint/burn, worker, transfer, security, participant isolation, persistence, and readiness tests remain green.
- [x] Enforcer and compiled-class `jdeps` retain `control-plane/adapters -> application -> domain`; Bouncy Castle exists only in the local adapter.

## Execution and evidence

- [x] Read Action Request 12 completely.
- [x] Confirm clean synchronized baseline and approved remote.
- [x] Read governing repository documents, standards, ADRs, Phase 4A plan, and matching workflows.
- [x] Inspect Phase 4A ports/service/persistence and Spring composition directly with `rg` and source reads.
- [x] Run the JDK 25 secp256k1/Ed25519 probe and record the provider decision.
- [x] Define the complete test matrix before production source.
- [x] Add adapter and control-plane RED tests; capture expected failures.
- [x] Implement the smallest adapter/profile composition and keep focused tests green.
- [x] Update living documentation and ADR evidence.
- [x] Run one self-review after focused tests are stable.
- [x] Invoke exactly one independent review after stable source; resolve in-scope Critical/Important findings.
- [x] Invoke Ponytail once on the final stable diff.
- [x] Run one complete offline reactor: `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify`.
- [x] Run focused Enforcer/`jdeps`, cryptographic, profile, replay/restart/inquiry, document-link, leakage/public-network, configuration, generated-artifact, PDF-blob, and diff gates; repeat the staged-only checks after exact staging.
- [x] Attempt one bounded tracked Graphify refresh; on failure restore only the three tracked artifacts, remove only attempt-created `.graphify_*`, record `tooling_deferred`, and continue.
- [ ] Re-fetch/fence live remote, stage only authorized paths, commit once as `feat: add isolated local signing adapter`, push non-force, and verify clean synchronization.

## Deferred by scope

Persistent or deterministic keys; production HSM/MPC/custody/KMS/Vault/remote signers; provider credentials; public signing APIs; Web3j/Foundry/Solidity/Anvil; Solana SDK/Rust/Anchor/SPL/validator; native transaction construction; nonce/gas/blockhash/RPC/submission; bank effects; transfer execution; observation; reconciliation; settlement; public networks; production custody; and production readiness.

## Evidence log

- **Baseline:** `git fetch origin main` followed by local/tracking/live-remote checks found `main`, `origin/main`, `FETCH_HEAD`, and live remote `main` all at `c739a048a44cd55139bde3dddfeb0f7c0ebff442`; the worktree was clean.
- **JDK provider probe:** `/opt/homebrew/opt/openjdk/bin/javac Probe.java` and `/opt/homebrew/opt/openjdk/bin/java Probe` passed the probe process. SunEC returned `Curve not supported: secp256k1 (1.3.132.0.10)` for EVM; SunEC Ed25519 produced a 64-byte signature with `verified=true`.
- **Offline dependency probe:** `./mvnw -o dependency:get -Dartifact=org.bouncycastle:bcprov-jdk18on:1.82 -Dtransitive=false` failed only because Maven offline mode could not resolve the not-yet-downloaded artifact. No `~/.m2` path was inspected.
- **Authorized dependency resolution:** one exact online `./mvnw dependency:get -Dartifact=org.bouncycastle:bcprov-jdk18on:1.82 -Dtransitive=false` invocation resolved the pinned POM and artifact; no repository-wide dependency download or cache inspection was performed.
- **RED evidence:** the adapter test command failed compilation because `LocalEphemeralSigner` did not exist; after those tests were green, the control-plane configuration test command failed compilation because `LocalSignerConfiguration` did not exist. The later stale-session integration test failed with `RetryableNoSignature` instead of manual review until inquiry was fenced by the bound session key.
- **Stable focused gate:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=LocalEphemeralSignerTest,LocalSigningAuthorityIntegrationTest,LocalSignerConfigurationTest,LocalSignerProfileIntegrationTest,DigitalBankingApplicationTests,HealthReadinessSmokeTests,OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test` passed twice, before and after review remediation. The final run passed 14 adapter/lifecycle tests and 13 control-plane/profile/readiness/OpenAPI tests across all six selected reactor modules.
- **Self-review:** direct source/diff/leakage review added explicit disallowed-role and startup-log redaction assertions and corrected an overstated profile-isolation sentence. The 586-line signer remains one cohesive provider/session/key/encoding boundary; extracting generic crypto wrappers would add seams without a second use case.
- **Independent review:** one read-only review reported no Critical findings, two Important findings, and one Minor documentation finding. The valid least-authority finding was resolved by reducing both profile-default role sets to `MINT_AUTHORITY` and proving that default. The recommendation to add a native local-network identifier was not applied: Phase 4A deliberately carries chain-neutral logical families (`ETHEREUM`/`SOLANA`) and binds opaque prebuilt payload/native-constraint digests; parsing or redesigning that contract in a signer would violate this slice and belongs to the future chain adapter. Public/mainnet identifiers are not representable in the typed local signer network allowlist. The Minor identity-derivation wording was corrected to match session/mode aliases, session/fingerprint key versions, and separate creation metadata.
- **Ponytail:** the single final minimalism pass found no removable dependency, wrapper, module, or speculative extension point. The one adapter, one provider dependency, two Spring configuration types, and cohesive signer/session/encoding implementation are the minimum boundaries needed to enforce the approved cryptographic and authority invariants; no production change was made from this pass.
- **Complete offline gate:** the one authorized `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` invocation passed all six reactor modules in 20.236 seconds. Fresh output records 404 tests with zero failures, errors, or skips: 254 domain, 50 application, 44 real-PostgreSQL persistence, 14 local-signer adapter, and 42 control-plane. Enforcer passed in every governed module, Flyway validated/applied V1-V4 from empty schemas, and the Boot artifact repackaged. No production, test, POM, runtime-configuration, or OpenAPI file changed after this gate.
- **Dependency boundaries:** offline Maven dependency-tree output pins the local adapter's single cryptographic dependency to `org.bouncycastle:bcprov-jdk18on:1.82`; its reactor-application POM warning is the expected packaged-not-installed result and the command succeeded. JDK 25 compiled-class `jdeps -s` reports domain -> JDK only; application -> domain/JDK; local signer -> application/domain/JDK plus unresolved external provider classes; and control plane -> signer/persistence/application/domain/JDK/JDBC plus unresolved approved runtime libraries. Source imports and Enforcer show no Bouncy Castle type in domain, application, persistence, or control-plane source.
- **Focused closeout:** seven changed Markdown documents passed local-link validation; the local-signer YAML and all six POMs parsed; `git diff --check` passed. Focused secret/private-key block/key-file/public-network/RPC scans returned no matches, and no authorized new file is executable. Git status contains only the intended module, profile composition/tests, POMs, documentation, ADR, and plan; ignored build outputs are absent from status.
- **PDF Git blob identity:** baseline and current index retain the same four mode-`100644` PDF blobs without reading PDF content: engineering companion `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`, reference implementation Volume III `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`, stablecoin architecture `ebe456d6e71685aca63312c8d7466f17a2b86828`, and executive brief `ae25c88118b3bb8356784d2f0f02f1096b034331`. `git diff --name-status` reports no `docs/reference` change.
- **Graphify — `tooling_deferred`:** the installed `graphify 0.8.47` and only the two pre-existing root transients (`.graphify_python`, `.graphify_root`) were confirmed before the one authorized `gtimeout 120 graphify update . --no-cluster` attempt. It exited 1 immediately with `Rebuild failed: [Errno 1] Operation not permitted`, before semantic extraction, clustering, or HTML. No retry, query, subagent, replacement extractor, manual merge, labeling, or reconstruction was used. The three tracked artifacts were restored explicitly from baseline `c739a048a44cd55139bde3dddfeb0f7c0ebff442`; they have no diff. The same two pre-existing transients remain, so no attempt-created `.graphify_*` file required removal. Existing Graphify artifacts remain non-authoritative navigation data.
