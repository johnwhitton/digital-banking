# Phase 5D Local Ethereum Redemption Custody and Burn Execution Plan

**Status:** `completed`

**Goal:** Execute one exact, server-resolved user-wallet transfer into ADMIN redemption custody and only then execute one separately signed ADMIN own-balance burn on local Anvil, with durable one-time custody evidence, independent native observation, and exact supply/balance proof.

**Approved request:** Action Request 17.

**Starting baseline:** `main` at `f436ff4aa9b581a335a844214d406de673ada473`. On 2026-07-18, local `HEAD`, `origin/main`, and freshly fetched `FETCH_HEAD` matched this SHA; the worktree was clean and `origin` was `git@github.com:johnwhitton/digital-banking.git`.

**Authority:** Action Request 17, `AGENTS.md`, `SECURITY.md`, accepted ADRs 0002, 0005, 0006, and 0008, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, and the completed Phase 5A-5C plans.

## Preflight evidence

- [x] Approved branch, remote, local SHA, tracking SHA, and freshly fetched live SHA match.
- [x] Worktree is clean before the plan is created.
- [x] `.env.local-anvil` exists, is mode `0600`, is ignored, is absent from the index/staged diff, and is structurally populated; no value was printed.
- [x] Direct source inspection confirms the accepted burn API already durably accepts exact burn operations without caller-supplied chain authority.
- [x] `LocalReferenceToken` has no burn function. The authorized smallest contract change is therefore required: a distinct `BURNER_ROLE` and `burn(uint256)` that burns only `msg.sender`'s balance.
- [x] V6 intentionally reconstructs both transfer endpoints as enabled user-custody identities and constrains calldata to `transfer(address,uint256)`. It cannot safely represent ADMIN redemption custody or a burn attempt without weakening its verified Phase 5C meaning.

## Minimal implementation design

- Keep the public token-operation API and OpenAPI unchanged. An accepted `BURN` remains the durable public operation. The local profile resolves the redeeming source wallet from trusted configuration and resolves `ADMIN_REDEMPTION` through the Phase 5B registry.
- Extend the existing internal wallet-transfer aggregate with one explicit purpose: `USER_CUSTODY_TRANSFER` or `REDEMPTION_CUSTODY`. Preserve Phase 5C's user-to-user rules; redemption alone permits an enabled user source and the configured ADMIN redemption destination. The user signs the custody transfer.
- Add forward-only V7 rather than changing V1-V6. V7 records the transfer purpose, one-to-one burn/custody correlation, one-time custody-evidence consumption, ADMIN burn attempt/observation facts, and bounded block-bound balance/supply observations. The existing shared `(chain, sender)` nonce cursor remains authoritative.
- Reuse the V6 transfer repository, handler, EIP-1559 codec, submit-once fence, hash inquiry, exact receipt/event checks, and finality lifecycle for custody. Add only the purpose-aware validation and persisted before/after custody facts required by this slice.
- Generalize the existing token-operation delivery handler only enough to run the same proven lifecycle for `MINT` and `BURN`. A thin redemption gate accepts/resumes custody, waits for exact custody blockchain finality, atomically consumes that evidence once, and then delegates the accepted burn to the shared lifecycle.
- Add one burn chain adapter/store using the existing `ChainPort`, transaction codec, shared nonce table, signing-authority service, EIP-1559 policy, submit classification, inquiry rules, canonicality checks, and event vocabulary. No replacement, fee bump, arbitrary calldata, or second burn path is added.
- Burn preparation re-reads the durable custody operation, attempt, canonical confirmed observation, reached blockchain-finality record, exact tuple, wallet/key/contract/policy versions, and one-time correlation. The ADMIN burn attempt is inserted only from that verified consumed correlation.
- V7 persists the signature digest/encoding and precomputed transaction hash but never the raw signed burn transaction. Signed bytes remain transient until the durable submission fence is claimed; restart before submission therefore fails closed to manual review, while restart after the fence uses the durable hash inquiry path.
- Burn observation requires the exact successful ADMIN transaction, nonce, contract, `burn(uint256)` calldata, canonical block, one non-removed `Transfer(ADMIN, zero, amount)` event, and the configured confirmation threshold. It records block-bound source/ADMIN balances and total supply before custody, after custody, and after burn.
- Update only `README.md`, `SECURITY.md` if the concrete burn rule requires it, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, and this plan. No ADR is needed unless implementation exposes an unapproved material decision.

## Test-first work

### Contract

- [x] RED/GREEN: unauthorized callers cannot burn.
- [x] RED/GREEN: an authorized ADMIN burns only its own balance.
- [x] RED/GREEN: insufficient balance reverts without state change.
- [x] RED/GREEN: supply decreases exactly and the standard ADMIN-to-zero event is emitted.
- [x] Keep mint behavior, decimals, admin, and minter-role behavior green.

### Redemption custody and durable correlation

- [x] RED/GREEN: exact generic quantity and server-resolved configured user source are retained; ADMIN redemption alias/address/version are immutable.
- [x] RED/GREEN: wrong/missing/disabled/stale/non-user source, wrong destination/purpose/network/contract/version, source=ADMIN, invalid quantity, and caller-controlled authority fail closed through the existing registry, acceptance, retained-context, and signer fences.
- [x] RED/GREEN: same replay resumes the original custody operation; conflict creates no second effect.
- [x] RED/GREEN: V7 migrates from empty PostgreSQL; restart/read-back retains purpose, correlation, finality, attempts, and evidence.
- [x] RED/GREEN: burn preparation fails before exact confirmed custody receipt/event/canonicality/finality.
- [x] RED/GREEN: mismatched, removed, orphaned, stale, ambiguous, or already-consumed custody evidence cannot authorize another burn; row locking plus the unique consumed-attempt binding fences concurrent consumption.

### ADMIN burn and native recovery

- [x] RED/GREEN: `burn(uint256)` selector and exact base-unit value are deterministic.
- [x] RED/GREEN: only ADMIN with `BURN_AUTHORITY` and current registry/key metadata signs; owner, bank, user, wrong purpose, or stale ADMIN fails.
- [x] RED/GREEN: ADMIN nonce allocation is durable and concurrency-safe and remains independent from user-wallet nonce allocation.
- [x] RED/GREEN: pre-submit no-effect, submit-once, response-loss inquiry, found-by-hash recovery, unresolved ambiguity/manual review, restart, and replay preserve one attempt/effect through the reused delivery/signing lifecycle and burn-specific durable attempt.
- [x] RED/GREEN: failed receipt and wrong hash/contract/sender/recipient/value/calldata, malformed/duplicate/conflicting/removed event, and non-canonical/reorg evidence never confirm burn.
- [x] RED/GREEN: custody leaves supply unchanged; burn decreases supply and ADMIN balance by the exact quantity with block-bound evidence.

### Integrated proof and regressions

- [x] One consolidated PostgreSQL/Anvil scenario mints `10_000` base units to a runtime-generated USER_WALLET_2 fixture, transfers exactly `10_000` to ADMIN, confirms custody, burns exactly `10_000` from ADMIN, and proves balances `user 10_000 -> 0`, `ADMIN 0 -> 10_000 -> 0`, and supply `10_000 -> 10_000 -> 0` with both exact events.
- [x] Existing Phase 5A mint and Phase 5C user-to-user transfer focused selections remain green.
- [x] Default Spring context remains chain-disabled; invalid local composition fails safely without key disclosure through the unchanged default/profile conditions and focused property tests; final smoke remains in the closeout gate.

## Stable-diff and closeout gates

- Use focused offline tests during development. Consolidate Anvil scenarios and do not run the complete reactor until the stable diff has completed its one independent review and one Ponytail review.
- Run one independent review focused on custody substitution, premature/double burn, authority confusion, arbitrary-holder burn, nonce races, ambiguity, spoofed/removed events, reorgs, secrets, and overclaiming. Resolve each in-scope Critical and Important finding with focused evidence.
- Run one Ponytail pass and remove only safe duplication/speculative Phase 6 surface.
- If Solidity changes, run one final stable `forge test` gate. Then run one final `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify`, except that an actual gate failure or later source/build-input change requires the corresponding rerun.
- Run Enforcer/compiled `jdeps`, V1-V7 empty migration/restart evidence, changed-document links, changed structured-file parsing, secret/raw-key/raw-transaction/public-network/generated-artifact/executable scans, `.env.local-anvil` checks, and diff/staged-diff checks.
- Inspect Graphify help/version at most once if needed, then attempt the supported refresh exactly once under 60 seconds with no HTML/query/retry/reconstruction. On failure, restore only the three tracked artifacts to the starting baseline, remove only attempt-created transients, record `tooling_deferred`, and continue.
- Compare the four reference PDF Git blobs and modes to the starting baseline once without opening any PDF.
- Archive this plan, re-fetch/fence live `origin/main`, stage only authorized files, commit once as `feat: add local Ethereum redemption and burn`, push non-force, and verify a clean synchronized local/tracking/live remote state.

## Intentional deferrals

Bank payout/credit/debit, reserve/liability accounting and release, complete redemption or settlement-only parents, Phase 6 orchestration, compensation mint/replacement/fee bump, public API/OpenAPI changes, Compose/scripts, public networks, production custody, Solana, PDF/publication changes, and Salus remain out of scope. Phase 6A synthetic reserves and mock banks is the next bounded slice after successful closeout.

## Evidence log

- **Plan created:** preflight and direct source/schema inspection established the minimal V7/reuse design above. No production, test, migration, contract, configuration, API, or documentation file other than this active plan has changed yet.
- **Contract RED/GREEN:** focused Foundry selection progressed from missing burn behavior to 9/9 passing contract tests covering distinct role, exact own-balance burn, insufficient balance, unchanged mint behavior, and exact ADMIN-to-zero event.
- **Application and persistence:** focused application, queue, V7 repository, replay, restart, one-time custody-consumption, retained-context, immutable balance-evidence, and property selections are green. A focused queue failure showed the shared adapter was parsing an optional Ethereum table even when its filter was inactive; the root fix carries transfer purpose in the durable outbox payload and removes the optional-table dependency. All 15 queue tests then passed.
- **Native regression:** `EthereumWalletTransferVerticalSliceIntegrationTest` passed 4/4, including the consolidated exact custody/burn response-loss scenario; `EthereumMintVerticalSliceIntegrationTest` passed 6/6 after its expected migration count advanced from V6 to V7. `EthereumBurnObservationTest` rejects wrong contract/source/destination/amount, removed, duplicate, conflicting, and malformed burn events.
- **Stable-diff reviews:** the single independent review reported no Critical findings and two valid Important findings: burn preparation did not yet require the complete reached-finality/confirmed-observation tuple, and V7 retained raw signed burn bytes. Focused RED/GREEN evidence now requires the matching `REACHED` blockchain-finality record, confirmed successful observation, confirmation threshold, transaction/intent/event/block tuple, and evidence reference before one-time consumption; V7 now retains hashes and lineage without a raw signed-transaction column. The one Ponytail pass found only the redundant redemption-only queue filter; it was removed in favor of the already-required local Ethereum demo queue.
- **Review remediation evidence:** the two focused V7 PostgreSQL tests passed 2/2 after first failing on the missing finality fence and raw-signed-transaction column; all 15 queue tests passed after the minimal filter removal; the affected consolidated Anvil custody/burn scenario passed 1/1 with one submission under synthetic response loss. `git diff --check` passed after remediation.
- **Minimalism decision:** the burn attempt store and adapter remain cohesive native/persistence boundaries even though their sizes trigger the documented extraction review. No new module, dependency, generic orchestration layer, replacement path, or speculative Phase 6 abstraction was introduced; extracting shared mint/transfer/burn machinery now would broaden a stable bounded slice.
- **Documentation:** `README.md`, `SECURITY.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, and `docs/TRANSFER_DEMO.md` now distinguish the bounded Phase 5D primitive from payout, reserve release, complete redemption, either demonstration, and production readiness. OpenAPI and PDFs remain unchanged.
- **Final native/build gates:** the stable Foundry suite passed 9/9. Maven 3.9.16 on JDK 25.0.2 passed the single offline `clean verify` across all seven reactor modules with 442 tests, zero failures, zero errors, and zero skips; the prescribed default-context/readiness selection then passed 4/4 without recompilation. Enforcer rules passed in the reactor, V1-V7 migrated from empty PostgreSQL, and compiled-class `jdeps --multi-release 25 -s --ignore-missing-deps` retained the approved inward dependency direction.
- **Graphify — `tooling_deferred`:** the single capped `/opt/homebrew/bin/gtimeout 60 graphify update . --no-cluster` refresh attempt exited 1 immediately because the local rebuild received `Operation not permitted`. No retry, query, clustering, report reconstruction, or HTML generation ran. The three tracked Graphify artifacts were restored explicitly to baseline `f436ff4aa9b581a335a844214d406de673ada473` and verified at blobs `665f42f5f26980afb72d1fbf13760ffbefd3013b`, `cff2a0dea1edb013e8abae2dc7caf81030f2e1f6`, and `9f86c483cb5924120c35324f72bfe3c42548950b`; the pre-existing ignored `.graphify_python` and `.graphify_root` markers were untouched.
- **Closeout checks:** 83 local links across the six changed Markdown documents resolve; the changed YAML parses; staged and repository-owned scans found no high-confidence key, seed/mnemonic, raw signed transaction, hosted/public-network default, or retained secret-bearing output. `.env.local-anvil` remained ignored, untracked, unstaged, absent from the package, unprinted, and mode `0600`. All four PDFs retained their baseline `100644` modes and exact Git blobs. No POM, dependency, OpenAPI, PDF, Graphify, Salus, executable, generated build output, or unrelated file is staged, and no source/build/configuration file changed after the successful Maven gate.
