# Phase 5C Local Ethereum Wallet Transfer Execution Plan

**Status:** `complete`

**Goal:** Execute one exact, server-resolved `USER_WALLET_1` to `USER_WALLET_2` USDZELLE transfer on local Anvil through the existing durable delivery, signing-authority, EIP-1559 submission, inquiry, observation, and finality boundaries without adding a public API, contract change, burn, bank, reserve, or parent-saga behavior.

**Approved request:** Action Request 16.

**Starting baseline:** `main` at `afd1c81eca0fb0c4939d1e7600ddc6062fe4618f`. On 2026-07-17, local `HEAD`, `origin/main`, and freshly fetched `FETCH_HEAD` matched this SHA; the worktree was clean and `origin` was `git@github.com:johnwhitton/digital-banking.git`.

**Authority:** `AGENTS.md`, `SECURITY.md`, accepted ADRs 0002, 0005, 0006, and 0008, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/IMPLEMENTATION_STANDARDS.md`, `docs/TRANSFER_DEMO.md`, the completed Phase 4A/4B/5A/5B plans, and Action Request 16.

## Boundaries and implementation decisions

- Keep the public API and Phase 3C five-effect parent unchanged. This slice supplies an internal standalone wallet-transfer command because the accepted user-held product model defines wallet transfer as a separate durable operation and the Phase 3C parent cannot advance its third effect without executing out-of-scope bank and mint predecessors.
- Reuse existing `TransferId`, `TransferEffect.Id`, `AttemptId`, `TokenQuantity`, `ParticipantScope`, `SettlementNetwork`, wallet-reference, finality, delivery-outcome, signing-authority, and wallet-registry vocabulary wherever those semantics fit. Add no second quantity, finality, signer, or Ethereum transaction model.
- Resolve source and destination only through `WalletIdentityRegistry`. Require distinct enabled `USER_CUSTODY` identities on Ethereum with `USER_CUSTODY_TRANSFER`; bind their public addresses, registry versions, and key versions durably before signing.
- Authorize only the resolved source key through Phase 4A with action `TRANSFER`, key role `TRANSFER_AUTHORITY`, exact digest, exact quantity, source/destination, contract action, nonce/fees, wallet versions, policy version, and approval evidence. Destination, ADMIN, owner, and bank identities cannot substitute.
- Use only the inherited standard ERC-20 `transfer(address,uint256)` on the configured local token. Retain EIP-1559 type 2, chain `31337`, bounded fees/gas, per-source durable nonces, precomputed hash, submit-once fencing, inquiry-before-retry, canonical block checks, and exact single-event validation. Add no allowance, `transferFrom`, permit, arbitrary-call, replacement, or fee-bump path.
- Generalize the existing Ethereum codec and native submission/observation mechanics where their semantics are shared. Do not duplicate raw-transaction encoding, signer recovery, RPC classification, hash normalization, receipt-intent checks, canonicality checks, or evidence hashing.
- Add forward-only V6. Direct inspection proves V5 cannot safely represent this operation: its attempt is foreign-keyed to a mint/burn `token_operation` and mint outbox; its table, recipient, constraint, status, and observation columns encode mint-only zero-address event semantics. V1-V5 remain byte-identical.
- Persist one internal wallet-transfer aggregate/context, scoped command identity, outbox delivery, inbox/effect attempt lineage, immutable source/destination/contract/asset/wallet/finality context, native attempt/submission state, normalized observations, and four distinct finalities. Keep external calls outside database transactions.
- Compose transfer processing only under the accepted `local-demo` plus `local-ethereum` profiles; `local-signer` remains the separate Phase 5A mint mode and stays mutually exclusive with `local-demo`. Default runtime remains chain-disabled.
- Retain raw signed bytes only inside the existing durable submit-once ambiguity boundary if deterministic recovery requires them; never expose or log them, and prove absence from reports, responses, plans, packaged resources, and diagnostics.

## Test-first execution matrix

### Provider-neutral acceptance, authority, and delivery

- [x] Exact internal acceptance persists one stable wallet-transfer/effect identity, scoped canonical command, immutable resolved wallet context, four finalities, and one pending outbox event atomically.
- [x] Exact replay returns the original immutable context even after configuration changes; a changed caller-owned quantity, asset/unit, source, or destination under the same scoped identity conflicts without another outbox or effect.
- [x] Missing, disabled, same, non-user, wrong-purpose, wrong-network, wrong-address, or stale-version wallets fail before signing/submission.
- [x] Only the exact source user wallet can sign; destination, another user, ADMIN, owner, or bank substitution fails closed.
- [x] Claim/lease, inbox, redelivery, rollback, and restart preserve one active transfer attempt and at-least-once delivery truth.

### Ethereum construction, persistence, and recovery

- [x] Direct calldata has the exact `transfer(address,uint256)` selector, normalized destination, and generic positive bounded atomic quantity.
- [x] V6 migrates V1-V6 from empty PostgreSQL while V1-V5 remain unchanged; normalized constraints reject malformed or mismatched durable facts.
- [x] Per-source nonce allocation is concurrency-safe; same-source commands receive distinct nonces and different sources retain independent cursors.
- [x] The prepared EIP-1559 attempt binds chain, source, destination, contract, amount, wallet/key versions, fees, nonce, calldata digest, signing digest, and immutable finality policy.
- [x] Pre-submit no-effect failures may retry; submission start is fenced; response loss and ambiguous RPC results retain the original hash and require inquiry/observation without another submission.
- [x] Unresolved ambiguity remains non-terminal/manual review; no replacement or fee bump is created.

### Observation, finality, and integration

- [x] Observation rejects failed receipts, wrong transaction/source/nonce/contract, wrong/malformed/removed/duplicate/conflicting event, wrong destination/value, and noncanonical/reorged block evidence.
- [x] Success requires exactly one matching standard `Transfer` event plus canonical receipt/transaction evidence and the immutable confirmation policy; only blockchain finality advances.
- [x] Real Anvil proof deploys the unchanged contract, mints fixture `10,000` base units to the generated source wallet, transfers all `10,000` to the generated destination, proves balances `10,000 -> 0` and `0 -> 10,000`, proves total supply unchanged at `10,000`, and proves only the source signed.
- [x] Existing Phase 5A mint, Phase 5B registry/signer, Phase 4 authority, Phase 3 delivery/transfer, default readiness, and public API/OpenAPI behavior remain green.

## Stable-diff and closeout gates

- Use focused offline tests during development. Do not run Forge because Solidity is unchanged.
- After the source/documentation diff is stable, run exactly one independent review focused on source authority, arbitrary-call prevention, nonce races, ambiguity, event spoofing, reorgs, secret leakage, and overclaiming; resolve every in-scope Critical and Important finding with focused evidence.
- Run one Ponytail review of the stable diff and remove safe duplication or speculative surface without weakening invariants.
- Run exactly one final `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` after review fixes stabilize, unless a relevant source/build/configuration change or a gate failure requires another run.
- Run Enforcer/compiled `jdeps`, V1-V6 migration and focused regression evidence, changed-document links, changed structured-file parsing, secret/public-network/raw-transaction/generated-artifact/executable scans, ignored-local-artifact checks, and diff/staged-diff checks.
- Attempt one Graphify refresh with a hard 60-second limit, no query, retry, HTML, clustering/reconstruction, or delegation. On failure, restore only the three tracked artifacts to the starting baseline, remove only attempt-created transients, record `tooling_deferred`, and continue.
- Compare only the four tracked PDF Git blob IDs/modes to the baseline once before staging; do not read PDF contents.
- Archive this plan, re-fetch/fence live `origin/main`, stage only authorized Phase 5C files, commit once as `feat: add local Ethereum wallet transfer`, push non-force, and verify a clean synchronized state.

## Intentional deferrals

Redemption custody, burn, bank effects, reserve/liability accounting, Phase 3C parent execution, complete Demo A or Demo B, public APIs/OpenAPI changes, Compose/demo scripts, Solidity changes, allowance/`transferFrom`/permit, replacement transactions, public networks, production custody, Solana, and production readiness remain out of scope. Phase 5D redemption and ADMIN burn is the next bounded slice.

## Evidence log

- **Preflight:** clean synchronized approved `main`; approved remote; ignored `.env.local-anvil` exists at mode `0600`, is absent from the index, and retained the approved ten-wallet/chain-31337 structure without printing key material.
- **Design inspection:** the inherited OpenZeppelin ERC-20 supplies direct `transfer(address,uint256)` without changing `LocalReferenceToken.sol`. V5 is structurally mint-only, so V6 is required; no new dependency, ADR, API, OpenAPI operation, or contract change is required.
- **Focused RED/GREEN:** exact acceptance/context tests passed; direct calldata and exact-event tests passed; V1-V6 acceptance/replay/inbox/restart and concurrent per-source nonce tests passed against PostgreSQL; the persistence queue passed 15 tests after a clean build against its native V1-V4 schema, proving the optional V6 column reference does not contaminate the base adapter.
- **Consolidated native proof:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/ethereum-web3j -am -Dtest=EthereumWalletTransferVerticalSliceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` passed all three scenarios in one PostgreSQL/Anvil execution: exact `10,000`-unit transfer with unchanged supply, response-loss inquiry without resubmission, and source-registry rotation fencing before native preparation.
- **Documentation scope:** only `README.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, and this execution plan changed. `SECURITY.md` remains unchanged because existing local-key, signed-material, local-network, and non-production controls already govern the slice. No new architectural decision required an ADR.
- **Stable-diff reviews:** the one Ponytail pass found no safe speculative surface to remove without broadening the refactor; it did expose a restart gap after the durable blockchain-finality save. A focused injected-crash test failed with an incomplete delivery, then passed after redelivery from `CHAIN_FINALITY_REACHED` was made idempotently completable. The one independent review found two Important integrity gaps: retained native attempt facts were not revalidated against the accepted operation, and removed-plus-valid transfer logs could select the wrong evidence digest. Both were corrected with exact retained-context recomputation, exact non-removed log selection, and focused PostgreSQL/Anvil/event evidence; the combined four-test focused run passed.
- **Final offline gate:** the first `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` correctly stopped on one stale Phase 5A assertion that still expected five Flyway migrations. The assertion was updated to the forward-only V1-V6 reality, its focused six-test integration class passed, and the required full rerun then passed **436 tests** across all seven modules with zero failures/errors/skips and `BUILD SUCCESS`.
- **Graphify — `tooling_deferred`:** installed `graphify 0.8.47` was confirmed, then the single capped `/opt/homebrew/bin/gtimeout 60 graphify update . --no-cluster --no-viz` attempt exited 2 immediately because this CLI does not accept `--no-viz` on `update`. Per the no-retry/no-HTML instruction, no second command, query, extraction, clustering, reconstruction, or report generation ran. The three tracked artifacts were restored explicitly to baseline `afd1c81eca0fb0c4939d1e7600ddc6062fe4618f`; they remain clean. The ignored `graphify-out/.graphify_python` and `.graphify_root` pre-existed the attempt and were left untouched.
- **Dependency and closeout checks:** compiled-class JDK 25 `jdeps --multi-release 25 -s --ignore-missing-deps` preserved inward module direction; focused import scans found no Spring/Web3j/Bouncy Castle leakage across governed boundaries. Changed production/test inputs contain no fixed 64-hex secret, private-key block, seed/mnemonic, API/RPC credential, public-network endpoint, or signed-material logging. `.env.local-anvil` remains ignored, mode `0600`, and absent from the index. No POM, dependency, OpenAPI file, existing V1-V5 migration, PDF, executable, Graphify artifact, Solidity file, or production file changed after the successful full Maven gate.
- **PDF identity:** hash-only comparison retained the same four baseline mode-`100644` blobs: engineering companion `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`, reference implementation `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`, stablecoin architecture `ebe456d6e71685aca63312c8d7466f17a2b86828`, and executive brief `ae25c88118b3bb8356784d2f0f02f1096b034331`. No PDF content was inspected.
- **Documentation and diff:** all local targets in the five changed Markdown files resolve after plan archival; `docs/plans/active/` contains only its index; `git diff --check` passes; and the final allowlist contains only the authorized Phase 5C implementation, tests, V6 migration, required living documentation, and this completed plan.
