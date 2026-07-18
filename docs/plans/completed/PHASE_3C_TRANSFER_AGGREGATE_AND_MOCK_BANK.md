# Phase 3C Transfer Aggregate and Mock-Bank Plan

**Status:** `completed`

**Goal:** Durably accept a participant-scoped, chain-neutral transfer with five ordered effects, persist its immutable server-resolved custody context, expose safe POST/GET APIs, and transactionally prepare only the first withdrawal through the existing Phase 3B delivery path. No bank or blockchain effect is executed.

## Baseline and authority

| Item | Evidence |
| --- | --- |
| Repository and branch | `/Users/johnwhitton/dev/johnwhitton/digital-banking`, direct work on `main` |
| Approved baseline | `214a31b1cf04503643afaa1352354a375d4c55c3` |
| Preflight | Local `HEAD`, `origin/main`, fetched `FETCH_HEAD`, and live remote `main` matched the baseline; worktree was clean |
| Remote | `git@github.com:johnwhitton/digital-banking.git` |
| Governing sources | Action Request 10 as corrected by the user, `AGENTS.md`, `SECURITY.md`, implementation standards, `DESIGN.md`, `IMPLEMENTATION.md`, `TRANSFER_DEMO.md`, ADR 0004, ADR 0005, OpenAPI, and executable tests |

Direct `rg` and source reading identified the existing exact-quantity model, participant/idempotency types, PostgreSQL acceptance/outbox adapters, Phase 3B lease worker, Spring API/security boundary, and test patterns. No exploratory Graphify query is needed.

## Design checkpoint

The original action-request wording allowed caller-supplied wallet references, while `DESIGN.md` and `TRANSFER_DEMO.md` require institution-controlled wallets to be selected server-side. The user explicitly corrected the request and retained the existing documents as authority.

The public request therefore contains only `amount`, `currency`, source and destination synthetic bank-account references, and optional `settlementNetwork`. It contains no wallet, participant, tenant, authority, asset/unit-version, or policy fields. The application resolves:

- the allowlisted `ETHEREUM` or `SOLANA` route, using one configured local default;
- the currency's exact versioned `AssetUnit` through the catalog; and
- opaque sender and recipient wallet roles through a provider-neutral resolver.

The accepted aggregate stores the resolved asset/unit, network, opaque wallet identities, and route/wallet policy versions as immutable context. Canonicalization includes both caller input and that resolved context so replay remains bound to the original durable result even if later configuration changes. Idempotency lookup occurs before new resolution when a binding already exists; a replay compares the caller-command digest stored with the binding, then returns the original aggregate without consulting current configuration.

No new dependency, module, chain SDK, network client, or ADR is planned. ADRs 0004 and 0005 already govern the PostgreSQL/outbox/runtime choices, and the governing documents already select the custody and route authority model.

## Implementation slices

### 1. Framework-free domain and application

- Add validated transfer identities, opaque bank/wallet references, network, lifecycle/effect states, immutable acceptance context, transition/evidence histories, and the aggregate.
- Construct exactly `BANK_WITHDRAWAL`, `TOKEN_MINT`, `TOKEN_TRANSFER`, `TOKEN_BURN`, and `BANK_DEPOSIT` with stable IDs, sequence, predecessor, attempt/evidence linkage, and distinct finality histories.
- Permit this slice only to move the first withdrawal from planned to prepared. Enforce order, one active attempt, ambiguity blocking, append-only evidence/history, and explicit no-effect/manual-review/compensation-required vocabulary.
- Add transport-neutral acceptance/canonicalization, route/catalog resolution, server-owned wallet resolution, repository, identity, and mock-bank ports. Mock-bank commands bind transfer/effect/participant/account/exact quantity/kind/idempotency/attempt/policy/time/deadline and return explicit outcomes.
- Add a deterministic synthetic mock-bank adapter for contract tests only; do not wire it as a production fallback.

### 2. PostgreSQL V3 and Phase 3B handler

- Add only `V3__create_transfer_persistence.sql`; keep V1/V2 immutable.
- Normalize transfer parent, idempotency binding, five effects, transitions/evidence/finalities, handler inbox, and immutable acceptance context with constrained exact quantities, identities, sequences, statuses, and versions.
- Generalize the existing outbox aggregate reference just enough to carry `TransferAccepted` alongside `TokenOperationAccepted`; preserve one worker, per-aggregate ordering, leases, and recovery.
- Implement atomic parent/idempotency/effects/history/outbox acceptance and participant-scoped full rehydration.
- Implement one transactional inbox operation keyed by Phase 3B delivery identity that inserts the dedup row and prepares the first withdrawal atomically. Redelivery returns the stored result. No external port is called inside that transaction.

### 3. REST, security, and OpenAPI

- Add `POST /v1/transfers` with `transfer:create` and `GET /v1/transfers/{transferId}` with `transfer:read`.
- Derive scope only from `ParticipantPrincipal`; reject unknown JSON fields so wallet or participant inputs cannot override server selection.
- Return `202` only after commit and the original transfer on exact replay. Use the same safe `404` for missing and cross-participant resources.
- Expose five safe effect views and participant-safe evidence, but no raw idempotency, internal actor/reason, policy version, infrastructure identity, exception detail, or secret.
- Extend the authoritative OpenAPI 3.1 file and recursive runtime/schema/example/problem conformance tests.

### 4. Documentation and closeout

- Update only the capability/status/repository-map and implemented-boundary portions of `README.md`, `DESIGN.md`, `IMPLEMENTATION.md`, and `TRANSFER_DEMO.md`.
- State that Phase 3C implements acceptance, effect planning, mock-bank contracts/test behavior, and first-step preparation—not any financial or chain execution, finality, settlement, or production readiness.
- Run Ponytail once on the final diff, one independent read-only review, one final offline reactor, hash-only PDF Git-blob comparison, and one final Graphify refresh with no HTML/query/cache artifacts.

## RED/GREEN and validation evidence

Record each result here before advancing:

| Gate | Command/evidence | Result |
| --- | --- | --- |
| Domain/application RED | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl application -am -Dtest=TransferTest,TransferApplicationServiceTest,TransferAcceptedDeliveryHandlerTest,MockBankPortTest -Dsurefire.failIfNoSpecifiedTests=false test` | Expected RED: domain test compilation failed only because the new transfer aggregate/value/effect/context types were absent; application was correctly not reached. Parent and domain Enforcer execution remained clean. |
| Domain/application GREEN | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl application -am -Dtest=TransferTest,TransferApplicationServiceTest,TransferAcceptedDeliveryHandlerTest,MockBankPortTest -Dsurefire.failIfNoSpecifiedTests=false test`, plus focused regressions before each correction | Initial GREEN passed 7 tests; later test-first review corrections brought the scoped set to 3 domain and 7 application tests. Expected REDs proved rehydration correlation, sub-unit, version-rejection, and evidence-constructor gaps before their GREEN fixes. The final reactor observed all 10 with zero failures/errors/skips; both Enforcer boundaries passed. |
| Persistence RED/GREEN | RED: `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl adapters/persistence-postgres -am -Dtest=PostgresTransferRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`; GREEN: same command | RED failed only on the absent adapter. Final focused GREEN passed 10 real-PostgreSQL tests covering V1-V3 migration, atomic acceptance/rollback, replay/conflict races, participant isolation, restart reconstruction, version fencing, consistent reads, outbox ownership/claiming, and concurrent inbox deduplication. |
| API RED/GREEN | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o -pl control-plane -am -Dtest=TransferApiIntegrationTest,OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test`, followed by `-Dtest=PostgresTransferRepositoryTest,TransferApiIntegrationTest,TransferApiFailureBoundaryTest,OpenApiContractTest,SyntheticMockBankAdapterTest` | RED failed only on absent transfer controller/response types. The final consolidated selection passed 23 tests: 10 persistence plus 13 API/security/redaction/OpenAPI/synthetic-adapter tests. A separate `0.01` RED returned 400 before the shared runtime/OpenAPI exact-quantity rule was corrected; its GREEN plus the five OpenAPI tests passed 6/6. |
| Full offline reactor | `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` exactly once after final source/docs | Passed once in 16.883 seconds: 364 tests (249 domain, 40 application, 38 persistence, 37 control-plane), zero failures/errors/skips; all five reactor projects and Enforcer rules succeeded, and all three Flyway migrations applied from empty PostgreSQL 17 schemas. |
| Focused closeout | Enforcer/dependency direction, `jdeps`, Flyway empty migration, transfer concurrency/rollback/restart/inbox/API/security/OpenAPI, changed-doc links, secret/public-network/error/idempotency scans, portability/structured files, generated artifacts, `git diff --check` | Passed. The required context/readiness selection passed 3 tests. JDK 25 `jdeps -s` reports domain only `java.base`, application only domain/`java.base`, persistence only inward modules/JDK SQL/Spring JDBC+transactions, and control-plane only inward modules plus the approved Spring/Jakarta/Tomcat stack. YAML (2), POM XML (5), JSON (1), TOML (1), 53 changed-document local links, safety/portability/generated-artifact scans, and `git diff --check` passed. |
| Review/integrity | Ponytail, one independent review, baseline PDF blob identities, Graphify disposition | Ponytail found no new dependency/module/speculative wrapper; the 660-line JDBC adapter remains cohesive around one transaction-owning transfer persistence boundary. The independent review has no unresolved Critical/Important findings. Baseline/current PDF blobs and `100644` modes match exactly: `2b36c73...`, `ebe456d6...`, `ae25c881...`. The user waived the Graphify refresh after its semantic extractor stalled; the three tracked artifacts were restored exactly to baseline `214a31b1cf04503643afaa1352354a375d4c55c3`, and existing Graphify output remains non-authoritative navigation data. |
| Git | Remote baseline recheck, exact staging, cached diff/check, one commit/push, synchronized clean verification | Pending |

## Independent review dispositions

Eight Important findings were resolved with focused regression evidence: same-delivery concurrency now locks the parent before inbox inspection; rehydration correlates append-only effect, attempt, transition, and evidence state; ambiguous mock-bank outcomes require evidence and full commands bind idempotent replay; exact positive sub-unit quantities pass runtime and OpenAPI validation; aggregate reads use one transaction plus a shared parent lock; delivery identity must belong to the target transfer/outbox event; the handler rejects unsupported event and payload versions without repository access; and every public mock-bank outcome constructor enforces bounded evidence. Future attempt-persistence work should additionally enforce domain-level identity uniqueness and every historical transition's exact status/time semantics.

The final simplicity review retained no new dependency, module, chain abstraction, or runtime mock-bank fallback. The standard `Supplier` is used for transition IDs. The PostgreSQL adapter exceeds the 500-line extraction-review prompt but remains below the 800-line design-smell threshold and is kept cohesive because acceptance, aggregate hydration, consistent-read locking, and transactional inbox preparation share one explicit mapping/transaction boundary; a mechanical split would add coordination without isolating an independent responsibility.

## Graphify waiver

The required refresh was started only after implementation, focused tests, the successful full offline Maven gate, and independent review. Structural extraction completed, but semantic extraction stalled. The user stopped and waived the refresh, authorized restoration of only `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.json`, and `graphify-out/manifest.json`, and prohibited further Graphify work for this action. Those files now match the approved baseline and are excluded from staging; Graphify remains advisory and non-authoritative.

## Stop conditions and deferrals

Stop for baseline/remote divergence, missing offline prerequisites, nondeterministic concurrency evidence, a required real effect or forbidden dependency/network, unresolved duplicate/lost-value or isolation risk, a material contract/authority redesign, or out-of-scope review finding.

Deferred: executing withdrawal/mint/transfer/burn/deposit, signer/chain adapters, native nonce/blockhash semantics, policy/approval execution, observation, reconciliation, compensation execution, settlement, real bank connectivity, broker/BPM selection, production identity/custody, and production-readiness claims. Reference PDFs remain byte-identical contextual evidence and are not inspected or modified.
