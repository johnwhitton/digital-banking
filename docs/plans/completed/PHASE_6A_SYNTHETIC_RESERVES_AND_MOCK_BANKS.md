# Phase 6A Synthetic Reserves and Executable Mock Banks Execution Plan

**Status:** `completed`

**Goal:** Deliver exact, durable, local-only synthetic bank operations and a small append-only reserve/liability ledger that can be invoked and reconciled independently, without orchestrating any bank or chain lifecycle.

**Approved request:** Action Request 18.

**Starting baseline:** `main` at `89a6846f1b86ce78e67caed5271df21a2ca4f2fa`. On 2026-07-18, local `HEAD`, `origin/main`, and freshly fetched `FETCH_HEAD` matched this SHA; the worktree was clean and `origin` was `git@github.com:johnwhitton/digital-banking.git`.

**Authority:** Action Request 18, `AGENTS.md`, `SECURITY.md`, accepted ADRs 0004, 0005, and 0008, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/IMPLEMENTATION_STANDARDS.md`, `docs/TRANSFER_DEMO.md`, and the completed Phase 3A-3C and Phase 5A-5D plans.

## Preflight evidence

- [x] Approved branch, remote, local SHA, tracking SHA, and freshly fetched live SHA match.
- [x] Worktree was clean before this plan was created.
- [x] `.env.local-anvil` exists, is mode `0600`, is ignored and absent from Git status; no value was read or printed.
- [x] The directly governing documents and completed predecessor plans were read once.
- [x] Direct source, migrations V1-V7, the exact-quantity model, participant/idempotency conventions, PostgreSQL transaction patterns, worker/inbox behavior, existing `MockBankPort`, security, profile, and OpenAPI patterns were inspected.
- [x] No ledger or journal implementation exists to reuse. The current synthetic adapter is deliberately in-memory/test-only and no production bean consumes the bank port.
- [x] No new dependency, module, image, chain change, endpoint in the default profile, or material redesign is required.

## Minimal implementation design

- Add a focused plain-Java accounting package in `domain` for non-negative exact USD cents, typed synthetic-bank/account/operation/evidence identities, immutable bank-operation/account views, trusted posting vocabulary, balanced journal entries, operational custody/supply positions, and explicit reconciliation outcomes.
- Evolve the existing application-owned `MockBankPort` into the executable request/inquiry/account-read contract. Add one application service that derives participant scope and canonical command/idempotency digests, allocates stable identities, and never accepts balances, outcomes, ledger accounts, directions, or failure modes from callers.
- Add one provider-neutral accounting/evidence use case. It resolves durable bank or synthetic confirmed-chain evidence by identity, applies only the closed posting rules, and delegates one-time consumption plus journal/position persistence to PostgreSQL.
- Add forward-only `V8__create_synthetic_reserve_and_mock_bank.sql` under the existing PostgreSQL adapter. Keep V1-V7 byte-identical. Normalize synthetic banks/accounts/operations/balance evidence, ledger accounts/journals/lines, evidence consumption, durable confirmed-chain evidence, operational positions, and reconciliation runs.
- Implement two cohesive JDBC classes in the existing persistence module: one for bank fixture/account/operation behavior and one for accounting/evidence/reconciliation. Use explicit `READ_COMMITTED`, PostgreSQL row locks/uniqueness, short transactions, append-only evidence, and no external call inside a transaction.
- Initialize `BANK_1` through `BANK_4` and only the two required user accounts from typed `local-demo` configuration. Initial balances are used only for first insert; immutable fixture definitions are verified on restart and never overwrite mutated balances.
- Add profile-isolated local controllers and composition only under `local-demo`, with separate debit, credit, and read authorities. Serve a separate local-only OpenAPI contract through the profile-scoped controller. The default security posture remains deny-by-default and no local credentials are added.
- Keep metrics low-cardinality and profile-local. No participant, account, operation, evidence, idempotency, or amount value becomes a label.
- Add ADR 0009 because the closed chart of accounts, trusted posting rules, operational custody memorandum position, and reserve/supply reconciliation boundary are a material accepted accounting decision not yet recorded in an ADR.

## Test-first slices

### 1. Exact money, bank contract, and pure accounting rules

- [x] Add behavior-first tests for exact USD cents, identifiers, balanced/linked journal construction, trusted posting mappings, custody/supply positions, and all reconciliation classifications.
- [x] Observe behavioral RED failures without relying on absent-class compilation as evidence.
- [x] Implement the smallest pure domain/application behavior and rerun the focused selection green.

### 2. V8 and durable synthetic banks

- [x] Add real-PostgreSQL tests first for V1-V8 migration, fixture idempotency/change fencing, withdrawal/deposit success and rejection, exact/conflicting replay, concurrent overdraft prevention, withdrawal/deposit races, rollback, post-commit ambiguity/inquiry, participant isolation, and restart read-back.
- [x] Add V8 and the minimum JDBC bank adapter; preserve atomic account mutation, operation, and balance-evidence persistence.

### 3. Durable accounting, evidence, and reconciliation

- [x] Add real-PostgreSQL tests first for every trusted posting, insufficient positions, one-time evidence consumption under concurrency, reversal linkage, payout/burn order independence after custody, append-only history, mismatches, stale/non-final evidence, and restart read-back.
- [x] Add the minimum accounting/evidence adapter and application service. No automatic bank or chain effect is authorized.

### 4. Profile-isolated HTTP, security, OpenAPI, configuration, and metrics

- [x] Add controller/profile tests first for endpoint absence outside `local-demo`, distinct authorities, participant-safe account/operation lookup, safe 404 equivalence, redacted problems, exact amount/currency/idempotency contract, local OpenAPI conformance, deterministic fixture composition, low-cardinality metrics, and unchanged default readiness.
- [x] Add the smallest local controller/configuration/resource changes and rerun the focused selection green.

### 5. Documentation, stable-diff review, and closeout

- [x] Synchronize only `README.md`, `SECURITY.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION.md`, `docs/TRANSFER_DEMO.md`, ADR index/0009, local OpenAPI, and this plan.
- [x] Run one Ponytail review and one independent review on the stable diff; resolve only valid in-scope Critical/Important findings with focused tests.
- [x] Run the one final offline Maven reactor, then focused structural/document/security/PDF/diff checks without rerunning completed gates for documentation-only evidence edits.
- [x] Attempt Graphify exactly once for at most 60 seconds using a supported non-HTML command; on failure restore only the three tracked artifacts, remove only attempt transients, and record `tooling_deferred`.
- [x] Archive this plan, re-fetch/fence `origin/main`, and stage only the authorized allowlist. The single approved commit and non-force push occur after this archived plan is staged and are reported in the final handoff.

## Validation commands

Focused selections will be recorded with observed test counts as each slice stabilizes. The final executable gate is:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
```

Closeout also requires Enforcer and compiled `jdeps`, V1-V8 empty migration and restart evidence, local OpenAPI/YAML/JSON/XML/TOML parsing where changed, changed-document links, secret/PII/account/public-network/raw-transaction/generated-artifact scans, `.env.local-anvil` mode/ignore/index/package checks, baseline PDF Git blob/mode equality, `git diff --check`, and `git diff --cached --check`.

## Stop conditions and deferrals

Stop for baseline/remote divergence, a required dependency or image, non-deterministic financial concurrency evidence, inability to isolate endpoints to the local profile, an accounting rule that materially conflicts with Action Request 18/ADR 0008, or a failing required acceptance gate.

Deferred: Phase 6B on-ramp/redemption orchestration, Phase 6C settlement orchestration, automatic evidence projection from chain adapters, payout/burn ordering, bank/chain compensation, real banks or reserves, production accounting/custody, public networks, Solana, Compose/demo scripts, PDFs, and Salus.

## Evidence log

- **Plan created:** Preflight and direct source/schema inspection established the minimal existing-module design above. No production, test, migration, configuration, API, or living-document file other than this active plan has changed yet.
- **Pure accounting RED/GREEN:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl domain -Dtest=UsdCentsTest,ReserveAccountingTest test` first compiled the new types, passed the exact-money test, and failed all three accounting tests only at the deliberately unimplemented posting engine. The GREEN rerun passed 4/4 tests with domain Enforcer green. Evidence covers exact cents/fraction/currency/bounds, closed reserve-funding/mint/redemption-custody/payout/burn rules, balanced journals, both payout/burn orderings, insufficient positions, all five reconciliation outcomes, and linked append-only reversal construction.
- **Application bank boundary:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl application -am -Dtest=MockBankApplicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` passed 2/2. It proves participant/account resolution before effect, versioned canonical key/payload digests, stable identity across bounded explicit pre-effect retry, no retry after `UNKNOWN`, and validation/not-found behavior without retaining the raw key.
- **Durable synthetic banks:** the consolidated real-PostgreSQL `PostgresMockBankAdapterTest` passed 6/6. V8 applied from empty, fixture initialization/restart and changed-definition fencing held, withdrawal/deposit/replay/conflict/rejection were atomic, concurrent withdrawal could not overdraw, withdrawal/deposit did not lose updates, post-commit ambiguity recovered by inquiry without a second effect, and forced rollback left no mutation/evidence.
- **Durable accounting:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/persistence-postgres -am -Dtest=PostgresReserveAccountingAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test` passed 6/6 after the stable evidence checks. It covers every posting, both payout/burn orders, exact replay and typed conflicting consumption, concurrent one-time use, missing/mismatched/stale/non-final/removed/non-canonical evidence, required native identity and observed-supply checks, insufficient-position rollback, mismatch persistence, restart, database-balanced journals, and append-only enforcement.
- **Profile/API boundary:** the focused `LocalMockBankOpenApiContractTest,LocalDemoProfileIntegrationTest` control-plane selection passed 3/3. Earlier focused default-context/OpenAPI selections passed 4/4. Evidence covers default absence, profile-only composition, V1-V8 empty migration, distinct authorities, participant-safe access, exact request shapes, replay/conflict, caller-control rejection, served OpenAPI equality, non-sensitive metrics, and unchanged readiness. The final narrow rerun after safe 503/202/metric classification changes passed 3/3 at 2026-07-18 12:49 PDT.
- **Documentation and decision record:** the authorized living documents now distinguish independently executable Phase 6A bank/accounting primitives from Phase 6B orchestration. ADR 0009 records the closed four-account chart, burn-as-position rule, one-time evidence use, exact zero-equity reserve/supply equations, and explicit break outcomes without adding a general ledger framework.
- **Stable-diff reviews and remediation:** the one Ponytail review concluded the slice was already lean and recommended shipping without another abstraction. The one independent review reported no Critical findings and four valid Important findings: caller-declared reconciliation state, unanchored chain shadow evidence, missing durable reversals, and a canonical-amount schema mismatch. Focused remediation removed caller assessments, derives reconciliation from all durable consumptions, joins chain pointers to authoritative Phase 5 operation/attempt/latest-observation/event/finality rows, adds digest-bound append-only linked reversals, and makes runtime/OpenAPI canonical decimal patterns identical. `PostgresReserveAccountingAdapterTest` passed 6/6 and the local profile/OpenAPI focused tests passed after remediation; the full-schema local-profile test also parses all three authoritative chain queries. No second review was run.
- **Extraction review:** `PostgresReserveAccountingAdapter` is 840 physical lines, predominantly explicit parameterized SQL for the three distinct existing Phase 5 schemas. It remains one cohesive `ReserveAccountingPort` transaction boundary so evidence claim, authoritative verification, ledger/position locks, posting/reversal, and reconciliation cannot be accidentally split across transactions. Extracting a generic evidence abstraction would add indirection across intentionally different mint, custody-transfer, and burn provenance without reducing authority or behavior; the final diff therefore retains the explicit adapter and no new dependency or module.
- **Final offline gate:** `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` passed once at 2026-07-18 13:24 PDT: 463 tests, zero failures, zero errors, zero skips across seven modules. Maven Enforcer passed in every module; V8 applied with V1-V4 in the persistence module and with V1-V7 in Ethereum/control-plane runs. No source, build, or configuration file changed afterward.
- **Closeout checks:** compiled `jdeps` found no forbidden Spring, persistence, JDBC, or Web3j dependency in domain/application classes; 97 changed-document local links resolved; changed YAML/OpenAPI parsed; staged and unstaged diff checks passed; high-confidence staged scans found no key, mnemonic/seed, credential, raw transaction, PII, hosted/public-network default, real bank identifier, or sensitive generated artifact. V1-V7, POMs, chain production source/migrations, contracts, `.env.example`, and PDFs were unchanged. All four PDF working blobs matched baseline mode `100644` and Git blob identities. `.env.local-anvil` remained mode `0600`, ignored, untracked, unstaged, unread, and unprinted.
- **Graphify:** `tooling_deferred`. The single supported `graphify . --update --no-viz` attempt used reviewed Graphify 0.8.47 under a 60-second timeout and exited before extraction because no permitted LLM backend was configured. It generated no tracked Graphify change or HTML; the two attempt transients were removed, and the three tracked Graphify artifacts remain at baseline bytes. No retry or reconstruction was performed.
