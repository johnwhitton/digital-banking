# Phase 5A Local Ethereum Mint Vertical Slice Execution Plan

**Status:** `completed`

**Goal:** Process one already-accepted mint through the existing durable delivery and signing boundaries, submit one EIP-1559 transaction to local Anvil, independently reconcile the receipt and mint event, and advance only blockchain/technical operation state justified by durable evidence.

**Approved request:** Action Request 13.

**Starting baseline:** `main` at `cf723ff9b9e1a42c5c2a58911c189d803148824e`. On 2026-07-17, local `HEAD`, `origin/main`, and fetched `FETCH_HEAD` matched this SHA; the worktree was clean and `origin` was `git@github.com:johnwhitton/digital-banking.git`.

**Authority:** `AGENTS.md`, `SECURITY.md`, accepted ADRs 0002, 0005, and 0006, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/IMPLEMENTATION_STANDARDS.md`, `docs/TRANSFER_DEMO.md`, the completed Phase 3B/4A/4B plans, and Action Request 13.

## Boundaries and decisions

- Reuse the existing `TokenOperationAccepted` delivery identity, token-operation aggregate, `SigningAuthorityService`, `SignerPort`, local ephemeral signer, PostgreSQL transaction patterns, and `ChainPort` lifecycle vocabulary. Add no second business API or signing authority.
- Keep the accepted ADR paths: `contracts/evm/` and `adapters/ethereum-web3j/`. Web3j and Ethereum transaction/RPC types remain in the adapter.
- Pin Foundry 1.5.1, local Solidity 0.8.25, OpenZeppelin Contracts v5.6.1 (MIT), and Web3j Core 4.14.0 (Apache-2.0). The repository owner authorized the single dependency-resolution step after the uncached dependencies were identified.
- Implement one non-upgradeable local reference token using OpenZeppelin ERC-20 and `AccessControl`, with `MINTER_ROLE`, two decimals matching the existing `USD_STABLE`/`USD` asset-unit catalog, and no burn, pause, permit, denylist, fee, bridge, governance, or upgrade behavior.
- Resolve the local signer alias and public address server-side. Derive the address from the signer's non-secret public key at the Ethereum adapter boundary; no adapter receives or exports a private key.
- Reserve nonces in PostgreSQL by local chain ID and signer address. Persist immutable EIP-1559 and finality-policy context, calldata/digest, signature evidence, signed bytes/hash, submission classification, bounded source/transaction/receipt/log observations, and reconciliation disposition in forward-only V5 structures.
- Keep database transactions short. Preparation/nonce reservation, signed-attempt persistence, submission outcome, observation, and operation progression are separate transactions; signer and RPC calls occur outside them.
- Treat a pre-send readiness outage as retryable only before the durable submission fence and without transmitting bytes. Treat response loss after Anvil acceptance as ambiguous. Reuse the precomputed hash for inquiry/observation and never build another transaction or reserve another nonce for that delivery.
- Give the local-Ethereum profile a mint-only PostgreSQL queue view; leave accepted burns and transfers untouched for separately authorized consumers.
- Use one explicit `local-ethereum` profile together with `local-signer`. Reject non-loopback RPC URLs and chain IDs other than the configured local chain. Contract deployment and role setup remain test/development harness responsibilities, not Spring runtime authority.
- Derive success only from an independently fetched successful receipt, matching transaction intent, canonical block identity, and exact OpenZeppelin `Transfer(address(0), recipient, amount)` event. Advance blockchain finality and the narrow technical lifecycle only; legal, customer-visible, and accounting finality remain untouched.

## Expected implementation map

- `contracts/evm/`: pinned Foundry configuration, reference token, focused Forge tests, OpenZeppelin submodule/remapping, and deterministic ABI/bytecode drift check inputs.
- `application`: the smallest framework-free mint-delivery orchestration and provider-neutral persistence/chain records required to coordinate preparation, signing, submit-once, inquiry, observation, and explicit outcomes.
- `adapters/ethereum-web3j`: forward-only V5 migration, explicit JDBC attempt/nonce/evidence store, deterministic ABI/EIP-1559 encoding, signature recovery/assembly, RPC submission/inquiry, receipt/event/canonicality observation, and real PostgreSQL/Anvil tests.
- `control-plane`: explicit local-Ethereum configuration/composition and real Anvil/PostgreSQL integration tests; default runtime and public API remain unchanged.
- `README.md`, `SECURITY.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, ADR 0002 or one bounded successor decision if required, the ADR index, and this plan.

## Test-first execution

- [x] Forge RED/GREEN proves authorized exact mint, exact `Transfer` event, unauthorized mint rejection, zero-address rejection, and explicit role separation.
- [x] Java RED/GREEN proves ABI/base-unit encoding, EIP-1559 signable digest and signed bytes against an independent Cast vector, low-`s`/recovery validation, and wrong/malformed signer rejection.
- [x] PostgreSQL RED/GREEN proves V1-V5 empty migration, nonce uniqueness, immutable attempt context, transactional redelivery fencing, fresh adapter/repository recovery, and ambiguous-attempt reuse.
- [x] Application RED/GREEN proves mint-only event dispatch, explicit outcome classification, separate durable transitions around signer/RPC calls, safe redelivery, and unchanged participant-facing operation status.
- [x] Real-Anvil RED/GREEN proves exact mint/balance/supply/event, detailed durable observation evidence, two concurrent nonces, pre-send retry with zero transmitted bytes, response loss after acceptance recovered by precomputed hash without resubmission, restart with a new signer session and changed runtime finality configuration without policy drift or a second mint, revert safety, and local chain/address fencing.
- [x] Existing focused default API/readiness tests remain green; final Enforcer and `jdeps` containment checks remain in closeout.

## Stable-diff and closeout gates

- Run focused tests during development and the final Forge suite once after contract tests stabilize.
- Obtain one independent review of the stable diff and resolve all in-scope Critical and Important findings. Run one Ponytail simplification pass.
- Run `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` exactly once after review fixes are stable, unless a relevant source/build change or failure requires another run.
- Run focused Maven/Anvil/PostgreSQL gates, Enforcer/`jdeps`, deterministic artifact drift, Markdown links, structured configuration, migration ordering, secret/private-key/public-network/generated-artifact scans, PDF Git-blob/mode comparison, and `git diff --check`.
- Attempt the normal tracked Graphify refresh once with a hard two-minute limit and no HTML, query, subagent, retry, or reconstruction. On failure, restore only the three tracked artifacts to the starting baseline, remove only attempt-created transients, record `tooling_deferred`, and continue.
- Re-fetch/fence live `origin/main`; stage only authorized Phase 5A files; commit once as `feat: add local Ethereum mint vertical slice`; push non-force; verify clean synchronized local/tracking/live remote state.

## Intentional deferrals

Ethereum burn, wallet transfer, the five-step transfer orchestration, fee replacement/cancellation, public networks, hosted RPC, persistent or production signing, production deployment/admin/upgrade authority, Solana, bank effects, accounting settlement, legal/customer finality, Compose, and production readiness remain out of scope.

## Evidence log

- **Preflight:** `git fetch origin main`; `pwd`; branch/status/SHA/remote/log checks. Clean synchronized `main` at the approved baseline and approved remote.
- **Toolchain:** Foundry/Anvil/Cast 1.5.1 (`b0a9dd9ceda36f63e2326ce530c10e6916f4b8a2`), local `solc` 0.8.25, Maven Wrapper 3.9.16, Java 25.0.2, and Docker 28.5.1 are available.
- **Dependency availability:** offline Maven resolution showed `org.web3j:core:4.14.0` was not cached; no OpenZeppelin checkout existed. The repository owner approved resolving Web3j 4.14.0 and OpenZeppelin Contracts v5.6.1 and no other dependency/software/container image.
- **Foundry RED/GREEN:** the final focused contract run passed 5 tests covering admin/minter separation, exact mint balance/supply/event, unauthorized mint, and zero-address rejection. No ABI/bytecode artifact is tracked, so no generated-artifact drift gate is required.
- **Transaction codec:** the two focused Java tests passed against an independent Cast EIP-1559 mint vector and rejected wrong, malformed, and high-`s` signatures without using `Credentials` or a private key.
- **Real infrastructure:** the focused adapter gate passed 8 tests: two independent Cast/Web3j codec vectors and six real PostgreSQL/Anvil scenarios. Those scenarios prove exact mint/evidence, concurrent nonces, retryable pre-send outage with zero transport calls, response-loss recovery by retained hash, restart under changed configuration without finality-policy drift or resubmission, unauthorized-minter revert safety, and V1-V5 migration. The earlier fresh-handler/fresh-adapter/new-signer-session recovery assertions remain covered.
- **Delivery isolation:** `PostgresOperationDeliveryQueueTest` passed 15 tests after adding a mint-only queue case. The filtered queue claims the accepted mint and reports only its own measurements while leaving accepted burn and transfer work pending.
- **Control-plane focus:** after composing the mint-only queue and explicit worker identity, `LocalEthereumPropertiesTest`, default context, and readiness smoke tests passed together (5 tests) with V1-V5 migration. The explicit profile rejects public/credentialed endpoints and wrong chain ID; the default runtime remains unchanged.
- **Documentation disposition:** README, security, design, implementation status, transfer-demo mapping, ADR index, accepted ADR 0007, and this plan changed because executable capability, module layout, dependencies, local commands, and status changed. OpenAPI and `docs/reference/README.md` do not change because the participant contract and code-to-publication navigation did not change. Reference PDFs remain immutable and will receive the single prescribed Git blob/mode comparison at closeout.
- **Ponytail simplification:** the stable diff retains one contract, one chain-adapter module, one forward migration, one mint handler, and one explicit profile. It adds no generated binding, SDK-wrapper hierarchy, deployment framework, endpoint, or speculative later-effect abstraction. One duplicate Enforcer exclusion was removed. The 760-line Web3j adapter received the required extraction review and remains one cohesive native build/sign/submit/inquire/observe boundary below the repository's 800-line design-smell threshold; splitting it now would add coordination wrappers without separating authority. No financial, authorization, durability, observation, or test evidence was simplified away.
- **Independent review:** the one authorized review found no Critical issues and four Important gaps: mixed-event worker consumption, incomplete durable observation detail, mutable restart finality context, and no reachable pre-send no-effect outcome. All four were resolved with the mint-only queue, immutable policy/confirmation columns, detailed source/transaction/receipt/log evidence, and a readiness fence that returns retryable no-effect before bytes can be sent. Focused PostgreSQL, Anvil, and Spring tests above prove the corrections; no second review cycle was started.
- **Final Foundry gate:** the stable `forge test` run passed all 5 contract tests with zero failures or skips. The cached-diff check then found and removed one trailing blank line from each new Foundry configuration input, so the permitted relevant-file rerun passed the same 5 tests again. Both runs reported no Solidity source change and skipped recompilation; Foundry's inability to write the user-level signature cache did not affect either suite or create a repository artifact.
- **Complete offline gate:** the one authorized `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` run passed all seven reactor projects in 25.502 seconds. Fresh output records 414 tests with zero failures, errors, or skips: 254 domain, 50 application, 45 real-PostgreSQL persistence, 14 local signer, 8 Ethereum adapter, and 43 control plane. Enforcer passed in every governed module, V1-V5 migrated from empty schemas where applicable, and the Boot artifact repackaged. No production, test, POM, migration, contract, runtime-configuration, or OpenAPI file changed afterward.
- **Dependency boundaries:** JDK 25 `jdeps --multi-release 25 -s` passed over compiled class directories: domain uses only `java.base`; application uses domain/JDK; persistence and the local signer use only inward modules/JDK plus unresolved approved libraries; the Ethereum adapter uses application/domain/JDK plus its unresolved approved libraries; and the control plane depends inward through the adapters. Enforcer and source-import scans confirm Web3j is absent from domain, application, persistence, and control-plane source, while Bouncy Castle remains confined to the local signer.
- **Focused closeout:** 73 local links across eight changed Markdown files resolved; the local-Ethereum YAML and all seven POMs parsed; the OpenZeppelin submodule metadata points to the approved upstream. Focused secret/private-key/credential, public-network/runtime-endpoint, forbidden-import, generated/cache/environment-artifact, Salus/trading, and scope scans passed. V1-V4 remain unchanged and V5 is the only new migration. The only directly added executable is the required Foundry Solidity-version shim; executable files inside the pinned OpenZeppelin gitlink retain their upstream modes.
- **PDF Git blob identity:** without reading PDF contents, the baseline and worktree retain the same four mode-`100644` PDF blobs: engineering companion `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`, reference implementation Volume III `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`, stablecoin architecture `ebe456d6e71685aca63312c8d7466f17a2b86828`, and executive brief `ae25c88118b3bb8356784d2f0f02f1096b034331`. No `docs/reference` path changed.
- **Graphify — `tooling_deferred`:** the single `gtimeout 120 graphify update . --no-cluster` attempt exited 1 immediately with `Rebuild failed: [Errno 1] Operation not permitted`. No query, HTML, clustering, retry, subagent, semantic reconstruction, or substitute report was used. The three tracked artifacts were restored explicitly to baseline `cf723ff9b9e1a42c5c2a58911c189d803148824e`; no `.graphify_*` transient existed before or after the attempt. Existing Graphify output remains non-authoritative navigation data.
