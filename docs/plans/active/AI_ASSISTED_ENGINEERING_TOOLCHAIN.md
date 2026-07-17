# AI-Assisted Engineering Toolchain Plan

**Status:** `ready_for_commit`

**Goal:** Integrate Graphify as a portable, repository-scoped Codex capability and verify the official Ponytail and Superpowers Codex plugins without weakening repository policy, financial invariants, security boundaries, or evidence gates.

**Authority:** Action Request 03, `AGENTS.md`, `AUTONOMOUS_EXECUTION_POLICY.md`, `SECURITY.md`, accepted ADRs, `docs/DESIGN.md`, and `docs/IMPLEMENTATION.md`.

**Architecture:** Graphify is the only tool that contributes tracked project integration: one canonical skill under `.codex/skills/`, discovered through the existing `.agents/skills` link, a reviewed Codex lifecycle hook, a bounded scan policy, and portable navigation artifacts. Ponytail and Superpowers remain user-installed official plugins. `AGENTS.md` and the owning repository documents define precedence and triggers; generated graph output remains advisory.

**Tech stack:** Codex CLI 0.142.1, Graphify/`graphifyy` 0.8.47, `uv` 0.11.24, Node.js 22.13.0, JSON, TOML, Markdown, Java 25, and Maven Wrapper 3.3.4 / Maven 3.9.16.

## Global constraints

- Work directly on synchronized `main` and create exactly one commit named `chore: integrate AI-assisted engineering toolchain`.
- Starting local and remote SHA: `4adc096dd079d79287da5841cc03b7de8e73578e`.
- Do not silently upgrade, downgrade, or replace an installed tool or plugin.
- Do not modify user-global Codex policy or commit user-specific paths, credentials, caches, cost records, or optional Graphify extras.
- Do not install Graphify Git `post-commit` or `post-checkout` hooks.
- Do not transmit repository content to a new model provider or hosted index. Semantic extraction may use only this approved Codex session.
- Do not index the immutable source PDFs in this action; architecture Markdown and the reference index retain their authority and traceability.
- Do not change product code, Maven dependencies, APIs, domain behavior, chain choices, or implementation phase status.
- Repository policy, security, architecture, active plans, tests, and focused repository skills override third-party instructions.
- AI suggestions and graph edges are navigation aids, not acceptance evidence.

## Verified baseline and inventory

| Item | Observation and disposition |
| --- | --- |
| Git | Clean `main`; local `HEAD`, fetched `origin/main`, and the Action Request 02 completion SHA all equal `4adc096dd079d79287da5841cc03b7de8e73578e`. |
| Remote | `git@github.com:johnwhitton/digital-banking.git`, the approved SSH form. |
| Codex | `codex-cli 0.142.1`; `hooks` is enabled and stable. The CLI supports `plugin add/list/remove` and Git/local marketplace management. |
| Repository hooks | `.codex/hooks.json` is valid JSON with an empty hook map; no project hook requires trust yet. Codex exposes hook trust through interactive `/hooks`, not a non-interactive trust subcommand. |
| Skill discovery | `.agents/skills` is a relative link to `../.codex/skills`; six repository skills are canonical under `.codex/skills/`. |
| Graphify | Official `graphifyy` installed through a `uv` tool receipt; executable reports `graphify 0.8.47`. PyPI's current stable is 0.9.13 (2026-07-12), so this action retains 0.8.47 rather than silently replacing it. No optional extra is installed. |
| Existing graph | Ignored pre-action output exists at `graphify-out/`: 133 nodes, 150 edges, 18 communities. It includes PDF-derived concepts and transient/cache material, so it is input for review, not an artifact accepted unchanged. |
| Ponytail | Global Ponytail skills are discoverable, but the official `ponytail` Codex marketplace/plugin is not configured. Upstream stable `v4.8.4` (2026-06-29), MIT, documents `codex plugin marketplace add DietrichGebert/ponytail` then `codex plugin add ponytail@ponytail`. Its three local Node hooks write only plugin state/context and deliberately exit successfully when Node or state operations are unavailable. |
| Superpowers | Global Superpowers skills were discoverable before this action. The official `superpowers@openai-curated` catalog entry installs plugin manifest version 5.1.3 under content revision `2f1a8948`, with no hooks. Upstream stable is `v6.1.1` (2026-07-02), MIT; retain and record the curated catalog lag rather than substituting an unofficial source. |
| Runtime | `uv 0.11.24`; Node.js `v22.13.0`; Java/Maven validation remains the repository gate. |

## Supply-chain decisions

1. Use only `https://github.com/safishamsi/graphify`, PyPI `graphifyy`, `https://github.com/DietrichGebert/ponytail`, and `https://github.com/obra/superpowers`.
2. Retain installed Graphify 0.8.47. Its package metadata identifies the canonical repository and MIT license; its installed Codex integration and executable source are reviewed before execution.
3. Run the official Graphify project installer, then reconcile its output. Preserve the repository's `.codex/skills` ownership through the existing `.agents/skills` relative link. Replace the installer's workstation-absolute hook command with an executable-resolved command that exits cleanly when Graphify is absent and propagates a present executable's failure while retaining the official local-only no-op `graphify hook-check` behavior in 0.8.47.
4. Commit the official Graphify skill/reference content required for project discovery with one clearly marked repository-guardrail preface that prohibits self-upgrade, external providers, PDF input, Git hooks, and unapproved persistent services. Add `THIRD_PARTY_NOTICES.md` because modified third-party skill content becomes tracked.
5. Install Ponytail only from its canonical Git marketplace and record the resolved plugin version/commit after installation. Review its Node scripts and trust prompt; never vendor it.
6. Install Superpowers only as `superpowers@openai-curated`. Record resolved version 5.1.3 unless the catalog changes before installation; never substitute an unofficial fork or vendor it.
7. Keep `.codex/config.toml` unchanged unless strict validation proves a required supported key is missing. `[features].hooks = true` is already valid for Codex 0.142.1.
8. Do not change `docs/DESIGN.md` or create an ADR: the integration changes contributor workflow and navigation, not system architecture, financial semantics, or implementation sequencing.

## Intended repository changes

| Path | Responsibility |
| --- | --- |
| `docs/plans/active/AI_ASSISTED_ENGINEERING_TOOLCHAIN.md` | Restartable execution decisions, evidence, review, and closeout. |
| `.codex/skills/graphify/` | Official Graphify 0.8.47 skill and required references, plus a narrow documented Digital Banking guardrail preface. |
| `.codex/hooks.json` | Structurally merged, trusted-project Codex hook using reviewed fail-open executable resolution. |
| `.graphifyignore` | Exclude VCS/build/cache/secret/environment/PDF material while retaining Java, Maven, Markdown, ADRs, plans, and AI workflow files. |
| `graphify-out/GRAPH_REPORT.md` | Reviewed portable Graphify navigation report. |
| `graphify-out/graph.json` | Reviewed portable knowledge graph with confidence classifications. |
| `graphify-out/manifest.json` | Relative-path update manifest; no workstation paths or transient cost/cache data. |
| `.gitignore` | Keep Graphify transient output ignored while allowing the three reviewed portable artifacts. |
| `THIRD_PARTY_NOTICES.md` | Graphify skill provenance, installed version, source, and MIT license notice. |
| `README.md` | Compact roles, triggers, setup/update/verification, trust, portability, and evidence guardrails. |
| `AGENTS.md` | Concise third-party precedence and invocation triggers; generated suggestions remain lowest authority. |
| `docs/IMPLEMENTATION.md` | Current repository workflow/layout and stable validation entry points; no phase-status change. |

## Ordered execution

### 1. RED: prove the repository integration is absent

- [x] Confirm `graphify` exists but `.codex/skills/graphify/SKILL.md` does not.
- [x] Confirm `.codex/hooks.json` contains no Graphify hook.
- [x] Confirm the README does not provide the required three-tool roles, verified install commands, hook trust, or graph evidence limits.
- [x] Confirm `superpowers@openai-curated` is available but not installed and the Ponytail marketplace is absent.

Expected RED assertions:

```text
test -f .codex/skills/graphify/SKILL.md
jq -e '.hooks.PreToolUse | any(.hooks[]?.command | contains("graphify"))' .codex/hooks.json
rg -n 'Ponytail|Superpowers|graphifyy' README.md
```

The first two assertions must fail and the README search must not satisfy the requested workflow before implementation.

### 2. Install and reconcile Graphify project integration

- [x] Run `graphify install --project --platform codex` from the repository root.
- [x] Inspect every generated file and diff before accepting it.
- [x] Preserve one canonical skill at `.codex/skills/graphify/`, exposed through `.agents/skills/graphify`; reject duplicate copies.
- [x] Replace verbose/generated `AGENTS.md` text with the repository-owned concise precedence and trigger section.
- [x] Structurally retain existing hooks and use a reviewed `PreToolUse` hook that resolves `graphify` from `PATH`, runs official `hook-check`, performs no network or repository write, and exits successfully when the executable is absent.
- [x] Add the Graphify third-party notice and scan policy. Do not add PDF or other optional extras.
- [x] Validate JSON, hook matcher/target behavior, the executable-present path, a deliberately restricted `PATH` unavailable path, canonical skill discovery, and fresh-process Codex discovery.

### 3. Install or verify official user plugins

- [x] Add the canonical Ponytail marketplace and install `ponytail@ponytail` only if still absent.
- [x] Record its resolved checkout/version and inspect installed manifest, hooks, scripts, permissions, network behavior, and plugin state paths.
- [x] Install `superpowers@openai-curated` only if still absent; record resolved version and verify its manifest has no hooks or external connector.
- [x] Use a fresh Codex process (`codex debug prompt-input`) to verify plugin skill discovery without changing repository configuration.
- [x] Record the bounded interactive `/hooks` trust/review step for Ponytail: after repository completion, start one new Codex task in this trusted repository, run `/hooks`, inspect the project Graphify definition and Ponytail's three lifecycle definitions, and trust the exact reviewed definitions. No non-interactive trust bypass is used.

### 4. Build and validate the portable graph

- [x] Exclude `docs/reference/*.pdf`, `.git`, build output, environment files, caches, Graphify local cost/memory/reflection files, and machine state in `.graphifyignore`; explicitly filter Graphify 0.8.47's special query-memory re-inclusion.
- [x] Rebuild Java/Maven structure locally and perform document semantic extraction only through approved Codex subagents; use no provider key or external backend.
- [x] Re-cluster and generate the report without accepting stale PDF nodes or absolute paths.
- [x] Commit only `GRAPH_REPORT.md`, `graph.json`, and `manifest.json`; omit HTML because the JSON/report already provide the required value.
- [x] Scan the artifacts for credentials, secret-like values, workstation paths, private remote metadata, publication reproduction, stale nodes, and unapproved providers/endpoints.
- [x] Diagnose dangling endpoints, duplicate IDs, same-endpoint collapse risk, missing source locations, and confidence labels. Disclose the raw-producer loss, metadata gaps, unknown semantic token cost, and working-tree provenance in the report and this plan rather than treating the graph as lossless evidence.

### 5. Query validation against authoritative source

- [x] Query: "Which module owns exact token-quantity invariants?" Compare with `domain` source/tests, `AGENTS.md`, and `docs/DESIGN.md`.
- [x] Query: "Where are chain-native SDK types prohibited?" Compare with module Enforcer rules, `AGENTS.md`, ADR 0001, and repository skills.
- [x] Query: "How does the design handle ambiguous submission?" Compare with `docs/DESIGN.md`, domain lifecycle tests, and financial/chain skills.
- [x] Query: "Which documents and tests govern operation lifecycle transitions?" Compare with active plans, design, source, and tests.
- [x] Record missing, stale, inferred, ambiguous, or wrong results. Save no query memory artifact for commit.

Query validation results:

| Question | Graph result | Authoritative comparison and limitation |
| --- | --- | --- |
| Exact token-quantity owner | Found `AssetUnit`, `TokenQuantity`, the financial-invariants skill, and domain lifecycle/test neighbors. | Correct: the plain-Java `domain` module owns `AssetUnit`/`TokenQuantity`, enforced by `domain/pom.xml` and `TokenQuantityTest`. The broad BFS returned unrelated lifecycle neighbors, so source remains necessary for exact ownership. |
| Chain-native SDK prohibition | Found plain-Java isolation, the chain-adapter review, ADR 0001, and native-semantics guidance. | Directionally correct but partial: it under-ranked `domain/pom.xml`'s Enforcer rule and `AGENTS.md`'s explicit Web3j/Solana-type prohibition. Direct dependency rules are authoritative. |
| Ambiguous submission | Found `ChainPort`, inquiry/observation records, `PortContractTest`, attempt identity, and submission result nodes. | Partial: it exposed the provider-neutral inquiry/observation contract but under-ranked `docs/DESIGN.md` and the `SUBMISSION_AMBIGUOUS` lifecycle guards that prohibit blind retry. |
| Lifecycle documents/tests | Found the Phase 2 plan, `TokenOperation`, `TokenOperationLifecycleTest`, transition helpers, evidence, and state nodes. | Substantively correct but noisy: the traversal also anchored on generic `Test`/state nodes and truncated broad output. The design, Phase 2 plan, aggregate source, `TokenOperationLifecycleTest`, and `TokenOperationEvidenceTest` remain the governing set. |

### 6. Synchronize workflow documentation

- [x] Replace README's AI-assisted section with the compact three-tool table, committed-versus-user-installed distinction, exact verified setup/update/verify commands, hook trust behavior, evidence disclaimer, and the rejected Salus-style absolute-hook contrast.
- [x] Add concise `AGENTS.md` precedence and trigger rules without duplicating third-party skill bodies.
- [x] Update `docs/IMPLEMENTATION.md` only for the repository map/workflow and stable validation commands; preserve all phase statuses and the next Phase 3 recommendation.
- [x] Record why `docs/DESIGN.md`, ADRs, product code, Maven files, prompts, and existing focused skills remain unchanged.

### 7. Review and full verification

- [x] Run Ponytail's final diff review. No safe deletion remains: the large files are the required official Graphify integration, license notice, portable graph, or restartable evidence. No simplification recommendation is allowed to weaken a repository invariant or required evidence.
- [x] Use Superpowers systematic debugging for real sandbox/command-wrapper failures and `verification-before-completion` for fresh evidence immediately before commit/push.
- [x] Obtain an independent read-only review of supply-chain provenance, hook behavior, instruction precedence, documentation accuracy, graph artifacts, and scope. Resolve every Critical or Important finding.
- [x] Validate TOML, JSON, YAML/frontmatter, shell command syntax, 34 relative Markdown links, exact source URLs, skill discovery, documented interactive hook trust state, and graph queries.
- [x] Run `JAVA_HOME=/opt/homebrew/opt/openjdk ./mvnw clean verify`, `git diff --check`, staged-diff scans, and staged-tree review.
- [x] Verify PDF bytes and checksums are unchanged and no product/Maven/domain/phase change entered the diff.

### 8. Single commit and authorized push

- [ ] Fetch `origin`; stop if `origin/main` moved from `4adc096dd079d79287da5841cc03b7de8e73578e`.
- [ ] Stage only the reviewed allowlist and inspect the complete staged diff.
- [ ] Commit once as `chore: integrate AI-assisted engineering toolchain`.
- [ ] Push `main` to `origin/main` without force, verify the remote SHA, and confirm a clean synchronized worktree.

## Stop and restart conditions

On restart, inspect Git status, this checklist, installed plugin state, hook trust, and the latest evidence before continuing. Stop for baseline divergence, unexpected user changes, unverifiable canonical source, required tool replacement, unapproved data egress, unsafe hook behavior, unsafe generated artifacts, destructive or credential handling, unresolved Critical findings, or a remote advance before push.

## Evidence log

| Gate | Command/evidence | Result |
| --- | --- | --- |
| Baseline | `git fetch --prune origin`; local/remote SHA and status inspection | Passed: clean synchronized `main` at `4adc096dd079d79287da5841cc03b7de8e73578e`. |
| Repository policy | Complete read of repository guidance, security, design, implementation, ADRs, plans, config, hooks, prompts, and six skills | Passed; tooling-only scope confirmed. |
| Codex docs | Current Codex manual sections for project config, plugins, hooks, trust, and CLI commands | Project hooks require trusted repositories and exact-definition trust; installed plugins require a new task/process for bundled skills. |
| Tool inventory | `codex --version`; plugin/marketplace/features lists; `graphify --version`; `uv --version`; `node --version` | Results recorded above. `uv tool list` attempted a write in the user tool directory under the sandbox, so the existing read-only receipt and package metadata were used instead. |
| Upstream review | Canonical GitHub release/README/manifests/licenses and PyPI package/release metadata | Graphify, Ponytail, and Superpowers sources, releases, licenses, install flows, and relevant hooks/manifests verified. |
| Hook trust inventory | Repository hooks plus `codex doctor --json` and local trust-file search | The pre-action repository hook map was empty. Codex 0.142.1 exposes review/trust through interactive `/hooks`; the redacted doctor report provides no separate hook-trust check. Strict config loading passes, while doctor separately reports pre-existing `TERM=dumb` and user-state database integrity failures that are outside repository scope and are not modified here. |
| Graph baseline | Existing ignored report/artifact inspection | Existing graph is useful but includes PDF-derived nodes and transient local files; it will not be committed unchanged. |
| RED | File, JSON, README, and plugin assertions above | Passed after plan creation: project skill and Graphify hook absent, README workflow absent, Ponytail marketplace absent, and curated Superpowers available but not installed. |
| Graphify integration | Installer, reconciliation, discovery, hook, graph, artifact, and query checks | Official installer first failed cleanly at `.codex/skills/graphify` under the host sandbox and left no partial skill; the same reviewed command succeeded with approval. Generated files were inspected and reconciled. JSON, canonical-link discovery, fresh-process Codex discovery, executable-present, executable-absent, and present-failure propagation checks pass. The final approved corpus is rebuilt locally with no PDF/provider/backend use; only the report, graph JSON, and relative manifest are accepted. |
| Graph artifacts | Full local extraction, clustering, diagnostics, portability/security scans, final manifest verification | The reviewed graph is built from the AR03 working tree atop `4adc096d`, so the misleading pre-action `built_at_commit` marker is omitted and final manifest content hashes are the freshness check. Final graph endpoints are valid and IDs unique. Raw Graphify 0.8.47 extraction diagnostics observed 163 dangling edges, 31 exact duplicates, and 89 undirected same-endpoint collapses before final graph construction; 24 of 531 nodes lacked `source_file` and 237 lacked `source_location` in the reviewed build. These and unavailable in-session subagent token accounting are disclosed in the report. No PDF node, absolute path, secret pattern, private remote metadata, or provider endpoint remains. |
| Graph queries | Four vocabulary-constrained queries plus direct-source comparison | Results recorded above. All four were useful navigation; SDK-boundary and ambiguous-submission answers were partial, and lifecycle retrieval was broad/noisy. No query memory is accepted for commit. |
| Ponytail | Official plugin installation/provenance, hook review, and final diff review | Installed and enabled as `ponytail@ponytail` 4.8.4 from canonical marketplace checkout `16f29800fd2681bdf24f3eb4ccffe38be3baec6b`. Manifest source remains the official mutable `main` ref; the resolved checkout is recorded. Three reviewed Node hooks are local-only, fail-open, and write only plugin data/config. Fresh-process discovery passes; interactive exact-definition trust remains the documented user action. The final simplicity review found no safe deletion. |
| Superpowers | Official plugin installation/provenance and verification workflow | Installed and enabled only as `superpowers@openai-curated`; Codex cache/content revision `2f1a8948`, plugin manifest version 5.1.3, canonical repository metadata, MIT, no hooks or connector. Fresh-process discovery of Superpowers and `verification-before-completion` passes. |
| Repository gate | Config/link/security scans and Maven clean verify | Passed: valid TOML/JSON/YAML/frontmatter, all 8 Graphify references and 34 local Markdown links resolve, no Git hooks or secret/absolute-path leakage in accepted artifacts, exact PDF hashes unchanged, `git diff --check` clean, and the Maven reactor passed 266 tests with both Enforcer rules. Pre-existing doctor warnings remain `TERM=dumb` and user-state database integrity; neither is changed. |
| Independent review | Supply-chain, hook, precedence, docs, graph, and scope review | No Critical findings. Four Important findings were resolved: refresh stale manifest hashes; disclose raw graph loss/unknown token accounting; omit the misleading pre-action graph commit marker; and add the Ponytail trigger plus Salus-hook contrast. Minor retained limitations are source metadata gaps, noisy lifecycle retrieval, mutable official Ponytail `main` resolved to an exact checkout, and reviewed upstream/catalog version lag. |
| Git closeout | One commit, fresh non-divergence check, non-force push, remote SHA, clean status | Pending. |

## Closeout state

`ready_for_commit` - implementation, official plugin verification, local graph/query workflow, documentation synchronization, independent review remediation, and repository gates are complete. The final allowlisted stage, non-divergence fetch, one commit, non-force push, remote-SHA check, and clean-status confirmation remain. No product, Maven, architecture, phase-status, user-global policy, or manually edited user-global configuration change was made.
