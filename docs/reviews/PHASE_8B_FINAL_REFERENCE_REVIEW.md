# Phase 8B Final Reference Review

## Verdict

The local dual-chain POC is suitable for its stated non-production reference
boundary once the release gates below are green. The review found no Critical
issue. One Important OpenAPI completeness defect was corrected test-first. A
second Important Solana startup-readiness defect was also corrected
test-first. No Critical or Important finding remains open.

This verdict does not certify production security, accounting, reserves,
compliance, legal or customer finality, availability, disaster recovery, or
public-chain operation.

## Reviewed boundary

The review covered the module POMs and imports, accepted ADRs, domain and
application ports, Spring security/controllers/problem handling, all four
OpenAPI documents, Flyway V1--V14, PostgreSQL acceptance/delivery/accounting
adapters, local signers, Web3j and Sava adapters, native observation and
replacement tests, the two demo environments, README/walkthrough/runbooks, and
the Phase 6D, 7F, and 8A completion evidence.

The four PDFs are frozen contextual publications. Their contents were not
opened or revalidated; release closeout uses only Git blob and diff identity.

## Findings

### P8B-I-001 — USDZELLE OpenAPI omitted predictable runtime failures

- **Severity:** Important
- **Boundary/files:** participant API contract in
  `control-plane/src/main/resources/static/openapi/usdzelle-workflows-v1.yaml`
  and `UsdzelleOpenApiContractTest`.
- **Evidence:** POST operations omitted the runtime's `415`, `500`, and `503`
  problem responses; GET operations omitted malformed-ID `400`, `500`, and
  `503`. The common `ApiExceptionHandler` and the sibling token/transfer
  contract already define those outcomes.
- **Consequence:** a reviewer or generated client could treat normal classified
  failure outcomes as undocumented behavior, weakening public-contract and
  share-readiness accuracy.
- **Disposition:** fixed without changing an endpoint or runtime behavior. The
  OpenAPI now enumerates the existing outcomes for both acquisition and
  redemption, and its contract test requires the exact response sets.
- **Validation:** the focused test first failed on the missing response set,
  then passed after the YAML correction. The final offline reactor remains the
  release evidence for the integrated result.
- **Remaining limitation:** the API remains an explicit local-demo interface,
  not a production identity, banking, or settlement API.

### P8B-I-002 — Solana readiness can terminate during a transient status failure

- **Severity:** Important
- **Boundary/files:** `scripts/demo/solana/wait-ready.sh` and its focused shell
  safety test.
- **Evidence:** the clean Phase 7F bootstrap started the private validator,
  classic-SPL fixture, PostgreSQL, and control plane, but the immediately
  required readiness step exited while the status endpoint returned its
  temporary "local Solana status mint is unavailable" invariant. Direct shell
  diagnosis showed that `demo_status_json` delegates failures to `demo_die`,
  whose `exit 1` terminates the readiness shell instead of allowing its bounded
  polling loop to retry.
- **Consequence:** a normal short startup race can make the documented clean
  Solana release sequence fail before Demo A even though the fixture and
  control plane are still converging.
- **Disposition:** fixed at the existing readiness seam. Health and port probes
  are explicit conditionals, and the status probe runs in a subshell so its
  existing failure exit cannot terminate the bounded polling process. No new
  helper, dependency, command, or timeout was added.
- **Validation:** the focused shell regression failed before the correction and
  the Phase 7F shell/configuration gate passed afterward. One clean Solana
  release sequence then passed Demo A and replay, scoped reset, Demo B and
  replay, scoped reset, restart recovery, final status, and safe stop.
- **Remaining limitation:** readiness remains bounded by its documented
  180-second timeout and reports the individual probe states on exhaustion.

### P8B-A-001 — several native and persistence classes exceed extraction-review thresholds

- **Severity:** Advisory
- **Boundary/files:** notably `SavaSolanaMintChainAdapter`,
  `PostgresReserveAccountingAdapter`, `SolanaMintAttemptStore`,
  `PostgresOperationDeliveryQueue`, `PostgresOperationRepository`, and the
  bounded Web3j transfer/burn adapters.
- **Evidence:** these cohesive adapter classes exceed the repository's roughly
  500-line extraction-review prompt; several exceed its roughly 800-line design
  smell threshold. Their tests exercise native encoding, observation,
  concurrency, replacement, restart, and exact-effect behavior through the
  current seams.
- **Consequence:** future changes carry higher review and merge-conflict cost,
  even though no correctness failure was found in this release slice.
- **Disposition:** deferred. Splitting proven native or transaction boundaries
  solely for line count would add release risk and speculative abstractions.
- **Validation:** the final Maven, native-chain, independent, and Ponytail gates
  cover the unchanged implementation.
- **Remaining limitation:** a future behavior-changing slice should reassess
  cohesive extraction before extending these classes.

### P8B-A-002 — some explicit JDBC readers still use `SELECT *`

- **Severity:** Advisory
- **Boundary/files:** settlement-instruction, signing-request, settlement-
  transfer, USDZELLE-workflow, and synthetic-bank PostgreSQL readers.
- **Evidence:** direct source inspection found bounded `SELECT *` statements
  despite the implementation standard's preference for explicit column lists.
  Current row mapping uses named columns and migration/integration tests cover
  V1--V14 reconstruction.
- **Consequence:** a future schema addition could create avoidable coupling or
  obscure the persistence projection under review.
- **Disposition:** deferred because no data or behavior defect was found and a
  broad mechanical rewrite is not required for this release.
- **Validation:** migration, restart, concurrency, and whole-aggregate tests are
  included in the final reactor; both clean demo gates reconstruct from the
  current schema.
- **Remaining limitation:** replace wildcard projections when the owning
  repository next changes, with focused row-mapping and migration tests.

### P8B-A-003 — production integrations and operational controls are intentionally absent

- **Severity:** Advisory
- **Boundary/files:** deployment, identity, custody, bank integration,
  accounting close, monitoring, backup/restore, and public-network operations.
- **Evidence:** defaults are deny-by-default and local-only; the repository has
  no production issuer, HSM/MPC/custody provider, real bank adapter, reserve
  attestation, HA/DR deployment, public RPC configuration, or production SLO.
- **Consequence:** the local evidence cannot be used as a production-readiness,
  reserve, compliance, or operational certification.
- **Disposition:** intentionally deferred outside the POC. The README,
  walkthrough, runbooks, release notes, and accepted ADRs state this boundary.
- **Validation:** safe-claim, public-network, credential, and changed-document
  checks remain part of closeout.
- **Remaining limitation:** each production integration requires separate
  architecture, threat, operational, legal, and acceptance authority.

## Boundary conclusions

- **Architecture:** `domain` remains plain Java; application ports point
  inward; Spring, JDBC, Web3j, and Sava remain adapter/composition concerns.
  Maven Enforcer rules fence the dependency direction. PostgreSQL is business
  truth, while native hashes, signatures, receipts, transactions, slots, and
  commitments are evidence.
- **Security and authority:** default resources deny access without an injected
  identity adapter. Participant scope and safe not-found behavior are tested.
  Bank, token, transfer, read, signer, and operator authorities remain distinct.
  Public requests contain no wallet/key/ADMIN/native-authority/policy selector;
  configured signers fence purpose, network, alias/address, registry/key
  version, and exact signable bytes.
- **API:** implemented resources match the four OpenAPI documents after
  P8B-I-001. Responses remain participant-safe and exclude internal evidence,
  wallet, signer, retry, journal, and finality-authority fields. Local bank and
  status resources remain profile-isolated.
- **Persistence and recovery:** Flyway remains ordered V1--V14. PostgreSQL
  constraints and transactions own concurrent idempotency, atomic acceptance
  and outbox, consumer deduplication, leases/fencing, immutable acceptance
  context, one-time evidence, and append-only accounting history. External
  effects do not execute inside acceptance transactions.
- **Chain semantics:** Ethereum persists source-scoped nonce/attempt identity
  before submit-once and inquires after response loss. Solana persists message,
  signature, blockhash lifetime, and parented replacement lineage; expiry must
  be proven before replacement. Both observers verify exact asset, authority,
  amount, source/destination, success, and canonical/finalized context before
  business finality advances.
- **Financial and reconciliation:** decimal-string requests resolve to exact
  cents/atomic units with no floating point. The four-account journal remains
  balanced; mint/burn and custody/supply are evidence positions, not duplicate
  dollar journals. Payout-before-burn and one-time payout/custody evidence are
  durable and fail closed.
- **Operations:** the README quick start now owns the concise Demo A/Demo B
  commands. Runbooks own prerequisites, readiness, retained state, restart,
  diagnostics, stop, and scoped destructive reset. Environments are mutually
  exclusive, loopback-only, and owned by exact repository scripts.

## Release evidence

The completed Phase 8B plan records exact commands, per-module test totals,
Foundry results, `jdeps`/Enforcer evidence, both clean demo sequences, review
outcomes, document/link/security checks, frozen-PDF Git identity, and
synchronized Git/tag/release state.
