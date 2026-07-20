# Phase 7E — Solana Product-Path Orchestration

Status: complete

## Outcome and authority

Route the existing Phase 6B user-held acquisition/redemption parents and Phase 6C settlement-only companion to the verified Phase 7B–7D Solana mint, wallet-transfer, redemption-custody, and burn implementations. Preserve the existing product aggregates, public endpoints, durable child identity, payout-before-burn policy, accounting authority, recovery model, and participant-safe projections. Do not create a Solana product workflow or begin Phase 7F demonstration packaging.

Action Request 26 is the approved design and execution authority. Work is authorized sequentially on `main` from the unique-prefix baseline `e468f54`, resolved as `e468f544034cc1cec391c2f507f192d1b82f3c0c`, using one non-force commit recommended as `feat: orchestrate Solana product paths`.

Repository authority remains `AGENTS.md`, `SECURITY.md`, accepted ADRs 0001/0003–0006/0008–0010, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, `docs/IMPLEMENTATION_STANDARDS.md`, and the completed Phase 6A–6C and Phase 7A–7D plans.

## Preflight evidence

- [x] Repository root is `/Users/johnwhitton/dev/johnwhitton/digital-banking`; branch is `main`.
- [x] `HEAD`, local `main`, `origin/main`, freshly fetched `FETCH_HEAD`, and live `refs/heads/main` all equal `e468f544034cc1cec391c2f507f192d1b82f3c0c`.
- [x] The worktree/index were clean before this plan; the approved SSH origin is `git@github.com:johnwhitton/digital-banking.git`.
- [x] The four reference PDFs—`digital-banking-engineering-companion.pdf`, `digital-banking-reference-implementation.pdf`, `stablecoin-settlement-reference-architecture.pdf`, and `zelle-digital-asset-settlement-executive-brief.pdf`—retain mode `100644` and baseline Git blobs `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`, `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`, `ebe456d6e71685aca63312c8d7466f17a2b86828`, and `ae25c88118b3bb8356784d2f0f02f1096b034331`; no PDF content was opened or recomputed.
- [x] `.env.local-anvil`, `.solana-tools/`, and `.solana-runtime/` are ignored and untracked. Their inspected top-level modes remain `0600`, `0700`, and `0700`; no value or key content was read or printed.
- [x] Governing documents and predecessor plans were read once. Direct source and V9–V13 inspection found no accepted authority or custody conflict.
- [x] No dependency, image, module, endpoint, secret mechanism, custom program, public network, or additional tool is required.

## Reuse and handler-ownership map

- `UsdzelleWorkflow`, `UsdzelleWorkflowApplicationService`, `LocalUsdzelleWorkflowStepExecutor`, V9 persistence, and `UsdzelleWorkflowAcceptedDeliveryHandler` remain the only acquisition/redemption parent model.
- `SettlementTransfer`, `LocalSettlementTransferStepExecutor`, V10 persistence, registered instructions, and `SettlementTransferAcceptedDeliveryHandler` remain the only settlement-only companion.
- Existing stable parent-derived idempotency keys continue to own withdrawal, posting, mint, user transfer, redemption, payout, burn, and reconciliation child identities. No child identity is derived from a native signature or mutable configuration.
- `SavaSolanaMintChainAdapter`, its common attempt/observation store, the configured Ed25519 signer, and the Phase 7B–7D handlers remain the only Solana effect executors.
- The PostgreSQL queue remains the only lease/fence/retry worker. Its local-Solana view must claim the existing parent events plus only Solana-owned children, while the Ethereum view and handlers remain unchanged.
- The existing API network field selects `SOLANA`; the server continues to resolve wallets, mint, programs, fee payer, signer, RPC, cluster, policy, and native evidence.

## V14 decision

V14 is required, but only as a bounded forward migration:

- V9 constrains `usdzelle_workflow.settlement_network` to `ETHEREUM`.
- V10 constrains both registered settlement instructions and settlement parents to `ETHEREUM`, and contains only Ethereum instruction fixtures.
- `accounting_confirmed_evidence` lacks a durable chain-owner discriminator even though Phase 7E must validate either Ethereum or Solana authoritative observations under the same accounting posting types.

The existing V9/V10 parent context, child-reference uniqueness, history, outbox aggregate identity, wallet metadata, route versions, and policy fields already represent immutable Solana acceptance and recovery. V14 will therefore only:

1. allow `ETHEREUM` or `SOLANA` in the existing parent/instruction constraints;
2. add the two versioned local Solana registered instructions without altering Ethereum rows;
3. add the smallest chain-owner discriminator/constraints needed for authoritative accounting pointers and retained workflow evidence; and
4. preserve V1–V13 byte-for-byte, empty migration, V13 upgrade, historical Ethereum read/routing, and handler exclusivity.

No duplicate parent, workflow, queue, or universal chain schema is authorized.

## Test-first slices

### 1. Route and authority selection

- [x] Add focused failing tests for immutable Solana workflow/settlement context, exact `10000` units, registered route selection, replay/conflict, server-owned wallets, and handler mutual exclusion.
- [x] Implement the smallest chain-neutral local workflow composition and Solana parent dispatch while preserving Ethereum behavior.

### 2. V14 and authoritative evidence

- [x] Add real-PostgreSQL RED tests for V1–V14 empty migration, upgrade, existing Ethereum rows, Solana parent/instruction acceptance, parent/child/outbox correlation, queue claim exclusivity, and restart reconstruction.
- [x] Add V14 and the minimum Solana workflow-evidence/accounting query support. Validate finalized matching Phase 7B–7D observations; do not infer truth from RPC acknowledgement, signature existence, or aggregate balances alone.
- [x] Prove the same stable custody correlation is bound once, payout remains before burn, and native expiry replacement remains inside the existing burn lineage.

### 3. Focused configuration/API/security proof

- [x] Prove `local-demo,local-solana` composes only loopback Solana signing/effect ownership plus the existing synthetic bank/accounting/API boundaries; default and Ethereum profiles remain unchanged.
- [x] Update the existing workflow OpenAPI enum/examples only to add `SOLANA`; retain recursive schema/controller/security/redaction conformance.

### 4. Consolidated native orchestration and regressions

- [x] Run one consolidated PostgreSQL/Agave 4.1.2 execution covering Scenario B acquisition/redemption and Scenario A settlement-only transfer with exact bank, journal, wallet, custody, and supply assertions.
- [x] Include bounded replay and process-reconstruction evidence while retaining the already-green Phase 7B–7D response-loss evidence without rerunning those native primitives independently.
- [x] Run focused Phase 6A–6C, 7B–7D, Ethereum, API/OpenAPI, and default/readiness regressions.

### 5. Documentation, review, and closeout

- [x] Synchronize only affected living documents and this plan after executable gates establish the boundary. Phase 7F remains the next slice and Phase 8 remains planned.
- [x] Run exactly one full Ponytail stable-diff review and exactly one independent review. Resolve only valid in-scope Critical/Important findings with affected focused tests; do not start a second review cycle.
- [x] Run one final `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` after production stabilizes and report per-module Surefire/Failsafe totals accurately. The opt-in Agave test may be skipped only after the separate consolidated gate passes.
- [x] Run Enforcer, compiled `jdeps`, V1–V14/order/structured-file/link/security/public-network/mode/generated-artifact checks, PDF Git identity comparison, and diff checks.
- [x] Attempt Graphify once after the stable diff with the installed supported non-HTML command under a 60-second cap. On failure record `tooling_deferred`, restore only changed tracked Graphify outputs/remove only attempt transients, and do not retry or reconstruct.
- [x] Mark complete, move this plan to `docs/plans/completed/`, and prepare the exact authorized allowlist for the single non-force closeout commit. Commit, push, and final local/tracking/fetched/live synchronization are recorded in the handoff because they necessarily occur after this historical plan is frozen in its owning commit.

## Stop conditions and deferrals

Stop for baseline/remote divergence, an authority/custody conflict, need for a dependency/image/module/endpoint/secret mechanism, inability to prove one-handler ownership or exact finalized evidence, public-network/credential requirement, or an acceptance gate that cannot be resolved in scope.

Deferred: Phase 7F Compose/bootstrap/reset/demo/runbook packaging, Phase 8 final review, arbitrary participants/assets/routes, public wallet or chain endpoints, compensation redesign, Token-2022, custom programs, Neon, bridges/CCTP, delegates/extensions, priority fees/ALTs/durable nonce, public clusters, real banks/funds, production identity/custody, PDFs/publications, and Salus.

## Evidence log

- **Plan created:** preflight, one-pass authority review, direct source tracing, and V9–V13 schema inspection established the reuse map and bounded V14 decision above. No production, test, migration, configuration, API, OpenAPI, or living-document file changed before this plan was added.
- **First routing RED/GREEN:** the new real-PostgreSQL workflow test failed at the existing V9 `ck_usdzelle_workflow_network` constraint when persisting an otherwise valid immutable `SOLANA` acquisition. After adding only the first V14 constraint evolution and mutually exclusive parent queue ownership, the focused `PostgresUsdzelleWorkflowRepositoryTest` passed 6/6. It proves Solana context survives read/replay, an Ethereum queue cannot claim the Solana parent, and the local-Solana queue claims exactly that stable parent identity. The expected module-local Flyway count is now nine resources through V14; no V1–V13 file changed.
- **Persistence, evidence, and route GREEN:** focused PostgreSQL workflow, settlement, and accounting tests passed 25/25. V14 extends only the existing network constraints/instructions and adds a chain discriminator to accounting evidence. The Solana evidence adapter accepts only matching finalized attempt/observation rows and preserves existing one-time accounting/custody consumption.
- **Server-resolved multi-key authority:** the configured signer now permits the two required `TRANSFER_AUTHORITY` keys without making role selection public. The API/OpenAPI request has no wallet or key-alias field and rejects a caller-supplied `transferAuthorityKeyAlias`. Focused signer and Sava tests prove selection follows the immutable accepted source identity; accepted alias, address, `SOLANA` network, `USER_TRANSFER`/`REDEMPTION_CUSTODY` purpose, registry version, and key version are retained, and alias/address/version tampering fails before any native attempt.
- **Consolidated native gate:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am '-Dtest=SolanaProductPathOrchestrationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test` passed 1/1 in 2:50 against fresh PostgreSQL and Agave 4.1.2. It completed acquisition/replay/redemption and settlement-only orchestration, observed seven exact finalized `10000`-unit native effects, enforced payout before burn, reconstructed the durable queue, reconciled bank/accounting state, and finished with zero supply and wallet balances.
- **Harness-only cadence:** the native fixture uses `DeliveryRetryPolicy(100, 100 ms, 2 s)` only inside the opt-in test to poll Agave within its bounded 150-second deadline. Production `DeliveryWorkerProperties` remains at its established defaults and `application-local-solana.yaml` does not override delivery retries; expiry, inquiry-before-resubmission, and manual-review logic are unchanged.
- **Compact regressions:** persistence 25/25, configured Solana signer 5/5, complete fake-RPC/PostgreSQL Sava lifecycle 8/8, Ethereum endpoint fence 1/1, API/OpenAPI 4/4, and standalone local-demo/Solana-properties/default/readiness 8/8 passed. The bundle first exposed that the common local-demo signer profile had been narrowed too far; the existing failing standalone test drove the one-line correction to exclude only `local-solana`, and its affected rerun passed.
- **Single stable-diff reviews:** the one Ponytail review found no removable dependency, module, endpoint, duplicate product aggregate, or speculative abstraction. The one independent review found no Critical issue and three Important closeout gaps: a missing stable Solana workflow-evidence replay binding, token-child queue ownership that did not consult the retained parent network, and stale authoritative status/profile text. No second review was run.
- **Review-finding RED/GREEN:** the queue test first proved both workers could lease product mint/burn children, then passed after token-child ownership was joined to the immutable parent network while standalone token operations retained their local route. The Solana evidence test first proved a superseding finalized observation could create a second accounting pointer, then passed after V14 and the adapter bound network/slot/signature/balances/supply once to the stable workflow/step/effect/child identity and rejected a mismatch. Focused persistence/accounting tests passed 13/13, the complete fake-RPC/PostgreSQL Sava lifecycle passed 8/8, and the Ethereum retained-evidence regression passed 1/1. The only intervening failures were test-fixture cleanup/outbox assumptions exposed by the broader class runs; production semantics did not change to accommodate them.
- **Final offline reactor:** the first two `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` attempts failed only on five historical module-local Flyway-count assertions after V14 increased each owning classpath by one migration. Focused reruns passed 3/3 and 2/2 after correcting those expected counts. The required rerun then completed all eight modules in 1:01 with Enforcer green: domain 265/265, application 75/75, persistence 67/67, signer 25/25, Ethereum 21/21, Solana 19/20 with the separately gated native fixture skipped, and control plane 64/65 with the separately green Phase 7E Agave fixture skipped. Total: 538 discovered, 536 executed, zero failures/errors, two intentional skips.
- **Structural and repository checks:** compiled `jdeps` summaries retained the expected inward JDK/reactor dependency shape with no new forbidden direct boundary. Both changed YAML files parse successfully; V1–V14 migration execution and ordering are covered by the green PostgreSQL and final reactor gates. Changed-file inspection found no POM, dependency, PDF, Solidity, Rust, Compose, Dockerfile, Graphify, executable-mode, or generated-cache change. High-confidence added-line secret scanning found no credential/key material; runtime URL scanning found only loopback endpoints, while documentation mentions public networks only as disabled/deferred. `.env.local-anvil`, `.solana-tools/`, and `.solana-runtime/` remain ignored, untracked, and mode-restricted without content inspection. The four PDF Git blobs and modes match the preflight identities, structured-document parsing passed, and `git diff --check` is clean.
- **Graphify — `tooling_deferred`:** the sole capped non-HTML Graphify 0.8.47 attempt, `/opt/homebrew/bin/gtimeout 60 /Users/johnwhitton/.local/bin/graphify update . --no-cluster`, exited 1 immediately because its local watcher rebuild received `[Errno 1] Operation not permitted`. It generated no tracked diff, HTML, or `.graphify_*` transient. No retry, query, subagent, reconstruction, clustering, or report generation was performed; existing Graphify output remains non-authoritative navigation data.
