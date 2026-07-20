# Phase 8B — Final Review and Dual-Chain POC Release

## Status

Complete on `main` from approved baseline
`fc49099750afcf168803a967cb463bee1875d773`. All required review, build,
Ethereum, Solana, documentation, and repository gates are green. Commit, push,
tag, and prerelease evidence belongs to the final handoff.

## Authority and outcome

Action Request 29 authorizes one final architecture, implementation-standards,
security, API/OpenAPI, persistence/concurrency, recovery, accounting,
operations, and share-readiness review of the verified local Ethereum and
Solana POC. It also authorizes the scoped Phase 6D and Phase 7F resets, the
complete local release gates, bounded test-first correction of valid Critical
or Important findings, one non-force push, the annotated
`digital-banking-v1.0.0-dual-chain-poc` tag, and a GitHub prerelease when the
existing authenticated CLI permits it.

This action does not authorize production readiness, real funds, public
networks, new product behavior, a dependency or image change, a new endpoint,
authority, migration, or workflow, PDF changes, or access to publication or
Salus work.

## Verified preflight

- Repository root, branch, clean worktree/index, approved SSH origin, local
  `HEAD`, local/tracking/fetched/live-remote `main`, and the baseline all match
  `fc49099750afcf168803a967cb463bee1875d773`.
- The release tag and GitHub release do not yet exist; the authenticated `gh`
  CLI has repository scope.
- Both local demo environments are stopped with no ownership conflict.
- Phase 6D and Phase 7F prerequisite checks pass without installing, pulling,
  or substituting anything.
- Java 25.0.2, Docker 28.5.1 / Compose 2.40.3, Foundry 1.5.1, Agave 4.1.2,
  SPL Token CLI 5.6.1, and the three approved cached image identities match
  the documented releases.
- `.env.local-anvil` remains ignored, untracked, a regular mode-`0600` file;
  no value was read or printed.
- The four frozen PDFs retain their approved baseline Git blobs and mode
  `100644`. Their content will not be opened, hashed, rendered, extracted, or
  regenerated during this action.

## Review method

1. Read the governing policy, design, standards, ADRs, predecessor plans,
   OpenAPI contracts, migrations, runbooks, and current source/test evidence
   once; use direct `rg` and focused source inspection for exact facts.
2. Correct README quick-start usability first, without changing either demo's
   meaning or duplicating the runbooks.
3. Review architecture boundaries, dependency direction, exact quantities,
   idempotency, signer/custody fencing, API minimization, database constraints
   and transactions, at-least-once recovery, ambiguity/replacement rules,
   accounting evidence, reconciliation, operator isolation, and local-network
   controls. Record every finding with severity, evidence, disposition, and
   validation.
4. Correct only valid in-scope Critical or Important findings, test-first and
   minimally. Record Minor observations and production-only gaps as bounded
   deferrals.
5. Freeze the diff; perform one Ponytail review and at most one independent
   correctness/security review. Resolve only valid Critical or Important
   findings and rerun only affected focused checks before the final gates.

## Release gate

- Run exactly one final offline Maven reactor after the implementation and
  reviews are stable:

  ```bash
  JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify
  ```

- Reconcile per-module Surefire/Failsafe totals, run configured Maven Enforcer
  and compiled-class Java 25 `jdeps` evidence once, and run the Foundry suite
  once with `cd contracts/evm && forge test`.
- Run the full clean-state Ethereum release sequence: start/readiness/status,
  Demo A and replay, scoped reset/start, Demo B and replay, scoped
  reset/start, deterministic restart recovery, final status, and safe stop.
- Stop Ethereum before the full clean-state Solana sequence: start/readiness/
  status, Demo A and replay, scoped reset/start, Demo B and replay, scoped
  reset/start, deterministic restart recovery, final status, and safe stop.
- Do not repeat a green full gate unless a relevant executable correction
  invalidates it. Preserve and report any failed state before deciding whether
  one authorized bounded correction is possible.
- Graphify is optional and non-authoritative. If the final workflow does not
  need it, record `not_applicable`; otherwise attempt it once for at most 60
  seconds with no HTML or manual reconstruction.

## Documentation and release artifacts

- Add `docs/reviews/PHASE_8B_FINAL_REFERENCE_REVIEW.md` with the reviewed
  surfaces, finding table, dispositions, exact gate evidence, residual risks,
  and explicit non-production verdict.
- Add `docs/releases/DIGITAL_BANKING_V1_0_0_DUAL_CHAIN_POC.md` as the authoritative prerelease
  body, covering both product paths and local chains, exact evidence, known
  limitations, runbook links, baseline/tag target, and validation results.
- Synchronize `README.md` and `docs/IMPLEMENTATION.md`; change `docs/DESIGN.md`,
  ADRs, runbooks, OpenAPI, or executable files only if a verified discrepancy
  requires it.
- Prove by Git object/diff checks that no PDF changed. Do not access prep or
  Salus.

## Closeout

1. Run changed-document links, YAML/JSON/shell syntax as applicable, focused
   secret/credential/private-key/public-network/unsafe-claim scans,
   `git diff --check`, full diff review, and exact staging allowlist checks.
2. Move this plan to `docs/plans/completed/` only after every required gate is
   green and the final evidence is recorded.
3. Re-fetch `origin/main`; stop on divergence. Create the single documentation
   closeout commit `docs: complete dual-chain POC release review` unless an
   executable correction required its separately allowed first commit.
4. Push `main` non-force, verify local/tracking/fetched/live-remote equality,
   create and push the annotated tag
   `digital-banking-v1.0.0-dual-chain-poc`, then create the GitHub prerelease
   from the release-notes file when existing authentication still succeeds.
5. Verify the final clean synchronized branch, tag target, prerelease state,
   frozen PDF blobs, and absence of untracked generated artifacts.

## Stop conditions and deferrals

Stop for baseline/remote divergence, an unapproved dependency/image/tool or
network, any PDF change, secret exposure, public/non-loopback binding,
unresolved Critical issue, unsafe reset scope, irreproducible duplicate value
effect, or a required architecture/authority change. Production identity,
custody/HSM/MPC, banks, reserves, accounting close, HA/DR, public-network
deployment, compliance/legal approval, performance certification, and a future
Solana-aligned publication release remain deferred.

## Evidence log

- **Preflight:** Complete and green as recorded above. No executable,
  documentation, build, runtime, migration, OpenAPI, or PDF file changed before
  this plan was created.
- **Authority pass:** Repository policy, security, implementation standards,
  design/status documents, accepted ADRs, Phase 6D/7F/8A plans, runbooks, and
  current API/schema authorities agree on the bounded non-production release
  posture. Detailed source/migration review and executable gate evidence are
  complete below.
- **Review:** Module POMs/imports, Spring security/controllers/problems, all
  OpenAPI contracts, Flyway V1--V14, database concurrency/evidence controls,
  signer fencing, Web3j/Sava submit/inquiry/observation semantics, exact
  accounting, and operator scripts were inspected against their focused tests.
  No Critical issue was found. One Important OpenAPI completeness defect and
  three non-blocking advisories are recorded in
  `docs/reviews/PHASE_8B_FINAL_REFERENCE_REVIEW.md`.
- **P8B-I-001 RED:** the focused `UsdzelleOpenApiContractTest` failed because
  the public workflow contract omitted the common runtime's `415`, `500`,
  `503`, and malformed-ID `400` outcomes.
- **P8B-I-001 GREEN:** the same focused offline Maven selection passed one test
  after adding the missing documented response sets. No runtime behavior,
  endpoint, dependency, migration, authority, or profile changed.
- **Ponytail:** the one full-mode stable-diff simplification review removed one
  repeated README stop/reset explanation. It found no unnecessary dependency,
  abstraction, wrapper, module, executable change, or additional removable
  complexity in the focused OpenAPI test and documentation diff.
- **Independent review:** the one read-only stable-diff correctness/security
  review found no remaining Critical or Important issue. It verified the
  README commands and Demo A/B semantics, response sets against the runtime
  problem boundary, the valid OpenAPI 3.1 bearer-role declarations against the
  exact Spring authorities, and the absence of any broadened endpoint,
  authority, dependency, migration, persistence, recovery, or chain boundary.
- **Graphify:** `tooling_deferred`. The installed CLI help was inspected once,
  one existing-graph query was used as advisory navigation, and the single
  permitted non-HTML refresh attempt
  (`gtimeout 60 graphify update . --no-cluster`) failed with watcher
  `Operation not permitted`. It produced no tracked or transient artifact and
  was not retried or reconstructed.
- **Final offline Maven:** the one required
  `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean verify` completed with
  `BUILD SUCCESS` in 48.246 seconds. Reconciled Surefire XML reports record
  `538` discovered, `536` executed, `536` passed, `0` failed, `0` errored, and
  `2` intentional skips: domain `265/265/265/0`, application `75/75/75/0`,
  persistence `67/67/67/0`, signer `25/25/25/0`, Ethereum `21/21/21/0`,
  Solana `20/19/19/1`, and control plane `65/64/64/1`. No Failsafe XML was
  present. Configured Maven Enforcer rules passed.
- **Foundry and dependency direction:** the one `forge test` run passed all
  `9` tests with no failure or skip. The one compiled-class Java 25 `jdeps`
  pass confirmed the intended inward module direction; approved external
  libraries remained unresolved only at adapter/composition edges.
- **Ethereum release gate:** from scoped clean state, Demo A and replay, Demo B
  and replay, and restart recovery all passed. Exact `10000`-unit positions,
  zero duplicate effects, payout-before-burn, reconciliation, and the expected
  final held restart state were observed. The Phase 6D environment was stopped
  safely with named state preserved.
- **Solana release gate RED:** the clean Phase 7F bootstrap provisioned the
  approved private Agave/classic-SPL fixture, PostgreSQL, and packaged control
  plane, but `start.sh` exited before reporting readiness. Retained logs show
  the status endpoint was queried while its local mint evidence was temporarily
  unavailable. Demo A, Demo B, replay, and restart recovery therefore did not
  run in Phase 8B.
- **Authorized correction cycle:** a focused shell regression first failed on
  `set -e`-sensitive readiness probes. The minimal conditional-probe patch and
  shell safety test passed, but the single post-correction clean Solana gate
  remained red. Focused diagnosis then showed `demo_status_json` calls
  `demo_die`, whose `exit 1` terminates the current readiness shell even when
  invoked in an `if`; the probe must be isolated in a subshell. The incomplete
  executable/test patch was removed rather than retained after the authorized
  cycle was exhausted. Both demo environments are stopped, and
  `git diff --check` remains clean.
- **Release preparation:** all build, review, Ethereum, and Solana release
  gates are green. No PDF, prep, Salus, dependency, image, migration, endpoint,
  runtime authority, or public-network boundary changed.
- **P8B-I-002 restart GREEN:** after explicit authorization, a focused
  regression failed because readiness probes could escape the polling loop
  under `set -e`. The minimal fix places the health and port probes in
  conditionals and runs the status probe in a subshell, containing its existing
  `demo_die` exit. The focused Phase 7F shell/configuration check and `sh -n`
  passed.
- **Solana release gate:** one clean post-fix sequence passed. Demo A
  acquisition `1bab5f31-6529-4c6f-9178-6b1ccb40e238` held exactly `10000`
  USER_1 atomic units against `10000` reserve/liability cents; redemption
  `69139211-00c1-46f3-8a37-4bef191ba2a1` enforced payout-before-burn and ended
  with zero supply/custody and reconciliation. After scoped reset, Demo B
  settlement `33e74048-1282-4cef-8dee-adcc1749a30c` completed its exact six
  effects, moved bank cents `10000 -> 0` and `0 -> 10000`, replayed without a
  duplicate effect, and ended reconciled with zero chain positions. After a
  second reset, restart workflow `48f2a193-4a2c-4e3a-ba58-955a2e3d0424`
  resumed against retained PostgreSQL/Agave with exactly one withdrawal and
  mint. Final status showed USER_1/supply and reserve/liability at `10000`, and
  the environment stopped safely with named state preserved.
- **Final repository checks:** all `111` local Markdown targets in the changed
  documents resolve; both authoritative OpenAPI YAML files parse; Phase 7F
  shell/configuration safety and changed-shell syntax pass; Flyway versions are
  exactly V1--V14; README commands, executable paths, Demo A/B labels,
  destructive-reset/preserving-stop language, and exact quantities agree.
  Focused secret/raw-transaction/public-network/workstation-path scans,
  `git diff --check`, mode checks, the exact nine-file worktree allowlist, and
  full diff review pass. Git diff identity proves no reference PDF changed.
