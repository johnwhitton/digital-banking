# Phase 6C Settlement-Only Transfer Orchestration Execution Plan

**Status:** `verified`; ready for exact staging and the single action commit.

**Goal:** Execute one exact participant-scoped settlement-only transfer behind the existing transfer API by durably composing the verified Phase 6B acquisition, Phase 5C user-wallet transfer, and Phase 6B recipient redemption boundaries, then derive completion only after cross-child reconciliation.

**Approved request:** Action Request 20.

**Starting baseline:** `main` at `addc62f77f5aed50bf70d5e68ddb1e4a5172b560`. On 2026-07-18, local `HEAD`, `origin/main`, and freshly fetched `FETCH_HEAD` matched this SHA; the worktree was clean and `origin` was `git@github.com:johnwhitton/digital-banking.git`.

**Authority:** Action Request 20, `AGENTS.md`, `SECURITY.md`, accepted ADRs 0005 and 0008, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/IMPLEMENTATION_STANDARDS.md`, `docs/TRANSFER_DEMO.md`, and the completed Phase 3C/5A-6B plans.

## Preflight evidence

- [x] Approved branch, remote, local SHA, tracking SHA, and fetched live SHA match.
- [x] The worktree was clean before this plan was created.
- [x] `.env.local-anvil` exists at mode `0600`, is ignored and absent from the index, and was not read or printed.
- [x] The directly governing documents and predecessor plans were read once.
- [x] Direct source and V1-V9 inspection identified the existing transfer API/idempotency boundary, V3 historical five-effect aggregate, V9 acquisition/redemption parents, generic outbox/lease worker, exact bank/accounting services, Phase 5C transfer, local signing/chain observation, and participant-safe response patterns.
- [x] V3 and V9 cannot be rewritten safely: V3 fixes five historical effects and V9 fixes acquisition/redemption semantics. A V10 companion keyed by the existing transfer ID is the smallest compatible representation.
- [x] No new dependency, module, contract, public endpoint, workflow engine, broker, image, network, or ADR is required.

## Minimal implementation design

- Keep the existing request fields, principal-derived sender scope, V3 transfer/idempotency record, and historical five-effect view compatible. Add an optional participant-safe orchestration projection to POST/GET when a V10 settlement companion exists.
- Add one provider-neutral registered settlement-instruction resolver. The local fixture binds immutable enabled sender and recipient routes to `BANK_1/USER_1_BANK_ACCOUNT/USER_WALLET_1` and `BANK_2/USER_2_BANK_ACCOUNT/USER_WALLET_2`, with recipient `AUTO_REDEEM`; requests cannot provide wallets, route policy, ADMIN, keys, or child identities.
- Snapshot the instruction identities/versions, both participant/account/wallet routes and public wallet metadata versions, exact USD cents/base units, asset/network/contract, and existing accounting/conversion/fee/finality/payout/reconciliation policy versions at acceptance.
- Add one plain-Java settlement parent keyed by `TransferId` with four ordered boundaries: sender acquisition, user transfer, recipient redemption, and final reconciliation. Store only child/evidence references and safe parent status/history; child repositories retain all bank, journal, signing, native, finality, and detailed reconciliation truth.
- Add only `V10__create_settlement_transfer_orchestration.sql`. Keep V1-V9 byte-identical. Persist registered instruction versions, immutable accepted context, ordered boundary/history/child correlations, and one conclusion. Reuse `operation_outbox.transfer_id` for `SettlementTransferAccepted` and the existing lease/retry worker rather than adding a scheduler or work table.
- Extend transfer acceptance so V3 plus its V10 companion and one V10 outbox event commit atomically for the registered local route; historical/unconfigured V3 behavior remains readable and does not manufacture a companion.
- Advance one parent boundary per delivery. Use stable parent-derived idempotency keys to accept/resume an acquisition for sender scope, the exact Phase 5C transfer from the snapshotted sender wallet to recipient wallet, and a redemption under the snapshotted recipient AUTO_REDEEM instruction. Re-read authoritative children and project manual review/ambiguity; never call child HTTP APIs.
- Final reconciliation verifies exact parent/child quantity, route/version, fully reconciled acquisition and redemption, completed canonical wallet transfer, and the durable reserve/chain reconciliation result before completion. It adds no balance mutation, compensation, refund, or reversal.
- Generalize only the existing local Phase 6B participant and chain-evidence configuration needed for both configured user wallets. Preserve payout-before-burn, server-owned ADMIN, source-only and recipient-custody signing, default-disabled runtime, and user-held API behavior.

## Test-first slices

### 1. Parent and registered instruction invariants

- [x] Add behavior-first tests for immutable two-party route snapshots, exact cents/base-unit equality, four ordered boundaries, child identity stability, explicit unknown/manual-review states, and completion only after reconciliation.
- [x] Add application/persistence/integration tests for sender ownership, enabled/versioned recipient AUTO_REDEEM resolution, rejection of disabled, ambiguous, mismatched, or self-conflicting routes, exact replay/conflict, and no caller-controlled wallet or authority override.

### 2. V10 atomic acceptance, persistence, and delivery

- [x] Add real-PostgreSQL tests for V1-V10 empty migration, unchanged V3/V9 compatibility, atomic V3+V10+outbox acceptance, replay/conflict concurrency, participant-safe read, immutable instruction snapshots, optimistic progress, child uniqueness, rollback, lease/redelivery, and restart reconstruction.
- [x] Implement V10 and the minimum settlement repository/composition. Extend only the existing outbox aggregate constraint/queue behavior needed for `SettlementTransferAccepted`.

### 3. Existing-child orchestration

- [x] Add focused application tests proving one stable acquisition child, then one exact user transfer, then one recipient redemption, then final reconciliation; no later boundary begins early.
- [x] Cover child dispatch/replay/conflict, pending/unknown/manual-review/no-effect projection, acquisition-complete transfer failure with sender-held tokens, transfer-complete redemption delay with recipient custody retained, payout-complete burn delay without another payout, exhausted delivery, and restart-safe retained identities.
- [x] Implement the smallest internal coordinator that delegates to the existing Phase 6B and Phase 5C services and re-reads their authoritative repositories.

### 4. API, profiles, and consolidated proof

- [x] Add focused transfer API/security/OpenAPI tests for unchanged request shape, source-only sender authorization, registered recipient resolution, durable `202`, replay/conflict, safe GET/404, minimized child-oriented progress, and redaction.
- [x] Add one consolidated fresh PostgreSQL/Anvil proof from sender bank `10,000`, recipient bank `0`, zero reserve/wallet/custody/supply through exact acquisition, sender-to-recipient transfer, recipient redemption, payout, burn, and final reconciliation to sender bank `0`, recipient bank `10,000`, and all reserve/liability/wallet/custody/supply positions zero.

### 5. Documentation, review, and closeout

- [x] Synchronize only `README.md`, `SECURITY.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, the existing transfer OpenAPI, safe local configuration, and this plan.
- [x] Run one Ponytail review and exactly one independent review on the stable diff; resolve all in-scope Critical and Important findings with focused tests.
- [x] Run the single final offline Maven reactor after executable/review changes stabilize, then focused structural, security, link, migration, PDF, diff, and staging gates without repeating Maven for documentation-only evidence.
- [x] Attempt Graphify once for at most 60 seconds using the installed supported non-HTML command. On failure restore only the three tracked artifacts, remove only attempt transients, record `tooling_deferred`, and continue.
- [x] Archive this plan, re-fetch/fence `origin/main`, stage only the authorized allowlist, and validate the staged diff. The single action commit, non-force push, and synchronized-state verification necessarily follow this plan's inclusion in that commit and are recorded in the final handoff.

## Validation commands

Focused offline Maven selections will be recorded with observed RED/GREEN results. Consolidate PostgreSQL/Anvil scenarios into the smallest practical executions. After reviews and fixes, run the one final executable gate:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
```

Closeout also requires Enforcer and compiled `jdeps`, V1-V10 migration/restart/historical compatibility, changed OpenAPI/YAML/JSON/XML/TOML parsing, changed-document links, secret/PII/account/public-network/raw-transaction/generated-artifact scans, `.env.local-anvil` mode/ignore/index/package checks, one baseline PDF Git-blob/mode comparison, `git diff --check`, and `git diff --cached --check`.

## Stop conditions and deferrals

Stop for baseline/remote divergence, a required dependency/image, inability to resolve a recipient through an immutable enabled AUTO_REDEEM instruction, breaking transfer API change, nondeterministic duplicate-value evidence, child authority/finality bypass, public-network/credential requirement, or a required acceptance gate that cannot be resolved in scope.

Deferred: automatic compensation/refund/reversal, arbitrary recipient enrollment or route editing, public wallet-transfer API, Docker/demo scripts, BPM/broker selection, real banking/reserves/PII, production identity/custody/networking, contract changes, Solana, publications/PDF changes, and production settlement/readiness claims.

## Evidence log

- **Plan created:** The approved request and direct source/schema inspection established the V10 companion design above from baseline `addc62f77f5aed50bf70d5e68ddb1e4a5172b560`.
- **Test-first implementation:** Added the plain-Java four-boundary companion, registered instruction resolver/registry, child coordinator and delivery handler, forward-only V10 persistence, existing-transfer atomic acceptance/read projection, local-profile composition, two-user chain-evidence configuration, bounded metrics, OpenAPI, and the consolidated proof. V1-V9, Solidity, dependencies, POMs, PDFs, public endpoints, and default-profile authority remain unchanged.
- **Focused GREEN evidence:** Domain, application, delivery, transfer acceptance, PostgreSQL migration/repository/queue, API/OpenAPI, metrics, local-profile, and default readiness selections passed. The consolidated `UsdzelleWorkflowVerticalSliceIntegrationTest` most recently reported `tests=1`, `failures=0`, `errors=0` after the final production hardening.
- **Migration/route hardening:** PostgreSQL V10 rejects mutable instruction identity/routing, allows explicit enable/disable, persists immutable accepted snapshots, and now fails closed when more than one enabled instruction matches a sender or recipient rather than choosing one. The focused `PostgresTransferRepositoryTest` passed after adding the ambiguity proof.
- **Focused failure resolved:** `UsdzelleWorkflowPropertiesTest` retained Phase 6B's obsolete single-participant assertion after the chain-evidence adapter became an explicit map. The production rule correctly permits multiple distinct mappings and rejects duplicate participant, account, or wallet authority routes; the updated focused configuration/default-readiness/OpenAPI selection passed with zero failures.
- **Tooling note:** Concurrent Maven invocations briefly left invalid incremental ECJ output in generated `target/` classes. No source defect remained; affected generated targets were rebuilt sequentially, and all subsequent focused selections passed. No cache or dependency inspection was used.
- **Cohesion review:** `SettlementTransfer` is approximately 647 production lines. Its validated snapshots, four-boundary lifecycle, transition history, and reconciliation terminality are one cohesive domain invariant set; splitting it mechanically would distribute validation without reducing behavior. It remains below the repository's 800-line design-smell threshold. The other new production classes remain materially smaller and each owns one port, adapter, or orchestration responsibility.
- **Single Ponytail review:** The stable diff was reviewed once for speculative layers, duplicated child truth, unnecessary dependencies/modules/endpoints, generic orchestration, and avoidable refactoring. No valid reduction was found: the V10 companion, instruction lookup/resolution, repository, boundary executor, and handler each correspond to a required durability or authority seam; all bank, accounting, signer, chain, ambiguity, observation, payout, burn, and reconciliation behavior remains delegated to existing services. No dependency, module, workflow engine, public endpoint, compensation abstraction, or Phase 6D preparation was added.
- **Exactly one independent review:** The reviewer found no Critical issues and two Important issues. First, a definitive child no-effect result incorrectly marked the whole parent `FAILED_NO_EFFECT` even after a prior acquisition/transfer had moved value, and an unknown first child could not transition to proven no-effect. Second, delivery revalidated instruction expiry at the historical acceptance instant even though V10 permits expiry updates. Both findings were verified against the implementation and remediated without scope expansion.
- **Review remediation GREEN:** `SettlementTransfer.failCurrentNoEffect` now accepts authoritative active/unknown no-effect, reports `FAILED_NO_EFFECT` only before any prior completed boundary, and preserves partial progression as `MANUAL_REVIEW`. Domain and delivery tests prove both cases. `RegisteredSettlementInstructionResolver` now uses an injected clock for delivery-time instruction validity; a focused expiring-instruction test proves an instruction valid at acceptance but stale at delivery fails closed. The domain/application review selection and the local-profile Spring configuration selection passed after these changes. No second review or Ponytail cycle was run.
- **Final offline reactor GREEN:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` passed all seven modules with 498 tests, zero failures, zero errors, and zero skips. The first attempt exposed only two stale V9 migration-count expectations after adding V10; the second exposed one controller mock still targeting the pre-companion repository method. Those test-only expectations were corrected, their focused selections passed, and the permitted final-gate rerun then completed successfully. No production source changed after the successful reactor.
- **Structural and integrity checks:** Maven 3.9.16 and JDK 25.0.2 remain the build runtime; Enforcer passed inside the successful reactor. Compiled JDK 25 `jdeps --multi-release 25 -s --ignore-missing-deps` retained the approved inward module direction, and direct imports found no Spring, Jakarta, JDBC, Web3j, or Bouncy Castle leakage into domain/application. The changed OpenAPI and local YAML parse successfully. V10 is the only changed migration; V1-V10 empty migration, historical compatibility, restart, and repository behavior passed in the final reactor. Focused scans found no fixed secret, private-key block, provider credential, real account/PII identifier, raw-transaction logging, hosted/public-network runtime default, generated output, executable-mode change, POM/dependency, contract, or PDF change. Runtime URLs remain loopback-only. `.env.local-anvil` remains mode `0600`, ignored, untracked, unstaged, unread, and unprinted.
- **PDF and source integrity:** All four PDF working blobs match baseline mode `100644` and Git blobs `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`, `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`, `ebe456d6e71685aca63312c8d7466f17a2b86828`, and `ae25c88118b3bb8356784d2f0f02f1096b034331`; no PDF was opened, rendered, or extracted. No product, build, configuration, migration, API, or test file changed after the successful full reactor.
- **Graphify — `tooling_deferred`:** Reviewed Graphify 0.8.47 received the single capped `/opt/homebrew/bin/gtimeout 60 graphify . --update --no-viz` attempt. It exited before extraction because no permitted semantic backend was configured. No HTML, query, extraction, labeling, clustering, report generation, retry, subagent, or reconstruction ran; no attempt transient remained. The three tracked artifacts were explicitly restored and verified at approved baseline blobs `665f42f5f26980afb72d1fbf13760ffbefd3013b`, `cff2a0dea1edb013e8abae2dc7caf81030f2e1f6`, and `9f86c483cb5924120c35324f72bfe3c42548950b`. Existing Graphify output remains non-authoritative navigation data.
- **Remote fence:** Immediately before staging, a fresh `git fetch origin main` left local `HEAD`, `origin/main`, and `FETCH_HEAD` equal to the approved baseline `addc62f77f5aed50bf70d5e68ddb1e4a5172b560`; `origin` remains `git@github.com:johnwhitton/digital-banking.git`.
