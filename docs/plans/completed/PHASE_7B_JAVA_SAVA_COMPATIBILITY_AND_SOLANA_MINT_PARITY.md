# Phase 7B — Java/Sava Compatibility and Solana Mint Parity

Status: completed — verified on the approved baseline and ready for the single action commit

## Outcome

Prove the smallest Java 25/Sava client set against the existing Agave 4.1.2 semantic boundary, then implement one durable local-only Solana mint through the existing participant-scoped mint API, operation/outbox lifecycle, signing authority, and blockchain-finality model. The slice ends at exact finalized mint evidence. Solana transfer, redemption, burn, product orchestration, custom programs, Token-2022, Neon, public networks, and production custody remain out of scope.

## Authority and baseline

- Action Request 23 is the owning specification.
- Work is authorized sequentially on `main` without a worktree.
- Approved baseline: `73047d375c3d36d9c890247cc8ee51e1ac0b69c3`.
- Approved remote: `git@github.com:johnwhitton/digital-banking.git`.
- At most one non-force commit is authorized, recommended as `feat: add local Solana mint vertical slice`.
- `AGENTS.md`, `SECURITY.md`, accepted ADRs, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, and `docs/IMPLEMENTATION_STANDARDS.md` remain governing authority.
- ADR 0010 fixes the native Agave/SPL, classic Token Program, canonical ATA, checked-instruction, exact-quantity, and blockhash-lifetime decisions. Phase 7B must not reopen them without contrary direct evidence.
- Graphify is advisory and deferred to one bounded final attempt. The four reference PDFs are immutable contextual inputs. Salus is out of scope.

## Preflight evidence

Completed 2026-07-19 before production edits:

- `git fetch origin main`, `git ls-remote origin refs/heads/main`, `git rev-parse HEAD`, `git rev-parse origin/main`, and `git rev-parse FETCH_HEAD` all identified the approved baseline.
- `git status --short --branch` returned only `## main...origin/main`; tracked worktree and index were clean.
- `git branch --show-current` returned `main`; the approved SSH origin is configured for fetch and push.
- Governing repository guidance, living documents, ADR and plan indexes, ADRs 0001/0003–0008/0010, and the completed Phase 7A plan were read once. No contradiction with Action Request 23 or ADR 0010 was found.
- The four PDFs remain tracked mode `100644` with baseline blob identities `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`, `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`, `ebe456d6e71685aca63312c8d7466f17a2b86828`, and `ae25c88118b3bb8356784d2f0f02f1096b034331`. No PDF content was opened, copied, rendered, extracted, or hashed.
- `.env.local-anvil`, `.solana-tools/`, `.solana-runtime/`, and inspected Phase 7A key paths are ignored and untracked. Existing secret/runtime directories are mode `0700`; inspected key files are mode `0600`. No key content or environment value was read or printed.
- `docker ps` reported no running containers.
- Maven offline `dependency:get -Dtransitive=false` reported the four proposed coordinates absent from the local cache. `~/.m2` was not inspected.
- Direct source tracing found the reusable generic `ChainPort`, `TokenOperationAcceptedDeliveryHandler`, Phase 4 signing service, V5 mint attempt/observation pattern, local signer boundary, Spring profiles, and Phase 7A key/runtime conventions. The current handler is single-signer/Ethereum-configured and the configured signer is EVM-only; both need the smallest explicit multi-signer/Solana extension rather than a parallel authority model.

## Dependency approval checkpoint

The repository owner approved the exact four-artifact set below on 2026-07-19. No other direct dependency, BOM, or `solana-programs` artifact is authorized.

### Proposed direct dependencies

| Coordinate | Role | Source/license | Java/cache | Maven Central JAR SHA-512 |
| --- | --- | --- | --- | --- |
| `software.sava:sava-core:25.8.0` | Public-key/Base58, native accounts/instructions, legacy message and transaction serialization, signature assembly/verification | `sava-software/sava` tag `25.8.0`, commit `d2cb6bdd37fb09e35377872ac8b128484fa8f2a2`; Apache-2.0 | Sava documents Java 25; not cached | `cce5bfac9890ebe8617eb4db52f5f9e19057aa31912ac214690ba11e6a1d2c06263bd65ff01935ad11c3aa8e3656100b30cdf5686880c0920f81a3cdf5295eb5` |
| `software.sava:sava-rpc:25.8.0` | Loopback Agave HTTP RPC requests and bounded response parsing for blockhash, status, transaction, account, slot, and commitment evidence | same source/tag/commit/license as Sava Core | Sava documents Java 25; not cached | `b69da3c3167e1b74b7aab707e5a9d1dcf4b43d8394ef3e34ee00b065b095db91da034b068d02c2ff02ea9796539e145cc20f4638eaeb95fb9ab1b9b06d203f4d` |

### Material transitives and version alignment

| Coordinate | Introduced by / disposition | Source/license | Java/cache | Maven Central JAR SHA-512 |
| --- | --- | --- | --- | --- |
| `software.sava:json-iterator:25.3.0` | Compile transitive of `sava-rpc`; no further POM dependencies | `sava-software/json-iterator` tag `25.3.0`, commit `9ab510e63e736e28c5656e329315423a699cc0d4`; Apache-2.0 | Published for the Java-25 Sava RPC line; executable compatibility remains a gate; not cached | `c4f2774089e37a4a21f0f267090c5aab27db735d6de3623b5b0e13e61a1c21a6ac8b07a145bad94089a7e5c5d2f40d68f90cd1d10cf83e8874e2a2f778f7f1c0` |
| `org.bouncycastle:bcprov-jdk18on:1.85` | Runtime transitive of `sava-core`; align the root managed version and existing `signer-local` direct dependency from 1.82 to 1.85 so one tested version exists on the composed classpath | `bcgit/bc-java` tag `r1rv85`, commit `57fbd3c501f7a369f64eda311d6a709fc0bcae84`; Bouncy Castle License (MIT-style) | Java 18+ line, compatible target Java 25; not cached | `33bbcbc3ad823898bda1fec8cea270fffa4688886b1c351afd93e713f41d29afd71aae9982cbe896d510aa98fece8b9a66c132536625801fe2c1af283a76e2b5` |

The complete proposed graph is `sava-rpc -> sava-core + json-iterator`, `sava-core -> bcprov-jdk18on (runtime)`, with no other POM transitives. Existing signer-local cryptographic tests must pass unchanged on 1.85 before integration continues.

### Resolved graph evidence

Resolution used Maven Central only and fetched exactly the four approved artifacts. The final offline dependency tree is:

```text
digital-banking-solana-sava
+- software.sava:sava-core:25.8.0
|  \- org.bouncycastle:bcprov-jdk18on:1.85 (runtime)
\- software.sava:sava-rpc:25.8.0
   \- software.sava:json-iterator:25.3.0

digital-banking-signer-local
\- org.bouncycastle:bcprov-jdk18on:1.85
```

No `solana-programs`, BOM, second Bouncy Castle version, or other direct/material transitive is present. Running the module-only tree before installing internal reactor artifacts emits expected warnings for the missing local SNAPSHOT POMs but still resolves and reports the complete filtered external graph; the reactor compile/tests provide the internal classpath evidence.

`software.sava:solana-programs:25.0.2` is deliberately excluded: its upstream repository says it will no longer receive updates, and its POM pins Sava Core/RPC 25.3.1 rather than the current 25.8.0 line. The two required classic-SPL instructions will use the small public instruction primitives in Sava Core and golden native layouts. No BOM/version catalog, IDL/Anchor client, Ravina, KMS, Geyser, web2, JavaScript, GitHub Packages repository, or convenience bundle is proposed.

### Approval and resolution procedure

- Approval received for the two direct artifacts, one parser transitive, and Bouncy Castle 1.85 alignment.
- After approval, resolve only those coordinates from Maven Central once, copy only inspection-needed artifacts to ignored `target/dependency-inspection/`, verify downloaded checksums against Maven Central, and record the actual dependency tree.
- If the exact compatibility matrix needs another direct/material artifact, stop for separate approval rather than expanding transitively.

## Minimal implementation shape

- Add one `adapters/solana-sava` Maven module; only it imports Sava types.
- Add exactly one forward-only `V11` migration owned by that module for Solana mint attempts, ordered public signatures, submit fencing, replacement lineage, bounded observations, and immutable native acceptance context. Do not edit V1–V10.
- Generalize the existing provider-neutral mint delivery/signing seam only enough to express an ordered fee-payer and mint-authority signature set over one exact message. Preserve the single Ethereum signer path and existing API behavior.
- Add explicit fee-payer signing purpose/role while retaining ADMIN mint authority. Raw Phase 7A keypair material is loaded only inside an opt-in local signer implementation; Sava receives public message/signature material only.
- Add an explicit disabled-by-default local Solana profile that accepts only loopback RPC, the Phase 7A cluster identity/programs/mint/roles, canonical USER_1 ATA, and finalized observation policy.
- Preserve the existing provider-neutral mint endpoint/OpenAPI unless executable conformance proves a correction is required.
- Keep adapter responsibilities cohesive: configuration/identity validation, message construction, signature assembly, submission/inquiry, observation, and durable attempt storage. Add no adapter factory, generic chain SDK wrapper, second worker, or speculative Phase 7C seam.
- Add ADR 0011 only if the implemented Sava isolation plus ordered multi-signer/recent-blockhash recovery decision is materially durable and not already fully governed by ADR 0010.

## Test-first execution ledger

1. Dependency approval and exact resolution/checksum/tree inspection.
2. RED then GREEN Java 25/Sava compatibility tests for the twelve required matrix items, including Phase 7A golden public vectors and no SDK-held signing.
3. RED then GREEN provider-neutral ordered multi-signer/signing-purpose behavior while preserving Ethereum tests.
4. RED then GREEN V11 empty/V10 upgrade, race, rollback, restart, fence, ambiguity, expiry/replacement, and bounded-evidence persistence against real PostgreSQL.
5. RED then GREEN Sava adapter build/signature assembly/submission/inquiry/observation and Spring local-profile routing/configuration safety.
6. One consolidated real PostgreSQL/Agave 4.1.2 vertical slice covering exact mint, replay, lost response, restart recovery, Ethereum selection, and safe unsupported Solana effects.
7. Stable-diff Ponytail review once, independent review once, focused remediation only for valid in-scope Critical/Important findings.
8. One final `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify`, followed by Enforcer/`jdeps`, the smallest Phase 7A smoke, focused document/config/link/security/network/generated-artifact checks, and `git diff --check`.
9. One Graphify refresh attempt capped at 60 seconds after the stable diff; fail open as `tooling_deferred` without retry or reconstruction.
10. Synchronize affected living docs, complete and move this plan, enforce the exact staging allowlist, fence the remote, commit once, push non-force, and verify clean synchronization.

## Acceptance and deferrals

Acceptance is the Action Request 23 gate: exact Java/Sava compatibility; Sava isolated to one module; existing API replay; immutable server-owned Solana context; exact `10000`/two-decimal ATA plus `MintToChecked`; ordered verified Ed25519 signatures through the signing boundary; pre-submission signature identity; durable submit/inquiry/expiry/replacement evidence; finalized independent matching observation; blockchain finality only; V11 migration/restart/concurrency evidence; preserved Ethereum/default behavior; one review cycle of each kind; and clean remote closeout.

Intentional deferrals remain Solana transfer/redemption/burn, Phase 7D demonstrations, custom Rust/SBF/Anchor/IDL, Token-2022, public clusters, production custody, real financial integration, automatic compensation, and every non-blockchain finality claim.

## Implemented decisions and focused evidence

- Added only `adapters/solana-sava`; Sava-native types remain inside it. The application gained the smallest ordered `SigningRequirement` extension, while the existing single-signer Ethereum default remains source-compatible.
- Added `FEE_PAYER` as a distinct signing role and one `local-solana` profile that is mutually exclusive with all existing local chain/signer profiles. The caller-facing mint OpenAPI is unchanged because route, programs, fee payer, authority, destination, policy, and finality remain server-owned.
- Added V11 only. It extends the V4 signing-role constraint forward and stores immutable Solana context including policy and fee limits, ordered signatures, one bounded active local-route preparation lane, submit fence, recent-blockhash lifetime, replacement lineage, and bounded observations without changing V1-V10.
- Added a local Solana signer using JDK Ed25519. It validates mode-`0700`/`0600` paths, rejects symlinks and mismatched roles/identities/versions, parses keypair JSON without creating an immutable private-key string, verifies private/public pairs and every signature, and supplies Sava no raw-key access. It retains only the binding and public 64-byte signature in ignored mode-restricted `signing-results/`, allowing provider inquiry after process restart without a second signing effect.
- ADR 0010 already fixes classic SPL, checked instructions, canonical ATA, role separation, exact quantities, and native lifetime/evidence. Sava isolation and the provider-neutral ordered signer extension implement those decisions without adding a new material architectural choice, so ADR 0011 was not created.
- `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl domain,application -am test` passed 338 tests after the multi-signer and durable-result-recovery extensions.
- `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/signer-local -am -Dtest=LocalSolanaConfiguredSignerTest,LocalConfiguredSignerTest,LocalEphemeralSignerTest,LocalConfiguredSigningAuthorityTest,LocalSigningAuthorityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` passed 22 tests on Bouncy Castle 1.85, covering existing Ethereum secp256k1, both Ed25519 signer modes, and process-restart inquiry of the original public signature.
- Sava compatibility and codec tests passed 8 tests. A randomized assertion initially assumed Base58 signatures always have 88 characters; the root cause was variable Base58 text length, and the corrected test compares the exact retained 64 signature bytes.
- The real PostgreSQL/HTTP adapter suite passed 4 tests for exact `10000` atomic-unit mint/replay, immutable policy/fee reconstruction after configuration rotation, two ordered signatures, finalized matching evidence, lost-response inquiry, handler reconstruction, explicit expired-attempt replacement lineage, forced signature rollback, partial-signature restart reconstruction, a concurrent single-winner submit fence, and serialization of two distinct mint preparations until the first authoritative observation releases the bounded local route. The replacement test first exposed that V11 incorrectly made `delivery_id` unique even though one durable delivery may own an explicitly authorized follow-up native attempt; removing only that uniqueness constraint preserved the foreign key and attempt identities while allowing the tested lineage.
- The stable-diff Ponytail pass found one speculative authority expansion: `FEE_PAYER` had been allowed for transfer and burn as well as mint. The application policy and V11 constraint now authorize that role only for a Solana mint; focused service and real-PostgreSQL tests pass. No dependency, wrapper, factory, generalized chain abstraction, Phase 7C hook, or other removable scope was retained beyond the requested adapter, codec, JDBC store, and configured signer responsibilities.
- `SavaSolanaMintChainAdapter` is 870 lines, so it received the required extraction/cohesion review. Its configuration validation, native preparation, signature assembly, submit-once/inquiry, and independent observation methods all implement the single Phase 7B adapter lifecycle; splitting them now would create speculative internal abstractions before a second Solana effect establishes a stable seam. The codec and JDBC store are already separate cohesive collaborators, so the remaining class is retained deliberately and must be reconsidered when Phase 7C introduces another effect.
- The single independent stable-diff review found no Critical issue and four valid in-scope artifacts: mutable configuration had been used when reconstructing retained attempts; a crash after durable signing could lose attachable signature material; concurrent preparations could share stale supply/balance snapshots; and native identity/path constraints were too narrow or incomplete. Remediation persists immutable policy/fee values, recovers the original verified public signature by durable provider inquiry, serializes the one bounded local route until observation, accepts the complete Base58 signature length range, constrains fee-payer authority to Solana mint, and verifies the immediate key directory mode. The affected application, signer, and four-scenario PostgreSQL suites pass after remediation; no second review cycle was started.
- `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=LocalSolanaPropertiesTest,DigitalBankingApplicationTests,HealthReadinessSmokeTests -Dsurefire.failIfNoSpecifiedTests=false test` passed 6 tests and applied all eleven migrations from an empty PostgreSQL schema. The default profile remained chain/signer-disabled.
- The conditional real Agave 4.1.2/PostgreSQL gate passed 1 test after a fresh public-only fixture and passed once more after the review remediation changed signer recovery and V11. It accepted exactly `100.00`/`10000` atomic units, deliberately lost the response after native acceptance, closed and reconstructed the signer/adapter/handler, submitted no second transaction, waited for finalized rooted evidence, and asserted exact mint supply, canonical ATA balance, durable completion, blockchain finality, replay, and submit fence. The initial five-second test wait was shorter than local rooted finalization; direct public RPC/Sava diagnostics proved the effect and all evidence matched, so the test now uses a bounded 45-second condition wait. Temporary diagnostic code was removed.
- `scripts/solana/stop.sh` stopped the validator after the gate. Ignored tools, key files, ledger, logs, and public runtime evidence remain untracked and are excluded from staging.
- The single bounded Graphify attempt, `/opt/homebrew/bin/gtimeout 60 /Users/johnwhitton/.local/bin/graphify update . --no-cluster`, failed immediately with `[Errno 1] Operation not permitted` and is recorded as `tooling_deferred`. No retry, clustering, HTML, tracked Graphify change, or `.graphify_*` transient was produced; existing Graphify artifacts remain non-authoritative navigation data.
- The first final reactor attempt exposed five existing Ethereum mint-test failures because the new prepared-policy equality guard had been applied across both chains. The focused fix keeps that immutable-policy guard Solana-only, preserving Ethereum's established separate signing and finality policy identifiers. `EthereumMintVerticalSliceIntegrationTest` then passed 6/6 and `SavaSolanaMintVerticalSliceIntegrationTest` passed 4/4 before the permitted repeated full gate.
- `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` then completed successfully across all eight modules: 521 tests discovered, 520 executed successfully, zero failures, zero errors, and one skipped opt-in private-validator test that had already passed separately. The prescribed post-reactor default-profile smoke passed 5 tests. The final filtered dependency tree contains only Sava Core/RPC 25.8.0, `json-iterator` 25.3.0, and Bouncy Castle 1.85; `jdeps -s` confirms Sava remains outside domain/application bytecode.
- Final focused closeout checks resolve 107 local links across the eight changed Markdown files; parse the Solana YAML and all eight POMs; validate the fixture as POSIX shell; find no high-confidence private-key/credential literal, embedded keypair array, Sava import outside its adapter, unauthorized BOM/`solana-programs` dependency, production public-network endpoint, PDF change, Graphify artifact, or executable surprise. The only public-network strings are two explicit rejection fixtures in `LocalSolanaPropertiesTest`. All four PDFs retain baseline mode `100644` and Git blob identities, and `docs/plans/active/` contains only its index. No production or build file changed after the successful repeated reactor.
- A final `git fetch origin main` left `HEAD`, `origin/main`, and `FETCH_HEAD` equal to approved baseline `73047d375c3d36d9c890247cc8ee51e1ac0b69c3` on `git@github.com:johnwhitton/digital-banking.git`. The exact 34-file allowlist is staged with no unstaged change, and the cached diff/check, PDF exclusion, Graphify exclusion, and single executable-mode addition were inspected before the authorized commit and non-force push.
- No PDF, OpenAPI, Solidity, Compose, Ethereum production source, or API schema changed. Living-document changes are limited to the required Phase 7B capability, topology, dependency, security, native-evidence, command, and next-slice status.
