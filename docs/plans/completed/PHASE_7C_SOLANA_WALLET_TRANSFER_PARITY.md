# Phase 7C — Solana Wallet Transfer Parity

Status: complete — implementation, validation, review, and repository closeout are complete; the sole action commit and non-force push carry this record

## Outcome

Implement one durable local-only Solana `USER_1` to `USER_2` wallet transfer for exactly `100.00 USDZELLE` (`10000` base units) through the existing wallet-transfer aggregate, signing authority, outbox worker, Sava adapter, and finalized observation boundary. Split the remaining roadmap into Phases 7D–7F without adding redemption/burn, parent orchestration, demonstrations, a public wallet endpoint, public networks, or production custody.

## Authority and baseline

- Action Request 24 is the owning specification and already-approved implementation design; it is not being reopened through a second speculative design document.
- Work is authorized sequentially on `main`, without a branch or worktree, from `aac18219d4fa32bfb93ec618cc5a15d9d7e1d576` on `git@github.com:johnwhitton/digital-banking.git`.
- At most one non-force commit is authorized, recommended as `feat: add local Solana wallet transfer`.
- `AGENTS.md`, `SECURITY.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, `docs/IMPLEMENTATION_STANDARDS.md`, accepted ADRs 0001/0003–0006/0008/0010, and the completed Phase 7A/7B plans govern the slice.
- ADR 0010 fixes native Agave 4.1.2, classic SPL Token, canonical ATA, exact checked-instruction, authority, and lifetime semantics. No new ADR is needed unless direct implementation evidence forces a new material choice.
- Graphify is advisory and receives one final non-blocking attempt only. The four PDFs are immutable Git objects. Salus is prohibited and will not be accessed.

## Preflight evidence

Completed on 2026-07-19 before edits:

- `pwd` returned `/Users/johnwhitton/dev/johnwhitton/digital-banking`; branch is `main`.
- `git fetch origin main`, local `HEAD`/`main`, `origin/main`, `FETCH_HEAD`, and `git ls-remote origin refs/heads/main` all returned the approved baseline.
- `git status --short --branch` returned only `## main...origin/main`; worktree and index were clean.
- The approved SSH remote is configured for fetch and push.
- The four PDFs remain mode `100644` with baseline blob identities `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`, `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`, `ebe456d6e71685aca63312c8d7466f17a2b86828`, and `ae25c88118b3bb8356784d2f0f02f1096b034331`; no PDF content was opened, copied, rendered, extracted, or hashed.
- `.env.local-anvil`, `.solana-tools/`, and `.solana-runtime/` are ignored/untracked/unstaged. `.env.local-anvil` is mode `0600`; tool/runtime/key/signing-result directories are mode `0700` and inspected key/result files are mode `0600`. Key and environment contents were not read or printed.
- Offline dependency-tree inspection confirms only Sava Core/RPC `25.8.0`, `json-iterator` `25.3.0`, and Bouncy Castle `1.85`; the expected missing internal-SNAPSHOT POM warnings do not affect the resolved external graph. No new artifact is needed or authorized.
- The one-time authority/document pass found no contradiction with ADR 0010 or the completed Phase 7B plan. Stale forward-looking `7C transfer and burn` / `7D demonstrations` wording is exactly the authorized roadmap reconciliation.

## Direct reuse trace and design

- Reuse `WalletTransferOperation`, `WalletTransferAcceptanceService`, `WalletTransferAcceptedDeliveryHandler`, `WalletTransferChainPort`, and `WalletTransferRepository`; do not add a Solana business aggregate or endpoint.
- Make acceptance and delivery use the operation's immutable `SettlementNetwork` instead of the current Ethereum constants. Ethereum remains the default compatibility path.
- Move the chain-neutral `PostgresWalletTransferRepository` and unchanged V6 migration resource from the Ethereum module to `adapters/persistence-postgres`; V6 already contains the common wallet-transfer aggregate and the runtime classpath remains semantically identical.
- Add only V12. It extends the common wallet-transfer address/route constraints to local Solana and generalizes V11's native attempt/signature/observation storage for `MINT` and `TRANSFER`, adding source owner/ATA/balance evidence and effect-specific signer-role constraints. V1–V11 contents remain unchanged and existing V11 data migrates as `MINT`.
- Reuse the Phase 7B codec, attempt store, ordered signature assembly, submit fence, inquiry, expiry/lineage, error classification, and RPC/account decoders. Rename/extract only demonstrated shared responsibilities now consumed by both mint and transfer.
- Add one transfer adapter path for canonical source/destination ATA derivation, optional idempotent destination ATA creation, exact classic `TransferChecked`, fee-payer plus source-owner signing, and finalized source/destination/supply observation.
- Extend the configured local Solana signer only for `TRANSFER_AUTHORITY`; add server-owned USER_1 and USER_2 wallet registry snapshots. USER_2 and mint authority do not sign the transfer.
- Compose only mint and standalone wallet-transfer events under `local-solana`; Solana burn/redemption/workflow events remain unsupported and unclaimed.
- OpenAPI and participant projections remain unchanged because this slice adds no public wallet-transfer resource or field.

## Minimal proposed file scope

### Application and persistence

- `application/.../WalletTransferAcceptanceService.java` and focused test.
- `application/.../delivery/WalletTransferAcceptedDeliveryHandler.java`, `application/.../port/WalletTransferChainPort.java`, `application/.../SigningAuthorityService.java`, and focused tests.
- `adapters/persistence-postgres/.../PostgresOperationDeliveryQueue.java` and focused queue tests.
- Move `PostgresWalletTransferRepository.java` and `V6__create_local_ethereum_wallet_transfer.sql` into `adapters/persistence-postgres` without changing V6 contents; update imports/tests.
- Add `adapters/solana-sava/src/main/resources/db/migration/V12__add_local_solana_wallet_transfer.sql`.

### Solana adapter, signer, configuration, and tests

- Generalize the existing Solana transaction codec and attempt store only where mint and transfer now share behavior.
- Preserve `SavaSolanaMintChainAdapter` and add the bounded wallet-transfer adapter plus focused codec/persistence/fake-RPC tests.
- Extend `LocalSolanaConfiguredSigner` and its tests for the configured USER_1 transfer-authority key.
- Extend `LocalSolanaProperties`, `LocalSolanaConfiguration`, `application-local-solana.yaml`, and focused context/property tests for USER_1/USER_2 identities, transfer routing, and mint compatibility.
- Update the public-only Solana fixture output/runbook only as required for the consolidated validator gate.
- Consolidate the real PostgreSQL/Agave gate into the existing opt-in validator test instead of adding a second validator harness.

### Documentation and plan

- `README.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, `scripts/solana/README.md`, and this plan.
- No PDF, OpenAPI, Solidity, Compose, dependency, or generated binding change is expected.

## Test-first execution

- [x] Record meaningful acceptance RED: the focused application suite discovered six tests and the new Solana case failed because the existing registry boundary rejected the trusted Solana identities. After deriving one network from both resolved wallets and validating that network's configured asset identity, the same six-test suite passed offline. Native accounts remain absent from the request contract.
- [x] Record meaningful signing-authority RED: the 13-test focused suite rejected a Solana transfer fee payer. The minimal action/role change now permits only Solana `TRANSFER` fee payers, retains burn/Ethereum denials, and the combined 19 acceptance/signing tests pass offline.
- [x] Implement the smallest network-aware acceptance/signing handler changes; run application GREEN plus Ethereum handler regression. The Solana path requires fee-payer then source-owner Ed25519 signatures, while the unchanged Ethereum path retains one source secp256k1 signature; the complete Ethereum wallet-transfer/repository regression is green.
- [x] Record codec RED: the Solana adapter test compile failed only because the transfer preparation/matching contract did not yet exist.
- [x] Implement shared codec/adapter behavior and run focused GREEN. Five codec tests preserve Phase 7B mint and cover canonical source/destination ATA derivation, destination present/absent instructions, exact opcode-12 `TransferChecked` data, fee-payer/source-owner ordering, mutation rejection, scale, and u64 bounds. The existing Sava adapter now implements both chain ports and branches only at effect-specific preparation/observation.
- [x] Record V12 real-PostgreSQL RED: the first isolated transfer run exposed that common V6 had moved but Ethereum-owned V7 was absent from the Solana test classpath, so `transfer_purpose` was missing. V12 now adds/checks that common column idempotently for both isolated and full-runtime migration sets. A second red exposed hard-coded mint signer hydration in the shared store.
- [x] Implement V12/shared attempt persistence and run focused GREEN. Fresh V1/V2/V3/V4/V6/V8–V12 migration succeeded; exact transfer then survived response loss/restart without resubmission and proved `10000 -> 0`, `0 -> 10000`, unchanged `10000` supply, ordered fee-payer/transfer-authority signatures, effect-kind/FK constraints, indexed transaction pre/post token balances, and separately retained source/destination/supply end-state evidence. The existing four-test mint integration also passed against V12 before transfer execution.
- [x] Add focused fake-RPC account/error/observation tests, signer tests, queue/routing/configuration/default-profile tests, and Ethereum/Phase 7B mint regressions. Six shared fake-RPC/PostgreSQL scenarios now include a V11-to-V12 schema restart plus transfer source/destination missing/wrong-owner/wrong-mint/wrong-program/frozen, insufficient balance, finalized failure, expiry, replay, response loss, and no-false-finality cases. The local queue claims Solana mints and wallet transfers while a burn and Ethereum wallet transfer remain pending. Five codec tests, three local-signer tests, 19 application acceptance/signing tests, local profile properties, the default context/readiness smoke, and the complete Ethereum transfer/repository regression are green.
- [x] Run one consolidated real PostgreSQL/Agave 4.1.2 scenario. An initial 33.95-second execution passed before the same test was strengthened to force transfer response loss; that relevant test-source change required the 36.71-second rerun. Review remediation then changed finalized transaction-balance evaluation, so the permitted affected-gate rerun passed one test with no skips in 34.38 seconds. It minted `10000` to USER_1, recovered the lost mint response, replayed acceptance, lost the transfer response after node acceptance, reconstructed signer/adapter/handler state, inquired by the same retained signature without a second submission, and finalized source `0`, destination `10000`, supply unchanged at `10000`, plus transaction-attributable and independently observed evidence. One setup invocation failed before test execution because its relative runtime path resolved below the module; the corrected absolute scoped path then ran the gate without changing source or validator state. The host sandbox reaps background validators at command boundaries, so the successful run held the exact loopback validator in one foreground session while approved fixture and Maven commands ran separately. The exact process was then stopped and `scripts/solana/status.sh --json` reported `stopped`.
- [x] Reconcile `README.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, and `scripts/solana/README.md` after executable gates established the verified boundary. The roadmap is now Phase 7C transfer verified, Phase 7D redemption/burn, Phase 7E product orchestration, Phase 7F demonstrations, and Phase 8 after 7F; no new ADR or OpenAPI change is warranted.

## Required stable-diff and closeout gates

- [x] Run Ponytail review exactly once on the stable diff and resolve valid in-scope findings with focused tests. The review found no removable dependency, duplicate aggregate/lifecycle, speculative module, wrapper, or optional abstraction: the diff extends the existing provider-neutral transfer port, V11 store/codec, local signer/profile, and queue. No change was warranted.
- [x] Run one independent review focused on transfer authority, duplicate-effect prevention, balance/supply evidence, routing, participant safety, and roadmap accuracy; resolve Critical/Important findings only. The single review found no Critical issue and four valid Important gaps: the signing-result/adapter-attachment crash window regenerated bound timestamps, transfer deltas came only from later account reads, V11-to-V12 coverage had no retained rows, and the design overview still denied the Solana adapter. Focused remediation resumes the exact durable signing request, persists and verifies indexed transaction pre/post balances separately from account end-state reads, migrates retained V11 attempt/signature/observation rows with validated new FKs, and corrects the living boundary sentence. The six-test PostgreSQL/Sava suite, affected application/signer selections, and the required real gate rerun are green; no second review cycle was started.
- [x] Run one final `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` after production code stabilizes; report discovered/executed/skipped totals accurately. The first closeout run exposed five persistence migration-count fixtures that still expected seven migrations after the unchanged V6 resource moved into that module, plus one reserve test's deliberately minimal duplicate V6 table fixture. Test-only corrections aligned those fixtures; the permitted affected-gate rerun then passed all eight modules in 57.637 seconds with 527 tests discovered, 526 executed successfully, and only the separately green opt-in validator test skipped.
- [x] Run Enforcer and compiled-class `jdeps`, focused Phase 7B mint and Ethereum transfer/policy regressions, and the prescribed default/readiness smoke. The four configured adapter Enforcer executions passed; the unscoped aggregator CLI goal has no configured default rules and was replaced by the correctly scoped `validate` invocation without a POM change. `jdeps` reports `domain -> java.base`, `application -> domain/java.base`, and no Sava, Web3j, Spring, or Jakarta dependency in core. The post-reactor Solana mint/transfer selection passed 11 tests, Ethereum transfer/persistence passed eight, and the default/readiness selection passed six.
- [x] Run structured-file, shell, migration-order, Markdown-link, secret/public-network/generated-artifact, executable-mode, PDF Git-identity, `git diff --check`, and complete diff/staging checks. YAML and shell syntax passed; V1–V12 each occur exactly once; 107 local links across the six changed Markdown files resolve; added material contains no secret literal, credential, public-network endpoint, Salus/trading reference, or generated runtime artifact; all existing modes are unchanged and new files are `100644`; no POM, dependency, OpenAPI, PDF, Solidity, Compose, or Graphify artifact changed; the four PDF index blobs remain the approved identities; and the final diff checks are clean.
- [x] Attempt one supported Graphify refresh after the stable diff, capped at 60 seconds; on failure restore only affected tracked graph artifacts/remove only new transients and record `tooling_deferred` without retry. `graphify update . --no-cluster` failed immediately with `[Errno 1] Operation not permitted`; `tooling_deferred` is recorded. The three tracked Graphify blobs remained identical to the baseline and no transient was created, so no restore/removal was necessary. No retry, reconstruction, query, clustering, report generation, or HTML generation was performed.
- [x] Stop local validator/database/application processes. `scripts/solana/status.sh --json` reports `stopped` with `rpcReady: false`; no Solana validator or Digital Banking application process remains, and test-scoped PostgreSQL containers were reaped.
- [x] Mark complete and move this plan to `docs/plans/completed/`, stage only the exact allowlist, and re-fetch the unchanged remote baseline. Immediately before the sole action commit, local `HEAD`/`main`, `origin/main`, `FETCH_HEAD`, and the live `refs/heads/main` all remained at `aac18219d4fa32bfb93ec618cc5a15d9d7e1d576`; the approved SSH remote was unchanged; the exact staged index passed diff/scope/mode checks with no unstaged change. The non-force commit/push and resulting clean local/tracking/fetched/live identities are final handoff evidence because they necessarily occur after this completed plan enters the action commit.

## Acceptance and intentional deferrals

Acceptance is the complete Action Request 24 gate: server-resolved immutable owners/ATAs/context, exact `10000`/two decimals, classic `TransferChecked`, fee payer plus USER_1 exact-message signatures, durable native identity/fencing/replay/inquiry/restart/lineage, finalized exact source decrement/destination increment with unchanged supply, preserved Phase 7B mint and Ethereum behavior, accurate roadmap split, one review of each kind, one final reactor, and clean remote closeout.

Phases 7D–7F remain planned: redemption-custody/burn, parent orchestration, and reproducible Solana demonstrations respectively. Public APIs/networks, production custody, Token-2022, custom programs, bridges/CCTP, delegates/extensions, priority fees, versioned transactions, automatic compensation, and production-readiness claims remain out of scope. Phase 7D is the next bounded recommendation after successful closeout.
