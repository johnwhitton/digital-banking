# Digital Banking Implementation Plan and Current State

## Purpose

This is the living delivery plan and current-state record. It turns `docs/DESIGN.md` into dependency-aware, evidence-gated slices. Update it whenever module layout, dependencies, validation commands, status, sequencing, or the recommended next slice changes.

## Status vocabulary

| Status        | Meaning                                                                            |
| ------------- | ---------------------------------------------------------------------------------- |
| `not_started` | No implementation has begun; design may still contain open questions.              |
| `planned`     | Direction and exit gate are accepted, but no execution plan is authorized or active. |
| `in_progress` | Work is active but has not passed its acceptance gate.                             |
| `blocked`     | A named external input, authority, tool, or decision prevents meaningful progress. |
| `implemented` | The scoped behavior exists and focused tests pass; broader phase gates may remain. |
| `verified`    | All phase acceptance commands and evidence have been run, inspected, and recorded. |

`scaffolded` may appear in the README's capability view; the phase roadmap uses the vocabulary above.

## Repository baseline discovered on 2026-07-16

- Target: the repository root.
- Starting branch/SHA: `main` at `2e7ec1f0fe700de3b7072b7995371b4727b2c535`.
- Starting state: clean worktree with one tracked `README.md` containing `# settlement`.
- Remote: `git@github.com:johnwhitton/digital-banking.git`; live `origin/HEAD` was `main` at the same SHA.
- Toolchain: Homebrew OpenJDK 25.0.2 existed at `/opt/homebrew/opt/openjdk` but was not linked onto `PATH`; Maven and Gradle were absent; Docker 28.5.1 and Compose 2.40.3 were available.
- Framework evidence: Spring Boot 4.0.6 is the requested baseline and was available from Spring Initializr/Maven Central; it manages Spring Framework 7.0.x.
- Build decision: Maven Wrapper 3.3.4 with Apache Maven 3.9.16; see [ADR 0001](adr/0001-maven-reactor-and-module-boundaries.md).
- Publication history: commit `84b2ff350639f537adddd2fc1695e09bae5375b4` supplied the original two PDFs; PDF-only commit `87f8aadf9f2b520c40631cd236eb0a5d91417e95` later synchronized their published v1.0.5 Ethereum-alignment replacements. The [reference index](reference/README.md) owns current hashes, blobs, metadata, and immutable release provenance.
- Read-only tooling source: Salus `main` at `fd9ffaf0d569ebaa232575d143e00488d31a2974`; no Salus file was changed.

## Bootstrap action: actual scope

Foundation creates:

- repository purpose, architecture, security, contribution, ADR, plan, and reference documentation;
- immutable source PDFs and a reference index with exact metadata, normalized paths, source-commit provenance, and verified SHA-256 values;
- a Maven reactor with dependency-free `domain` and Spring Boot `control-plane` modules;
- a committed wrapper, Java 25 / Spring Boot 4.0.6 baseline, and domain dependency guard;
- Spring application context and health/readiness verification only;
- repository-local Codex config, structurally valid reviewed hooks, reusable prompt templates, focused workflow skills, and portable Graphify navigation artifacts; and
- hygiene rules for generated files, secrets, line endings, and build output.

Foundation intentionally does not create mint/burn endpoints, domain lifecycle behavior, persistence, OpenAPI, signers, Web3j, Solana SDKs, Solidity, Rust, Compose, mainnet/testnet configuration, or production claims.

## Current phase status

| Phase                                  | Status        | Evidence summary                                                                               |
| -------------------------------------- | ------------- | ---------------------------------------------------------------------------------------------- |
| 1. Foundation                          | `verified`    | Source publications and the full foundation gate are verified; see the closed bootstrap plan. |
| 2. Domain and operation lifecycle      | `verified`    | Exact quantities, guarded lifecycle/finality histories, canonical commands, idempotent acceptance contracts, and bound ports passed 264 pure tests and the 266-test reactor. |
| 3. Durable API and persistence         | `verified`    | Phase 3A acceptance/read-back, Phase 3B worker/recovery, and Phase 3C transfer acceptance/first deduplicated internal preparation pass the 364-test offline reactor. External workflow effects remain absent by scope. |
| 4. Signing boundary                    | `verified`    | Phase 4A provides durable authority/evidence; Phase 4B adds explicit-profile, session-ephemeral secp256k1/Ed25519 signing. Production custody remains absent. |
| 5A. Local Ethereum mint                | `verified`    | One accepted mint completes on local Anvil with durable signing/submission/observation evidence. |
| 5B. Local multi-wallet custody         | `verified`    | Versioned named ADMIN, bank, redemption, and user identities plus local-demo-only configured signing passed the 426-test offline reactor. |
| 5C. Ethereum wallet transfer           | `verified`    | One internal exact user-custody ERC-20 transfer reuses the durable signing, submission, ambiguity, and observation lifecycle on Anvil. |
| 5D. Ethereum redemption and burn       | `verified`    | One local user-to-ADMIN custody transfer gates one exact ADMIN own-balance burn with durable one-time evidence and supply reconciliation. |
| 6A. Synthetic reserves and mock banks  | `verified` | Exact local USD withdrawals/deposits/inquiry plus closed reserve/liability posting, authoritative one-time evidence consumption, durable reversals, and reserve/supply reconciliation pass focused and full offline gates. |
| 6B. User-held workflows                | `verified` | Separate acquisition and redemption parents compose exact synthetic bank, accounting, custody, mint/burn, payout-before-burn, and reconciliation boundaries under the combined local profiles; the 487-test offline reactor is green. |
| 6C. Settlement-only orchestration      | `verified` | A V10 companion durably composes sender acquisition, exact custody transfer, recipient `AUTO_REDEEM`, and final reconciliation for one server-registered local transfer route; the consolidated PostgreSQL/Anvil proof, stable-diff reviews, and 498-test offline reactor are green. |
| 6D. Ethereum demo environment          | `verified` | Digest-pinned loopback-only Compose, deterministic contract bootstrap, API-driven Demo A/B assertions, durable restart recovery, scoped cleanup, stable-diff reviews, and the 503-test offline reactor are green. |
| 7A. Native Solana semantic gate        | `verified`    | Pinned native Agave/SPL tooling proves classic checked mint/transfer/redemption/burn semantics, exact quantities, canonical accounts, classified authority/valid-second-mint failures, finalized evidence, and zero end state on loopback. |
| 7B. Solana mint parity                 | `verified`    | Sava 25.8.0 passes the Java 25 compatibility gate; an explicit local profile executes one exact classic-SPL mint through ordered external Ed25519 signatures, V11 durable attempt/fence/observation state, response-loss inquiry, restart recovery, and finalized exact supply/balance evidence. The 521-test offline reactor is green. |
| 7C. Solana wallet transfer parity      | `verified`    | One internal exact USER_1-to-USER_2 classic-SPL transfer reuses durable ordered signing, submission fencing, inquiry, restart, and finalized independent source/destination/supply evidence. |
| 7D. Solana redemption and burn parity  | `verified` | One exact USER_1-to-ADMIN redemption-custody transfer gates one separately signed ADMIN burn through V13 one-time finalized evidence and safe same-lineage expiry replacement. |
| 7E. Solana product orchestration       | `verified` | The existing user-held and settlement-only parents route through the verified Solana primitives under `local-demo,local-solana`; the consolidated PostgreSQL/Agave gate and 538-test offline reactor are green. |
| 7F. Solana demonstrations              | `implemented` | Host-native Agave/Java plus cached digest-pinned PostgreSQL package both existing product paths with exact API assertions, replay, restart recovery, and scoped teardown; final stable-diff reviews/reactor closeout remain. |
| 8. Final reference review              | `planned`     | Architecture, code, security, recovery, API/demo, and share-readiness review. |

The current executable boundary includes Phase 3C transfer acceptance, Phase 4 signing, Phase 5A-5D's bounded local-Anvil effects, Phase 6A's exact-USD synthetic bank/accounting primitives, Phase 6B's user-held parents, Phase 6C's registered settlement-only companion, Phase 6D's reproducible Ethereum environment, Phase 7A's native semantic gate, Phase 7B-7D's local Solana primitives, Phase 7E's Solana realization of both existing product parents, and Phase 7F's reproducible local Solana packaging. `local-demo` alone initializes version-fenced synthetic fixtures and the local mock-bank/accounting boundary. Combined with `local-ethereum` it retains the existing Ethereum handlers and product routes; combined instead with mutually exclusive `local-solana` it routes those same `/v1/usdzelle` and `/v1/transfers` parents through the isolated Sava adapter and configured Ed25519 signer. The server—not callers—resolves immutable wallet alias/address/network/purpose/version context. The default runtime has no identity provider, signer, chain client, synthetic-bank fixture/controller, accounting service, workflow resource, or enabled worker. No dynamic wallet management, real bank/reserve, arbitrary settlement routing, automatic compensation, or production custody exists. Each planned phase below requires its own separately authorized plan.

## Phase 1: Foundation

**Deliverables:** repository policy; architecture and delivery docs; immutable source references and index; ADR/plan system; `.codex` operating model; Maven wrapper/reactor; plain domain boundary; Spring application; health/readiness; context and boundary tests.

**Tests and validation:** wrapper version; reactor clean verify; targeted Spring tests; domain Enforcer rule; PDF SHA-256 and byte comparison; Markdown links; skill metadata/references; hooks JSON; stale-reference/secret search; diff and Git status checks.

**Acceptance gate:** application context loads, `/actuator/health/readiness` is `UP`, only health is exposed, domain has no forbidden dependencies, exact supplied references are byte-identical when available, docs and status claims agree, and the active plan records fresh evidence.

**Risks:** local JDK not on `PATH`; first wrapper run needs network; installed Codex discovers repo skills under `.agents/skills` rather than the requested `.codex/skills`; immutable publication provenance must remain intact.

**Disposition:** set `JAVA_HOME` explicitly; commit wrapper; expose `.codex/skills` through native discovery compatibility entries; preserve the supplied PDFs byte-for-byte under `docs/reference/`.

**Deferred:** all business behavior and external infrastructure.

## Phase 2: Domain and operation lifecycle

**Deliverables:**

- exact `AssetUnit` and `TokenQuantity` types with canonical string serialization and bounds;
- `MintCommand` and `BurnCommand` with versioned canonical payloads;
- scoped idempotency key and payload-hash contract;
- stable `OperationId` and `AttemptId` types;
- token-operation aggregate and explicit transition guards;
- operation/attempt repository, policy/approval, signer, chain, clock, ID, and evidence ports expressed in plain Java; and
- pure unit tests with no Spring context.

**Tests:** excess precision, negative/zero/boundary quantities, overflow, unit mismatch, serialization round-trip, NFC normalization/malformed Unicode, same-key/same-version/hash replay, canonical-version/digest conflict, retained acceptance context, every allowed/forbidden state transition, attempt prerequisites and lineage, ambiguous outcome rules, finality status/time rules, exact signer digest/effect/lifetime binding, rejected-payload safety, and stable chain inquiry/observation correlation.

**Acceptance gate:** `domain` has no runtime dependency, `application` has only `domain` at runtime, all transitions are reachable only through invariant-enforcing methods, no `double`/`float` or framework/native type exists, and pure tests prove the lifecycle independent of a chain. The repository acceptance contract does not claim persistence until Phase 3 supplies a durable implementation.

**Risks:** premature database/JSON choices; over-generic chain port; conflating operation completion with all finalities.

**Deferred:** Spring API, SQL implementation, actual signing, and native adapters.

## Phase 3: Durable API and persistence

### Phase 3A: durable acceptance and read-back

**Implemented deliverables:** one design-first OpenAPI 3.1 contract; secured Spring mint/burn create and participant-scoped operation-status resources; a versioned participant projection that omits internal transition actor/reason and finality authority/policy strings; stateless deny-by-default security and safe RFC 9457 problems including a redacted internal-error boundary; PostgreSQL schema/Flyway migration; explicit JDBC repository; durable hashed idempotency; optimistic concurrency; normalized operation/attempt/transition/finality/evidence storage; one atomic pending acceptance-outbox record; and production composition with no in-memory fallback. See [ADR 0004](adr/0004-postgresql-jdbc-flyway-atomic-outbox.md).

**Tests:** OpenAPI parsing, recursive nested-record conformance, and executable accepted-example equality; 401/403 boundaries; HTTP validation and expected problem classification; 202/Location behavior; replay/conflict; principal-derived participant scope despite request-controlled participant fields/parameters/headers; missing/cross-participant 404 equivalence; participant-status field minimization; safe internal invariant and database failures; empty migration; schema/index/quantity constraints; complete aggregate replay; optimistic conflict; parallel duplicate/conflicting requests without retries; rollback; uniqueness; outbox atomicity; process restart; and sensitive-field absence.

**Acceptance gate:** an accepted command is committed before HTTP 202; duplicates cannot create a second operation or outbox row; conflicts leave no partial state; no external effect occurs inside the acceptance transaction; and real PostgreSQL restart/concurrency tests prove durable read-back.

**Current limitation:** the repository configures no real identity adapter, issuer, token decoder, local user, password, or static credential. Business endpoints deny by default; tests use fixture-only `ParticipantPrincipal` authentication and authorities.

### Phase 3B: asynchronous worker and delivery recovery

**Verified deliverables:** framework-free delivery identity/outcome/queue/handler contracts, deterministic bounded retry policy, and a non-overlapping worker use case; one forward-only `V2` migration for outbox delivery state and append-only attempt history; explicit PostgreSQL `READ_COMMITTED`/`FOR UPDATE SKIP LOCKED` claim, lease, fenced acknowledgement/retry/manual-review, measurement, and expired-lease recovery mechanics; and a typed Spring-managed polling lifecycle with existing Micrometer observability. See [ADR 0005](adr/0005-postgresql-operation-delivery-worker.md).

The worker is disabled by default. Enabling requires an explicit visible-ASCII worker identity, a real `OperationDeliveryHandler`, and bounded durations of at least one microsecond to match database timestamp precision; startup fails rather than wiring a no-op consumer. It claims and commits durable delivery intent before invoking the handler and never holds the claim transaction across handler work.

Delivery is at least once. Lease expiry or post-handler/pre-acknowledgement loss can redeliver the same stable outbox `event_id`. Phase 3C applies this rule to `TransferAccepted`: its handler persists the delivery identity with the first-withdrawal preparation transition and returns the durable duplicate result on redelivery. Other future consumers must provide the same atomic inbox/effect proof.

Explicit handler outcomes distinguish delivered, duplicate, retryable no-effect, terminal no-effect, and ambiguous acknowledgement. Unexpected exceptions are retained only as safe internal class diagnostics and a stable ambiguous failure code. Retries use durable deterministic bounded backoff; terminal/exhausted work and all attempt evidence are retained for manual review. Stale owners cannot overwrite a replacement lease, and earlier unresolved work blocks later work for the same operation without serializing unrelated operations.

Implemented measurements are eligible work, active leases, oldest eligible age, claims, deliveries, duplicates/redeliveries, retries, manual review/exhaustion, lease recovery, stale updates, and overlap suppression. They have no operation/participant/idempotency/address/payload/error labels. Handler/business latency, full audit export, alerts, and production SLOs remain future work.

The database-backed Java/Spring worker is the accepted self-contained baseline, not a selection of an enterprise messaging or workflow product. Camunda, Temporal, Kafka, JMS, TIBCO EMS/BusinessWorks/BPM Enterprise, MuleSoft, and SAP remain unselected; any later dependency requires organizational evidence, a focused spike, and a superseding/additional ADR while preserving the application-owned contracts.

**Tests:** deterministic retry/backoff/exhaustion and unexpected-exception ambiguity; scheduler overlap/config/lifecycle/metrics; real PostgreSQL two-worker exclusion, commit-before-handler lock release, success/no-redelivery, lease expiry, post-handler/pre-ack deduplication, durable retry eligibility, exhaustion, stale-owner fencing, cross-operation concurrency, same-operation ordering, forced rollback, fresh-pool restart recovery, empty migration, and existing API/readiness/security/redaction behavior.

**Acceptance gate:** the PostgreSQL baseline passes its deterministic delivery/recovery contract without adding a handler business effect, broker, workflow platform, signer, chain, transfer, or public network. Any real consumer must prove transactional deduplication for its own effect.

**Risks:** PostgreSQL-specific queue coupling, handler work exceeding its lease, incorrect future consumer deduplication, exactly-once overclaims, and configuration/SLO choices not yet production-tested. Expiry makes a late owner stale rather than authoritative; it does not cancel a possible handler effect.

**Deferred:** other consumer transitions, broker/workflow topology, full ledger, effect execution, signing, chain execution, observation, reconciliation, and settlement.

### Phase 3C: chain-neutral transfer aggregate and mock-bank boundary

**Dependency:** Phase 3B worker/recovery mechanics are verified before this slice performs or coordinates any child effect.

**Verified deliverables:** one framework-free `Transfer` aggregate with five stable ordered effects; `POST /v1/transfers` and participant-scoped `GET /v1/transfers/{transferId}`; exact amount/currency and caller-command canonical idempotency; immutable server-resolved asset/unit, network, and opaque sender/recipient wallet context; normalized V3 parent/effect/history/finality/inbox persistence; `TransferAccepted` support in the existing outbox worker; a provider-neutral mock-bank port and deterministic synthetic test/local adapter; and a transactional handler that advances only the first withdrawal from `PLANNED` to `PREPARED`.

**Tests:** aggregate ordering/ambiguity guards/finality separation; route defaulting and allowlisting; server-owned wallet selection; mock-bank command/outcome/idempotency behavior; API/OpenAPI/security/minimization; exact replay/conflict and deterministic parallel duplicates; participant isolation; V1-V3 empty migration; parent/effect/history reconstruction through a fresh repository; outbox claim compatibility; inbox/preparation redelivery; and rollback of inbox plus transition/effect state.

**Acceptance gate:** the parent and every planned effect have stable durable identities; duplicate acceptance/delivery creates no duplicate transfer/effect/preparation; no external call occurs in acceptance or inbox transactions; caller fields cannot select custody wallets; chain children remain planned and no external value effect is claimed.

**Risks:** future attempt/evidence persistence beyond the preparation transition, hiding child ambiguity behind a parent status, treating synthetic adapters as production integrations, and beginning chain execution before signer controls.

**Deferred:** runtime mock-bank execution/inquiry, mint/transfer/burn/deposit execution, child token-operation acceptance, settlement-wallet provisioning, production signer/custody implementations, native chain effects, observation/reconciliation, compensation execution, and both complete demonstrations.

Phases 4-8 below consume the relevant acceptance criteria in [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md). Each future phase or bounded sub-slice requires a focused active plan before implementation; this roadmap does not authorize combining both chains in one action.

## Phase 4: Signing boundary

**Phase 4A implemented deliverables:** framework-free typed signing request/attempt/provider/key identities; exact mint/transfer/burn authority context; distinct EVM `secp256k1` digest and Solana `Ed25519` serialized-message commands; versioned complete-context canonicalization; non-secret key-registry and policy ports; durable replay/conflict, provider-request-before-call, ambiguity inquiry, linked retry, append-only evidence, and optimistic concurrency; explicit JDBC/Flyway V4 persistence; and a deterministic synthetic signer in test sources only.

**Phase 4B implemented deliverables:** one executable `adapters/signer-local` module implementing the Phase 4A provider and key registry; centrally managed Bouncy Castle 1.85 confined to real exact-digest secp256k1 signing with compact low-`s` recovery encoding; JDK-native exact-message Ed25519; two session-only in-memory keys with opaque aliases/versions, public fingerprints, typed role/network allowlists, safe local evidence, and supported cleanup; and explicit Spring profile `local-signer` composition with no default bean, fallback, endpoint, or key source. See [ADR 0006](adr/0006-local-development-signing-provider.md).

**Tests:** Phase 4A defensive copies/redaction, complete-context binding, authorization, replay/conflict, ambiguity/inquiry/retry, V1-V4 persistence, concurrency, restart, and no-secret schema; plus Phase 4B exact EVM verification/no-double-hash/low-`s`/recovery, exact 64-byte Solana verification, length/mode/authority/session fencing, same-provider replay/conflict, no-signature and ambiguity fixtures, completed replay without re-signing, stale-pending manual review, explicit-profile/default-disabled/invalid-config behavior, warning, and no public signing mapping.

**Acceptance gate:** application code can authorize, durably invoke by stable identity, inquire, and record signer evidence for the exact child operation/attempt. The explicit local profile can sign exact prebuilt material with disposable process-memory keys; the default runtime cannot. No key/seed/credential/key file is read or persisted, completed replay does not re-sign, stale pending work cannot use a replacement session key, and a signature advances no submission or financial finality.

**Risks:** treating signing as business authorization; presenting local keys as production custody; Java/provider memory not guaranteeing physical zeroization; using a local signature as execution/finality evidence.

**Deferred:** HSM/MPC/custody adapters and recovery ceremony, provider credentials, persistent keys, native transaction construction, submission, observation, and all production integration.

## Phase 5: Ethereum delivery

**Dependency:** phases 2-4 verified. Build this first, then review the common ports before beginning Solana.

**Phase 5A implemented deliverables:** Foundry 1.5.1 with Solidity 0.8.25 and pinned OpenZeppelin Contracts v5.6.1; one non-upgradeable two-decimal `LocalReferenceToken` with explicit admin and `MINTER_ROLE`; one isolated `adapters/ethereum-web3j` module using Web3j Core 4.14.0; deterministic EIP-1559 mint encoding and signer recovery; one forward-only V5 migration for local-chain nonce cursors, immutable mint/finality context, signature/submission evidence, detailed observations, and reconciliation disposition; one mint-only delivery handler and queue view reusing the Phase 3B and Phase 4 boundaries without claiming burn/transfer work; and explicit `local-ethereum` plus `local-signer` Spring composition. See [ADR 0007](adr/0007-local-ethereum-mint-vertical-slice.md).

**Phase 5A tests:** Foundry role/exact-mint/event/zero-address behavior; independent Cast/Web3j transaction vector, low-`s` and signer checks; real PostgreSQL V1-V5 migration and concurrent nonce allocation; mint-only queue isolation; and a real random-session Anvil path covering exact balance/supply/event, detailed durable observation evidence, full-operation concurrent nonces, a pre-send outage with zero transmitted bytes, post-acceptance response loss recovered by the precomputed hash without resubmission, changed restart configuration without policy drift, duplicate delivery, and unauthorized-minter revert/manual review. Default-context/readiness and local-only configuration tests remain green.

**Phase 5A acceptance gate:** an already-accepted mint can progress through durable authorization, signing, submission, and observation on chain `31337`; the adapter records stable nonce/hash/evidence, never blind-resubmits after ambiguity, verifies transaction plus exact mint event and canonical block, and advances only blockchain finality and narrow technical completion. Web3j/native models do not enter application/domain signatures. The public API and default runtime remain unchanged.

**Risks:** unsafe admin/upgrade authority, fake finality, submit/observe provider coupling, fixture keys presented as production patterns.

**Deferred from Phase 5A:** Ethereum wallet transfer, burn, replacement/cancellation, longer-lived reorg monitoring, parent-transfer integration, public networks, production contracts/deployment/admin, and provider/custody selection. Phases 5C-5D subsequently add separate bounded transfer and burn paths without changing the Phase 5A mint boundary.

### Phase 5B: local multi-wallet custody and configured signer

**Status:** `verified`.

**Dependency:** verified Phases 4A-4B and 5A.

**Plan:** completed at [`docs/plans/completed/PHASE_5B_LOCAL_MULTI_WALLET_CUSTODY.md`](plans/completed/PHASE_5B_LOCAL_MULTI_WALLET_CUSTODY.md).

**Deliverables:** a versioned wallet registry for `ADMIN`, `ADMIN_REDEMPTION`, bank-settlement, and user identities; explicit key purpose/authority even if one local ADMIN key serves multiple roles; local-only ignored environment or equivalent secret injection; address derivation and optional expected-address validation; deterministic demo identity across restart; least-authority component composition; and production rejection of raw-key configuration.

**Exit gate:** named local identities resolve and sign only authorized exact payloads without leaking key material. Default and production profiles remain raw-key-free.

**Non-goals:** chain transfer, burn, reserve/bank behavior, public API changes, self-custody implementation, omnibus accounting, or production custody.

**Implemented boundary:** one provider-neutral `WalletIdentityRegistry` exposes immutable non-secret identity metadata; one configured local-EVM signer reuses the Phase 4A `SignerPort`/`SigningKeyRegistry` and the existing Bouncy Castle secp256k1 behavior; stable public versions fence replacement keys; and exact alias/purpose/address/network/version checks prevent cross-wallet signing. `local-demo` requires owner/deployer, ADMIN/redemption, four bank, and four user identities on chain `31337`, while the registry remains collection-based. The committed `.env.example` has blank keys; the actual `.env.local-anvil` remains ignored, mode `0600`, and outside Git. The default and session-ephemeral profiles remain unchanged, simultaneous local signer profiles fail closed, and no API, migration, RPC, chain effect, or dependency was added.

### Phase 5C: Ethereum generic wallet transfer

**Status:** `verified`

**Dependency:** Phase 5B and the verified Phase 5A native attempt/observation seam.

**Plan:** completed at [`docs/plans/completed/PHASE_5C_ETHEREUM_WALLET_TRANSFER.md`](plans/completed/PHASE_5C_ETHEREUM_WALLET_TRANSFER.md).

**Delivered boundary:** one internal standalone command resolves distinct enabled `USER_CUSTODY` source and destination identities server-side, persists their opaque references, public addresses, registry/key versions, exact two-decimal quantity, route/policy, four finalities, outbox, and one stable attempt. The source key alone authorizes the exact ERC-20 `transfer(address,uint256)` digest through Phase 4A. PostgreSQL V6 reuses the Phase 5A per-source nonce cursor, adds normalized transfer attempt/observation evidence, and preserves submit-once ambiguity recovery. Independent local-Anvil observation verifies the exact sender, contract, nonce, calldata, successful receipt, one non-removed `Transfer` event, confirmation count, and canonical block before advancing only blockchain finality and narrow technical completion.

**Exit gate:** an exact `100.00` transfer (`10,000` atomic units) moves the complete fixture balance from `USER_WALLET_1` to `USER_WALLET_2` on Anvil without changing supply; redelivery cannot resubmit it, response loss recovers by the retained transaction identity, per-source nonce allocation is concurrency-safe, and changed source authority metadata fails before native preparation or signing.

**Non-goals:** bank-wallet transfer, burn, redemption payout, parent orchestration, public API/OpenAPI changes, arbitrary caller-selected wallets or calldata, replacement transactions, or production/public networks.

### Phase 5D: Ethereum redemption and ADMIN burn

**Status:** `verified`

**Dependency:** Phase 5C.

**Plan:** completed at [`docs/plans/completed/PHASE_5D_ETHEREUM_REDEMPTION_AND_BURN.md`](plans/completed/PHASE_5D_ETHEREUM_REDEMPTION_AND_BURN.md).

**Delivered boundary:** an accepted exact BURN causes the local worker to create or resume one server-resolved transfer from the configured user source to `ADMIN_REDEMPTION`. PostgreSQL V7 durably correlates the operations, records immutable wallet/quantity/policy context and block-bound balances/supply, and consumes exact confirmed custody evidence once before preparing a burn. The token exposes a distinct `BURNER_ROLE` whose `burn(uint256)` destroys only the caller's own balance. ADMIN alone signs exact EIP-1559 burn calldata; submission is fenced once, response loss is inquired by the retained hash, and independent transaction/receipt/single-event/canonical-block evidence plus exact ADMIN/supply deltas advances only technical completion and blockchain finality.

**Exit gate:** one confirmed redemption transfer followed by ADMIN burn reduces the ADMIN balance and total supply by the exact amount once. The final offline reactor passed 442 tests with zero failures, errors, or skips, and the Foundry suite passed 9/9.

**Non-goals:** arbitrary burn from another wallet, bank payout, reserve release, complete redemption parent, or production token administration.

## Phase 6: Synthetic banking, reserves, and Ethereum demonstrations

### Phase 6A: synthetic reserve ledger and executable mock banks

**Status:** `verified`.

**Dependency:** Phase 5D.

**Plan:** completed at [`docs/plans/completed/PHASE_6A_SYNTHETIC_RESERVES_AND_MOCK_BANKS.md`](plans/completed/PHASE_6A_SYNTHETIC_RESERVES_AND_MOCK_BANKS.md).

**Delivered boundary:** provider-neutral exact-USD cents and bank/accounting contracts; configured four-bank vocabulary with only `BANK_1/USER_1_BANK_ACCOUNT` at `10,000` cents and `BANK_2/USER_2_BANK_ACCOUNT` at zero; durable withdrawal/deposit/inquiry with participant scope, hashed idempotency, exact replay/conflict, bounded pre-effect retry, post-effect ambiguity recovery, row-locked concurrency, rollback, restart, and immutable balance evidence; profile-only local endpoints with distinct debit/credit/read authorities and separate OpenAPI; a closed four-account double-entry chart; trusted withdrawal/mint/custody/payout/burn posting rules anchored to authoritative Phase 5 operation/attempt/observation/finality rows; one-time durable evidence consumption; append-only linked corrections; caller-independent reconciliation over durable evidence; separate custody/supply/inventory positions; and five explicit reconciliation outcomes under [ADR 0009](adr/0009-synthetic-reserve-ledger-and-reconciliation.md).

**Exit gate:** focused pure, real-PostgreSQL, profile/security/OpenAPI, and default-context tests are green. The one final offline reactor passed 463 tests with zero failures, errors, or skips across seven modules. The one Ponytail review requested no simplification; the one independent review found no Critical issue, and all four valid Important findings were resolved with focused tests before the final reactor.

**Non-goals:** automatic bank/accounting/chain orchestration; real bank integration, deposits, funds, reserves, customers, or PII; production accounting/custody; yield/revenue sharing; public networks; or legal, accounting-finality, attestation, and readiness conclusions.

### Phase 6B: user-held on-ramp and redemption workflows

**Status:** `verified`

**Dependency:** Phases 6A and 5D.

**Plan:** completed at [`docs/plans/completed/PHASE_6B_USER_HELD_ONRAMP_AND_REDEMPTION.md`](plans/completed/PHASE_6B_USER_HELD_ONRAMP_AND_REDEMPTION.md).

**Delivered boundary:** two explicit plain-Java parents with immutable accepted bank, exact USD/token, user/ADMIN wallet, local network/contract, and policy context; V9 normalized workflow/idempotency/step/transition/child/evidence/conclusion persistence; atomic outbox acceptance, database-authoritative concurrent replay/conflict, restart reconstruction, leases, and one-step delivery; acquisition ordering of withdrawal, reserve funding, mint, mint accounting, and reconciliation; redemption ordering of custody, custody accounting, payout, payout accounting, ADMIN burn, and reconciliation; existing bank inquiry plus mint/transfer/burn ambiguity/observation machinery; and local-profile participant POST/GET resources with distinct acquire/redeem/read authorities and a minimized OpenAPI contract. The configured proof maps only `USER_1`; optional user-to-user transfer remains its separate internal Phase 5C operation.

**Exit gate:** the consolidated real-PostgreSQL/Anvil test proves acquisition from bank `10,000` cents and zero reserve/supply to user `10,000` atomic units, followed by a separately accepted redemption back to bank `10,000` and zero reserve/liability/custody/supply. It proves payout accounting precedes burn confirmation and exact replay does not create a second deposit. One Ponytail review, one independent review with focused remediation, and the 487-test offline reactor are green.

**Non-goals:** settlement-only orchestration, public user-to-user transfer, automatic compensation, self-custody production support, omnibus subledgering, real bank/reserve integration, production identity/custody, public networks, or Solana.

### Phase 6C: settlement-only fiat transfer orchestration

**Status:** `verified`.

**Dependency:** Phases 6A, 5C, and 5D.

**Plan:** completed at [`docs/plans/completed/PHASE_6C_SETTLEMENT_ONLY_TRANSFER_ORCHESTRATION.md`](plans/completed/PHASE_6C_SETTLEMENT_ONLY_TRANSFER_ORCHESTRATION.md).

**Delivered boundary:** the existing participant-scoped transfer API and V3 record remain compatible. When the combined local profiles resolve the registered `USER_1` source and `USER_2` destination, acceptance atomically adds a V10 companion plus one settlement outbox event. The companion snapshots exact USD cents/base units, versioned sender-acquisition and recipient-`AUTO_REDEEM` instructions, both synthetic accounts and server-owned custody identities, ADMIN, local contract/network, and policies. Four durable ordered boundaries reuse one Phase 6B acquisition, one Phase 5C exact transfer, one Phase 6B recipient redemption, and final reserve/supply reconciliation. Stable parent-derived idempotency, child identity retention, optimistic version fencing, inquiry/observation, exhausted-delivery synchronization, and participant-safe status preserve restart and ambiguity behavior without copying child truth.

**Exit gate:** the consolidated real-PostgreSQL/Anvil proof starts sender bank `10,000` cents, recipient bank `0`, and zero reserve/liability/wallet/custody/supply; the existing transfer POST drives one exact acquisition, custody transfer, forced recipient redemption, payout, burn, and reconciliation; it ends sender bank `0`, recipient bank `10,000`, and all reserve/liability/wallet/custody/supply positions zero. The API exposes separate bank, blockchain, accounting, and reconciliation dimensions without recipient, wallet, child, or native-evidence leakage. One Ponytail review, one independent review with focused remediation, and the 498-test offline reactor are green.

**Non-goals:** arbitrary or caller-selected routes, persistent recipient holdings, public user-to-user transfer, automatic compensation/refund/reversal, Phase 6D environment/scripts, real bank/reserve/custody integration, public networks, Solana, legal/accounting/customer finality, or a production settlement claim. The POC uses segregated local custody aliases to prove the economic flow; it does not claim the broader institutional bank-wallet realization is complete.

### Phase 6D: Ethereum local demonstration environment and scripts

**Status:** `verified`

**Dependency:** Phases 6B and 6C.

**Plan:** completed at [`docs/plans/completed/PHASE_6D_REPRODUCIBLE_ETHEREUM_DEMO_ENVIRONMENT.md`](plans/completed/PHASE_6D_REPRODUCIBLE_ETHEREUM_DEMO_ENVIRONMENT.md).

**Deliverables:** Docker Compose or an accepted equivalent for PostgreSQL, Anvil, the control plane, executable mock banks, identity fixtures, contract deployment, health/dependency ordering, deterministic cleanup, and concise evidence output; local keys are generated or supplied through ignored local secret input and never committed.

**Delivered boundary:** root Compose uses the exact approved PostgreSQL 17.10, Foundry/Anvil 1.5.1, and Temurin 25.0.2 image digests with `pull_policy: never`, an internal named network, named PostgreSQL/Anvil volumes, and loopback-only host ports. Bootstrap verifies the ignored mode-`0600` ten-key map, generates only missing mode-restricted database/bearer/run material, builds the packaged JAR and minimal non-root runtime image, and deploys/verifies the existing token through a one-shot Foundry service. A profile-isolated local bearer adapter maps one sender to only the existing Phase 6B/6C authorities and a distinct operator to a bounded read-only status/OpenAPI resource.

**Exit gate:** Demo A drives exact `USD 100.00` acquisition, held-token/reserve/liability checkpoints, payout-before-burn redemption, zeroed final positions, reconciliation, and exact replay. After explicit reset, Demo B drives the existing transfer resource through exactly one withdrawal, mint, user transfer, redemption-custody transfer, payout, and burn; sender/recipient fiat moves `10,000` cents, all chain/accounting positions return to zero, reconciliation completes, and replay creates no duplicate. A durably accepted non-terminal acquisition resumes after control-plane restart with PostgreSQL/Anvil preserved and produces one withdrawal/mint. Whole-stack stop/start retains the contract, block, workflow, balances, supply, and effect counts. Explicit reset removes only the named demo state. One Ponytail review and one independent review completed with all valid in-scope Important findings remediated before the single final offline reactor passed 503 tests with zero failures, errors, or skips; the prescribed five-test default/readiness selection also passed.

**Non-goals:** cloud deployment, CI production topology, public networks, real funds, production custody, or combining the two product paths into one claim.

## Phase 7: Native Solana parity

Direct issuer-authority mint/burn remains distinct from Circle CCTP. A future CCTP cross-chain workflow requires its own operation semantics, evidence, and decision gate.

### Phase 7A: native Solana tooling and semantic gate

**Status:** `verified`

**Dependency:** Phase 6D and the Ethereum evidence seams it validates.

**Plan:** completed in [`docs/plans/completed/PHASE_7A_NATIVE_SOLANA_TOOLCHAIN_AND_SEMANTIC_GATE.md`](plans/completed/PHASE_7A_NATIVE_SOLANA_TOOLCHAIN_AND_SEMANTIC_GATE.md).

**Deliverables:** approved repository-local Agave 4.1.2 and `spl-token-cli 5.6.1` on the native Apple Silicon host; loopback-only validator, symlink-safe mode-restricted disposable keys/state, exact PID/command/RPC-identity cleanup, safe status/stop/reset commands, and sanitized evidence; classic SPL Token plus canonical associated token accounts; one exact `10000`-base-unit checked mint, USER_1-to-USER_2 transfer, USER_2-to-ADMIN-redemption transfer, and ADMIN-owner burn; rejected unauthorized mint/transfer/burn and valid-second-mint account mismatch with classified outcomes and unchanged state; actual signatures/recent blockhashes/slots, live validity and pre-submission expiry-window observations, finalized commitment, and zero final supply/balances. The gate records Sava as the provisional Phase 7B candidate but accepts no Java dependency. [ADR 0010](adr/0010-native-solana-toolchain-and-classic-spl-semantics.md) records the toolchain and semantic decisions.

**Exit gate:** the semantic gate passes twice from scoped clean reset state with deterministic local evidence; native Solana semantics remain explicit; restrictive state modes and loopback-only RPC hold; no SDK or Rust type enters the core; unnecessary custom program code is absent.

**Non-goals:** Java adapter or durable application chain effects, business API/database/profile changes, public clusters, CCTP, custom/production program deployment, or Java-client dependency selection. The CLI's UI decimal parser is not an application exactness boundary; Phase 7B must convert through the versioned asset/unit catalog to integer atomic units and persist the exact chosen blockhash/last-valid height before signing.

### Phase 7B: Solana mint parity

**Status:** `verified`

**Dependency:** Phase 7A.

**Plan:** completed in [`docs/plans/completed/PHASE_7B_JAVA_SAVA_COMPATIBILITY_AND_SOLANA_MINT_PARITY.md`](plans/completed/PHASE_7B_JAVA_SAVA_COMPATIBILITY_AND_SOLANA_MINT_PARITY.md).

**Deliverables:** one isolated `adapters/solana-sava` module with Sava Core/RPC 25.8.0 and parser 25.3.0; centrally aligned Bouncy Castle 1.85; Java 25/Agave compatibility fixtures; one V11 migration for immutable Solana mint context, policy/fee snapshots, ordered signatures, a single active local preparation lane, submit fence, replacement lineage, and observations; provider-neutral ordered fee-payer/mint-authority signing; a mode-restricted local Ed25519 signer that never exposes key material to Sava and retains only bound public signature outcomes for crash-safe inquiry; an explicit loopback-only `local-solana` profile; canonical ATA creation plus exact two-decimal `MintToChecked`; inquiry-before-retry and finalized independent effect matching; and a public-only native fixture command.

**Exit gate:** compatibility, codec, signer, default-profile, real-PostgreSQL/HTTP, and real private-validator gates are green. The real gate accepts exactly `100.00`/`10000` atomic units, loses the submission response after node acceptance, reconstructs signer/adapter/handler state, submits no second transaction, and completes only after finalized transaction/instruction/account/supply/balance evidence agrees. The final offline reactor reports 521 tests across eight modules, with 520 executed successfully and only the separately green opt-in validator test skipped by default.

**Non-goals:** transfer, burn, either full demonstration, public clusters, or production authority.

### Phase 7C: Solana wallet transfer parity

**Status:** `verified`

**Dependency:** Phase 7B.

**Plan:** completed in [`docs/plans/completed/PHASE_7C_SOLANA_WALLET_TRANSFER_PARITY.md`](plans/completed/PHASE_7C_SOLANA_WALLET_TRANSFER_PARITY.md).

**Deliverables:** one internal server-resolved USER_1-to-USER_2 classic-SPL `TransferChecked` through the provider-neutral wallet-transfer port; exact `100.00`/`10000` conversion through the versioned asset/unit catalog; ordered fee-payer and source-owner Ed25519 signatures over one immutable legacy message; V12 migration of the shared Solana attempt/signature/observation boundary for mint or transfer context; destination-ATA creation only when absent; inquiry-before-retry after response loss; and finalized independent transaction/instruction/account/source/destination/supply evidence, including indexed transaction pre/post token balances kept distinct from later account-state reads. The common PostgreSQL wallet-transfer repository now resides in the persistence adapter rather than either chain adapter.

**Exit gate:** focused codec, signer, acceptance, persistence, Sava, Ethereum-regression, profile, and default-context tests are green. One consolidated real PostgreSQL/Agave gate mints exactly `10000` units to USER_1, loses the transfer submission response after node acceptance, reconstructs signer/adapter/handler state, submits no second transaction, and finalizes only after USER_1 is `0`, USER_2 is `10000`, and total supply remains `10000` with matching native evidence. The final offline reactor discovered 527 tests across eight modules, executed 526 successfully, and skipped only the separately green opt-in validator test.

**Non-goals:** redemption custody, burn, parent bank workflows, product-path orchestration, demonstrations, public API expansion, public clusters, production custody, or treating EVM and Solana evidence as interchangeable.

### Phase 7D: Solana redemption custody and burn parity

**Status:** `verified`

**Dependency:** Phase 7C.

**Plan:** completed in [`docs/plans/completed/PHASE_7D_SOLANA_REDEMPTION_CUSTODY_AND_BURN_PARITY.md`](plans/completed/PHASE_7D_SOLANA_REDEMPTION_CUSTODY_AND_BURN_PARITY.md).

**Deliverables:** one accepted exact burn creates or resumes a server-resolved USER_1-to-ADMIN redemption-custody transfer through the existing provider-neutral boundary; classic-SPL `TransferChecked` uses ordered fee-payer/USER_1 signatures; V13 retains the custody correlation and atomically consumes its exact finalized transfer/finality evidence once for the stable burn identity; classic-SPL `BurnChecked` uses ordered fee-payer/ADMIN signatures; a proven pre-submission blockhash expiry creates a new native attempt within that same burn lineage without releasing or duplicating custody evidence; response-loss inquiry, restart recovery, immutable cluster/registry/key context, exact indexed transaction balances, independent finalized account/supply observation, and replay remain distinct for custody and burn. The profile and queue route these primitives internally without an API or parent-workflow change.

**Exit gate:** focused application, signer, codec, migration, PostgreSQL/fake-RPC, queue/profile, and Ethereum regression tests pass. One consolidated PostgreSQL/Agave gate mints exact `10000` to USER_1, recovers lost responses independently for mint, USER_1-to-ADMIN custody, and ADMIN burn across reconstructed process boundaries, submits each native effect once, and finishes with supply, USER_1, and ADMIN balances at `0`. Focused PostgreSQL evidence additionally proves one safe burn replacement after pre-submission expiry, with the same correlation still consumed by only the first burn attempt, and rejects cluster/registry provenance drift. Unsafe custody evidence creates no burn attempt; replay is duplicate; and an unrelated later mint-authority configuration change cannot relabel the retained ADMIN burn. The final offline reactor discovered 531 tests, passed 530, and skipped only the separately green opt-in validator test.

**Non-goals:** parent bank workflows, demonstrations, public clusters, production custody, or arbitrary-holder burn.

### Phase 7E: chain-selectable Solana product orchestration

**Status:** `verified`

**Dependency:** Phase 7D and the accepted Demo A/Demo B contracts.

**Plan:** completed in [`docs/plans/completed/PHASE_7E_SOLANA_PRODUCT_PATH_ORCHESTRATION.md`](plans/completed/PHASE_7E_SOLANA_PRODUCT_PATH_ORCHESTRATION.md).

**Deliverables:** `SOLANA` selection behind the unchanged business APIs; V14 extension of the existing workflow/registered-route constraints plus chain-discriminated accounting evidence; mutually exclusive PostgreSQL queue ownership; one common product-parent composition using the existing Phase 6B/6C aggregates and Phase 7B–7D handlers; two configured `TRANSFER_AUTHORITY` keys selected only from immutable server-resolved wallet alias/address/registry/key-version context; exact finalized Solana evidence for mint, transfer, custody, and burn; and unchanged payout-before-burn, replay, participant-safe projection, and recovery semantics.

**Exit gate:** one opt-in PostgreSQL/Agave 4.1.2 execution completed exact user-held acquisition/replay/redemption and one settlement-only sender-acquisition/transfer/recipient-`AUTO_REDEEM` flow. It retained finalized native evidence, completed one durable worker path across reconstructed queue instances, enforced payout before burn, reconciled the synthetic bank and accounting state, and ended with zero supply and wallet balances. Focused persistence, signer, adapter, API/OpenAPI, profile, Ethereum-fence, default, and readiness regressions are green. The final offline reactor discovered 538 tests, executed 536 successfully, and skipped only the separately gated Phase 7B and Phase 7E native-validator tests.

**Non-goals:** operator demo packaging, simultaneous cross-chain value movement, bridging, CCTP, public clusters, mainnet/testnet configuration, or one genericized finality model.

### Phase 7F: reproducible local Solana demonstrations

**Status:** `verified`

**Dependency:** Phase 7E.

**Plan:** completed in [`docs/plans/completed/PHASE_7F_REPRODUCIBLE_LOCAL_SOLANA_DEMONSTRATIONS.md`](plans/completed/PHASE_7F_REPRODUCIBLE_LOCAL_SOLANA_DEMONSTRATIONS.md).

**Deliverables:** one PostgreSQL-only Compose project using the approved cached immutable image; host-native Agave 4.1.2 and packaged Java 25.0.2; mode-restricted ignored credentials, keys, cluster/mint metadata, logs, and evidence; thin prerequisite/bootstrap/start/readiness/status/stop/reset commands; API-driven Demo A user-held and Demo B settlement-only commands; retained-state restart recovery; and one chain-neutral local-only status projection backed by exact finalized classic-SPL balances/supply and chain-discriminated confirmed-effect counts.

**Exit gate:** all gates are green. Demo A completed exact acquisition, held `10000` atomic units, replayed without a duplicate, paid out before burn, reconciled, and ended at zero supply/custody. After explicit reset, Demo B completed the exact six-effect registered route, replayed without a duplicate, moved bank cents `10000 -> 0` and `0 -> 10000`, reconciled, and ended at zero supply/balances. A separate clean restart proof retained PostgreSQL/private Agave, resumed one durable accepted acquisition after Java restart, and produced one withdrawal/mint. The Ponytail review found no removable complexity. The independent review's two Important local-boundary findings were corrected test-first by rejecting non-loopback status URLs before token access and restricting native runtime roots to the two approved ignored locations. The single final offline reactor discovered 538 tests, executed 536 successfully, and skipped only the separately gated Phase 7B and Phase 7E native-validator tests; Enforcer and the domain/application dependency-direction checks passed.

**Non-goals:** simultaneous cross-chain value movement, bridging, CCTP, public clusters, mainnet/testnet configuration, or one genericized finality model.

## Phase 8: Share readiness and final review

### Phase 8A: share-ready demo documentation and publication reconciliation

**Status:** `verified`

**Dependency:** Phase 7F.

**Plan:** [completed Phase 8A plan](plans/completed/PHASE_8A_SHARE_READY_DEMO_DOCUMENTATION.md).

**Baseline/evidence:** PDF-sync baseline
`87f8aadf9f2b520c40631cd236eb0a5d91417e95`; executable Solana evidence
`173ebcbb002cacad479c7ced4361106e7c6f21dc`. The PDF-only baseline is not an
implementation-evidence commit.

**Deliverables:** the [canonical POC walkthrough](DEMO_WALKTHROUGH.md), one
topology and two workflow sequence diagrams, compact README onboarding, exact
Ethereum/Solana runtime handoff, runbook state boundaries, synchronized living
documentation, and reconciled four-PDF metadata/provenance. Volume I and the
Executive Brief remain the published v1.0.5 Ethereum-alignment snapshot; a
future Solana publication alignment is separately versioned work.

**Exit gate:** focused metadata/provenance, changed-document link, navigation,
Mermaid-source, terminology, exact-quantity, accounting-map, safe-claim, and
diff/allowlist checks pass without changing or running executable behavior.

### Phase 8B: final code, security, recovery, API, and share-readiness review

**Status:** `verified`

**Dependency:** Phase 8A.

**Plan:** [completed Phase 8B plan](plans/completed/PHASE_8B_FINAL_REVIEW_AND_DUAL_CHAIN_POC_RELEASE.md).

**Deliverables:** architecture-to-code and implementation-standards audits; threat/security and dependency review; database concurrency, recovery, backup/restore, and reconciliation review; API/OpenAPI and demo-usability review; deterministic clean-room evidence for both demos on both local chains; and final reconciliation of README, design, roadmap, ADR, limitation, and operator documentation.

**Exit gate:** all Critical and Important findings are resolved or explicitly accepted; both chain realizations of both demos have reproducible evidence; documentation accurately distinguishes verified capability, planned work, limitations, and non-production status.

**Current review result:** no Critical issue was found. One Important contract
completeness defect was corrected test-first: the USDZELLE OpenAPI now lists
the existing classified `415`, `500`, `503`, and malformed-ID `400` outcomes
for both product parents. A second Important operational defect was corrected
at the existing Solana readiness seam: transient health, status, and port
failures remain inside the bounded polling loop. Both local chain release
sequences now pass Demo A, Demo B, replay, restart recovery, exact accounting,
and safe stop. Large cohesive adapter classes, remaining explicit JDBC
wildcard projections, and production integrations/operations remain
non-blocking advisories in the [Phase 8B final review](reviews/PHASE_8B_FINAL_REFERENCE_REVIEW.md).

**Non-goals:** production certification, regulatory/legal approval, real-fund deployment, vendor selection, cloud/mainnet/testnet rollout, or a claim of operational readiness.

## Publications

- **Volume I - [Digital Banking Reference Architecture](reference/stablecoin-settlement-reference-architecture.pdf)** (`published`, v1.0.5, 152 pages): Ethereum-alignment architecture snapshot synchronized in PDF-only commit `87f8aadf9f2b520c40631cd236eb0a5d91417e95`.
- **Executive Brief - [Digital Asset Settlement for Zelle](reference/zelle-digital-asset-settlement-executive-brief.pdf)** (`published`, v1.0.6, 27 pages): concise Solana/dual-chain alignment synchronized after Phase 8B and pinned to implementation evidence `173ebcbb002cacad479c7ced4361106e7c6f21dc`.
- **Volume II - [Digital Banking Engineering Companion](reference/digital-banking-engineering-companion.pdf)** (`published`, v1.1.0, 46 pages): vendor-neutral implementation and operations guidance covering durable workflow, Java/Spring, wallets and signing, EVM, Solana, submission and observation, infrastructure, testing, performance, and delivery. It is not production certification or a runnable implementation. Its code-status discussion is pinned to `e921fcb1877b46a6881437f46b1a6ebfa115ae58`; use this current plan, the README, accepted ADRs, source, and tests for live repository status.
- **Volume III - [Digital Banking Reference Implementation](reference/digital-banking-reference-implementation.pdf)** (`published`, v1.0.0, 45 pages): code companion mapping architecture to repository modules, APIs, database schema and migrations, implementation excerpts, local build/run/test flows, and the boundary between local demonstrations and production integrations. Current design, ADRs, contracts, source, tests, and this living plan remain authoritative for implementation status.

The v1.0.6 synchronization changes only the Executive Brief. Volume I remains
v1.0.5, Volume II remains v1.1.0, and Volume III remains v1.0.0. It changes no
executable capability or Phase 8B evidence. Publishing or synchronizing any
volume does not change executable phase status, replace a focused implementation
plan or acceptance test, or imply production readiness. Publication snapshots
may lag the live code; broader Solana alignment of Volumes I-III remains
separately governed future publication work.

## Plans and ADRs

- Plan lifecycle and index: [`docs/plans/README.md`](plans/README.md).
- Completed foundation plan: [`docs/plans/completed/BOOTSTRAP.md`](plans/completed/BOOTSTRAP.md).
- Completed Phase 2 plan: [`docs/plans/completed/DOMAIN_OPERATION_LIFECYCLE.md`](plans/completed/DOMAIN_OPERATION_LIFECYCLE.md).
- Completed Phase 3A plan: [`docs/plans/completed/PHASE_3A_DURABLE_API_AND_PERSISTENCE.md`](plans/completed/PHASE_3A_DURABLE_API_AND_PERSISTENCE.md).
- Completed Phase 3B worker/recovery plan: [`docs/plans/completed/PHASE_3B_DURABLE_WORKER_AND_RECOVERY.md`](plans/completed/PHASE_3B_DURABLE_WORKER_AND_RECOVERY.md).
- Completed Phase 3C transfer plan: [`docs/plans/completed/PHASE_3C_TRANSFER_AGGREGATE_AND_MOCK_BANK.md`](plans/completed/PHASE_3C_TRANSFER_AGGREGATE_AND_MOCK_BANK.md).
- Completed Phase 4A signing-authority plan: [`docs/plans/completed/PHASE_4A_SIGNING_AUTHORITY_BOUNDARY.md`](plans/completed/PHASE_4A_SIGNING_AUTHORITY_BOUNDARY.md).
- Completed Phase 4B local signer plan: [`docs/plans/completed/PHASE_4B_LOCAL_DEVELOPMENT_SIGNER.md`](plans/completed/PHASE_4B_LOCAL_DEVELOPMENT_SIGNER.md).
- Completed Phase 5A local Ethereum mint plan: [`docs/plans/completed/PHASE_5A_ETHEREUM_LOCAL_MINT_VERTICAL_SLICE.md`](plans/completed/PHASE_5A_ETHEREUM_LOCAL_MINT_VERTICAL_SLICE.md).
- Completed Phase 5B configured local custody plan: [`docs/plans/completed/PHASE_5B_LOCAL_MULTI_WALLET_CUSTODY.md`](plans/completed/PHASE_5B_LOCAL_MULTI_WALLET_CUSTODY.md).
- Completed Phase 5C local Ethereum wallet-transfer plan: [`docs/plans/completed/PHASE_5C_ETHEREUM_WALLET_TRANSFER.md`](plans/completed/PHASE_5C_ETHEREUM_WALLET_TRANSFER.md).
- Completed Phase 5D redemption and burn plan: [`docs/plans/completed/PHASE_5D_ETHEREUM_REDEMPTION_AND_BURN.md`](plans/completed/PHASE_5D_ETHEREUM_REDEMPTION_AND_BURN.md).
- Completed Phase 6A synthetic reserves/mock banks plan: [`docs/plans/completed/PHASE_6A_SYNTHETIC_RESERVES_AND_MOCK_BANKS.md`](plans/completed/PHASE_6A_SYNTHETIC_RESERVES_AND_MOCK_BANKS.md).
- Completed Phase 6B user-held workflow plan: [`docs/plans/completed/PHASE_6B_USER_HELD_ONRAMP_AND_REDEMPTION.md`](plans/completed/PHASE_6B_USER_HELD_ONRAMP_AND_REDEMPTION.md).
- Completed Phase 6C settlement-only orchestration plan: [`docs/plans/completed/PHASE_6C_SETTLEMENT_ONLY_TRANSFER_ORCHESTRATION.md`](plans/completed/PHASE_6C_SETTLEMENT_ONLY_TRANSFER_ORCHESTRATION.md).
- Completed Phase 6D reproducible local demo plan: [`docs/plans/completed/PHASE_6D_REPRODUCIBLE_ETHEREUM_DEMO_ENVIRONMENT.md`](plans/completed/PHASE_6D_REPRODUCIBLE_ETHEREUM_DEMO_ENVIRONMENT.md).
- Completed Phase 7A native Solana semantic-gate plan: [`docs/plans/completed/PHASE_7A_NATIVE_SOLANA_TOOLCHAIN_AND_SEMANTIC_GATE.md`](plans/completed/PHASE_7A_NATIVE_SOLANA_TOOLCHAIN_AND_SEMANTIC_GATE.md).
- Completed Phase 7B Java/Sava compatibility and Solana mint-parity plan: [`docs/plans/completed/PHASE_7B_JAVA_SAVA_COMPATIBILITY_AND_SOLANA_MINT_PARITY.md`](plans/completed/PHASE_7B_JAVA_SAVA_COMPATIBILITY_AND_SOLANA_MINT_PARITY.md).
- Completed Phase 7C Solana wallet-transfer-parity plan: [`docs/plans/completed/PHASE_7C_SOLANA_WALLET_TRANSFER_PARITY.md`](plans/completed/PHASE_7C_SOLANA_WALLET_TRANSFER_PARITY.md).
- Completed Phase 7D Solana redemption-custody and ADMIN-burn-parity plan: [`docs/plans/completed/PHASE_7D_SOLANA_REDEMPTION_CUSTODY_AND_BURN_PARITY.md`](plans/completed/PHASE_7D_SOLANA_REDEMPTION_CUSTODY_AND_BURN_PARITY.md).
- Completed Phase 7E Solana product-orchestration plan: [`docs/plans/completed/PHASE_7E_SOLANA_PRODUCT_PATH_ORCHESTRATION.md`](plans/completed/PHASE_7E_SOLANA_PRODUCT_PATH_ORCHESTRATION.md).
- Completed Phase 7F reproducible local Solana demonstrations plan: [`docs/plans/completed/PHASE_7F_REPRODUCIBLE_LOCAL_SOLANA_DEMONSTRATIONS.md`](plans/completed/PHASE_7F_REPRODUCIBLE_LOCAL_SOLANA_DEMONSTRATIONS.md).
- Completed Phase 8A share-ready documentation plan: [`docs/plans/completed/PHASE_8A_SHARE_READY_DEMO_DOCUMENTATION.md`](plans/completed/PHASE_8A_SHARE_READY_DEMO_DOCUMENTATION.md).
- Completed Executive Brief v1.0.6 synchronization and release reconciliation: [`docs/plans/completed/EXECUTIVE_BRIEF_V1_0_6_SYNC_AND_RELEASE_RECONCILIATION.md`](plans/completed/EXECUTIVE_BRIEF_V1_0_6_SYNC_AND_RELEASE_RECONCILIATION.md).
- Completed dual-product-path and delivery-roadmap alignment: [`docs/plans/completed/DUAL_PRODUCT_PATHS_AND_DELIVERY_ROADMAP.md`](plans/completed/DUAL_PRODUCT_PATHS_AND_DELIVERY_ROADMAP.md).
- Completed Zelle share-readiness and transfer-roadmap plan: [`docs/plans/completed/ZELLE_SHARE_READINESS_AND_TRANSFER_ROADMAP.md`](plans/completed/ZELLE_SHARE_READINESS_AND_TRANSFER_ROADMAP.md).
- ADR process and index: [`docs/adr/README.md`](adr/README.md).
- Accepted build/module choice: [`ADR 0001`](adr/0001-maven-reactor-and-module-boundaries.md).
- Accepted EVM approach: [`ADR 0002`](adr/0002-evm-foundry-and-web3j.md).
- Accepted Solana approach: [`ADR 0003`](adr/0003-native-solana-spl-token.md).
- Accepted PostgreSQL/JDBC/Flyway/atomic-outbox approach: [`ADR 0004`](adr/0004-postgresql-jdbc-flyway-atomic-outbox.md).
- Accepted PostgreSQL delivery-worker/lease-recovery approach: [`ADR 0005`](adr/0005-postgresql-operation-delivery-worker.md).
- Accepted local-development signing provider: [`ADR 0006`](adr/0006-local-development-signing-provider.md).
- Accepted local Ethereum mint realization: [`ADR 0007`](adr/0007-local-ethereum-mint-vertical-slice.md).
- Accepted USDZELLE product paths, ownership/custody, reserve, and delivery boundaries: [`ADR 0008`](adr/0008-usdzelle-product-paths-ownership-custody-reserve-boundaries.md).
- Accepted synthetic reserve ledger, trusted posting, and reconciliation boundary: [`ADR 0009`](adr/0009-synthetic-reserve-ledger-and-reconciliation.md).
- Accepted native Solana toolchain and classic SPL semantic boundary: [`ADR 0010`](adr/0010-native-solana-toolchain-and-classic-spl-semantics.md).

Create a restartable active plan for each newly authorized implementation action and follow the lifecycle above. Create an ADR only when evidence requires an accepted material decision.

## AI-assisted engineering workflow

The repository tracks Graphify's project-scoped skill, portable fail-open hook, scan policy, and the reviewed `graphify-out/GRAPH_REPORT.md`, `graph.json`, and `manifest.json`. Transient visualization, cache, query-memory, cost, reflection, and machine-state output remains ignored; immutable source PDFs are not indexed. Use the graph first for repository-navigation questions and validate its suggestions against authoritative source.

Ponytail and Superpowers are user-installed plugins, not repository dependencies or vendored content. Ponytail supplies explicit simplicity/YAGNI modes and the final over-engineering review. Superpowers supplies matching planning, TDD, debugging, review, and verification workflows. The user's approved request, repository policy/security/design, active plan, ADRs, focused repository skills, source, and tests take precedence over all three tools.

Contributor setup, reviewed update commands, exact hook-trust behavior, and evidence limitations are maintained in the README. The completed implementation and review record for this integration is [`docs/plans/completed/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md`](plans/completed/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md). This workflow changes no product capability, phase status, module dependency, financial invariant, or next-slice recommendation.

## Implementation Standards

[`docs/IMPLEMENTATION_STANDARDS.md`](IMPLEMENTATION_STANDARDS.md) is the detailed normative authority for Java, Spring, persistence, API, asynchronous workflow, signer, chain, and test implementation. It requires inward dependency direction, immutable validated domain types, exact quantities, constructor injection, principal-derived scope, explicit participant-safe response projections, classified safe problems, parameterized JDBC, atomic acceptance and outbox, database-authoritative concurrency, attempt-before-effect sequencing, native adapter semantics, deterministic failure-path tests, and an executable present need for every dependency or abstraction.

The completed Phase 3A baseline was reviewed against those rules in [`docs/reviews/PHASE_3A_IMPLEMENTATION_STANDARDS_REVIEW.md`](reviews/PHASE_3A_IMPLEMENTATION_STANDARDS_REVIEW.md). Action Request 07 resolves its two required pre-Phase-3B findings. Action Request 09 then implements the database-authoritative claim/lease/retry/recovery boundary under the focused Phase 3B plan and ADR 0005 without changing the standards, API, domain lifecycle, dependency set, or external-effect boundary.

## Current validation commands

These commands are the verified foundation entry points on the bootstrap workstation:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw --version
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl control-plane -am -Dtest=DigitalBankingApplicationTests,HealthReadinessSmokeTests -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl domain enforcer:enforce
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl application enforcer:enforce dependency:tree
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl adapters/persistence-postgres -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -pl control-plane -am test
(cd contracts/evm && forge test)
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/ethereum-web3j -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/solana-sava -am test
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/solana-sava dependency:tree
sh -n scripts/solana/*.sh
scripts/solana/bootstrap.sh
scripts/solana/phase7b-fixture.sh
scripts/solana/semantic-gate.sh --yes
scripts/solana/status.sh --json
graphify --version
test -f .agents/skills/graphify/SKILL.md
jq -e '.hooks.PreToolUse[] | select(.matcher == "^Bash$")' .codex/hooks.json
```

Reference, documentation, skill/hook, stale-reference, diff, and Git commands are recorded with results in the completed bootstrap plan. Add commands here only after they are stable contributor entry points.

## Latest bounded corrective slice

Action Request 07 corrects the Phase 3A API boundary without adding capability:

- participant responses no longer copy internal transition actor/reason or finality authority/policy values;
- the OpenAPI status schemas and accepted example match an executable response and nested record shapes recursively;
- caller-owned operation-ID, idempotency-key, quantity, and command validation uses an explicit application failure, while internal invariant failures are not reclassified as HTTP 400; and
- focused tests preserve acceptance, replay, conflict, distinct authorities, principal-derived scope, safe 404 equivalence, redaction, and durable read-back.

The completed RED-GREEN and validation record is [`docs/plans/completed/PHASE_3A_API_BOUNDARY_CORRECTIONS.md`](plans/completed/PHASE_3A_API_BOUNDARY_CORRECTIONS.md). This correction adds no endpoint, dependency, migration, runtime configuration, worker, external effect, signer, chain adapter, or production-readiness claim.

## Latest bounded vertical slice

Action Request 30 completes **Executive Brief v1.0.6 Synchronization and
Release Reconciliation**:

- the user-committed Executive Brief is reconciled by immutable Git object and
  SHA-256 identity with the prep v1.0.6 release tag and records;
- README, publication index, current implementation status, and v1.0.1 patch
  notes now distinguish the Solana-aligned Executive Brief from unchanged
  Volumes I-III; and
- the existing immutable Phase 8B v1.0.0 release remains historical evidence,
  while one documentation-only v1.0.1 prerelease records the publication sync.

No executable file, test, PDF byte, dependency, endpoint, authority, native
effect, or production-readiness claim changed. The current implementation
evidence remains `173ebcbb002cacad479c7ced4361106e7c6f21dc`; no Maven,
Foundry, Ethereum, Solana, or demo gate was rerun for this metadata-only patch.

Action Request 29 completes **Phase 8B Final Review and Dual-Chain POC
Release**:

- one final architecture, security, API, persistence, recovery, accounting,
  chain-semantics, and operations review leaves no unresolved Critical or
  Important finding;
- the USDZELLE OpenAPI enumerates the runtime's classified failure responses,
  and Solana readiness now contains transient probe failures inside its bounded
  polling loop;
- the final offline Maven, Foundry, Enforcer, `jdeps`, Ethereum, and Solana
  gates pass with exact replay, reconciliation, payout-before-burn, and restart
  evidence; and
- the README quick start, final review, release notes, and completed evidence
  preserve the local-only, synthetic, non-production boundary.

No dependency, migration, endpoint, authority, PDF, public-network behavior,
or production claim was added. Future production identity, custody, bank,
accounting-close, operations, or publication alignment remains separately
authorized work.

Action Request 28 completes **Phase 8A Share-Ready Demo Documentation and
Publication Reconciliation**:

- the [POC walkthrough](DEMO_WALKTHROUGH.md) explains the two product meanings,
  runtime topology, exact synthetic accounting, server-resolved signers,
  PostgreSQL business truth, native evidence, replay, and stop/reset behavior;
- one topology and two sequence diagrams distinguish outward commands from
  independently observed evidence without implying a global transaction;
- README and both runbooks give a first-time reviewer an explicit, mutually
  exclusive Ethereum/Solana operating and state-preservation path; and
- all four publication records are reconciled, with v1.0.5 Volume I/Executive
  PDFs synchronized at `87f8aadf9f2b520c40631cd236eb0a5d91417e95` and the
  current Solana implementation evidence retained at
  `173ebcbb002cacad479c7ced4361106e7c6f21dc`.

This slice changes no executable file, PDF byte, endpoint, authority, native
effect, or production-readiness claim. Phase 8B remains the next separately
authorized code, security, recovery, API, and final share-readiness review.

Action Request 27 implements **Phase 7F Reproducible Local Solana Demonstrations**:

- the existing Phase 6B/6C product parents and Phase 7E Solana realization remain unchanged while thin scripts package host-native Agave 4.1.2 and Java 25.0.2 with the cached digest-pinned PostgreSQL image;
- every listener remains loopback-only, generated credentials and native fixture material remain ignored and mode-restricted, ordinary stop preserves the dedicated database/ledger, and explicit reset removes only the named Phase 7F state;
- Demo A drives exact user-held acquisition, hold, replay, payout-before-burn redemption, reconciliation, and zero final supply/custody; after reset, Demo B drives the exact six-effect settlement-only route, replay, reconciliation, and zero final chain/accounting positions; and
- one retained-state proof accepts acquisition with delivery disabled, restarts only Java while PostgreSQL and private Agave remain live, and completes exactly one withdrawal and mint after recovery.

The local-only status route now projects chain-neutral network, observation-height, asset, balance, supply, and confirmed-effect fields while retaining its legacy Ethereum fields. This packaging adds no product behavior, migration, dependency, image, public endpoint, native program, public network, or production custody claim. Phase 8A now documents this verified behavior; Phase 8B retains the broader final review.

Action Request 26 implements **Phase 7E Solana Product-Path Orchestration**:

- the existing Phase 6B acquisition/redemption parents and Phase 6C settlement-only companion accept the allowlisted `SOLANA` route under `local-demo,local-solana`; no parallel aggregate or public endpoint is added;
- V14 extends the existing workflow and registered-instruction networks, chain-discriminates accounting and retained workflow evidence, and keeps the Ethereum and Solana workers exclusive across parent, wallet-transfer, mint, and burn children;
- the server resolves both `TRANSFER_AUTHORITY` keys from immutable wallet alias, address, network, purpose, registry version, and key version; requests cannot supply an alias and any retained-context mismatch fails before native attempt creation;
- exact finalized Solana mint, transfer, redemption-custody, and burn evidence is bound once to the stable workflow/step/effect/child identity before accounting consumption, so replay cannot adopt a later observation; and
- one consolidated PostgreSQL/Agave gate completes user-held acquisition/replay/redemption and the registered settlement-only sender-acquisition/transfer/recipient-`AUTO_REDEEM` flow with durable queue reconstruction, payout-before-burn, reconciliation, and zero final token supply and balances.

The opt-in gate's shorter polling cadence exists only in `SolanaProductPathOrchestrationIntegrationTest`. Production delivery defaults, inquiry-before-resubmission, proven-expiry replacement, and manual-review policies are unchanged. Phase 7F demonstration packaging remains the next bounded recommendation; no dependency, image, module, endpoint, secret mechanism, custom program, public cluster, or PDF changed.

Action Request 25 implements **Phase 7D Solana Redemption Custody and ADMIN Burn Parity**:

- the unchanged exact burn API remains the only public command; the mutually exclusive `local-solana` profile resolves USER_1, ADMIN redemption, fee payer, mint, programs, signer roles, and policies without caller wallet/authority fields;
- the existing provider-neutral redemption and wallet-transfer boundaries create or resume one exact USER_1-to-ADMIN classic-SPL `TransferChecked` through ordered fee-payer and USER_1 signatures, separately from the burn operation/attempt;
- V13 extends the shared Solana attempt/signature/observation records for burn context and atomically binds one stable burn identity to one exact finalized custody operation/effect/attempt/evidence correlation, preventing premature or double consumption while permitting only a parented native replacement after proven pre-submission expiry;
- one classic-SPL `BurnChecked` destroys exact `10000` atomic units from the ADMIN associated account through ordered fee-payer and ADMIN signatures, and completion requires finalized matching instruction, indexed transaction balance, account ownership, ADMIN decrement, and equal supply decrement evidence; and
- focused PostgreSQL/fake-RPC tests plus one consolidated PostgreSQL/Agave gate prove unsafe-evidence rejection, exact one-time custody consumption, same-lineage expiry replacement, mint/custody/burn response-loss inquiry, restart recovery, no duplicate submission, retained burn observation across unrelated mint-authority configuration change, and final supply/USER_1/ADMIN balances at zero. Only blockchain finality advances.

No endpoint/OpenAPI, dependency/module/image, product-parent selection, bank payout/accounting orchestration, arbitrary route/quantity, custom program, Token-2022, CCTP, public cluster, production custody, or general/operator-triggered replacement policy is added. ADRs 0003 and 0010 already govern the native authority and evidence decisions, so no new ADR is required. Phase 7E product-path orchestration is the next bounded recommendation; Phase 7F then packages both demonstrations, and Phase 8 follows Phase 7F.

Action Request 24 implements **Phase 7C Solana Wallet Transfer Parity**:

- the existing provider-neutral wallet-transfer acceptance and delivery path accepts the allowlisted local Solana network while callers still cannot select wallets, signers, programs, RPC, policy, or finality;
- the isolated Sava adapter constructs the canonical USER_1 and USER_2 associated token accounts, creates only a missing destination ATA, and serializes one exact classic-SPL `TransferChecked` for `100.00`/`10000` atomic units;
- the configured local signer supplies ordered fee-payer and source-owner Ed25519 signatures over the same immutable legacy message, with exact role, alias, key-version, action, and network fencing;
- V12 generalizes the existing V11 Solana attempt/signature/observation records in place for mint or transfer context, while the common wallet-transfer repository and V6 migration move to the persistence adapter so neither chain adapter owns shared durable state; and
- focused fake-RPC/PostgreSQL coverage plus one consolidated real PostgreSQL/Agave gate prove source/destination account validation, sufficient balance, exact instruction/signer order, crash recovery from a durable signing result before adapter attachment, response-loss inquiry, restart recovery, no duplicate submission, populated V11-to-V12 preservation, indexed transaction balance attribution, finalized source `0`, destination `10000`, and unchanged supply `10000`. Only blockchain finality advances.

At that Phase 7C boundary, redemption custody, burn, product-path orchestration, demonstrations, public clusters, production custody, custom programs, Token-2022, CCTP, API expansion, and automatic replacement remained out of scope. ADRs 0003 and 0010 already governed the native authority and evidence decisions.

Action Request 23 implements **Phase 7B Java/Sava Compatibility and Solana Mint Parity**:

- Sava Core/RPC 25.8.0 and `json-iterator` 25.3.0 pass executable Java 25 coverage for Phase 7A public vectors, classic Token/ATA layouts, checked-instruction fields, legacy message/signer ordering, external JDK Ed25519 signatures, and Agave 4.1.2 response shapes; Sava remains isolated to one adapter;
- Bouncy Castle is centrally aligned from 1.82 to 1.85, the resolved tree contains only the approved Sava/parser/BC graph, and the existing Ethereum secp256k1 plus Solana Ed25519 signer suites remain green;
- V11 durably retains immutable cluster/program/account/asset/unit/policy/fee context, serializes preparation-to-observation for the bounded single local route, and records recent blockhash and last-valid height, ordered fee-payer/mint-authority signatures, stable pre-submission identity, submit fence, replacement lineage, and bounded observations;
- the unchanged participant-scoped mint API can run under a mutually exclusive loopback-only `local-solana` profile whose configured signer reads only ignored mode-restricted Phase 7A key files, retains only bound public signature results for crash-safe inquiry, and gives Sava no raw key access; and
- real PostgreSQL plus Agave 4.1.2 proves one exact `100.00`/`10000` atomic-unit classic-SPL mint, forced response loss, process-boundary reconstruction, inquiry by the retained signature, no second submission, and finalized exact transaction/instruction/account/supply/balance evidence. Only blockchain finality advances.

At that Phase 7B boundary, transfer, redemption, burn, either Solana demonstration, public clusters, production custody, custom programs, Token-2022, CCTP, API expansion, and automatic replacement remained out of scope. ADR 0010 governs the native authority and evidence decisions.

Action Request 22 implements **Phase 7A Native Solana Toolchain and Semantic Gate**:

- exact approved native Agave 4.1.2 and SPL Token CLI 5.6.1 artifacts are checksum-verified and kept under ignored repository-local tooling state on the Apple Silicon host;
- scoped commands reject symlinks/symlinked parents, provision mode-`0700`/`0600` disposable local identities, bind only loopback RPC, report truthful health/completion state, stop only the exact PID/command/RPC identity, and reset only ignored Solana runtime state;
- the original Token Program and canonical associated token accounts execute checked mint, user transfer, redemption-custody transfer, and owner burn instructions for exactly `10000` base units at two decimals;
- classified authority failures and a valid-second-mint account mismatch leave both supplies and exact balances unchanged, while every successful transaction retains native signature, actual recent blockhash, slot, finalized commitment, validity-window observations, and zero final supply/balances; and
- [ADR 0010](adr/0010-native-solana-toolchain-and-classic-spl-semantics.md) preserves classic SPL first, no freeze/extension/custom program, distinct ADMIN/redemption/user owners, and the Phase 7B exactness/blockhash obligations.

This slice adds no Java dependency/module, API, migration, runtime profile, custom Rust program, Compose change, public network, production custody, or parity claim. The root Compose file and all Maven-controlled files remain unchanged, so the Phase 6D 503-test offline reactor remains the latest full Maven evidence and was not repeated. The semantic gate passes twice from scoped clean resets; Phase 7B Java-client compatibility and Solana mint parity are the next bounded recommendation.

Action Request 21 implements **Phase 6D Reproducible Ethereum Demo Environment**:

- one root digest-pinned Compose topology for the cached/approved PostgreSQL, Foundry/Anvil, and Temurin images, with an internal network, loopback ports, persistent database/chain volumes, deterministic contract/role bootstrap, health ordering, and a minimal non-root application image;
- ignored mode-restricted runtime secrets, separate local sender/operator bearer identities, deny-by-default authorities, and one fixed-scope aggregate status/OpenAPI projection with no mutation, raw transaction, key, token, or unrestricted evidence surface;
- POSIX commands for prerequisite aggregation, bootstrap, start/readiness/status, Demo A user-held lifecycle, Demo B settlement-only transfer, restart recovery, state-preserving stop, and explicitly destructive exact-project reset;
- exact API-driven cents/atomic-unit, bank/ledger/custody/supply, six-effect, payout-before-burn, reconciliation, replay, and no-duplicate assertions; and
- ordinary control-plane and whole-stack restart evidence preserving authoritative PostgreSQL and Anvil state.

This slice adds no dependency, migration, Solidity behavior, public product field, general orchestration, public network, real bank/reserve/funds, production identity/custody/deployment, automatic compensation, or Solana behavior. The [local runbook](runbooks/LOCAL_ETHEREUM_DEMO.md) is the operator entry point. The 503-test offline reactor and stable-diff reviews are green; Phase 7A subsequently established the separate native Solana semantic gate without changing this environment.

Action Request 20 implements **Phase 6C Settlement-Only Transfer Orchestration**:

- one V10 companion keyed by the existing V3 `TransferId`, with immutable exact amount, two versioned server-owned instructions, two synthetic participant/account/custody routes, ADMIN, network/contract, policy, child-correlation, ordered-boundary, history, and reconciliation context;
- atomic V3+V10+outbox acceptance and exact replay without command re-resolution, plus participant-scoped source-only read-back and an optional minimized orchestration projection on the existing transfer API/OpenAPI;
- one-step delivery that reuses the authoritative Phase 6B acquisition and redemption parents and Phase 5C transfer rather than duplicating bank, accounting, signing, submission, ambiguity, observation, payout, burn, or reconciliation truth;
- a recipient resolved only through the registered `AUTO_REDEEM` instruction, so callers cannot select the recipient participant, either wallet, ADMIN, child identities, policies, status, evidence, or outcome;
- explicit pending, unknown, no-effect, manual-review, completion, exhausted-delivery, and cross-child reconciliation handling with stable parent-derived idempotency and version fencing; and
- one consolidated PostgreSQL/Anvil proof that moves exact value from `USER_1`'s synthetic bank account to `USER_2`'s while all intermediate token, reserve, liability, custody, and supply positions return to zero.

This slice adds no endpoint, dependency, contract change, caller wallet control, arbitrary route enrollment, recipient retention mode, automatic compensation/refund/reversal, environment script, real bank/reserve/custody, public network, Solana behavior, or production settlement/finality claim. The bounded local proof uses segregated custody aliases to preserve the existing child authorities; broader institutional bank-wallet routing remains future work. Phase 6D subsequently packages this path without changing its product contract.

Action Request 19 implements **Phase 6B User-Held On-Ramp and Redemption Orchestration**:

- separate exact acquisition and redemption parents with immutable participant, bank, asset/unit, user/ADMIN wallet, local contract/network, and policy context;
- forward-only V9 normalized workflow/idempotency/step/history/child/evidence/conclusion persistence plus the existing outbox lease/recovery boundary;
- one-step orchestration reusing authoritative synthetic bank, accounting, mint, redemption-custody, burn, observation, and reconciliation services without copying child truth;
- payout-before-burn after confirmed custody accounting, with one retained payout identity and an explicit paid ADMIN-custody-pending-burn recovery position;
- participant-scoped local-profile acquisition/redemption POST/GET resources, distinct acquire/redeem/read authorities, minimized status projection, OpenAPI, and bounded metrics; and
- one consolidated fresh PostgreSQL/Anvil proof that acquires and later redeems exact `10,000` cents/base units, pays once, and returns bank/reserve/liability/custody/supply to the expected zero-net state.

This slice adds no settlement-only parent, public user-wallet API, automatic compensation, dependency, contract change, demo script/environment, real banking/reserve/custody, public network, Solana behavior, or production claim. Its one Ponytail review, one independent review with focused remediation, and 487-test offline reactor are green; Phase 6C settlement-only orchestration is next after closeout.

Action Request 18 implements **Phase 6A Synthetic Reserves and Executable Mock Banks**:

- exact integer USD cents plus deterministic version-fenced `local-demo` fixtures for four bank identities and the two authorized participant accounts;
- provider-neutral withdrawal/deposit/inquiry with durable operation/evidence identity, hashed scoped idempotency, row-locked balance mutation, explicit rejection/ambiguity, bounded pre-effect retry, and restart-safe inquiry;
- local-only endpoints and OpenAPI with separate `local-bank:debit`, `local-bank:credit`, and `local-bank:read` authorities, participant-safe lookup, and no caller-controlled balance/outcome/posting fields;
- forward-only V8 normalized bank, journal, one-time evidence-consumption, custody/supply-position, and reconciliation persistence with append-only and balanced-entry database enforcement; and
- the ADR 0009 closed four-account posting model, trusted durable evidence verification, both payout/burn orderings, and explicit reserve-ledger/chain-supply/incomplete/stale break results.

This slice invokes bank and accounting primitives independently. It adds no parent orchestration, automatic mint/transfer/burn, chain/Solidity/signing change, real banking/reserves, audited statement, attestation, accounting finality, public network, Compose, Solana, or production claim. Phase 6B subsequently composes these primitives only for the user-held local path.

Action Request 17 implements **Phase 5D Local Ethereum Redemption Custody and Burn**:

- one server-resolved exact redemption-custody transfer from a configured user wallet to `ADMIN_REDEMPTION`, durably correlated to an accepted BURN without adding request wallet fields or changing OpenAPI;
- one forward-only V7 migration for transfer purpose, one-time custody consumption, ADMIN burn attempts/observations, and block-bound balance/supply evidence while V1-V6 remain unchanged;
- one `BURNER_ROLE` own-balance contract method and deterministic `burn(uint256)` EIP-1559 path using ADMIN authority, the shared nonce cursor, durable submit-once fencing, and inquiry after response loss;
- independent confirmation of the exact custody and ADMIN-to-zero burn events, canonical blocks, unchanged custody supply, and exact ADMIN/supply decrease; and
- consolidated real-PostgreSQL and real-Anvil proof for `10,000` atomic units moving user to ADMIN to zero, with supply ending at zero and no burn resubmission after ambiguity.

This slice adds no public API/OpenAPI, dependency, bank payout, reserve release, complete redemption parent, Phase 3C parent execution, Compose, public network, production custody, or Solana behavior. Its execution record is the Phase 5D plan above; Phase 6A synthetic reserves and executable mock banks is the next bounded recommendation.

Action Request 16 implements **Phase 5C Local Ethereum Wallet Transfer**:

- one internal standalone acceptance service with exact quantities, scoped replay/conflict, immutable server-resolved source/destination custody context, four finalities, and no endpoint or OpenAPI change;
- one forward-only V6 migration for wallet-transfer aggregate, outbox/inbox, attempt, submission, ambiguity, and normalized observation facts while V1-V5 remain unchanged;
- one direct ERC-20 `transfer(address,uint256)` EIP-1559 path that reuses Phase 5A encoding, nonce, submit-once, inquiry, and canonical observation semantics without adding arbitrary calldata or replacement behavior;
- source-only Phase 4A authorization fenced by accepted and current registry/key versions, with the destination, ADMIN, owner, and bank keys unable to substitute;
- exact transaction/receipt/single-event/canonicality observation that advances blockchain finality only; and
- consolidated real-PostgreSQL and real-Anvil evidence for exact balance movement, unchanged supply, response-loss recovery without resubmission, authority rotation fencing, and per-source concurrent nonces.

This slice adds no public API/OpenAPI, Solidity, dependency, burn, redemption, bank/reserve, parent-orchestration, Compose, public-network, production-custody, or Solana behavior. Its execution record is the [completed Phase 5C plan](plans/completed/PHASE_5C_ETHEREUM_WALLET_TRANSFER.md); Phase 5D redemption receipt and ADMIN burn is the next bounded recommendation.

Action Request 15 implements **Phase 5B Local Multi-Wallet Custody and Configured Signing**:

- one immutable provider-neutral wallet registry carrying server-owned reference/aliases, owner category, Ethereum network, normalized derived address, key reference, registry/key version, explicit purpose set, and enabled status;
- one collection-backed local configured signer for owner/deployer, ADMIN/redemption, four bank-settlement, and four segregated user-custody identities, with no maximum encoded into the registry contract;
- strict canonical secp256k1 parsing, address derivation and expected-address comparison, stable public versioning, exact purpose/address/network/version enforcement, and stale-key manual review through the existing Phase 4A boundary;
- one opt-in `local-demo` profile that is mutually exclusive with the unchanged session-ephemeral `local-signer`, creates no chain adapter or endpoint, and leaves the default runtime unchanged;
- one safe tracked `.env.example` plus an ignored mode-`0600` `.env.local-anvil` local artifact boundary, with redacted property diagnostics and no dotenv dependency; and
- focused generated-key tests for wallet/purpose isolation, restart stability, rotation fencing, configuration failures, redaction, profile conflict/readiness, default/ephemeral isolation, and absence of chain/public signing reachability.

This slice adds no API/OpenAPI, migration, dependency, contract, RPC, transaction, transfer, redemption, burn, reserve, bank, parent-orchestration, public-network, production-custody, or Solana behavior. Its execution record is the [completed Phase 5B plan](plans/completed/PHASE_5B_LOCAL_MULTI_WALLET_CUSTODY.md); Phase 5C now consumes its user-custody identities through the separately bounded transfer path.

Action Request 13 implements **Phase 5A Local Ethereum Mint Vertical Slice**:

- a Foundry project pinned to Solidity 0.8.25 and OpenZeppelin Contracts v5.6.1, with a minimal role-gated, two-decimal, non-upgradeable local reference token;
- a Web3j 4.14.0 adapter that owns ABI/EIP-1559 encoding, signer recovery, nonce/hash/native evidence, RPC submission/inquiry, and receipt/event/canonicality interpretation;
- PostgreSQL V5 nonce, immutable attempt, signature/submission, observation, and reconciliation records with short transaction scopes and attempt-before-effect fencing;
- a mint-only application handler that reuses accepted operation, delivery, signing-authority, and finality models without adding an endpoint or transfer path;
- explicit `local-ethereum` plus `local-signer` composition fenced to uncredentialed loopback HTTP and chain `31337`, with no default contract/recipient address; and
- Foundry, independent transaction-vector, real PostgreSQL, real Anvil, concurrent nonce, duplicate, response-loss, revert, default-context, and configuration evidence under [ADR 0007](adr/0007-local-ethereum-mint-vertical-slice.md) and the [completed Phase 5A plan](plans/completed/PHASE_5A_ETHEREUM_LOCAL_MINT_VERTICAL_SLICE.md).

This is one local development mint effect, not either complete demonstration. It neither executes an accepted Phase 3C transfer effect nor implements burn, replacement/cancellation, production custody, hosted/public RPC, legal/customer/accounting finality, or settlement. Phase 5B supplies the named least-authority local identities without changing this mint path; Phase 5C adds a separate internal user-custody transfer primitive.

The preceding Action Request 12 implements **Phase 4B Isolated Local-Development Signer**:

- one focused `adapters/signer-local` module reusing the Phase 4A `SignerPort` and `SigningKeyRegistry` rather than creating another authority model;
- one centrally managed Bouncy Castle 1.85 dependency confined to exact 32-byte secp256k1 digest signing, low-`s` normalization, and compact recovery encoding, with JDK-native Ed25519 for exact Solana message bytes;
- one in-memory EVM key and one in-memory Solana key generated only under explicit profile `local-signer`, with opaque session aliases/versions, public fingerprints, typed role/network allowlists, and no persisted/private input;
- exact replay through the durable Phase 4A result without re-signing, stable provider-identity conflict, inquiry after ambiguity, bounded no-signature retry, and stale-session manual review;
- explicit Spring composition, safe startup warning, invalid-configuration failure, and unchanged default context/readiness/OpenAPI/public resources; and
- accepted [ADR 0006](adr/0006-local-development-signing-provider.md) plus the [completed Phase 4B plan](plans/completed/PHASE_4B_LOCAL_DEVELOPMENT_SIGNER.md).

This capability is local-development infrastructure, not production custody. It reads or persists no private key, seed, mnemonic, keystore, credential, or wallet address. Phase 5A now consumes its transient EVM signature only under the separate local-Ethereum profile; the signer itself still constructs/submits no transaction and grants no finality.

The preceding Action Request 11 implements **Phase 4A Durable Signing-Authority Boundary**:

- one immutable signing aggregate with stable request/provider-attempt identities, complete operation/transfer/effect correlation, exact quantity, key-role metadata, policy/approval context, expiry, and append-only lifecycle/evidence;
- separate EVM 32-byte `secp256k1` digest and Solana serialized-message `Ed25519` provider commands plus inquiry—no generic signer command;
- versioned canonical intent and resolved-key hashes that make exact replay stable and any bound substitution a conflict;
- policy/key validation before provider use, a durable provider identity before invocation, inquiry after unresolved or ambiguous outcomes, and linked retry only after evidence proves no signature;
- one forward-only V4 migration and explicit JDBC repository storing hashes, lengths, encodings, non-secret metadata, and opaque evidence references rather than raw payloads, signatures, keys, or credentials; and
- a deterministic test-only synthetic provider with success, denial, retryable-no-signature, ambiguity/inquiry, conflict, and infrastructure-failure fixtures.

At the Phase 4A checkpoint this internal use case was deliberately not composed into Spring and exposed no runtime provider or real cryptography. Action Request 12 supplies the isolated local-development implementation described above; Action Request 13 consumes its EVM mode for one bounded mint. HSM/MPC/custody adapters and all other native effects remain later slices, and every provider/chain change must retain Phase 4A's durable identity/inquiry boundary.

Action Request 10 implements **Phase 3C Chain-Neutral Transfer Aggregate and Mock-Bank Boundary**:

- one immutable parent with exact quantity, participant scope, server-resolved custody context, separate finalities, and five ordered stable effect identities;
- one forward-only V3 migration for transfer/idempotency/effect/history/finality/inbox records plus transfer aggregate support in the existing outbox;
- participant-scoped transfer POST/GET resources with separate authorities, safe 404 equivalence, minimized projections, and an authoritative OpenAPI contract that contains no wallet request fields;
- database-authoritative replay/conflict, deterministic concurrent duplicate resolution, rollback, restart reconstruction, and exact V3 constraints;
- a provider-neutral mock-bank port plus a deterministic synthetic adapter that is not runtime fallback infrastructure; and
- a `TransferAccepted` handler that transactionally deduplicates the Phase 3B delivery identity and prepares only the first withdrawal.

The final offline reactor passed 364 tests with zero failures, errors, or skips: 249 domain, 40 application, 38 real-PostgreSQL persistence, and 37 control-plane tests. The independent review has no unresolved Critical or Important findings.

This slice performs no bank, signer, RPC, mint, token-transfer, burn, deposit, observation, reconciliation, compensation, or settlement effect. The server chooses institution-controlled wallet roles; callers supply only amount/currency, source/destination synthetic bank references, and an optional allowlisted logical network. Phases 4A-5A subsequently added the bounded signing and local-mint capabilities described above; execution of the remaining parent effects still needs separate authorized plans and evidence.

The previously verified Action Request 09 implements **Phase 3B Durable Worker and Delivery Recovery**:

- framework-free application delivery identity, outcomes, handler/queue ports, retry policy, and worker orchestration;
- one forward-only delivery-state/attempt-history migration and a separate explicit JDBC queue adapter;
- PostgreSQL multi-worker exclusion, ordered claims, expiring leases, stale-owner fencing, durable retries, manual review, and restart recovery;
- a disabled-by-default, typed, Spring-managed single-thread polling lifecycle that requires a real handler when enabled;
- existing Micrometer counters/gauges with bounded, non-sensitive dimensions; and
- deterministic pure tests plus real PostgreSQL failure-window tests, including transactional consumer deduplication in test scope.

Phase 3B remains at-least-once delivery infrastructure. Phase 3C now consumes it only for the bounded internal first-withdrawal preparation; the default worker is still disabled and no external effect is performed.

The previously verified Phase 3A slice supplies:

- one `adapters/persistence-postgres` module using explicit Spring JDBC, HikariCP, Flyway, and the PostgreSQL driver;
- one normalized migration for operations, hashed idempotency bindings, ordered transitions/evidence, attempt lineage, four finality/evidence histories, and pending outbox events;
- one atomic acceptance transaction with database-authoritative concurrent replay/conflict behavior;
- `POST /v1/token-operations/mints`, `POST /v1/token-operations/burns`, and participant-scoped `GET /v1/token-operations/{operationId}`;
- stateless Spring Security with separate mint/burn/read authorities and no configured identity provider or credential;
- one authoritative OpenAPI 3.1 YAML resource plus executable conformance tests; and
- real pinned PostgreSQL integration/API tests for migrations, exact quantities, rollback, concurrency, restart, safe failures, authorization, and data exposure.

The Phase 3A acceptance transaction durably records requests only; it does not poll the outbox or perform an external effect. Phase 3B adds the separate delivery boundary described above without changing that acceptance claim.

The previously verified Phase 2 slice supplies:

- exact asset/unit and token quantity types;
- mint and burn commands;
- scoped idempotency, versioned canonical payload hashing, and immutable accepted-command context;
- operation and attempt identity;
- explicit operation state transitions including ambiguous submission and manual review;
- distinct append-only finality histories with lifecycle prerequisites;
- provider-neutral chain and signer ports with stable internal correlation and exact-byte constraint binding; and
- pure Java tests proving invariants without Spring, persistence, Web3j, Solana SDKs, Solidity, Rust, or Compose.

Those contracts preserve opaque native identity and separate prepare, submit-once, inquiry, observation, lifetime/retry, and evidence semantics without implementing either chain adapter.

The implemented transfer/signing boundaries, bounded local-Ethereum effects, configured local custody, synthetic bank/accounting primitives, Phase 6B user-held parents, Phase 6C settlement-only companion, Phase 6D operator environment, Phase 7A native semantic gate, Phase 7B-7D local Solana primitives, Phase 7E local Solana product paths, and Phase 7F local Solana demonstration packaging are mapped in [`docs/TRANSFER_DEMO.md`](TRANSFER_DEMO.md) and introduced in the [POC walkthrough](DEMO_WALKTHROUGH.md). Phase 8B closes the current dual-chain POC release evidence. No further executable slice is authorized; production integrations and future publication alignment each require a separately approved bounded action.
