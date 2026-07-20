# Phase 7F — Reproducible Local Solana Demonstrations

Status: completed
Authority: Action Request 27, including the correction that Demo A is the
user-held lifecycle and Demo B is settlement-only
Baseline: `e882b7066dd69f447146f48fc0cf43e7d377575c` on `main`

## Outcome and boundary

Package the already verified Phase 6A–7E behavior as two reproducible,
loopback-only Solana demonstrations. Demo A acquires, holds, and redeems exact
`USD 100.00` / `10000` atomic units. Demo B executes the existing six-effect
settlement-only route. Both must prove stable replay, exact accounting and
native evidence, and bounded recovery without adding product behavior.

No new dependency, image, module, endpoint, workflow, migration, chain
instruction, authority model, public network, or production claim is
authorized. PDFs and Salus remain untouched. Historical completed evidence is
not renamed or reinterpreted.

## Verified preflight

- Repository root: `/Users/johnwhitton/dev/johnwhitton/digital-banking`.
- `HEAD`, local `main`, `origin/main`, fetched main, and live remote main all
  resolved to the baseline; worktree/index were clean and the SSH origin was
  approved.
- Host-native Agave CLI and validator `4.1.2`, SPL Token CLI `5.6.1`, Maven
  `3.9.16`, Java `25.0.2` through `/opt/homebrew/opt/openjdk`, Docker Engine
  `28.5.1`, Compose `2.40.3`, curl `8.7.1`, and jq `1.7.1` are available.
- The approved cached PostgreSQL image is
  `postgres:17.10-alpine3.23@sha256:8189a1f6e40904781fc9e2612687877791d21679866db58b1de996b31fc312e4`.
  No pull is required or authorized.
- Required loopback ports are available and no Digital Banking Ethereum demo
  project is running. Unrelated host/container resources remain out of scope.
- `.solana-tools/`, `.solana-runtime/`, and `.env.local-anvil` are ignored;
  existing local directories/files retain modes `0700`, `0700`, and `0600`.
- The four reference PDF Git entries retain mode `100644` and approved blobs
  `2b36c73c…`, `b3b907c4…`, `ebe456d6…`, and `ae25c881…`; their contents were not
  opened or recomputed.

## Minimal reuse and change map

- Reuse `scripts/solana/lib.sh`, validator bootstrap, fixed key layout, private
  cluster identity, classic Token/ATA programs, and the Phase 7B fixture.
- Reuse the Phase 6D shell assertion/polling vocabulary and existing Phase
  6B/6C REST resources, bearer boundary, durable worker seam, status schema,
  accounting projections, and redacted summaries.
- Add a PostgreSQL-only Solana demo Compose file using the approved image,
  dedicated project/volume, and loopback host port. Agave and the packaged Java
  25 JAR remain host-native.
- Add only thin commands under `scripts/demo/solana/` for prerequisites,
  bootstrap/start/readiness/status, Demo A, Demo B, restart, stop, and explicit
  reset. Runtime state is isolated beneath ignored `.demo-runtime/solana/`.
- Make the existing local-only status boundary chain-neutral and add bounded
  read-only Sava balance/supply evidence. Preserve the Ethereum response and
  profile behavior. No new HTTP route is introduced.
- Reconcile the mislabeled Demo A/B headings in `docs/DESIGN.md`; update only
  required living docs, the Solana script guide, and the new runbook.

## Test-first and executable gates

1. Add focused failing profile/status and shell-safety tests, observe the
   intended failures, then implement the smallest passing change.
2. Run focused default-profile, local-auth/status, Phase 6B/6C, Phase 7B–7E,
   shell syntax/safety, Compose, and documentation checks.
3. Build the packaged application offline and validate the private fixture.
4. From clean named state, run Demo A once plus replay, explicit reset, Demo B
   once plus replay, one deterministic restart proof, preserved-state stop/start,
   and one final scoped reset. Do not repeat green demos without a relevant
   production/runtime change.
5. Freeze the diff; perform exactly one Ponytail review and one independent
   correctness/security review. Resolve only valid Critical/Important findings
   and rerun affected gates.
6. Run exactly one final `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw -o clean
   verify`, calculate per-module Surefire totals, explain both opt-in native-gate
   skips, and run Enforcer plus compiled-class `jdeps` evidence.
7. Attempt one supported Graphify refresh for at most 60 seconds without HTML or
   retry. On failure restore only Graphify attempt changes/transients and record
   `tooling_deferred`.
8. Complete secret/public-network/mode/migration/PDF/link/diff/staging checks,
   move this plan to `completed/`, create the single commit
   `feat: add reproducible local Solana demos`, push non-force, and verify clean
   local/tracking/fetched/live-remote synchronization.

## Evidence log

- 2026-07-19: baseline, remote, clean-worktree, toolchain, cached-image,
  loopback-port, ignored-state, and PDF-mode/blob preflight passed. The topology
  is viable without a new image, dependency, tool, or network exposure.
- 2026-07-19: accepted documentation reconciliation recorded: Demo A remains
  user-held acquisition/hold/redemption and Demo B remains settlement-only.
- 2026-07-19: the first clean bootstrap stopped before fixture creation because
  the wrapper mistook the newly created validator ledger for stale unowned
  state. No business effect ran. The redundant precheck was removed; the
  existing fixture's zero-supply/fresh-ledger check remains authoritative.
- 2026-07-19: the first packaged-JAR launch proved the executable intentionally
  has no runtime YAML parser. The Phase 7F launcher now follows the established
  Phase 6D pattern: one generated ignored properties file is the complete config
  location. No dependency was added and default classpath configuration remains
  unchanged.
- 2026-07-19: the packaged start gate exposed two host-readiness defects before
  any demo effect: PostgreSQL startup was not awaited, and Docker Desktop did
  not publish a port for the otherwise unused internal-only Compose network.
  Bootstrap now uses Compose `--wait`; the single PostgreSQL service uses its
  normal project network while retaining the exact `127.0.0.1:15432` binding.
  Fresh inspection proved the binding and a packaged Java/Flyway connection.
- 2026-07-19: status reached healthy after the newly created mint and associated
  accounts became visible at finalized commitment. The first-status transient
  failed closed and mutated no business state. The healthy snapshot proved the
  private cluster, classic SPL mint, configured authorities/accounts, zero
  supply, zero token/accounting effects, and redacted chain-neutral projection.
- 2026-07-19: the new readiness and restart wrappers initially read a nonexistent
  `validator` JSON field instead of the established `state` field. Shell checks
  were added red-first and both wrappers now require the exact healthy state,
  loopback RPC, cluster/mint fixture, API, status, and PostgreSQL binding.
- 2026-07-19: the first Demo A attempt reconciled its acquisition but stopped at
  the hold assertion because the existing local status projection counted only
  Ethereum observation tables. The chain-neutral UNION correction counts only
  completed operations with confirmed Ethereum or Solana native observations;
  the focused Ethereum vertical-slice regression passed. The failed runtime was
  explicitly reset before the complete demonstration and was not reported as a
  passed demo.
- 2026-07-19: complete Demo A passed from clean state. Acquisition and replay
  produced one withdrawal/mint, held exactly `10000` USER_1 atomic units and
  supply against `10000` reserve/liability cents, then redemption paid out
  before one custody transfer/burn. Final bank balance returned to `10000`, all
  token/custody/supply/accounting positions were zero, and both parents were
  `RECONCILED` without duplicate effects.
- 2026-07-19: after explicit scoped reset, complete Demo B passed from clean
  state. One withdrawal, mint, user transfer, redemption-custody transfer,
  payout, and burn moved bank cents `10000 -> 0` and `0 -> 10000`, ended with
  zero supply/balances/positions, reconciled, and replayed the same transfer ID
  without a duplicate effect.
- 2026-07-19: a separate clean restart gate durably accepted an acquisition with
  delivery disabled, retained PostgreSQL and private Agave while Java stopped,
  resumed after Java restart, reconciled with exactly one withdrawal/mint, and
  replayed without duplication. The final scoped reset removed only the Phase
  7F project/volume/network and ignored runtime. Codex's process supervisor
  reaps detached host children after a command returns, so validation kept the
  exact repository-defined Agave/Java commands attached; ordinary terminal
  behavior and script topology were unchanged.
- 2026-07-19: the compact stable-diff gate passed eight focused default,
  readiness, identity, and Ethereum-workflow tests; all fourteen migrations
  applied from empty schema; shell syntax/safety, Compose configuration,
  OpenAPI YAML, and `git diff --check` passed.
- 2026-07-19: the one Ponytail review found no removable complexity. The one
  independent review found no Critical issues and two Important local-boundary
  issues. Both were corrected red-first: the demo API URL must be loopback HTTP
  before any bearer token is read, and the shared native helper accepts only
  `.solana-runtime` or `.demo-runtime/solana/validator` as runtime roots. The
  affected shell/configuration gate passed; no second review or native run was
  performed.
- 2026-07-19: the single final offline reactor passed in 1:01. Surefire
  discovered 538 tests, executed 536, skipped only the Phase 7B and Phase 7E
  opt-in native gates, and reported zero failures/errors. Per module:
  `domain 265/265`, `application 75/75`, `persistence-postgres 67/67`,
  `signer-local 25/25`, `ethereum-web3j 21/21`, `solana-sava 20/19 with one
  skip`, and `control-plane 65/64 with one skip`. Enforcer passed; `jdeps`
  showed domain depends only on `java.base` and application only on `java.base`
  plus its unresolved internal domain artifact.
- 2026-07-19: the single Graphify 0.8.47 incremental `--no-viz` attempt was
  capped at 60 seconds and stopped after two seconds because 125 changed
  non-code files required an external LLM key. Gemini, Google, Anthropic,
  OpenAI, and other external backends were not selected; no repository content
  was sent externally, no HTML/query was generated, and no tracked Graphify or
  transient file changed. The non-authoritative refresh is recorded as
  `tooling_deferred`; no retry was performed.
- 2026-07-19: after the successful reactor, only README/roadmap/plan closeout
  evidence changed; no production, test, build, migration, OpenAPI, runtime, or
  script file changed. Final changed-document links, OpenAPI YAML, shell syntax,
  Compose configuration, executable/source modes, secret-like literals,
  public-network literals, unauthorized-file filters, and `git diff --check`
  passed. The four PDF entries remain byte-unmodified Git blobs `2b36c73c…`,
  `b3b907c4…`, `ebe456d6…`, and `ae25c881…`, all mode `100644`. No Salus asset
  was read, copied, modified, or retained.

## Intentional deferrals

Public clusters, production banking/custody/HSM/MPC, audited reserves,
legal/compliance/customer finality, deployment hardening, performance claims,
and Phase 8 conclusions remain out of scope. Phase 8 is the next bounded action
after successful Phase 7F closeout.
