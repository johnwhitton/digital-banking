# Phase 5B Local Multi-Wallet Custody Execution Plan

**Status:** `completed`

**Goal:** Add an opt-in, configured local-EVM custody profile that resolves the approved server-owned wallet identities and signs only through the existing durable Phase 4A authorization boundary, without enabling a chain effect or public signing API.

**Approved request:** Action Request 15.

**Starting baseline:** `main` at `ef7b8e36e093bb8a53442f72117a3dcd5b4c93b5`. On 2026-07-17, local `HEAD`, `origin/main`, and fetched live `FETCH_HEAD` matched this SHA; the worktree was clean and `origin` was `git@github.com:johnwhitton/digital-banking.git`.

**Authority:** `AGENTS.md`, `SECURITY.md`, accepted ADRs 0006 and 0008, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/IMPLEMENTATION_STANDARDS.md`, `docs/TRANSFER_DEMO.md`, the completed Phase 4A/4B/5A plans, and Action Request 15.

## Boundaries and decisions

- Keep wallet ownership, public wallet identity, signing purpose, key reference, and private key material distinct. Add one immutable, provider-neutral application registry contract; configuration and cryptography stay in the existing local signer and Spring composition boundaries.
- Use a collection-backed EVM registry with no bank/user maximum. Require the approved owner, admin, four bank-settlement, and four user-custody entries for `local-demo` startup.
- Represent `CONTRACT_DEPLOYER` and `ADMIN_REDEMPTION` as explicit wallet aliases to the owner and admin entries. Aliases share one configured key reference and public identity rather than duplicating key material.
- Keep distinct purpose vocabulary for contract administration/deployment, role administration, mint, burn, redemption custody, bank settlement transfer, and user custody transfer. A resolved wallet and key context must agree on alias, address, network, purpose, and version before signing.
- Derive each EVM address from its canonical 32-byte secp256k1 private scalar inside the local signer adapter. Compare a normalized configured expected address and fail closed with redacted diagnostics on invalid material or mismatch.
- Derive stable registry/key versions from public configuration and public-key identity. Identical keys reconstruct identical public versions; changed key material changes the version so existing Phase 4A metadata checks fence stale requests.
- Reuse the existing Bouncy Castle dependency and low-`s`, recoverable EVM signing behavior. Add no dotenv, Web3j dependency, module, API, migration, chain call, Solana configured key, production custody abstraction, or key-management endpoint.
- Preserve `local-signer` as session-ephemeral. Compose `local-demo` independently and fail startup when both profiles are active. `local-demo` alone must not activate the Phase 5A Ethereum chain path.
- Commit only a blank-key `.env.example`. Create the exact authorized `.env.local-anvil` locally at mode `0600`; keep it ignored and absent from the index, staged diff, logs, reports, and tests.
- Existing V4 signing persistence already retains provider-neutral alias, registry/key version, key role, network, authorization, and signature evidence. The registry version hashes the exact allowed-purpose set, so a purpose change fences an outstanding request without adding a Phase 5C transfer-purpose field or migration in this startup catalog slice.

## Expected implementation map

- `domain`: extend the existing signing-purpose vocabulary only as required by the approved wallet roles.
- `application`: immutable provider-neutral wallet identity registry contract; no framework or crypto types.
- `adapters/signer-local`: configured EVM registry/signer, strict key/address parsing, public versioning, purpose/address/version fencing, and focused tests; preserve the ephemeral signer.
- `control-plane`: redacted `local-demo` properties, explicit profile composition and conflict failure, safe YAML environment bindings, and profile/readiness/security tests.
- `.env.example`, `README.md`, `SECURITY.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, and this plan.

## Test-first execution

- [x] Record behavior-level RED/GREEN evidence for configured key parsing, derived address, stable/changed versions, aliases, required identities, purpose isolation, and stale request fencing.
- [x] Record behavior-level RED/GREEN evidence for missing/invalid/mismatched configuration, redacted diagnostics, default/profile isolation, valid readiness, profile conflict, and absence of a signing endpoint.
- [x] Keep existing Phase 4A/4B focused signing, replay, ambiguity, evidence, and ephemeral-profile behavior green.
- [x] Validate the ignored local artifact maps all ten physical accounts to the approved addresses without printing key material.

## Stable-diff and closeout gates

- Run focused offline tests while developing. Do not run the full reactor until the stable diff has completed its one independent review and one Ponytail review.
- Resolve all in-scope Critical and Important review findings. Defer findings that require transfer, burn, reserve, bank effects, public networks, production custody, Solana, or demo orchestration.
- Run `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` once after review fixes stabilize.
- Run Maven Enforcer and compiled-class `jdeps`, changed-document links, structured-file parsing, Git-ignore/index/mode assertions, secret/private-key/log/report/public-network/generated-artifact scans, PDF Git blob/mode comparison, and diff checks.
- Attempt the normal Graphify refresh once with a hard 60-second limit and no query, retry, HTML, manual reconstruction, or delegated recovery. On failure, restore only the three tracked Graphify artifacts to the starting baseline, remove only attempt-created transients, and record `tooling_deferred`.
- Archive this plan, re-fetch and fence live `origin/main`, stage only authorized Phase 5B files, commit once as `feat: add configured local multi-wallet signer`, push non-force, and verify synchronized clean state.

## Intentional deferrals

Ethereum wallet transfer remains Phase 5C. Ethereum redemption/burn remains Phase 5D. Contract deployment/role grants, reserve and bank behavior, parent orchestration, runnable demos/Compose, production custody, public networks, key administration/rotation, self-custody, omnibus accounting, Solana, and production readiness remain planned.

## Evidence log

- **Preflight:** `git fetch origin main`; repository, branch/status/SHA/remote/log checks. Clean synchronized `main` at the approved baseline and approved remote.
- **Design inspection:** The accepted design requires no public API, chain effect, dependency, or migration for this slice. The existing Phase 4A request persists immutable alias, registry/key version, role, network, authorization, and signature evidence; the existing Spring local-Ethereum path still explicitly requires `local-signer`.
- **Signer RED:** A compiling `LocalConfiguredSigner` scaffold deliberately had no behavior. The first focused run executed four generated-key tests and failed all four at the unimplemented constructor; no missing-class failure is claimed as RED evidence.
- **Signer GREEN:** `LocalConfiguredSignerTest` plus the refactored existing ephemeral-signer suite passed 13 tests. They prove collection-based ten-wallet resolution, explicit owner/admin/bank/user purposes, owner/deployer and ADMIN/redemption aliases, distinct addresses, stable reconstructed versions, changed-key version/address drift, cross-bank and cross-user wallet rejection, wrong-purpose/network rejection, strict key parsing, mismatch failure, redacted diagnostics, and unchanged low-`s`/recoverable ephemeral behavior.
- **Spring RED/GREEN:** Four `LocalDemoConfigurationTest` behaviors first failed against the unimplemented profile composition and unredacted serialization shape, then passed after configuration validation, secret wrapper/redaction, required identity mapping, mutual profile exclusion, and Phase 4A composition were implemented. The focused generated-key Spring/default/ephemeral/readiness gate passed 13 tests with no failures; the real PostgreSQL local-demo context reported readiness `UP`, exposed no signing route or chain adapter, and left the default and `local-signer` contexts intact.
- **Phase 4A restart/rotation fence:** `LocalConfiguredSigningAuthorityTest` proves an outstanding approval-bound request created under one configured key reaches `MANUAL_REVIEW` with the safe `local-wallet-stale` provider code after the same alias is reconstructed with changed key material. A separate behavior-first test first failed because same-key reconstruction returned `RetryableNoSignature` for an unknown process-local provider result; it now returns `local-provider-outcome-unknown` conflict so Phase 4A holds the request for manual review rather than risking a duplicate signature. Together with the existing signer integration suite, the focused authority gate passed 20 tests covering replay, ambiguity/inquiry, retry, evidence, stale-session behavior, and both configured restart fences.
- **Ignored local artifact:** A one-use, subsequently removed test read only `.env.local-anvil`, derived all ten addresses through the same package-local secp256k1 implementation, and passed one test without printing key material. Separate checks confirmed mode `0600`, Git ignore coverage for `.env` variants, `.env.example` trackability, and absence of the local file from the index and staged diff.
- **Documentation disposition:** README, security policy, design, implementation roadmap, transfer-demo contract, `.env.example`, local-demo YAML, and this plan change because capability status, custody/security posture, startup workflow, and next-slice sequencing changed. ADR 0008 remains authoritative and needs no amendment; OpenAPI, migrations, POMs, contracts, reference index, and PDFs do not change because this slice adds no public contract, durable schema, dependency, chain effect, or publication update.
- **Independent review:** One review reported no Critical findings. Its restart-ambiguity finding reproduced and was fixed with the conflict/manual-review fence above. Its purpose-isolation concern was resolved by making the signer check the wallet's exact configured allowed-purpose set as well as action, key role, alias, source address, network, and registry/key version; cross-bank and cross-user signing tests pass. No new durable bank-versus-user transfer-purpose field was added because this slice executes no transfer, the exact purpose set is already immutably bound by the persisted registry version, and adding a Phase 5C request/schema vocabulary would exceed this action. Stale Phase 5B `planned`/future wording in the implementation and security design sections was corrected.
- **Ponytail review:** No new dependency, module, migration, public API, provider abstraction, Solana path, or production-custody stub was added. The shared secp256k1 helper has exactly the two current local-signer consumers; the review removed an unused classification constant and an unnecessary injected `Clock`, leaving a fixed epoch for missing registry metadata. No further cut preserves all required validation, redaction, profile isolation, and restart fencing.
- **Post-review focused gates:** The signer/authority/ephemeral selection passed 20 tests with no failures; the generated-key Spring/default/ephemeral/profile/readiness selection passed 14 tests with no failures, including the real PostgreSQL local-demo readiness context.
- **Complete offline gate:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` ran once after review fixes and passed all seven modules with 426 tests, 0 failures, 0 errors, and 0 skips. Every Maven Enforcer rule passed. Compiled-class `jdeps` checks confirmed domain/application have no Spring, Bouncy Castle, or Web3j dependency; the local signer has no Spring/Web3j dependency; and the control plane has no Bouncy Castle/Web3j dependency.
- **Graphify — `tooling_deferred`:** the approved installed `graphify 0.8.47` was confirmed before the single capped `/opt/homebrew/bin/gtimeout 60 graphify update . --no-cluster` attempt. It exited 1 immediately with `Rebuild failed: [Errno 1] Operation not permitted`, before semantic extraction, clustering, report generation, or HTML. There was no retry, query, subagent, manual reconstruction, or substitute output. The three tracked artifacts were explicitly restored to baseline `ef7b8e36e093bb8a53442f72117a3dcd5b4c93b5`; the only root `.graphify_*` files before and after were the pre-existing `.graphify_python` and `.graphify_root`. Existing Graphify output remains non-authoritative navigation data.
- **Closeout validation:** Changed-document local links passed for 79 targets; local-demo YAML parsed; both environment files have the required warning header; `.env.example` has ten blank key slots and remains trackable; `.env`, `.env.local-anvil`, and a production-style variant are ignored. The ignored local file remains mode `0600`, absent from the index, structurally contains ten distinct key/address mappings on chain `31337`, and its earlier one-use derivation check proved all ten approved addresses. An exact-value scan found none of those keys in tracked files, test/build reports, or packaged build output. The changed local profile contains no public-network URL/default, and no POM, API/OpenAPI, migration, contract, or Solidity file changed. `git diff --check` passed.
- **Immutable inputs:** The four tracked reference PDFs retained their exact baseline Git blob IDs and mode `100644`; none was opened, rendered, extracted, rewritten, or staged. Salus was not accessed or changed.
- **Post-build source fence:** A source/build-input snapshot immediately after the successful full reactor and again after documentation closeout matched `9a0c5f5a584562f5b79f193323739e3e956d6bc2fec5b1902aad8b9795c72ce2`, confirming no production, test, Maven, migration, API, configuration, or other build input changed after the final gate.
- **Closeout:** The plan was archived with `docs/plans/active/` containing only its directory guide. Exact staging, the single approved commit, non-force push, and clean synchronized remote state are verified in the final Git handoff because they necessarily occur after this committed plan snapshot.
