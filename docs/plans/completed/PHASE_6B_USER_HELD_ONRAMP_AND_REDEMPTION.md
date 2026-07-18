# Phase 6B User-Held On-Ramp and Redemption Execution Plan

**Status:** `completed`

**Goal:** Deliver two independently durable, participant-scoped local-Ethereum workflows that acquire USDZELLE after confirmed synthetic reserve funding and redeem USDZELLE through confirmed ADMIN custody, payout-before-burn, exact accounting, and reconciliation.

**Approved request:** Action Request 19.

**Starting baseline:** `main` at `ba32447894593fb9f22c3350d37d66d6c72a34c8`. On 2026-07-18, local `HEAD`, `origin/main`, and freshly fetched `FETCH_HEAD` matched this SHA; the worktree was clean and `origin` was `git@github.com:johnwhitton/digital-banking.git`.

**Authority:** Action Request 19, `AGENTS.md`, `SECURITY.md`, accepted ADRs 0005 and 0008, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/IMPLEMENTATION_STANDARDS.md`, `docs/TRANSFER_DEMO.md`, and the completed Phase 3B/3C and Phase 5A-6A plans.

## Preflight evidence

- [x] Approved branch, remote, local SHA, tracking SHA, and freshly fetched live SHA match.
- [x] The worktree was clean before this plan was created.
- [x] `.env.local-anvil` exists, is ignored and untracked, is mode `0600`, and was not read or printed.
- [x] The directly governing documents and completed predecessor plans were read once.
- [x] Direct source and V1-V8 inspection identified the existing exact USD/token quantities, participant/idempotency conventions, generic outbox/lease worker, bank execution/inquiry, trusted accounting postings, mint, redemption custody, burn, independent observation, and reconciliation seams.
- [x] No workflow engine, broker, new module, dependency, image, contract change, public network, or breaking existing API change is required.

## Minimal implementation design

- Add two explicit plain-Java parent workflow kinds and state vocabularies, backed by one immutable aggregate shape. Each accepted parent binds participant, exact USD cents and matching two-decimal USDZELLE base units, configured bank account, user and ADMIN wallet metadata, local Ethereum route/contract, workflow/conversion/accounting/finality/reconciliation policy versions, canonical command digest, hashed idempotency identity, accepted time, and ordered step definitions.
- Keep child truth in its owning repositories. The parent stores only stable child operation and evidence references, posting references, step status, append-only transitions, and reconciliation/completion conclusion; it does not copy bank evidence, journal lines, chain receipts/logs, signatures, balances, or raw provider data.
- Add one framework-free acceptance/orchestration service and one provider-neutral workflow repository port. Acceptance, initial step, history, idempotency binding, and a `UsdzelleWorkflowAccepted` outbox event commit atomically. Each delivery advances at most one eligible durable boundary and uses stable parent-derived child idempotency identities.
- Reuse `MockBankApplicationService` and inquiry for withdrawal/payout, `AccountingApplicationService` for the five closed posting types and reconciliation, `TokenOperationApplicationService` plus the existing local Ethereum mint/burn handlers, and `WalletTransferAcceptanceService` plus the existing custody handler. Never infer child completion from dispatch; re-read the authoritative child aggregate, finality, observation, evidence-consumption, journal, and reconciliation state.
- Add only `V9__create_usdzelle_workflow.sql`. Keep V1-V8 byte-identical. Normalize parent, idempotency, accepted context, ordered steps, transitions, child/evidence/posting correlations, workflow inbox/lease schedule, and one completion/reconciliation conclusion. Extend the existing generic outbox constraint for the new parent event instead of adding a second queue.
- Expose `POST/GET /v1/usdzelle/acquisitions` and `POST/GET /v1/usdzelle/redemptions`. Requests contain exact amount/currency and one participant-owned synthetic bank-account reference plus the established idempotency header and optional accepted logical network. All wallets, ADMIN identity, asset/unit, route, contract, signing, policy, child, status, and evidence fields remain server-owned. Return `202` only after durable acceptance; exact replay returns the original resource and conflict returns stable `409`.
- Compose workflow execution only when the explicit local workflow, local-demo, and local-ethereum conditions are satisfied. Defaults remain disabled and contain no fixture principal, signer, RPC, bank, or workflow worker. Metrics use only workflow kind, step category, outcome, reconciliation conclusion, and bounded age bucket.
- Select payout-before-burn only after confirmed redemption custody and accounting reclassification. Payout success is consumed exactly once before burn dispatch. Any later burn delay, ambiguity, rejection, or manual review retains the paid ADMIN-custody-pending-burn position and cannot issue another payout.
- Add one narrow ADR only if implementation proves that ADRs 0005/0008 and the approved request do not already durably govern database-worker orchestration and payout-before-burn. Otherwise record the approved policy in the plan and living design without another decision file.

## Test-first slices

### 1. Parent exactness, acceptance, and transitions

- [x] Add behavior-first domain/application tests for exact USD-to-base-unit conversion, canonical acquisition/redemption identity, server-owned context, explicit legal step order, forbidden transitions, derived completion, and payout-before-burn.
- [x] Observe focused RED failures against compiling test fixtures, then add the smallest parent aggregate, canonicalizer, acceptance service, and repository contracts.
- [x] Prove exact replay/conflict, participant isolation, caller-field rejection, one initial eligible step, and stable child identities.

### 2. V9 and durable workflow repository

- [x] Add real-PostgreSQL tests for V1-V9 empty migration, atomic acceptance/outbox, replay/conflict concurrency, complete read-back, participant-safe lookup, optimistic versioning, queue claim/lease compatibility, and restart.
- [x] Add V9 and the minimum explicit JDBC adapter. Extend only the existing generic outbox/queue behavior needed to claim `UsdzelleWorkflowAccepted` work.

### 3. Acquisition orchestration

- [x] Compose the existing focused bank, accounting, mint, delivery/recovery, and reconciliation failure evidence with parent tests for rejection, retained unknown-child inquiry, one-step ordering, no-effect/manual-review conclusions, and reconciliation mismatch.
- [x] Implement one-step-at-a-time acquisition progression through confirmed withdrawal, reserve funding, existing mint, exact confirmed mint accounting, and reconciliation. A confirmed withdrawal never repeats and remains reserve-funded/pending-mint until safe recovery or manual review.

### 4. Redemption orchestration

- [x] Compose the existing focused custody, bank, accounting, burn, delivery/recovery, and reconciliation failure evidence with parent tests for retained child identity, payout-before-burn ordering, terminal unsafe outcomes, and mismatch/manual review.
- [x] Implement one-step-at-a-time redemption progression through existing custody transfer, custody accounting, exact payout, payout accounting, existing ADMIN burn, burn position consumption, and reconciliation. Confirmed payout can never repeat.

### 5. Participant API, OpenAPI, profiles, metrics, and consolidated proof

- [x] Add API/security/OpenAPI tests for distinct acquire/redeem/read authorities, principal-derived scope, durable `202`, unknown-field rejection, minimized status projections, and schema/response field equality, while application/persistence tests cover replay/conflict and participant-safe lookup.
- [x] Add default-disabled and required combined-profile composition evidence plus fail-closed typed policy/mapping validation and low-cardinality workflow kind/step/outcome/reconciliation/age metrics. Existing worker tests retain lease, retry, and overlap-suppression coverage.
- [x] Add one consolidated real-PostgreSQL/local-Anvil lifecycle proof for `10_000` cents/base units from bank `10_000`, reserve/liabilities/wallets/supply zero through completed acquisition and redemption back to all-zero reserve/liability/custody/supply with bank `10_000`, proving every bank and chain effect once.

### 6. Documentation, review, and closeout

- [x] Update only `README.md`, `SECURITY.md` where the concrete endpoint/payout boundary changes it, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, authoritative OpenAPI, safe local configuration only when required, and this plan.
- [x] Run one Ponytail review and one independent review on the stable diff; resolve all in-scope Critical and Important findings with focused tests.
- [x] Run the single final offline Maven reactor after executable/review changes stabilize, then the prescribed focused structural, security, link, PDF, diff, and staging gates without repeating Maven for documentation-only closeout evidence.
- [x] Attempt Graphify once for at most 60 seconds with the installed version's supported non-HTML command. On failure, restore only the three tracked artifacts, remove only attempt transients, record `tooling_deferred`, and continue.
- [x] Archive this plan, re-fetch/fence `origin/main`, and stage only the authorized allowlist. The single approved commit and non-force push occur after this archived plan is staged and are reported in the final handoff.

## Validation commands

Focused test selections will be recorded with observed RED/GREEN results as each slice stabilizes. The final executable gate is:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
```

Closeout also requires Maven Enforcer and compiled `jdeps`, V1-V9 empty migration and restart evidence, the consolidated PostgreSQL/Anvil lifecycle, OpenAPI/YAML/JSON/XML/TOML parsing where changed, changed-document links, secret/PII/account/public-network/raw-transaction/generated-artifact scans, `.env.local-anvil` mode/ignore/index/package checks, baseline PDF Git blob/mode equality, `git diff --check`, and `git diff --cached --check`.

## Stop conditions and deferrals

Stop for baseline/remote divergence, a required dependency/image, nondeterministic duplicate-value evidence, an accepted API that cannot support server-owned orchestration without breaking change, conflict with payout-before-burn or authoritative accounting/custody semantics, public-network/credential requirement, or any failing required acceptance gate that cannot be resolved in scope.

Deferred: Phase 6C settlement-only orchestration, Docker/demo scripts, production BPM/broker selection, automatic compensation/refund/token return, real banking/reserves/PII, production identity/custody, public networks, contract changes, Solana, reserve investment/yield, production retry/cutoff policy, PDFs, publications, and Salus.

## Evidence log

- **Plan created:** The approved request and direct source/schema inspection establish the existing-module design above. No production, test, migration, configuration, API, OpenAPI, or living-document file other than this active plan has changed yet.
- **Focused domain/application/persistence/API proof:** The combined Phase 6B selection passed the domain aggregate (4), acceptance (4), delivery handler (4), wallet-custody acceptance (5), V9 PostgreSQL repository (5), OpenAPI (1), API/security (2), and consolidated PostgreSQL/Anvil lifecycle (1) tests. One existing wallet-transfer repository test exposed a stale expected migration count after V9; changing only the V9 count expectation made the affected repository and existing mint integration suites pass 10/10.
- **Consolidated lifecycle:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=UsdzelleWorkflowVerticalSliceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` passed against fresh PostgreSQL and Anvil. It proved acquisition, a separately accepted payout-before-burn redemption, exact final zero reserve/liability/custody/supply positions, one payout, replay without another payout, and `RECONCILED` conclusions.
- **Metrics/default boundary:** after adding only the requested bounded telemetry and default-profile absence assertions, `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=UsdzelleWorkflowMetricsTest,UsdzelleWorkflowApiTest,DigitalBankingApplicationTests,UsdzelleWorkflowVerticalSliceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` passed 7 tests. The metric tags contain only workflow kind, step, bounded result/conclusion, and age bucket.
- **Ponytail:** one full review retained the shared parent/repository/chain-evidence classes as cohesive required boundaries, added no dependency/module/engine, and removed the only redundant one-line burn wrapper. No optional Phase 6C, BPM, compensation, API, or configuration abstraction was added.
- **Independent review and remediation:** the one independent stable-diff review identified three in-scope issues: accepted-context drift after configuration rotation, retained chain evidence not being rechecked for canonicality after a crash gap, and exhausted delivery being projected only onto the outbox instead of the parent. The executor and chain-child routing now revalidate the immutable accepted server context and fail closed; retained evidence re-reads its original block and exact balances/supply; and exhausted workflow delivery atomically projects `MANUAL_REVIEW` into the parent step/history. The focused application command passed 4 tests, the V9 repository command passed 5 tests, and `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=UsdzelleWorkflowPropertiesTest,UsdzelleWorkflowVerticalSliceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` passed 2 tests, including the tampered-retained-block rejection.
- **Final offline gate:** the first authorized `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` exposed only four stale legacy assertions expecting the pre-V9 persistence-module migration count. Updating those assertions from five to six changed tests only; the focused four-class persistence selection then passed 36 tests. The required gate rerun passed all seven modules with 487 tests, zero failures, zero errors, and zero skips in 45.324 seconds. Maven Enforcer passed throughout, V1-V9 migrated from empty PostgreSQL, and the consolidated Anvil workflow passed. The prescribed default-context/readiness selection then passed 5 tests without recompilation.
- **Structural and closeout evidence:** compiled JDK 25 `jdeps --multi-release 25 -s --ignore-missing-deps` retained the approved inward dependency direction, and direct imports found no Spring, JDBC, Web3j, Jakarta, or Bouncy Castle leakage into domain/application. Changed YAML and OpenAPI parse. Focused scans found only loopback RPC URLs, random Anvil mnemonic flags, documented placeholders/prohibitions, and existing raw-submission test calls; no committed key, seed, credential, fixed 64-hex secret, PII/real account identifier, hosted/public-network default, secret-bearing output, generated artifact, executable-mode change, POM/dependency, contract, or PDF change exists. `.env.local-anvil` remains mode `0600`, ignored, untracked, unstaged, unread, and unprinted. All four PDFs retain baseline mode `100644` and blobs `2b36c73cd47953bfd690b6db2e7d4317b8fff04d`, `b3b907c414a7c4a68741ad63bd2279d5ea007c6b`, `ebe456d6e71685aca63312c8d7466f17a2b86828`, and `ae25c88118b3bb8356784d2f0f02f1096b034331`. No production, build, or configuration file changed after the successful full gate; the executable input digest remains `81a120c793325b2bd81ba0093b4393e90822172c600ea165096497e97b935602`.
- **Graphify — `tooling_deferred`:** reviewed Graphify 0.8.47 received the single capped `/opt/homebrew/bin/gtimeout 60 graphify . --update --no-viz` attempt. It exited 1 immediately before extraction because no permitted LLM backend was configured. No query, HTML, extraction, labeling, clustering, retry, or reconstruction ran; no `.graphify_*` transient was created. The three tracked artifacts were restored explicitly and verified at approved baseline blobs `665f42f5f26980afb72d1fbf13760ffbefd3013b`, `cff2a0dea1edb013e8abae2dc7caf81030f2e1f6`, and `9f86c483cb5924120c35324f72bfe3c42548950b`. Existing Graphify output remains non-authoritative navigation data.
